package com.mcworldexplorer.experimental.v04.data;

import java.util.Arrays;
import java.util.List;

public final class VoxelSection {
    private static final int BLOCKS_PER_SECTION = 16 * 16 * 16;

    private final int sectionY;
    private final List<VoxelBlockState> palette;
    private final BlockStateStorage storage;

    public VoxelSection(int sectionY, List<VoxelBlockState> palette, long[] data)
            throws VoxelDataException {
        this.sectionY = sectionY;
        this.palette = List.copyOf(palette);
        this.storage = new BlockStateStorage(palette.size(), data);
    }

    public int sectionY() {
        return sectionY;
    }

    public VoxelBlockState blockState(int localX, int localY, int localZ)
            throws VoxelDataException {
        if (localX < 0 || localX >= 16
                || localY < 0 || localY >= 16
                || localZ < 0 || localZ >= 16) {
            throw new IndexOutOfBoundsException("local block coordinates must be between 0 and 15");
        }
        int blockIndex = localY * 256 + localZ * 16 + localX;
        int paletteIndex = storage.paletteIndex(blockIndex);
        if (paletteIndex < 0 || paletteIndex >= palette.size()) {
            throw new VoxelDataException(
                    VoxelDataException.Reason.PALETTE_INDEX_OUT_OF_RANGE,
                    sectionY,
                    "palette index " + paletteIndex + " is invalid at local block "
                            + localX + "," + localY + "," + localZ);
        }
        return palette.get(paletteIndex);
    }

    private static final class BlockStateStorage {
        private final int paletteSize;
        private final int bitsPerBlock;
        private final int valuesPerLong;
        private final long mask;
        private final long[] data;
        private final boolean padded;

        private BlockStateStorage(int paletteSize, long[] data) throws VoxelDataException {
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
                throw new VoxelDataException(
                        VoxelDataException.Reason.INVALID_BLOCK_STATE_STORAGE,
                        -1,
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
