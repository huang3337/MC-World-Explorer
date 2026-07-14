package com.mcworldexplorer.preview;

import com.mcworldexplorer.region.RegionChunkData;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SurfaceSampler {
    private static final int BLOCKS_PER_SECTION = 16 * 16 * 16;
    private static final long MAX_CHUNK_NBT_BYTES = 64L * 1024 * 1024;
    private static final BinaryTagIO.Reader CHUNK_READER = BinaryTagIO.reader(MAX_CHUNK_NBT_BYTES);

    public ChunkSurface sample(RegionChunkData chunkData) throws SurfaceSamplingException {
        if (chunkData == null) {
            throw new IllegalArgumentException("chunkData must not be null");
        }
        try (InputStream input = chunkData.openNbtStream()) {
            return sample(input);
        } catch (SurfaceSamplingException e) {
            throw e;
        } catch (IOException e) {
            throw new SurfaceSamplingException(
                    SurfaceSamplingException.Reason.INVALID_NBT,
                    "failed to close chunk NBT input",
                    e);
        }
    }

    ChunkSurface sample(InputStream input) throws SurfaceSamplingException {
        CompoundBinaryTag root;
        try {
            root = CHUNK_READER.read(input, BinaryTagIO.Compression.NONE);
        } catch (IOException | RuntimeException e) {
            throw new SurfaceSamplingException(
                    SurfaceSamplingException.Reason.INVALID_NBT,
                    "failed to parse chunk NBT",
                    e);
        }

        ChunkSurfaceLayout layout;
        ListBinaryTag sections;
        if (root.keySet().contains("sections")) {
            layout = ChunkSurfaceLayout.MODERN_ROOT;
            sections = root.getList("sections", BinaryTagTypes.COMPOUND);
        } else {
            CompoundBinaryTag level = root.getCompound("Level");
            if (!level.keySet().contains("Sections")) {
                throw new SurfaceSamplingException(
                        SurfaceSamplingException.Reason.UNSUPPORTED_CHUNK_LAYOUT,
                        "chunk has neither root sections nor Level/Sections palette data");
            }
            layout = ChunkSurfaceLayout.LEVEL_PALETTE;
            sections = level.getList("Sections", BinaryTagTypes.COMPOUND);
        }

        List<SectionView> sectionViews = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            CompoundBinaryTag section = sections.getCompound(i);
            SectionView view = readSection(section, layout);
            if (view != null) {
                sectionViews.add(view);
            }
        }
        sectionViews.sort(Comparator.comparingInt(SectionView::sectionY).reversed());

        ChunkSurface surface = new ChunkSurface(layout);
        for (SectionView section : sectionViews) {
            fillSurface(surface, section);
            if (surface.getPopulatedColumnCount() == ChunkSurface.WIDTH * ChunkSurface.WIDTH) {
                break;
            }
        }
        return surface;
    }

    private static SectionView readSection(
            CompoundBinaryTag section,
            ChunkSurfaceLayout layout) throws SurfaceSamplingException {
        if (!section.keySet().contains("Y")) {
            throw new SurfaceSamplingException(
                    SurfaceSamplingException.Reason.INVALID_SECTION,
                    "section is missing Y");
        }

        ListBinaryTag palette;
        long[] blockStates;
        if (layout == ChunkSurfaceLayout.MODERN_ROOT) {
            CompoundBinaryTag stateContainer = section.getCompound("block_states");
            if (stateContainer.keySet().isEmpty()) {
                return null;
            }
            palette = stateContainer.getList("palette", BinaryTagTypes.COMPOUND);
            blockStates = stateContainer.getLongArray("data");
        } else {
            if (!section.keySet().contains("Palette")) {
                return null;
            }
            palette = section.getList("Palette", BinaryTagTypes.COMPOUND);
            blockStates = section.getLongArray("BlockStates");
        }

        if (palette.size() == 0 || palette.size() > BLOCKS_PER_SECTION) {
            throw new SurfaceSamplingException(
                    SurfaceSamplingException.Reason.INVALID_PALETTE,
                    "section palette size is outside 1.." + BLOCKS_PER_SECTION);
        }
        String[] names = new String[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            String name = palette.getCompound(i).getString("Name");
            if (name == null || name.isBlank()) {
                throw new SurfaceSamplingException(
                        SurfaceSamplingException.Reason.INVALID_PALETTE,
                        "palette entry " + i + " has no block name");
            }
            names[i] = name;
        }

        return new SectionView(
                section.getInt("Y"),
                names,
                new BlockStateStorage(names.length, blockStates));
    }

    private static void fillSurface(ChunkSurface surface, SectionView section)
            throws SurfaceSamplingException {
        for (int localY = 15; localY >= 0; localY--) {
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    if (surface.hasColumn(localX, localZ)) {
                        continue;
                    }
                    int blockIndex = localY * 256 + localZ * 16 + localX;
                    int paletteIndex = section.storage().paletteIndex(blockIndex);
                    if (paletteIndex < 0 || paletteIndex >= section.palette().length) {
                        throw new SurfaceSamplingException(
                                SurfaceSamplingException.Reason.PALETTE_INDEX_OUT_OF_RANGE,
                                "palette index " + paletteIndex
                                        + " exceeds palette size " + section.palette().length);
                    }
                    String blockName = section.palette()[paletteIndex];
                    if (!isAir(blockName)) {
                        surface.setColumn(
                                localX,
                                localZ,
                                blockName,
                                section.sectionY() * 16 + localY);
                    }
                }
            }
        }
    }

    private static boolean isAir(String blockName) {
        return blockName.equals("minecraft:air")
                || blockName.equals("minecraft:cave_air")
                || blockName.equals("minecraft:void_air");
    }

    private record SectionView(int sectionY, String[] palette, BlockStateStorage storage) {
    }

    private static final class BlockStateStorage {
        private final int paletteSize;
        private final int bitsPerBlock;
        private final int valuesPerLong;
        private final long mask;
        private final long[] data;
        private final boolean padded;

        private BlockStateStorage(int paletteSize, long[] data) throws SurfaceSamplingException {
            this.paletteSize = paletteSize;
            this.data = data;
            if (paletteSize == 1) {
                bitsPerBlock = 0;
                valuesPerLong = 0;
                mask = 0;
                padded = true;
                return;
            }

            bitsPerBlock = Math.max(4, Integer.SIZE - Integer.numberOfLeadingZeros(paletteSize - 1));
            valuesPerLong = Long.SIZE / bitsPerBlock;
            mask = (1L << bitsPerBlock) - 1L;
            int paddedLength = divideRoundUp(BLOCKS_PER_SECTION, valuesPerLong);
            int compactLength = divideRoundUp(BLOCKS_PER_SECTION * bitsPerBlock, Long.SIZE);
            if (data.length == paddedLength) {
                padded = true;
            } else if (data.length == compactLength) {
                padded = false;
            } else {
                throw new SurfaceSamplingException(
                        SurfaceSamplingException.Reason.INVALID_BLOCK_STATE_STORAGE,
                        "block state data has " + data.length
                                + " longs, expected " + paddedLength + " or " + compactLength);
            }
        }

        private int paletteIndex(int blockIndex) {
            if (paletteSize == 1) {
                return 0;
            }
            if (padded) {
                int longIndex = blockIndex / valuesPerLong;
                int bitOffset = blockIndex % valuesPerLong * bitsPerBlock;
                return (int) ((data[longIndex] >>> bitOffset) & mask);
            }

            long bitIndex = (long) blockIndex * bitsPerBlock;
            int longIndex = (int) (bitIndex / Long.SIZE);
            int bitOffset = (int) (bitIndex % Long.SIZE);
            long value = data[longIndex] >>> bitOffset;
            if (bitOffset + bitsPerBlock > Long.SIZE) {
                value |= data[longIndex + 1] << (Long.SIZE - bitOffset);
            }
            return (int) (value & mask);
        }

        private static int divideRoundUp(int value, int divisor) {
            return (value + divisor - 1) / divisor;
        }
    }
}
