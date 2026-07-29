package com.mcworldexplorer.preview;

import java.util.Arrays;

public final class ChunkSectionView {
    private static final int BLOCKS_PER_SECTION = 16 * 16 * 16;

    private final int sectionY;
    private final String[] palette;
    private final BlockStateStorage storage;

    ChunkSectionView(int sectionY, String[] palette, long[] blockStates)
            throws SurfaceSamplingException {
        this.sectionY = sectionY;
        this.palette = Arrays.copyOf(palette, palette.length);
        this.storage = new BlockStateStorage(palette.length, blockStates);
    }

    public int sectionY() {
        return sectionY;
    }

    public boolean contains(String blockName) {
        for (String entry : palette) {
            if (entry.equals(blockName)) {
                return true;
            }
        }
        return false;
    }

    public String blockName(int localX, int localY, int localZ)
            throws SurfaceSamplingException {
        if (localX < 0 || localX >= 16
                || localY < 0 || localY >= 16
                || localZ < 0 || localZ >= 16) {
            throw new IndexOutOfBoundsException("local block coordinates must be between 0 and 15");
        }
        int blockIndex = localY * 256 + localZ * 16 + localX;
        int paletteIndex = storage.paletteIndex(blockIndex);
        if (paletteIndex < 0 || paletteIndex >= palette.length) {
            throw new SurfaceSamplingException(
                    SurfaceSamplingException.Reason.PALETTE_INDEX_OUT_OF_RANGE,
                    "palette index " + paletteIndex + " exceeds palette size " + palette.length);
        }
        return palette[paletteIndex];
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
            this.data = Arrays.copyOf(data, data.length);
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
