package com.mcworldexplorer.voxel.io;

import com.mcworldexplorer.region.RegionChunkData;
import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.data.ChunkSection;
import com.mcworldexplorer.voxel.data.VoxelChunk;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ChunkNbtDecoder {
    private static final int MAX_PALETTE_SIZE = 16 * 16 * 16;
    private static final long MAX_CHUNK_NBT_BYTES = 64L * 1024 * 1024;
    private static final BinaryTagIO.Reader CHUNK_READER = BinaryTagIO.reader(MAX_CHUNK_NBT_BYTES);

    private ChunkNbtDecoder() {
    }

    public static DecodedChunk read(RegionChunkData data, int chunkX, int chunkZ)
            throws ChunkDecodeException {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        try (InputStream input = data.openNbtStream()) {
            return read(input, chunkX, chunkZ);
        } catch (ChunkDecodeException e) {
            throw e;
        } catch (IOException e) {
            throw new ChunkDecodeException(
                    ChunkDecodeException.Reason.INVALID_NBT,
                    -1,
                    "failed to close chunk NBT input",
                    e);
        }
    }

    public static DecodedChunk read(InputStream input) throws ChunkDecodeException {
        return readRoot(input, null, null);
    }

    public static DecodedChunk read(InputStream input, int chunkX, int chunkZ)
            throws ChunkDecodeException {
        return readRoot(input, chunkX, chunkZ);
    }

    private static DecodedChunk readRoot(InputStream input, Integer expectedX, Integer expectedZ)
            throws ChunkDecodeException {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        CompoundBinaryTag root;
        try {
            root = CHUNK_READER.read(input, BinaryTagIO.Compression.NONE);
        } catch (IOException | RuntimeException e) {
            throw new ChunkDecodeException(
                    ChunkDecodeException.Reason.INVALID_NBT,
                    -1,
                    "failed to parse chunk NBT",
                    e);
        }

        ChunkStorageLayout layout;
        ListBinaryTag sectionTags;
        CompoundBinaryTag coordinateContainer;
        if (root.keySet().contains("sections")) {
            layout = ChunkStorageLayout.MODERN_ROOT;
            sectionTags = root.getList("sections", BinaryTagTypes.COMPOUND);
            coordinateContainer = root;
        } else {
            CompoundBinaryTag level = root.getCompound("Level");
            if (!level.keySet().contains("Sections")) {
                throw new ChunkDecodeException(
                        ChunkDecodeException.Reason.UNSUPPORTED_CHUNK_LAYOUT,
                        -1,
                        "chunk has neither root sections nor Level/Sections palette data");
            }
            layout = ChunkStorageLayout.LEVEL_PALETTE;
            sectionTags = level.getList("Sections", BinaryTagTypes.COMPOUND);
            coordinateContainer = level;
        }

        int chunkX = expectedX == null ? storedCoordinate(coordinateContainer, "xPos") : expectedX;
        int chunkZ = expectedZ == null ? storedCoordinate(coordinateContainer, "zPos") : expectedZ;
        if (expectedX != null) {
            validateStoredCoordinates(coordinateContainer, expectedX, expectedZ);
        }

        List<ChunkSection> sections = new ArrayList<>();
        for (int i = 0; i < sectionTags.size(); i++) {
            ChunkSection section = readSection(sectionTags.getCompound(i), layout);
            if (section != null) {
                sections.add(section);
            }
        }
        if (layout == ChunkStorageLayout.LEVEL_PALETTE
                && sectionTags.size() > 0
                && sections.isEmpty()) {
            throw new ChunkDecodeException(
                    ChunkDecodeException.Reason.UNSUPPORTED_CHUNK_LAYOUT,
                    -1,
                    "Level/Sections contains no palette-based block states");
        }
        return new DecodedChunk(layout, new VoxelChunk(chunkX, chunkZ, sections));
    }

    private static int storedCoordinate(CompoundBinaryTag container, String key) {
        return container.keySet().contains(key) ? container.getInt(key) : 0;
    }

    private static void validateStoredCoordinates(
            CompoundBinaryTag container,
            int chunkX,
            int chunkZ) throws ChunkDecodeException {
        boolean hasX = container.keySet().contains("xPos");
        boolean hasZ = container.keySet().contains("zPos");
        if (hasX != hasZ) {
            throw new ChunkDecodeException(
                    ChunkDecodeException.Reason.CHUNK_COORDINATE_MISMATCH,
                    -1,
                    "chunk contains only one of xPos/zPos");
        }
        if (hasX && (container.getInt("xPos") != chunkX || container.getInt("zPos") != chunkZ)) {
            throw new ChunkDecodeException(
                    ChunkDecodeException.Reason.CHUNK_COORDINATE_MISMATCH,
                    -1,
                    "stored chunk coordinates " + container.getInt("xPos") + ","
                            + container.getInt("zPos") + " do not match Region slot "
                            + chunkX + "," + chunkZ);
        }
    }

    private static ChunkSection readSection(
            CompoundBinaryTag section,
            ChunkStorageLayout layout) throws ChunkDecodeException {
        if (!section.keySet().contains("Y")) {
            throw new ChunkDecodeException(
                    ChunkDecodeException.Reason.INVALID_SECTION,
                    -1,
                    "section is missing Y");
        }
        int sectionY = section.getInt("Y");
        ListBinaryTag palette;
        long[] data;
        if (layout == ChunkStorageLayout.MODERN_ROOT) {
            CompoundBinaryTag states = section.getCompound("block_states");
            if (states.keySet().isEmpty()) {
                return null;
            }
            palette = states.getList("palette", BinaryTagTypes.COMPOUND);
            data = states.getLongArray("data");
        } else {
            if (!section.keySet().contains("Palette")) {
                return null;
            }
            palette = section.getList("Palette", BinaryTagTypes.COMPOUND);
            data = section.getLongArray("BlockStates");
        }
        if (palette.size() == 0 || palette.size() > MAX_PALETTE_SIZE) {
            throw new ChunkDecodeException(
                    ChunkDecodeException.Reason.INVALID_PALETTE,
                    sectionY,
                    "section palette size is outside 1.." + MAX_PALETTE_SIZE);
        }

        List<BlockState> states = new ArrayList<>(palette.size());
        for (int i = 0; i < palette.size(); i++) {
            states.add(readState(palette.getCompound(i), sectionY, i));
        }
        return new ChunkSection(sectionY, states, data);
    }

    private static BlockState readState(
            CompoundBinaryTag state,
            int sectionY,
            int paletteIndex) throws ChunkDecodeException {
        String name = state.getString("Name");
        if (name == null || name.isBlank()) {
            throw new ChunkDecodeException(
                    ChunkDecodeException.Reason.INVALID_PALETTE,
                    sectionY,
                    "palette entry " + paletteIndex + " has no block name");
        }
        Map<String, String> properties = new TreeMap<>();
        if (state.keySet().contains("Properties")) {
            CompoundBinaryTag propertyTag = state.getCompound("Properties");
            for (String key : propertyTag.keySet()) {
                BinaryTag value = propertyTag.get(key);
                if (value == null || value.type() != BinaryTagTypes.STRING) {
                    throw new ChunkDecodeException(
                            ChunkDecodeException.Reason.INVALID_PALETTE_PROPERTY,
                            sectionY,
                            "palette entry " + paletteIndex
                                    + " property " + key + " is not a string");
                }
                properties.put(key, propertyTag.getString(key));
            }
        }
        try {
            return new BlockState(name, properties);
        } catch (IllegalArgumentException e) {
            throw new ChunkDecodeException(
                    ChunkDecodeException.Reason.INVALID_PALETTE_PROPERTY,
                    sectionY,
                    "palette entry " + paletteIndex + " has an invalid property",
                    e);
        }
    }
}
