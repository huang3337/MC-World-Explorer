package com.mcworldexplorer.nbt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mcworldexplorer.world.GameType;
import com.mcworldexplorer.world.WorldInfo;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public class LevelDatReaderTest {

    @TempDir
    Path tempDir;

    @Test
    public void testReaderHandlesMissingDirectory() {
        Path fakePath = Paths.get("some/fake/path/that/does/not/exist");
        assertThrows(IOException.class, () -> {
            LevelDatReader.readLevelDat(fakePath);
        });
    }

    @Test
    void returnsUnparsedWorldForUnsupportedContent() throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("broken-world"));
        Files.writeString(worldFolder.resolve("level.dat"), "not nbt data");

        WorldInfo info = LevelDatReader.readLevelDat(worldFolder);

        assertFalse(info.isParsed());
        assertEquals("broken-world", info.getLevelName());
    }

    @Test
    void usesSafeDefaultsWhenOptionalBasicFieldsAreMissing() throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("minimal-world"));
        CompoundBinaryTag data = CompoundBinaryTag.builder()
                .putString("UnrelatedField", "value")
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("Data", data)
                .build();
        BinaryTagIO.writer().write(root, worldFolder.resolve("level.dat"), BinaryTagIO.Compression.GZIP);

        WorldInfo info = LevelDatReader.readLevelDat(worldFolder);

        assertTrue(info.isParsed());
        assertEquals("minimal-world", info.getLevelName());
        assertEquals(GameType.SURVIVAL, info.getGameType());
        assertEquals(0, info.getLastPlayed());
    }

    @Test
    void returnsUnparsedWorldWhenDataTagIsMissing() throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("missing-data"));
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .putString("UnrelatedField", "value")
                .build();
        BinaryTagIO.writer().write(root, worldFolder.resolve("level.dat"), BinaryTagIO.Compression.GZIP);

        WorldInfo info = LevelDatReader.readLevelDat(worldFolder);

        assertFalse(info.isParsed());
        assertEquals("missing-data", info.getLevelName());
    }

    @Test
    void readsFolderCreationGameTimeAndCompleteSpawnPosition() throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("detail-world"));
        CompoundBinaryTag data = CompoundBinaryTag.builder()
                .putString("LevelName", "Detail World")
                .putLong("Time", 72_000L)
                .putInt("SpawnX", 0)
                .putInt("SpawnY", 64)
                .putInt("SpawnZ", -12)
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder().put("Data", data).build();
        BinaryTagIO.writer().write(root, worldFolder.resolve("level.dat"), BinaryTagIO.Compression.GZIP);

        WorldInfo info = LevelDatReader.readLevelDat(worldFolder);

        assertTrue(info.isFolderCreationTimeAvailable());
        assertTrue(info.getFolderCreationTime() > 0);
        assertEquals(72_000L, info.getGameTime());
        assertTrue(info.isSpawnPositionAvailable());
        assertEquals(0, info.getSpawnX());
        assertEquals(64, info.getSpawnY());
        assertEquals(-12, info.getSpawnZ());
    }

    @Test
    void keepsSpawnPositionUnavailableWhenAnyCoordinateIsMissing() throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("partial-spawn-world"));
        CompoundBinaryTag data = CompoundBinaryTag.builder()
                .putInt("SpawnX", 1)
                .putInt("SpawnY", 2)
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder().put("Data", data).build();
        BinaryTagIO.writer().write(root, worldFolder.resolve("level.dat"), BinaryTagIO.Compression.GZIP);

        WorldInfo info = LevelDatReader.readLevelDat(worldFolder);

        assertFalse(info.isSpawnPositionAvailable());
    }

    @Test
    void readsPlayerRespawnPositionAndStringDimension() throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("player-respawn-world"));
        CompoundBinaryTag player = CompoundBinaryTag.builder()
                .putInt("SpawnX", -120)
                .putInt("SpawnY", 70)
                .putInt("SpawnZ", 320)
                .putString("SpawnDimension", "minecraft:overworld")
                .build();
        CompoundBinaryTag data = CompoundBinaryTag.builder().put("Player", player).build();
        CompoundBinaryTag root = CompoundBinaryTag.builder().put("Data", data).build();
        BinaryTagIO.writer().write(root, worldFolder.resolve("level.dat"), BinaryTagIO.Compression.GZIP);

        WorldInfo info = LevelDatReader.readLevelDat(worldFolder);

        assertTrue(info.isPlayerRespawnPositionAvailable());
        assertEquals(-120, info.getPlayerRespawnX());
        assertEquals(70, info.getPlayerRespawnY());
        assertEquals(320, info.getPlayerRespawnZ());
        assertEquals("minecraft:overworld", info.getPlayerRespawnDimension());
    }

    @Test
    void readsLegacyNumericPlayerRespawnDimension() throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("legacy-respawn-world"));
        CompoundBinaryTag player = CompoundBinaryTag.builder()
                .putInt("SpawnX", 10)
                .putInt("SpawnY", 64)
                .putInt("SpawnZ", 20)
                .putInt("SpawnDimension", 0)
                .build();
        CompoundBinaryTag data = CompoundBinaryTag.builder().put("Player", player).build();
        CompoundBinaryTag root = CompoundBinaryTag.builder().put("Data", data).build();
        BinaryTagIO.writer().write(root, worldFolder.resolve("level.dat"), BinaryTagIO.Compression.GZIP);

        WorldInfo info = LevelDatReader.readLevelDat(worldFolder);

        assertTrue(info.isPlayerRespawnPositionAvailable());
        assertEquals("0", info.getPlayerRespawnDimension());
    }

    @Test
    void ignoresIncompletePlayerRespawnPosition() throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("partial-player-respawn-world"));
        CompoundBinaryTag player = CompoundBinaryTag.builder()
                .putInt("SpawnX", 10)
                .putInt("SpawnZ", 20)
                .putString("SpawnDimension", "minecraft:overworld")
                .build();
        CompoundBinaryTag data = CompoundBinaryTag.builder().put("Player", player).build();
        CompoundBinaryTag root = CompoundBinaryTag.builder().put("Data", data).build();
        BinaryTagIO.writer().write(root, worldFolder.resolve("level.dat"), BinaryTagIO.Compression.GZIP);

        WorldInfo info = LevelDatReader.readLevelDat(worldFolder);

        assertFalse(info.isPlayerRespawnPositionAvailable());
    }

    @Test
    void readsLevelDatLargerThanTheLibraryDefaultLimit() throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("large-world"));
        CompoundBinaryTag data = CompoundBinaryTag.builder()
                .putString("LevelName", "Large Modpack World")
                .putByteArray("ModData", new byte[512 * 1024])
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("Data", data)
                .build();
        BinaryTagIO.writer().write(root, worldFolder.resolve("level.dat"), BinaryTagIO.Compression.GZIP);

        WorldInfo info = LevelDatReader.readLevelDat(worldFolder);

        assertTrue(info.isParsed());
        assertEquals("Large Modpack World", info.getLevelName());
    }

    @Test
    void keepsAnUpperBoundForAbnormallyLargeLevelDat() throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("oversized-world"));
        CompoundBinaryTag data = CompoundBinaryTag.builder()
                .putString("LevelName", "Oversized World")
                .putByteArray("ModData", new byte[(int) LevelDatReader.MAX_LEVEL_DAT_BYTES + 1])
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("Data", data)
                .build();
        BinaryTagIO.writer().write(root, worldFolder.resolve("level.dat"), BinaryTagIO.Compression.GZIP);

        WorldInfo info = LevelDatReader.readLevelDat(worldFolder);

        assertFalse(info.isParsed());
    }
}
