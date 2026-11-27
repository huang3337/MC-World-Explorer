package com.mcworldexplorer.preview;

import com.mcworldexplorer.region.RegionChunkData;
import com.mcworldexplorer.region.RegionFileReader;
import com.mcworldexplorer.world.WorldInfo;
import com.mcworldexplorer.world.WorldScanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MultiDimensionPreviewIntegrationTest {
    private static final String HOME_PROPERTY = "mcworldexplorer.home";
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    @TempDir
    Path tempDir;

    private final String originalHome = System.getProperty(HOME_PROPERTY);

    @AfterEach
    void restoreHome() {
        if (originalHome == null) {
            System.clearProperty(HOME_PROPERTY);
        } else {
            System.setProperty(HOME_PROPERTY, originalHome);
        }
    }

    @Test
    void generatesNetherEndAndModPreviewsWithoutModifyingSaves() throws IOException {
        String versionsDirectory = System.getenv("MCWORLD_TEST_VERSIONS_DIR");
        assumeTrue(versionsDirectory != null && !versionsDirectory.isBlank());

        List<WorldInfo> worlds = WorldScanner.scanSelectedPath(Path.of(versionsDirectory))
                .values().stream().flatMap(List::stream).toList();
        Map<DimensionKind, PreviewTarget> targets = findTargets(worlds);
        assertTrue(targets.containsKey(DimensionKind.NETHER), "No readable Nether target found");
        assertTrue(targets.containsKey(DimensionKind.END), "No readable End target found");
        assertTrue(targets.containsKey(DimensionKind.MOD), "No readable Mod target found");

        System.setProperty(HOME_PROPERTY, tempDir.toString());
        PreviewGenerator generator = new PreviewGenerator();
        PreviewCache cache = new PreviewCache();
        for (DimensionKind kind : List.of(DimensionKind.NETHER, DimensionKind.END, DimensionKind.MOD)) {
            PreviewTarget target = targets.get(kind);
            DimensionHeightRange range = DimensionHeightResolver.resolve(target.dimension());
            PreviewLayer layer = kind == DimensionKind.NETHER
                    ? range.bandContaining(64)
                    : PreviewLayer.surfaceOverview();
            PreviewRequest request = new PreviewRequest(
                    target.dimension(),
                    target.center(),
                    layer);
            List<Path> sources = relevantSources(target.world(), request);
            Map<Path, FileSnapshot> before = snapshot(sources);

            PreviewGenerationResult result = generator.generate(target.world(), request);
            PreviewCacheResult stored = cache.store(target.world(), request, result);

            assertTrue(result.sampledChunks() > 0, "No chunks sampled for " + target.dimension().id());
            assertTrue(result.populatedColumns() > 0, "No columns populated for " + target.dimension().id());
            assertEquals(stored, cache.findReusable(target.world(), request).orElseThrow());
            assertEquals(before, snapshot(sources),
                    "Save files changed while generating " + target.dimension().id());
            System.out.printf(
                    "Generated %s %s: sampled=%d, missing=%d, failed=%d, columns=%d%n",
                    target.dimension().id(),
                    layer,
                    result.sampledChunks(),
                    result.missingChunks(),
                    result.failedChunks(),
                    result.populatedColumns());
        }
    }

    private static Map<DimensionKind, PreviewTarget> findTargets(List<WorldInfo> worlds) throws IOException {
        Map<DimensionKind, PreviewTarget> targets = new EnumMap<>(DimensionKind.class);
        for (WorldInfo world : worlds) {
            for (WorldDimension dimension : WorldDimensionDiscovery.discover(world)) {
                if (dimension.kind() == DimensionKind.OVERWORLD || targets.containsKey(dimension.kind())) {
                    continue;
                }
                findFirstChunkCenter(dimension).ifPresent(center ->
                        targets.put(dimension.kind(), new PreviewTarget(world, dimension, center)));
            }
            if (targets.keySet().containsAll(List.of(
                    DimensionKind.NETHER,
                    DimensionKind.END,
                    DimensionKind.MOD))) {
                break;
            }
        }
        return targets;
    }

    private static Optional<PreviewCenter> findFirstChunkCenter(WorldDimension dimension) {
        SurfaceSampler sampler = new SurfaceSampler();
        try (Stream<Path> paths = Files.list(dimension.regionDirectory())) {
            for (Path regionFile : paths
                    .filter(Files::isRegularFile)
                    .filter(path -> REGION_NAME.matcher(path.getFileName().toString()).matches())
                    .sorted()
                    .limit(8)
                    .toList()) {
                Matcher matcher = REGION_NAME.matcher(regionFile.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                int regionX = Integer.parseInt(matcher.group(1));
                int regionZ = Integer.parseInt(matcher.group(2));
                try (RegionFileReader reader = new RegionFileReader(regionFile)) {
                    for (int localZ = 0; localZ < 32; localZ++) {
                        for (int localX = 0; localX < 32; localX++) {
                            Optional<RegionChunkData> chunk = reader.readChunk(localX, localZ);
                            if (chunk.isPresent()
                                    && sampler.sectionRange(chunk.orElseThrow()).isPresent()) {
                                int chunkX = regionX * 32 + localX;
                                int chunkZ = regionZ * 32 + localZ;
                                return Optional.of(new PreviewCenter(
                                        chunkX * 16 + 8,
                                        chunkZ * 16 + 8,
                                        PreviewCenterSource.DIMENSION_ORIGIN));
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // Try another real Region file from the same dimension.
                }
            }
        } catch (IOException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static List<Path> relevantSources(WorldInfo world, PreviewRequest request) throws IOException {
        Map<Path, Boolean> sources = new LinkedHashMap<>();
        Path levelDat = world.getFolderPath().resolve("level.dat");
        if (Files.isRegularFile(levelDat)) {
            sources.put(levelDat, true);
        }
        int minBlockX = request.center().x() - PreviewGenerator.BLOCK_RANGE / 2;
        int minBlockZ = request.center().z() - PreviewGenerator.BLOCK_RANGE / 2;
        int maxBlockX = minBlockX + PreviewGenerator.BLOCK_RANGE - 1;
        int maxBlockZ = minBlockZ + PreviewGenerator.BLOCK_RANGE - 1;
        int minRegionX = Math.floorDiv(minBlockX, 512);
        int minRegionZ = Math.floorDiv(minBlockZ, 512);
        int maxRegionX = Math.floorDiv(maxBlockX, 512);
        int maxRegionZ = Math.floorDiv(maxBlockZ, 512);
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                Path region = request.dimension().regionDirectory().resolve(
                        "r." + regionX + "." + regionZ + ".mca");
                if (Files.isRegularFile(region)) {
                    sources.put(region, true);
                }
            }
        }
        return List.copyOf(sources.keySet());
    }

    private static Map<Path, FileSnapshot> snapshot(List<Path> files) throws IOException {
        Map<Path, FileSnapshot> snapshots = new LinkedHashMap<>();
        for (Path file : files) {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            snapshots.put(file, new FileSnapshot(
                    attributes.size(),
                    attributes.lastModifiedTime().toMillis(),
                    sha256(file)));
        }
        return snapshots;
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        try (InputStream input = Files.newInputStream(file);
             DigestInputStream digestInput = new DigestInputStream(input, digest)) {
            digestInput.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record PreviewTarget(
            WorldInfo world,
            WorldDimension dimension,
            PreviewCenter center) {
    }

    private record FileSnapshot(long size, long modifiedMillis, String sha256) {
    }
}
