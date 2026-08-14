package com.mcworldexplorer.voxel.io;

import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.data.VoxelChunk;
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

class ChunkNbtDecoderTest {
    @Test
    void preservesModernPropertiesAndStoredCoordinates() throws Exception {
        CompoundBinaryTag state = CompoundBinaryTag.builder()
                .putString("Name", "minecraft:oak_log")
                .put("Properties", CompoundBinaryTag.builder()
                        .putString("waterlogged", "false")
                        .putString("axis", "y")
                        .build())
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .putInt("xPos", -3)
                .putInt("zPos", 7)
                .put("sections", sections(section(0, palette(state), new long[0])))
                .build();

        DecodedChunk decoded = ChunkNbtDecoder.read(stream(root), -3, 7);
        BlockState block = decoded.chunk().blockState(2, 0, 4);

        assertEquals(ChunkStorageLayout.MODERN_ROOT, decoded.layout());
        assertEquals(-3, decoded.chunk().chunkX());
        assertEquals(7, decoded.chunk().chunkZ());
        assertEquals("minecraft:oak_log", block.name());
        assertEquals(Map.of("axis", "y", "waterlogged", "false"), block.properties());
    }

    @Test
    void decodesPaddedStorageAcrossNegativeSection() throws Exception {
        long[] data = new long[256];
        setPadded(data, 4, blockIndex(2, 3, 4), 1);

        VoxelChunk chunk = ChunkNbtDecoder.read(stream(modernChunk(section(
                -2,
                palette(named("minecraft:air"), named("minecraft:deepslate")),
                data)))).chunk();

        assertEquals("minecraft:deepslate", chunk.blockState(2, -29, 4).name());
        assertEquals(BlockState.AIR, chunk.blockState(2, 20, 4));
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
        CompoundBinaryTag oldSection = CompoundBinaryTag.builder()
                .putInt("Y", 4)
                .put("Palette", palette(entries.toArray(CompoundBinaryTag[]::new)))
                .putLongArray("BlockStates", data)
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 1)
                        .putInt("zPos", 2)
                        .put("Sections", sections(oldSection))
                        .build())
                .build();

        DecodedChunk decoded = ChunkNbtDecoder.read(stream(root), 1, 2);

        assertEquals(ChunkStorageLayout.LEVEL_PALETTE, decoded.layout());
        assertEquals("example:block_16", decoded.chunk().blockState(12, 64, 0).name());
    }

    @Test
    void rejectsInvalidPropertyStorageAndDuplicateSection() throws IOException {
        CompoundBinaryTag invalidProperty = CompoundBinaryTag.builder()
                .putString("Name", "minecraft:oak_log")
                .put("Properties", CompoundBinaryTag.builder().putInt("axis", 1).build())
                .build();
        ChunkDecodeException propertyFailure = assertThrows(
                ChunkDecodeException.class,
                () -> ChunkNbtDecoder.read(stream(modernChunk(
                        section(0, palette(invalidProperty), new long[0])))));
        assertEquals(ChunkDecodeException.Reason.INVALID_PALETTE_PROPERTY,
                propertyFailure.reason());

        CompoundBinaryTag invalidStorage = modernChunk(section(
                0,
                palette(named("minecraft:air"), named("minecraft:stone")),
                new long[1]));
        ChunkDecodeException storageFailure = assertThrows(
                ChunkDecodeException.class,
                () -> ChunkNbtDecoder.read(stream(invalidStorage)));
        assertEquals(ChunkDecodeException.Reason.INVALID_BLOCK_STATE_STORAGE,
                storageFailure.reason());

        ChunkDecodeException duplicateFailure = assertThrows(
                ChunkDecodeException.class,
                () -> ChunkNbtDecoder.read(stream(modernChunk(
                        section(2, palette(named("minecraft:stone")), new long[0]),
                        section(2, palette(named("minecraft:dirt")), new long[0])))));
        assertEquals(ChunkDecodeException.Reason.INVALID_SECTION, duplicateFailure.reason());
        assertEquals(2, duplicateFailure.sectionY());
    }

    @Test
    void rejectsCoordinateMismatchAndPreFlatteningData() throws IOException {
        CompoundBinaryTag mismatch = CompoundBinaryTag.builder()
                .putInt("xPos", 4)
                .putInt("zPos", -7)
                .put("sections", sections(section(
                        0,
                        palette(named("minecraft:stone")),
                        new long[0])))
                .build();
        ChunkDecodeException coordinateFailure = assertThrows(
                ChunkDecodeException.class,
                () -> ChunkNbtDecoder.read(stream(mismatch), 3, -7));
        assertEquals(ChunkDecodeException.Reason.CHUNK_COORDINATE_MISMATCH,
                coordinateFailure.reason());

        CompoundBinaryTag oldSection = CompoundBinaryTag.builder()
                .putInt("Y", 0)
                .putByteArray("Blocks", new byte[4096])
                .putByteArray("Data", new byte[2048])
                .build();
        CompoundBinaryTag oldRoot = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder()
                        .putInt("xPos", 0)
                        .putInt("zPos", 0)
                        .put("Sections", sections(oldSection))
                        .build())
                .build();
        ChunkDecodeException oldFailure = assertThrows(
                ChunkDecodeException.class,
                () -> ChunkNbtDecoder.read(stream(oldRoot), 0, 0));
        assertEquals(ChunkDecodeException.Reason.UNSUPPORTED_CHUNK_LAYOUT,
                oldFailure.reason());
    }

    private static CompoundBinaryTag named(String name) {
        return CompoundBinaryTag.builder().putString("Name", name).build();
    }

    private static ListBinaryTag palette(CompoundBinaryTag... entries) {
        ListBinaryTag.Builder<CompoundBinaryTag> builder =
                ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (CompoundBinaryTag entry : entries) {
            builder.add(entry);
        }
        return builder.build();
    }

    private static ListBinaryTag sections(CompoundBinaryTag... entries) {
        ListBinaryTag.Builder<CompoundBinaryTag> builder =
                ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
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
