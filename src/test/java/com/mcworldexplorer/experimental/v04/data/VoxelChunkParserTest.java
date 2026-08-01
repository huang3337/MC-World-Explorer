package com.mcworldexplorer.experimental.v04.data;

import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VoxelChunkParserTest {
    @Test
    void preservesModernBlockPropertiesInStableOrder() throws Exception {
        CompoundBinaryTag state = CompoundBinaryTag.builder()
                .putString("Name", "minecraft:oak_log")
                .put("Properties", CompoundBinaryTag.builder()
                        .putString("waterlogged", "false")
                        .putString("axis", "y")
                        .build())
                .build();
        CompoundBinaryTag root = modernChunk(section(0, palette(state), new long[0]));

        VoxelChunk chunk = VoxelChunkParser.read(stream(root), -3, 7);
        VoxelBlockState block = chunk.blockState(2, 0, 4);

        assertEquals(-3, chunk.chunkX());
        assertEquals(7, chunk.chunkZ());
        assertEquals("minecraft:oak_log", block.name());
        assertEquals(Map.of("axis", "y", "waterlogged", "false"), block.properties());
        assertEquals(List.of("axis", "waterlogged"), new ArrayList<>(block.properties().keySet()));
        assertThrows(UnsupportedOperationException.class,
                () -> block.properties().put("axis", "x"));
    }

    @Test
    void decodesPaddedStorageAcrossNegativeSection() throws Exception {
        ListBinaryTag palette = palette(named("minecraft:air"), named("minecraft:deepslate"));
        long[] data = new long[256];
        setPadded(data, 4, blockIndex(2, 3, 4), 1);

        VoxelChunk chunk = VoxelChunkParser.read(
                stream(modernChunk(section(-2, palette, data))),
                0,
                0);

        assertEquals("minecraft:deepslate", chunk.blockState(2, -29, 4).name());
        assertEquals(VoxelBlockState.AIR, chunk.blockState(2, 20, 4));
    }

    @Test
    void decodesLegacyCompactCrossLongStorage() throws Exception {
        List<CompoundBinaryTag> entries = new ArrayList<>();
        entries.add(named("minecraft:air"));
        for (int i = 1; i <= 16; i++) {
            entries.add(named("example:block_" + i));
        }
        long[] data = new long[320];
        setCompact(data, 5, blockIndex(12, 0, 0), 16);
        CompoundBinaryTag section = CompoundBinaryTag.builder()
                .putInt("Y", 4)
                .put("Palette", palette(entries.toArray(CompoundBinaryTag[]::new)))
                .putLongArray("BlockStates", data)
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .put("Sections", sections(section))
                        .build())
                .build();

        VoxelChunk chunk = VoxelChunkParser.read(stream(root), 1, 2);

        assertEquals("example:block_16", chunk.blockState(12, 64, 0).name());
    }

    @Test
    void rejectsNonStringPropertyAndInvalidStorage() throws IOException {
        CompoundBinaryTag invalidProperty = CompoundBinaryTag.builder()
                .putString("Name", "minecraft:oak_log")
                .put("Properties", CompoundBinaryTag.builder().putInt("axis", 1).build())
                .build();
        VoxelDataException propertyFailure = assertThrows(
                VoxelDataException.class,
                () -> VoxelChunkParser.read(
                        stream(modernChunk(section(0, palette(invalidProperty), new long[0]))),
                        0,
                        0));
        assertEquals(VoxelDataException.Reason.INVALID_PALETTE_PROPERTY,
                propertyFailure.reason());

        CompoundBinaryTag invalidStorage = modernChunk(section(
                0,
                palette(named("minecraft:air"), named("minecraft:stone")),
                new long[1]));
        VoxelDataException storageFailure = assertThrows(
                VoxelDataException.class,
                () -> VoxelChunkParser.read(stream(invalidStorage), 0, 0));
        assertEquals(VoxelDataException.Reason.INVALID_BLOCK_STATE_STORAGE,
                storageFailure.reason());
    }

    @Test
    void rejectsBlankPropertyAndDuplicateSectionY() throws IOException {
        CompoundBinaryTag blankProperty = CompoundBinaryTag.builder()
                .putString("Name", "minecraft:oak_log")
                .put("Properties", CompoundBinaryTag.builder().putString("axis", "").build())
                .build();
        VoxelDataException propertyFailure = assertThrows(
                VoxelDataException.class,
                () -> VoxelChunkParser.read(
                        stream(modernChunk(section(0, palette(blankProperty), new long[0]))),
                        0,
                        0));
        assertEquals(VoxelDataException.Reason.INVALID_PALETTE_PROPERTY,
                propertyFailure.reason());

        CompoundBinaryTag duplicateSections = modernChunk(
                section(2, palette(named("minecraft:stone")), new long[0]),
                section(2, palette(named("minecraft:dirt")), new long[0]));
        VoxelDataException sectionFailure = assertThrows(
                VoxelDataException.class,
                () -> VoxelChunkParser.read(stream(duplicateSections), 0, 0));
        assertEquals(VoxelDataException.Reason.INVALID_SECTION, sectionFailure.reason());
        assertEquals(2, sectionFailure.sectionY());
    }

    @Test
    void rejectsStoredCoordinatesThatDifferFromRegionSlot() throws IOException {
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .putInt("xPos", 4)
                .putInt("zPos", -7)
                .put("sections", sections(section(
                        0,
                        palette(named("minecraft:stone")),
                        new long[0])))
                .build();

        VoxelDataException failure = assertThrows(
                VoxelDataException.class,
                () -> VoxelChunkParser.read(stream(root), 3, -7));

        assertEquals(VoxelDataException.Reason.CHUNK_COORDINATE_MISMATCH, failure.reason());
    }

    @Test
    void rejectsPreFlatteningSectionsWithoutPaletteData() throws IOException {
        CompoundBinaryTag oldSection = CompoundBinaryTag.builder()
                .putInt("Y", 0)
                .putByteArray("Blocks", new byte[4096])
                .putByteArray("Data", new byte[2048])
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 0)
                        .putInt("zPos", 0)
                        .put("Sections", sections(oldSection))
                        .build())
                .build();

        VoxelDataException failure = assertThrows(
                VoxelDataException.class,
                () -> VoxelChunkParser.read(stream(root), 0, 0));

        assertEquals(VoxelDataException.Reason.UNSUPPORTED_CHUNK_LAYOUT, failure.reason());
    }

    private static CompoundBinaryTag named(String name) {
        return CompoundBinaryTag.builder().putString("Name", name).build();
    }

    private static ListBinaryTag palette(CompoundBinaryTag... entries) {
        ListBinaryTag.Builder<CompoundBinaryTag> builder = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (CompoundBinaryTag entry : entries) {
            builder.add(entry);
        }
        return builder.build();
    }

    private static ListBinaryTag sections(CompoundBinaryTag... entries) {
        ListBinaryTag.Builder<CompoundBinaryTag> builder = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (CompoundBinaryTag entry : entries) {
            builder.add(entry);
        }
        return builder.build();
    }

    private static CompoundBinaryTag section(int y, ListBinaryTag palette, long[] data) {
        CompoundBinaryTag.Builder states = CompoundBinaryTag.builder().put("palette", palette);
        if (data.length > 0) {
            states.putLongArray("data", data);
        }
        return CompoundBinaryTag.builder()
                .putInt("Y", y)
                .put("block_states", states.build())
                .build();
    }

    private static CompoundBinaryTag modernChunk(CompoundBinaryTag... entries) {
        return CompoundBinaryTag.builder().put("sections", sections(entries)).build();
    }

    private static ByteArrayInputStream stream(CompoundBinaryTag root) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BinaryTagIO.writer().write(root, output, BinaryTagIO.Compression.NONE);
        return new ByteArrayInputStream(output.toByteArray());
    }

    private static int blockIndex(int x, int y, int z) {
        return y * 256 + z * 16 + x;
    }

    private static void setPadded(long[] data, int bits, int index, int value) {
        int valuesPerLong = Long.SIZE / bits;
        int longIndex = index / valuesPerLong;
        int bitOffset = index % valuesPerLong * bits;
        data[longIndex] |= (long) value << bitOffset;
    }

    private static void setCompact(long[] data, int bits, int index, int value) {
        long bitIndex = (long) index * bits;
        int longIndex = (int) (bitIndex / Long.SIZE);
        int bitOffset = (int) (bitIndex % Long.SIZE);
        data[longIndex] |= (long) value << bitOffset;
        if (bitOffset + bits > Long.SIZE) {
            data[longIndex + 1] |= (long) value >>> (Long.SIZE - bitOffset);
        }
    }
}
