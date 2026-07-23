package com.mcworldexplorer.preview;

import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DimensionHeightResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void usesKnownVanillaDimensionDefinitions() throws IOException {
        Path world = tempDir.resolve("world");

        assertEquals(new DimensionHeightRange(-64, 319),
                DimensionHeightResolver.resolve(WorldDimension.overworld(world)));
        assertEquals(new DimensionHeightRange(0, 255),
                DimensionHeightResolver.resolve(WorldDimension.nether(world)));
        assertEquals(new DimensionHeightRange(0, 255),
                DimensionHeightResolver.resolve(WorldDimension.end(world)));
    }

    @Test
    void derivesModRangeFromActualChunkSections() throws IOException {
        Path regionDirectory = Files.createDirectories(tempDir.resolve("world/dimensions/example/caves/region"));
        Path regionFile = regionDirectory.resolve("r.0.0.mca");
        writeSingleChunkRegion(regionFile, chunkWithSections(-2, 4));
        WorldDimension dimension = new WorldDimension(
                "example:caves",
                "caves",
                regionDirectory,
                DimensionKind.MOD);

        DimensionHeightRange range = DimensionHeightResolver.resolve(dimension);

        assertEquals(new DimensionHeightRange(-32, 79), range);
    }

    @Test
    void skipsUnreadableChunksAndContinuesWithinTheSameRegion() throws IOException {
        Path regionDirectory = Files.createDirectories(tempDir.resolve("world/dimensions/example/caves/region"));
        Path regionFile = regionDirectory.resolve("r.0.0.mca");
        writeRegionWithUnreadableFirstChunk(regionFile, chunkWithSections(-1, 3));
        WorldDimension dimension = new WorldDimension(
                "example:caves",
                "caves",
                regionDirectory,
                DimensionKind.MOD);

        assertEquals(new DimensionHeightRange(-16, 63),
                DimensionHeightResolver.resolve(dimension));
    }

    private static CompoundBinaryTag chunkWithSections(int... sectionYs) {
        ListBinaryTag.Builder<CompoundBinaryTag> sections = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (int y : sectionYs) {
            sections.add(CompoundBinaryTag.builder()
                    .putInt("Y", y)
                    .put("block_states", CompoundBinaryTag.builder()
                            .put("palette", ListBinaryTag.builder(BinaryTagTypes.COMPOUND)
                                    .add(CompoundBinaryTag.builder()
                                            .putString("Name", "minecraft:stone")
                                            .build())
                                    .build())
                            .build())
                    .build());
        }
        return CompoundBinaryTag.builder().put("sections", sections.build()).build();
    }

    private static void writeSingleChunkRegion(Path path, CompoundBinaryTag root) throws IOException {
        Files.write(path, regionBytes(root, false));
    }

    private static void writeRegionWithUnreadableFirstChunk(
            Path path,
            CompoundBinaryTag validRoot) throws IOException {
        byte[] file = regionBytes(validRoot, true);
        Files.write(path, file);
    }

    private static byte[] regionBytes(CompoundBinaryTag root, boolean includeUnreadableFirstChunk)
            throws IOException {
        ByteArrayOutputStream nbt = new ByteArrayOutputStream();
        BinaryTagIO.writer().write(root, nbt, BinaryTagIO.Compression.NONE);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream output = new DeflaterOutputStream(compressed)) {
            output.write(nbt.toByteArray());
        }

        byte[] payload = compressed.toByteArray();
        int chunkLength = payload.length + 1;
        int sectorCount = (chunkLength + Integer.BYTES + 4095) / 4096;
        int validSector = includeUnreadableFirstChunk ? 3 : 2;
        byte[] file = new byte[(validSector + sectorCount) * 4096];
        if (includeUnreadableFirstChunk) {
            file[0] = 0;
            file[1] = 0;
            file[2] = 2;
            file[3] = 1;
            ByteBuffer.wrap(file).order(ByteOrder.BIG_ENDIAN)
                    .position(2 * 4096)
                    .putInt(2)
                    .put((byte) 99)
                    .put((byte) 0);
        }
        int locationIndex = includeUnreadableFirstChunk ? 4 : 0;
        file[locationIndex] = 0;
        file[locationIndex + 1] = 0;
        file[locationIndex + 2] = (byte) validSector;
        file[locationIndex + 3] = (byte) sectorCount;
        ByteBuffer buffer = ByteBuffer.wrap(file).order(ByteOrder.BIG_ENDIAN);
        buffer.position(validSector * 4096);
        buffer.putInt(chunkLength);
        buffer.put((byte) 2);
        buffer.put(payload);
        return file;
    }
}
