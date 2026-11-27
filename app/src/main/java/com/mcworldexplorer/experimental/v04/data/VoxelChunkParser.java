package com.mcworldexplorer.experimental.v04.data;

import com.mcworldexplorer.preview.ChunkSurfaceLayout;
import com.mcworldexplorer.region.RegionChunkData;
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

public final class VoxelChunkParser {
    private static final int MAX_PALETTE_SIZE = 16 * 16 * 16;
    private static final long MAX_CHUNK_NBT_BYTES = 64L * 1024 * 1024;
    private static final BinaryTagIO.Reader CHUNK_READER = BinaryTagIO.reader(MAX_CHUNK_NBT_BYTES);

    private VoxelChunkParser() {
    }

    public static VoxelChunk read(RegionChunkData data, int chunkX, int chunkZ)
            throws VoxelDataException {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        try (InputStream input = data.openNbtStream()) {
            return read(input, chunkX, chunkZ);
        } catch (VoxelDataException e) {
            throw e;
        } catch (IOException e) {
            throw new VoxelDataException(
                    VoxelDataException.Reason.INVALID_NBT,
                    -1,
                    "failed to close chunk NBT input",
                    e);
        }
    }

    static VoxelChunk read(InputStream input, int chunkX, int chunkZ)
            throws VoxelDataException {
        CompoundBinaryTag root;
        try {
            root = CHUNK_READER.read(input, BinaryTagIO.Compression.NONE);
        } catch (IOException | RuntimeException e) {
            throw new VoxelDataException(
                    VoxelDataException.Reason.INVALID_NBT,
                    -1,
                    "failed to parse chunk NBT",
                    e);
        }

        ChunkSurfaceLayout layout;
        ListBinaryTag sectionTags;
        CompoundBinaryTag coordinateContainer;
        if (root.keySet().contains("sections")) {
            layout = ChunkSurfaceLayout.MODERN_ROOT;
            sectionTags = root.getList("sections", BinaryTagTypes.COMPOUND);
            coordinateContainer = root;
        } else {
            CompoundBinaryTag level = root.getCompound("Level");
            if (!level.keySet().contains("Sections")) {
                throw new VoxelDataException(
                        VoxelDataException.Reason.UNSUPPORTED_CHUNK_LAYOUT,
                        -1,
                        "chunk has neither root sections nor Level/Sections palette data");
            }
            layout = ChunkSurfaceLayout.LEVEL_PALETTE;
            sectionTags = level.getList("Sections", BinaryTagTypes.COMPOUND);
            coordinateContainer = level;
        }
        validateStoredCoordinates(coordinateContainer, chunkX, chunkZ);

        List<VoxelSection> sections = new ArrayList<>();
        for (int i = 0; i < sectionTags.size(); i++) {
            VoxelSection section = readSection(sectionTags.getCompound(i), layout);
            if (section != null) {
                sections.add(section);
            }
        }
        if (layout == ChunkSurfaceLayout.LEVEL_PALETTE
                && sectionTags.size() > 0
                && sections.isEmpty()) {
            throw new VoxelDataException(
                    VoxelDataException.Reason.UNSUPPORTED_CHUNK_LAYOUT,
                    -1,
                    "Level/Sections contains no palette-based block states");
        }
        return new VoxelChunk(chunkX, chunkZ, sections);
    }

    private static void validateStoredCoordinates(
            CompoundBinaryTag container,
            int chunkX,
            int chunkZ) throws VoxelDataException {
        boolean hasX = container.keySet().contains("xPos");
        boolean hasZ = container.keySet().contains("zPos");
        if (hasX != hasZ) {
            throw new VoxelDataException(
                    VoxelDataException.Reason.CHUNK_COORDINATE_MISMATCH,
                    -1,
                    "chunk contains only one of xPos/zPos");
        }
        if (hasX && (container.getInt("xPos") != chunkX || container.getInt("zPos") != chunkZ)) {
            throw new VoxelDataException(
                    VoxelDataException.Reason.CHUNK_COORDINATE_MISMATCH,
                    -1,
                    "stored chunk coordinates " + container.getInt("xPos") + ","
                            + container.getInt("zPos") + " do not match Region slot "
                            + chunkX + "," + chunkZ);
        }
    }

    private static VoxelSection readSection(
            CompoundBinaryTag section,
            ChunkSurfaceLayout layout) throws VoxelDataException {
        if (!section.keySet().contains("Y")) {
            throw new VoxelDataException(
                    VoxelDataException.Reason.INVALID_SECTION,
                    -1,
                    "section is missing Y");
        }
        int sectionY = section.getInt("Y");
        ListBinaryTag palette;
        long[] data;
        if (layout == ChunkSurfaceLayout.MODERN_ROOT) {
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
            throw new VoxelDataException(
                    VoxelDataException.Reason.INVALID_PALETTE,
                    sectionY,
                    "section palette size is outside 1.." + MAX_PALETTE_SIZE);
        }

        List<VoxelBlockState> states = new ArrayList<>(palette.size());
        for (int i = 0; i < palette.size(); i++) {
            states.add(readState(palette.getCompound(i), sectionY, i));
        }
        try {
            return new VoxelSection(sectionY, states, data);
        } catch (VoxelDataException e) {
            if (e.sectionY() >= 0) {
                throw e;
            }
            throw new VoxelDataException(e.reason(), sectionY, e.getMessage(), e);
        }
    }

    private static VoxelBlockState readState(
            CompoundBinaryTag state,
            int sectionY,
            int paletteIndex) throws VoxelDataException {
        String name = state.getString("Name");
        if (name == null || name.isBlank()) {
            throw new VoxelDataException(
                    VoxelDataException.Reason.INVALID_PALETTE,
                    sectionY,
                    "palette entry " + paletteIndex + " has no block name");
        }
        Map<String, String> properties = new TreeMap<>();
        if (state.keySet().contains("Properties")) {
            CompoundBinaryTag propertyTag = state.getCompound("Properties");
            for (String key : propertyTag.keySet()) {
                BinaryTag value = propertyTag.get(key);
                if (value == null || value.type() != BinaryTagTypes.STRING) {
                    throw new VoxelDataException(
                            VoxelDataException.Reason.INVALID_PALETTE_PROPERTY,
                            sectionY,
                            "palette entry " + paletteIndex
                                    + " property " + key + " is not a string");
                }
                properties.put(key, propertyTag.getString(key));
            }
        }
        try {
            return new VoxelBlockState(name, properties);
        } catch (IllegalArgumentException e) {
            throw new VoxelDataException(
                    VoxelDataException.Reason.INVALID_PALETTE_PROPERTY,
                    sectionY,
                    "palette entry " + paletteIndex + " has an invalid property",
                    e);
        }
    }
}
