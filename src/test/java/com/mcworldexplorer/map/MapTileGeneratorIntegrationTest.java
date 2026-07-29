package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.DimensionKind;
import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.preview.WorldDimensionDiscovery;
import com.mcworldexplorer.storage.WorldCachePaths;
import com.mcworldexplorer.world.WorldInfo;
import com.mcworldexplorer.world.WorldScanner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MapTileGeneratorIntegrationTest {
    private static final Pattern REGION_NAME = Pattern.compile(
            "r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    @Test
    void generatesTilesForAvailableDimensionKindsWithoutChangingRegions() throws IOException {
        String versionsDirectory = System.getenv("MCWORLD_TEST_VERSIONS_DIR");
        assumeTrue(versionsDirectory != null && !versionsDirectory.isBlank());
        List<WorldInfo> worlds = WorldScanner.scanSelectedPath(Path.of(versionsDirectory))
                .values().stream()
                .flatMap(List::stream)
                .toList();
        Map<DimensionKind, Target> targets = findTargets(worlds);
        assumeTrue(targets.keySet().containsAll(List.of(
                DimensionKind.OVERWORLD,
                DimensionKind.NETHER,
                DimensionKind.END,
                DimensionKind.MOD)));

        MapTileGenerator generator = new MapTileGenerator();
        for (Target target : targets.values()) {
            AtomicInteger partials = new AtomicInteger();
            AtomicLong firstPartialNanos = new AtomicLong();
            FileState before = FileState.read(target.regionFile());
            Matcher matcher = REGION_NAME.matcher(target.regionFile().getFileName().toString());
            assertTrue(matcher.matches());
            long tileX = Long.parseLong(matcher.group(1));
            long tileZ = Long.parseLong(matcher.group(2));
            MapTileKey key = new MapTileKey(
                    WorldCachePaths.worldDirectoryName(target.world()),
                    target.dimension().id(),
                    PreviewLayer.surfaceOverview(),
                    MapZoomLevel.BLOCKS_2,
                    tileX,
                    tileZ,
                    "integration");

            long generationStarted = System.nanoTime();
            MapTileGenerationResult result = generator.generate(
                    target.world(),
                    target.dimension(),
                    key,
                    new MapTileGenerationMonitor() {
                        @Override
                        public double focusX() {
                            return key.bounds().minX() + 256;
                        }

                        @Override
                        public double focusZ() {
                            return key.bounds().minZ() + 256;
                        }

                        @Override
                        public void onPartial(MapTilePartialResult partial) {
                            partials.incrementAndGet();
                            firstPartialNanos.compareAndSet(0, System.nanoTime());
                            assertTrue(partial.completedChunks() <= partial.totalChunks());
                        }
                    });

            assertEquals(256, result.image().getWidth());
            assertEquals(256, result.image().getHeight());
            assertTrue(result.sampledChunks() > 0);
            if (result.populatedColumns() > 0) {
                assertTrue(partials.get() > 0);
                long firstPartialMillis =
                        (firstPartialNanos.get() - generationStarted) / 1_000_000;
                assertTrue(firstPartialMillis <= 1_500,
                        "first partial took " + firstPartialMillis + "ms for "
                                + target.dimension().id());
            }
            assertEquals(before, FileState.read(target.regionFile()));
        }
    }

    private static Map<DimensionKind, Target> findTargets(List<WorldInfo> worlds)
            throws IOException {
        Map<DimensionKind, Target> targets = new EnumMap<>(DimensionKind.class);
        for (WorldInfo world : worlds) {
            if (!world.isParsed()) {
                continue;
            }
            for (WorldDimension dimension : WorldDimensionDiscovery.discover(world)) {
                if (targets.containsKey(dimension.kind())) {
                    continue;
                }
                Path region = firstNonEmptyRegion(dimension.regionDirectory());
                if (region != null) {
                    targets.put(dimension.kind(), new Target(world, dimension, region));
                }
            }
            if (targets.size() == DimensionKind.values().length) {
                break;
            }
        }
        return targets;
    }

    private static Path firstNonEmptyRegion(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> REGION_NAME.matcher(path.getFileName().toString()).matches())
                    .filter(path -> {
                        try {
                            return Files.size(path) > 0;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .sorted()
                    .findFirst()
                    .orElse(null);
        }
    }

    private record Target(
            WorldInfo world,
            WorldDimension dimension,
            Path regionFile) {
    }

    private record FileState(long size, FileTime modified, String sha256) {
        static FileState read(Path path) throws IOException {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (var input = Files.newInputStream(path)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        digest.update(buffer, 0, read);
                    }
                }
                return new FileState(
                        Files.size(path),
                        Files.getLastModifiedTime(path),
                        HexFormat.of().formatHex(digest.digest()));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is unavailable", e);
            }
        }
    }
}
