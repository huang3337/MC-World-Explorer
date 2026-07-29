package com.mcworldexplorer.nbt;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.world.PlayerLocation;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.DoubleBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerDataReaderTest {
    private static final UUID FIRST =
            UUID.fromString("5b35922a-b98e-4cef-b229-ca5890e979b6");
    private static final UUID SECOND =
            UUID.fromString("6e247085-30f8-4d7c-8408-73447f628018");

    @TempDir
    Path tempDirectory;

    @Test
    void readsPlayersAndNamesFromBoundedInstanceCaches() throws IOException {
        Path instance = tempDirectory.resolve("instance");
        Path world = instance.resolve("saves").resolve("world");
        writePlayer(world, FIRST, "minecraft:overworld", -713.7, 65, -602.2);
        writePlayer(world, SECOND, "callfromthedepth_:depth", 1623.4, 46, -2528.1);
        Files.writeString(
                instance.resolve("usernamecache.json"),
                "{\"" + FIRST + "\":\"huang3337\"}",
                StandardCharsets.UTF_8);
        Files.writeString(
                instance.resolve("usercache.json"),
                "[{\"name\":\"ljf02119\",\"uuid\":\"" + SECOND + "\"}]",
                StandardCharsets.UTF_8);

        List<PlayerLocation> players = new PlayerDataReader().read(world);

        assertEquals(2, players.size());
        assertEquals("huang3337", players.get(0).displayName());
        assertEquals(WorldDimension.OVERWORLD_ID, players.get(0).dimensionId());
        assertEquals(-713.7, players.get(0).x());
        assertEquals("ljf02119", players.get(1).displayName());
        assertEquals("callfromthedepth_:depth", players.get(1).dimensionId());
    }

    @Test
    void skipsBrokenPlayersAndFallsBackToShortUuidName() throws IOException {
        Path world = tempDirectory.resolve("world");
        writePlayer(world, FIRST, "-1", 12, 70, 34);
        Path playerDirectory = world.resolve("playerdata");
        Files.writeString(
                playerDirectory.resolve(SECOND + ".dat"),
                "not nbt",
                StandardCharsets.UTF_8);
        Files.writeString(
                playerDirectory.resolve("not-a-uuid.dat"),
                "ignored",
                StandardCharsets.UTF_8);

        List<PlayerLocation> players = new PlayerDataReader().read(world);

        assertEquals(1, players.size());
        assertEquals("5b35922a", players.get(0).displayName());
        assertEquals(WorldDimension.NETHER_ID, players.get(0).dimensionId());
    }

    @Test
    void ignoresPlayerWithMissingPosition() throws IOException {
        Path world = tempDirectory.resolve("world");
        Path playerDirectory = Files.createDirectories(world.resolve("playerdata"));
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .putString("Dimension", "minecraft:overworld")
                .build();
        BinaryTagIO.writer().write(
                root,
                playerDirectory.resolve(FIRST + ".dat"),
                BinaryTagIO.Compression.GZIP);

        assertTrue(new PlayerDataReader().read(world).isEmpty());
    }

    private static void writePlayer(
            Path world,
            UUID uuid,
            String dimension,
            double x,
            double y,
            double z) throws IOException {
        Path playerDirectory = Files.createDirectories(world.resolve("playerdata"));
        ListBinaryTag position = ListBinaryTag.from(List.of(
                DoubleBinaryTag.doubleBinaryTag(x),
                DoubleBinaryTag.doubleBinaryTag(y),
                DoubleBinaryTag.doubleBinaryTag(z)));
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("Pos", position)
                .putString("Dimension", dimension)
                .build();
        BinaryTagIO.writer().write(
                root,
                playerDirectory.resolve(uuid + ".dat"),
                BinaryTagIO.Compression.GZIP);
    }
}
