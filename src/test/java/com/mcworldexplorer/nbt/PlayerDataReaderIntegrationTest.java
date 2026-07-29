package com.mcworldexplorer.nbt;

import com.mcworldexplorer.world.PlayerLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PlayerDataReaderIntegrationTest {
    @Test
    void readsRealMultiplayerPlayersWithoutChangingSourceFiles() throws IOException {
        String worldDirectory = System.getenv("MCWORLD_MULTIPLAYER_TEST_WORLD");
        assumeTrue(worldDirectory != null && !worldDirectory.isBlank());
        Path world = Path.of(worldDirectory);
        assumeTrue(Files.isDirectory(world.resolve("playerdata")));

        List<Path> sources;
        try (Stream<Path> files = Files.list(world.resolve("playerdata"))) {
            sources = Stream.concat(
                            Stream.of(world.resolve("level.dat")),
                            files.filter(path -> path.getFileName().toString().endsWith(".dat")))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        }
        Map<Path, FileState> before = states(sources);

        List<PlayerLocation> players = new PlayerDataReader().read(world);

        assertEquals(4, players.size());
        assertEquals(
                Set.of("huang3337", "13825", "ljf02119", "RockZhou"),
                players.stream().map(PlayerLocation::displayName).collect(
                        java.util.stream.Collectors.toSet()));
        assertTrue(players.stream().anyMatch(player ->
                "minecraft:overworld".equals(player.dimensionId())));
        assertTrue(players.stream().anyMatch(player ->
                "callfromthedepth_:depth".equals(player.dimensionId())));
        assertEquals(before, states(sources));
    }

    private static Map<Path, FileState> states(List<Path> files) throws IOException {
        Map<Path, FileState> states = new LinkedHashMap<>();
        for (Path file : files) {
            states.put(file, FileState.read(file));
        }
        return states;
    }

    private record FileState(long size, FileTime modified, String sha256) {
        private static FileState read(Path file) throws IOException {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = Files.newInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        digest.update(buffer, 0, read);
                    }
                }
                return new FileState(
                        Files.size(file),
                        Files.getLastModifiedTime(file),
                        HexFormat.of().formatHex(digest.digest()));
            } catch (NoSuchAlgorithmException failure) {
                throw new IllegalStateException("SHA-256 is unavailable", failure);
            }
        }
    }
}
