package com.mcworldexplorer.preview;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SurfaceSamplerTest {
    private final SurfaceSampler sampler = new SurfaceSampler();

    @Test
    void samplesModernRootPaletteAndHighestNonAirBlock() throws IOException {
        ListBinaryTag palette = palette("minecraft:air", "minecraft:stone");
        long[] data = new long[256];
        setPadded(data, 4, blockIndex(2, 3, 4), 1);
        CompoundBinaryTag blockStates = CompoundBinaryTag.builder()
                .put("palette", palette)
                .putLongArray("data", data)
                .build();
        CompoundBinaryTag section = CompoundBinaryTag.builder()
                .putInt("Y", 0)
                .put("block_states", blockStates)
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("sections", sections(section))
                .build();

        ChunkSurface surface = sampler.sample(stream(root));

        assertEquals(ChunkSurfaceLayout.MODERN_ROOT, surface.getLayout());
        assertEquals(new SurfaceColumn("minecraft:stone", 3), surface.getColumn(2, 4).orElseThrow());
        assertFalse(surface.getColumn(0, 0).isPresent());
    }

    @Test
    void samplesSinglePaletteAcrossNegativeSection() throws IOException {
        CompoundBinaryTag blockStates = CompoundBinaryTag.builder()
                .put("palette", palette("minecraft:deepslate"))
                .build();
        CompoundBinaryTag section = CompoundBinaryTag.builder()
                .putInt("Y", -1)
                .put("block_states", blockStates)
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("sections", sections(section))
                .build();

        ChunkSurface surface = sampler.sample(stream(root));

        assertEquals(256, surface.getPopulatedColumnCount());
        assertEquals(new SurfaceColumn("minecraft:deepslate", -1),
                surface.getColumn(0, 0).orElseThrow());
    }

    @Test
    void samplesHighestWalkableFloorInsideHeightBand() throws IOException {
        ListBinaryTag palette = palette("minecraft:air", "minecraft:stone");
        long[] data = new long[256];
        setPadded(data, 4, blockIndex(2, 3, 4), 1);
        setPadded(data, 4, blockIndex(2, 8, 4), 1);
        setPadded(data, 4, blockIndex(2, 9, 4), 1);
        CompoundBinaryTag root = modernChunk(section(0, palette, data));

        ChunkSurface surface = sampler.sample(
                stream(root),
                PreviewLayer.heightBand(0, 7));

        assertEquals(new SurfaceColumn("minecraft:stone", 3),
                surface.getColumn(2, 4).orElseThrow());
    }

    @Test
    void checksHeadroomAcrossSectionBoundary() throws IOException {
        ListBinaryTag palette = palette("minecraft:air", "minecraft:stone");
        long[] lowerData = new long[256];
        setPadded(lowerData, 4, blockIndex(1, 15, 1), 1);
        CompoundBinaryTag upperAir = CompoundBinaryTag.builder()
                .putInt("Y", 1)
                .put("block_states", CompoundBinaryTag.builder()
                        .put("palette", palette("minecraft:air"))
                        .build())
                .build();
        CompoundBinaryTag root = modernChunk(section(0, palette, lowerData), upperAir);

        ChunkSurface surface = sampler.sample(
                stream(root),
                PreviewLayer.heightBand(0, 15));

        assertEquals(new SurfaceColumn("minecraft:stone", 15),
                surface.getColumn(1, 1).orElseThrow());
    }

    @Test
    void reportsActualSectionRange() throws IOException {
        CompoundBinaryTag lower = CompoundBinaryTag.builder()
                .putInt("Y", -2)
                .put("block_states", CompoundBinaryTag.builder()
                        .put("palette", palette("minecraft:deepslate"))
                        .build())
                .build();
        CompoundBinaryTag upper = CompoundBinaryTag.builder()
                .putInt("Y", 4)
                .put("block_states", CompoundBinaryTag.builder()
                        .put("palette", palette("minecraft:stone"))
                        .build())
                .build();

        assertEquals(
                new DimensionHeightRange(-32, 79),
                sampler.sectionRange(stream(modernChunk(lower, upper))).orElseThrow());
    }

    @Test
    void supportsLevelPaletteWithCompactCrossLongPacking() throws IOException {
        List<String> names = new ArrayList<>();
        names.add("minecraft:air");
        for (int i = 1; i <= 16; i++) {
            names.add("example:block_" + i);
        }
        long[] compact = new long[320];
        setCompact(compact, 5, blockIndex(12, 0, 0), 16);
        CompoundBinaryTag section = CompoundBinaryTag.builder()
                .putInt("Y", 4)
                .put("Palette", palette(names.toArray(String[]::new)))
                .putLongArray("BlockStates", compact)
                .build();
        CompoundBinaryTag level = CompoundBinaryTag.builder()
                .put("Sections", sections(section))
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder().put("Level", level).build();

        ChunkSurface surface = sampler.sample(stream(root));

        assertEquals(ChunkSurfaceLayout.LEVEL_PALETTE, surface.getLayout());
        assertEquals(new SurfaceColumn("example:block_16", 64),
                surface.getColumn(12, 0).orElseThrow());
    }

    @Test
    void supportsPaddedFiveBitPacking() throws IOException {
        List<String> names = new ArrayList<>();
        names.add("minecraft:air");
        for (int i = 1; i <= 16; i++) {
            names.add("example:block_" + i);
        }
        long[] padded = new long[342];
        setPadded(padded, 5, blockIndex(12, 0, 0), 16);
        CompoundBinaryTag blockStates = CompoundBinaryTag.builder()
                .put("palette", palette(names.toArray(String[]::new)))
                .putLongArray("data", padded)
                .build();
        CompoundBinaryTag section = CompoundBinaryTag.builder()
                .putInt("Y", 0)
                .put("block_states", blockStates)
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("sections", sections(section))
                .build();

        ChunkSurface surface = sampler.sample(stream(root));

        assertEquals(new SurfaceColumn("example:block_16", 0),
                surface.getColumn(12, 0).orElseThrow());
    }

    @Test
    void rejectsUnsupportedLayoutAndInvalidStorageLength() throws IOException {
        SurfaceSamplingException unsupported = assertThrows(
                SurfaceSamplingException.class,
                () -> sampler.sample(stream(CompoundBinaryTag.empty())));
        assertEquals(SurfaceSamplingException.Reason.UNSUPPORTED_CHUNK_LAYOUT, unsupported.getReason());

        CompoundBinaryTag blockStates = CompoundBinaryTag.builder()
                .put("palette", palette("minecraft:air", "minecraft:stone"))
                .putLongArray("data", new long[1])
                .build();
        CompoundBinaryTag section = CompoundBinaryTag.builder()
                .putInt("Y", 0)
                .put("block_states", blockStates)
                .build();
        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .put("sections", sections(section))
                .build();

        SurfaceSamplingException invalidStorage = assertThrows(
                SurfaceSamplingException.class,
                () -> sampler.sample(stream(root)));
        assertEquals(SurfaceSamplingException.Reason.INVALID_BLOCK_STATE_STORAGE,
                invalidStorage.getReason());
    }

    private static ListBinaryTag palette(String... names) {
        ListBinaryTag.Builder<CompoundBinaryTag> builder = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (String name : names) {
            builder.add(CompoundBinaryTag.builder().putString("Name", name).build());
        }
        return builder.build();
    }

    private static ListBinaryTag sections(CompoundBinaryTag... sections) {
        ListBinaryTag.Builder<CompoundBinaryTag> builder = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (CompoundBinaryTag section : sections) {
            builder.add(section);
        }
        return builder.build();
    }

    private static CompoundBinaryTag section(int y, ListBinaryTag palette, long[] data) {
        return CompoundBinaryTag.builder()
                .putInt("Y", y)
                .put("block_states", CompoundBinaryTag.builder()
                        .put("palette", palette)
                        .putLongArray("data", data)
                        .build())
                .build();
    }

    private static CompoundBinaryTag modernChunk(CompoundBinaryTag... sections) {
        return CompoundBinaryTag.builder().put("sections", sections(sections)).build();
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
