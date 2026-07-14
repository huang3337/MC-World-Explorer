package com.mcworldexplorer.preview;

import com.mcworldexplorer.nbt.LevelDatReader;
import com.mcworldexplorer.world.WorldInfo;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PreviewGeneratorIntegrationTest {
    private static final String HOME_PROPERTY = "mcworldexplorer.home";

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
    void generatesAndCachesRealPreviewWithoutModifyingWorld() throws IOException {
        String worldDirectory = System.getenv("MCWORLD_PREVIEW_TEST_WORLD");
        assumeTrue(worldDirectory != null && !worldDirectory.isBlank());

        Path worldPath = Path.of(worldDirectory);
        assumeTrue(Files.isRegularFile(worldPath.resolve("level.dat")));
        WorldInfo world = LevelDatReader.readLevelDat(worldPath);
        assertTrue(world.isParsed());
        PreviewCenter center = PreviewCenterResolver.resolve(world);
        Map<Path, FileSnapshot> before = snapshot(relevantRegionFiles(worldPath, center));

        long started = System.nanoTime();
        PreviewGenerationResult generated = new PreviewGenerator().generate(world);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(PreviewGenerator.OUTPUT_SIZE, generated.image().getWidth());
        assertEquals(PreviewGenerator.OUTPUT_SIZE, generated.image().getHeight());
        assertTrue(generated.sampledChunks() > 0);
        assertTrue(generated.populatedColumns() > 0);
        assertEquals(generated.totalChunks(),
                generated.sampledChunks() + generated.missingChunks() + generated.failedChunks());

        System.setProperty(HOME_PROPERTY, tempDir.toString());
        PreviewCacheResult cached = new PreviewCache().store(world, generated);
        assertTrue(Files.isRegularFile(cached.imagePath()));
        assertTrue(Files.isRegularFile(cached.metadataPath()));
        assertFalse(cached.imagePath().startsWith(worldPath));

        Map<Path, FileSnapshot> after = snapshot(relevantRegionFiles(worldPath, center));
        assertEquals(before, after, "Real Region files changed during preview generation");
        System.out.printf(
                "Generated %dx%d preview in %d ms without modification: "
                        + "chunks=%d/%d, missing=%d, failed=%d, columns=%d, unknown=%d%n",
                generated.image().getWidth(),
                generated.image().getHeight(),
                elapsedMillis,
                generated.sampledChunks(),
                generated.totalChunks(),
                generated.missingChunks(),
                generated.failedChunks(),
                generated.populatedColumns(),
                generated.unknownBlockColumns());
    }

    private static Map<Path, FileSnapshot> snapshot(Iterable<Path> files) throws IOException {
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

    private static Iterable<Path> relevantRegionFiles(Path worldPath, PreviewCenter center) {
        int minBlockX = center.x() - PreviewGenerator.BLOCK_RANGE / 2;
        int minBlockZ = center.z() - PreviewGenerator.BLOCK_RANGE / 2;
        int maxBlockX = minBlockX + PreviewGenerator.BLOCK_RANGE - 1;
        int maxBlockZ = minBlockZ + PreviewGenerator.BLOCK_RANGE - 1;
        int minRegionX = Math.floorDiv(Math.floorDiv(minBlockX, 16), 32);
        int minRegionZ = Math.floorDiv(Math.floorDiv(minBlockZ, 16), 32);
        int maxRegionX = Math.floorDiv(Math.floorDiv(maxBlockX, 16), 32);
        int maxRegionZ = Math.floorDiv(Math.floorDiv(maxBlockZ, 16), 32);

        Map<Path, Boolean> files = new LinkedHashMap<>();
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                Path path = worldPath.resolve("region")
                        .resolve("r." + regionX + "." + regionZ + ".mca");
                if (Files.isRegularFile(path)) {
                    files.put(path, true);
                }
            }
        }
        return files.keySet();
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

    private record FileSnapshot(long size, long lastModifiedMillis, String sha256) {
    }
}
