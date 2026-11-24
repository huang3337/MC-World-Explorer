package com.mcworldexplorer.voxel.io;

import com.mcworldexplorer.nbt.SavedLegacyIdMap;
import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.data.ChunkSection;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.ByteArrayBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes pre-flattening (1.2.1–1.12.2) Anvil chunk sections stored as fixed-size
 * {@code Blocks[4096]}, optional {@code Add[2048]}, and {@code Data[2048]} byte arrays
 * into compact palette-backed {@link ChunkSection} instances.
 */
final class LegacyAnvilSectionDecoder {
    private static final int BLOCKS_LENGTH = 4096;
    private static final int NIBBLE_LENGTH = 2048;

    private LegacyAnvilSectionDecoder() {
    }

    static ChunkSection decode(
            CompoundBinaryTag section,
            int sectionY,
            SavedLegacyIdMap legacyIdMap,
            boolean scene) throws ChunkDecodeException {
        byte[] blocks = requireByteArray(section, "Blocks", BLOCKS_LENGTH, sectionY);
        byte[] data = requireByteArray(section, "Data", NIBBLE_LENGTH, sectionY);
        byte[] add = optionalByteArray(section, "Add", NIBBLE_LENGTH, sectionY);
        rejectUnsupportedExtendedIds(section, sectionY);

        List<BlockState> palette = new ArrayList<>();
        Map<Integer, Integer> keyToPaletteIndex = new HashMap<>();
        Map<BlockState, Integer> stateToPaletteIndex = new HashMap<>();
        int[] indices = new int[BLOCKS_LENGTH];

        for (int index = 0; index < BLOCKS_LENGTH; index++) {
            int low = blocks[index] & 0xFF;
            int high = add == null ? 0 : nibble(add, index);
            int blockId = (high << 8) | low;
            int meta = nibble(data, index);
            int key = (blockId << 4) | meta;

            Integer paletteIndex = keyToPaletteIndex.get(key);
            if (paletteIndex == null) {
                BlockState state = VanillaLegacyBlocks.resolve(blockId, meta, legacyIdMap, scene);
                paletteIndex = stateToPaletteIndex.get(state);
                if (paletteIndex == null) {
                    paletteIndex = palette.size();
                    palette.add(state);
                    stateToPaletteIndex.put(state, paletteIndex);
                }
                keyToPaletteIndex.put(key, paletteIndex);
            }
            indices[index] = paletteIndex;
        }

        if (palette.size() == 1) {
            return new ChunkSection(sectionY, palette, new long[0]);
        }

        int bitsPerBlock = Math.max(4, Integer.SIZE - Integer.numberOfLeadingZeros(palette.size() - 1));
        int valuesPerLong = Long.SIZE / bitsPerBlock;
        long[] packed = new long[(BLOCKS_LENGTH + valuesPerLong - 1) / valuesPerLong];
        for (int i = 0; i < BLOCKS_LENGTH; i++) {
            int longIndex = i / valuesPerLong;
            int bitOffset = (i % valuesPerLong) * bitsPerBlock;
            packed[longIndex] |= ((long) indices[i]) << bitOffset;
        }
        return new ChunkSection(sectionY, palette, packed);
    }

    private static int nibble(byte[] array, int index) {
        int b = array[index >>> 1] & 0xFF;
        return (index & 1) == 0 ? (b & 0x0F) : ((b >>> 4) & 0x0F);
    }

    private static byte[] requireByteArray(
            CompoundBinaryTag section,
            String key,
            int expectedLength,
            int sectionY) throws ChunkDecodeException {
        BinaryTag tag = section.get(key);
        if (!(tag instanceof ByteArrayBinaryTag arrayTag)) {
            throw new ChunkDecodeException(
                    ChunkDecodeException.Reason.INVALID_BLOCK_STATE_STORAGE,
                    sectionY,
                    "legacy section is missing or has non-byte-array " + key);
        }
        byte[] bytes = arrayTag.value();
        if (bytes.length != expectedLength) {
            throw new ChunkDecodeException(
                    ChunkDecodeException.Reason.INVALID_BLOCK_STATE_STORAGE,
                    sectionY,
                    "legacy section " + key + " length is " + bytes.length + ", expected " + expectedLength);
        }
        return bytes;
    }

    private static byte[] optionalByteArray(
            CompoundBinaryTag section,
            String key,
            int expectedLength,
            int sectionY) throws ChunkDecodeException {
        BinaryTag tag = section.get(key);
        if (tag == null) {
            return null;
        }
        if (!(tag instanceof ByteArrayBinaryTag arrayTag)) {
            throw new ChunkDecodeException(
                    ChunkDecodeException.Reason.INVALID_BLOCK_STATE_STORAGE,
                    sectionY,
                    "legacy section " + key + " must be a byte array");
        }
        byte[] bytes = arrayTag.value();
        if (bytes.length != expectedLength) {
            throw new ChunkDecodeException(
                    ChunkDecodeException.Reason.INVALID_BLOCK_STATE_STORAGE,
                    sectionY,
                    "legacy section " + key + " length is " + bytes.length + ", expected " + expectedLength);
        }
        return bytes;
    }

    private static void rejectUnsupportedExtendedIds(CompoundBinaryTag section, int sectionY)
            throws ChunkDecodeException {
        BinaryTag add2Tag = section.get("Add2");
        if (add2Tag == null) {
            return;
        }
        if (!(add2Tag instanceof ByteArrayBinaryTag bytesTag)) {
            throw new ChunkDecodeException(
                    ChunkDecodeException.Reason.UNSUPPORTED_CHUNK_LAYOUT,
                    sectionY,
                    "legacy section contains unsupported Add2 extended block ID tag");
        }
        for (byte b : bytesTag.value()) {
            if (b != 0) {
                throw new ChunkDecodeException(
                        ChunkDecodeException.Reason.UNSUPPORTED_CHUNK_LAYOUT,
                        sectionY,
                        "legacy section uses non-zero Add2 (>4096 extended block IDs), which is unsupported");
            }
        }
    }
}
