package com.mcworldexplorer.region;

import com.mcworldexplorer.nbt.LevelDatReader;
import com.mcworldexplorer.preview.PreviewCenter;
import com.mcworldexplorer.preview.PreviewCenterResolver;
import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RegionFileReaderIntegrationTest {
    @Test
    void readsCenterChunksWithoutModifyingRealRegionFiles() throws IOException {
        String versionsDirectory = System.getenv("MCWORLD_TEST_VERSIONS_DIR");
        assumeTrue(versionsDirectory != null && !versionsDirectory.isBlank());

        List<Path> levelDatFiles;
        try (Stream<Path> paths = Files.walk(Path.of(versionsDirectory))) {
            levelDatFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("level.dat"))
                    .toList();
        }
        assertFalse(levelDatFiles.isEmpty(), "No level.dat files found for Region verification");

        Map<Path, ChunkTarget> targets = new LinkedHashMap<>();
        for (Path levelDat : levelDatFiles) {
            WorldInfo world = LevelDatReader.readLevelDat(levelDat.getParent());
            assertTrue(world.isParsed(), "Failed to parse " + levelDat);
            PreviewCenter center = PreviewCenterResolver.resolve(world);
            int chunkX = Math.floorDiv(center.x(), 16);
            int chunkZ = Math.floorDiv(center.z(), 16);
            int regionX = Math.floorDiv(chunkX, 32);
            int regionZ = Math.floorDiv(chunkZ, 32);
            Path region = world.getFolderPath().resolve("region")
                    .resolve("r." + regionX + "." + regionZ + ".mca");
            if (Files.isRegularFile(region)) {
                targets.put(region, new ChunkTarget(Math.floorMod(chunkX, 32), Math.floorMod(chunkZ, 32)));
            }
        }
        assertFalse(targets.isEmpty(), "No center Region files found");

        Map<Path, FileSnapshot> before = snapshot(targets.keySet().stream().toList());
        Map<String, Integer> outcomes = new HashMap<>();
        for (Map.Entry<Path, ChunkTarget> entry : targets.entrySet()) {
            ChunkTarget target = entry.getValue();
            try (RegionFileReader reader = new RegionFileReader(entry.getKey())) {
                String outcome = reader.readChunk(target.localX(), target.localZ())
                        .map(chunk -> chunk.getCompression().name())
                        .orElse("MISSING");
                outcomes.merge(outcome, 1, Integer::sum);
            } catch (RegionReadException e) {
                outcomes.merge(e.getReason().name(), 1, Integer::sum);
            }
        }
        Map<Path, FileSnapshot> after = snapshot(targets.keySet().stream().toList());

        assertEquals(before, after, "Real Region files changed during verification");
        assertTrue(outcomes.getOrDefault("GZIP", 0)
                + outcomes.getOrDefault("ZLIB", 0)
                + outcomes.getOrDefault("UNCOMPRESSED", 0) > 0,
                "No supported center chunks were read: " + outcomes);
        System.out.printf("Verified %d Region files without modification: %s%n", targets.size(), outcomes);
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

    private record ChunkTarget(int localX, int localZ) {
    }

    private record FileSnapshot(long size, long lastModifiedMillis, String sha256) {
    }
}
