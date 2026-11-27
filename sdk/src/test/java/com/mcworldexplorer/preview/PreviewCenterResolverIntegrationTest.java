package com.mcworldexplorer.preview;

import com.mcworldexplorer.nbt.LevelDatReader;
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
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PreviewCenterResolverIntegrationTest {
    @Test
    void resolvesRealWorldCentersWithoutModifyingLevelDatFiles() throws IOException {
        String versionsDirectory = System.getenv("MCWORLD_TEST_VERSIONS_DIR");
        assumeTrue(versionsDirectory != null && !versionsDirectory.isBlank());

        List<Path> levelDatFiles;
        try (Stream<Path> paths = Files.walk(Path.of(versionsDirectory))) {
            levelDatFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("level.dat"))
                    .toList();
        }
        assertFalse(levelDatFiles.isEmpty(), "No level.dat files found for center verification");

        Map<Path, FileSnapshot> before = snapshot(levelDatFiles);
        Map<PreviewCenterSource, Integer> sourceCounts = new EnumMap<>(PreviewCenterSource.class);
        for (Path levelDat : levelDatFiles) {
            WorldInfo info = LevelDatReader.readLevelDat(levelDat.getParent());
            assertTrue(info.isParsed(), "Failed to parse " + levelDat);

            PreviewCenter center = PreviewCenterResolver.resolve(info);
            sourceCounts.merge(center.source(), 1, Integer::sum);
        }
        Map<Path, FileSnapshot> after = snapshot(levelDatFiles);

        assertEquals(before, after, "Real level.dat files changed during center verification");
        assertEquals(levelDatFiles.size(), sourceCounts.values().stream().mapToInt(Integer::intValue).sum());
        System.out.printf("Verified %d centers without modification: %s%n", levelDatFiles.size(), sourceCounts);
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

    private record FileSnapshot(long size, long lastModifiedMillis, String sha256) {
    }
}
