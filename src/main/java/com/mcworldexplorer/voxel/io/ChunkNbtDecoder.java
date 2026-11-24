package com.mcworldexplorer.voxel.io;

import com.mcworldexplorer.nbt.SavedLegacyIdMap;
import com.mcworldexplorer.region.RegionChunkData;
import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.data.ChunkSection;
import com.mcworldexplorer.voxel.data.VoxelChunk;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

public final class ChunkNbtDecoder {
    private static final int MAX_PALETTE_SIZE = 16 * 16 * 16;
    private static final long MAX_CHUNK_NBT_BYTES = 64L * 1024 * 1024;
    private static final BinaryTagIO.Reader CHUNK_READER = BinaryTagIO.reader(MAX_CHUNK_NBT_BYTES);

    private ChunkNbtDecoder() {
    }

    public static DecodedChunk read(RegionChunkData data, int chunkX, int chunkZ)
            throws ChunkDecodeException {
        return read(data, chunkX, chunkZ, SavedLegacyIdMap.vanillaOnly());
    }

    public static DecodedChunk read(
            RegionChunkData data,
            int chunkX,
            int chunkZ,
            SavedLegacyIdMap legacyIdMap) throws ChunkDecodeException {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        try (InputStream input = data.openNbtStream()) {
            return read(input, chunkX, chunkZ, legacyIdMap);
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
        return readRoot(input, null, null, true, SavedLegacyIdMap.vanillaOnly());
    }

    public static DecodedChunk read(InputStream input, SavedLegacyIdMap legacyIdMap)
            throws ChunkDecodeException {
        return readRoot(input, null, null, true, legacyIdMap);
    }

    public static DecodedChunk read(InputStream input, int chunkX, int chunkZ)
            throws ChunkDecodeException {
        return readRoot(input, chunkX, chunkZ, true, SavedLegacyIdMap.vanillaOnly());
    }

    public static DecodedChunk read(
            InputStream input,
            int chunkX,
            int chunkZ,
            SavedLegacyIdMap legacyIdMap) throws ChunkDecodeException {
        return readRoot(input, chunkX, chunkZ, true, legacyIdMap);
    }

    /** Surface and portal sampling require block identity only. */
    public static DecodedChunk readSurface(InputStream input) throws ChunkDecodeException {
        return readRoot(input, null, null, false, SavedLegacyIdMap.vanillaOnly());
    }

    /** Surface and portal sampling with optional Forge/FML numeric block ID mapping. */
    public static DecodedChunk readSurface(InputStream input, SavedLegacyIdMap legacyIdMap)
            throws ChunkDecodeException {
        return readRoot(input, null, null, false, legacyIdMap);
    }

    private static DecodedChunk readRoot(
            InputStream input,
            Integer expectedX,
            Integer expectedZ,
            boolean scene,
            SavedLegacyIdMap legacyIdMap) throws ChunkDecodeException {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        SavedLegacyIdMap effectiveLegacyMap = legacyIdMap == null
                ? SavedLegacyIdMap.vanillaOnly()
                : legacyIdMap;
        CompoundBinaryTag root;
        try {
            // Region chunks are already in memory. Keep the NBT reader's size and depth checks.
            root = input instanceof java.io.ByteArrayInputStream
                    ? CHUNK_READER.read((java.io.DataInput) new java.io.DataInputStream(input))
                    : CHUNK_READER.read(input, BinaryTagIO.Compression.NONE);
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
                        "chunk has neither root sections nor Level/Sections data");
            }
            sectionTags = level.getList("Sections", BinaryTagTypes.COMPOUND);
            coordinateContainer = level;
            boolean sawPalette = false;
            boolean sawLegacy = false;
            for (int i = 0; i < sectionTags.size(); i++) {
                CompoundBinaryTag sectionTag = sectionTags.getCompound(i);
                if (sectionTag.keySet().contains("Palette")) {
                    sawPalette = true;
                } else if (sectionTag.keySet().contains("Blocks")) {
                    sawLegacy = true;
                }
            }
            if (sawPalette && sawLegacy) {
                throw new ChunkDecodeException(
                        ChunkDecodeException.Reason.INVALID_SECTION,
                        -1,
                        "Level/Sections mixes palette-based and pre-flattening block storage");
            }
            layout = sawLegacy ? ChunkStorageLayout.LEVEL_LEGACY : ChunkStorageLayout.LEVEL_PALETTE;
        }

        int chunkX = expectedX == null ? storedCoordinate(coordinateContainer, "xPos") : expectedX;
        int chunkZ = expectedZ == null ? storedCoordinate(coordinateContainer, "zPos") : expectedZ;
        if (expectedX != null) {
            validateStoredCoordinates(coordinateContainer, expectedX, expectedZ);
        }

        int version = root.get("DataVersion") instanceof IntBinaryTag number ? number.value() : -1;
        var paletteReader = new BlockPaletteDecoder(version, scene);
        List<ChunkSection> sections = new ArrayList<>();
        var biomes = new TreeMap<Integer, com.mcworldexplorer.voxel.data.BiomeSection>();
        for (int i = 0; i < sectionTags.size(); i++) {
            var sectionTag = sectionTags.getCompound(i);
            ChunkSection section = readSection(sectionTag, layout, paletteReader, effectiveLegacyMap, scene);
            if (section != null) {
                sections.add(section);
            }
            if (scene && layout == ChunkStorageLayout.MODERN_ROOT && sectionTag.get("biomes") != null) {
                int y = sectionTag.getInt("Y");
                if (biomes.putIfAbsent(y, com.mcworldexplorer.voxel.data.BiomeSection.read(sectionTag.get("biomes"))) != null)
                    throw new ChunkDecodeException(ChunkDecodeException.Reason.INVALID_SECTION, y, "duplicate saved biome section");
            }
        }
        if ((layout == ChunkStorageLayout.LEVEL_PALETTE || layout == ChunkStorageLayout.LEVEL_LEGACY)
                && sectionTags.size() > 0
                && sections.isEmpty()) {
            throw new ChunkDecodeException(
                    ChunkDecodeException.Reason.UNSUPPORTED_CHUNK_LAYOUT,
                    -1,
                    "Level/Sections contains no supported block states");
        }
        if (!scene) return new DecodedChunk(layout, new VoxelChunk(chunkX, chunkZ, sections));
        List<com.mcworldexplorer.voxel.data.BlockEntityData> entities = new ArrayList<>();
        var entityTags = coordinateContainer.getList(
                layout == ChunkStorageLayout.MODERN_ROOT ? "block_entities" : "TileEntities",
                BinaryTagTypes.COMPOUND);
        if (entityTags.size() > 4096)
            throw new ChunkDecodeException(ChunkDecodeException.Reason.INVALID_NBT, -1, "方块实体数量超过 4096，无法完整准备该区块外观");
        for (int i = 0; i < entityTags.size(); i++)
            com.mcworldexplorer.voxel.data.BlockEntityData.read(entityTags.getCompound(i), chunkX, chunkZ).ifPresent(entities::add);
        return new DecodedChunk(layout, new VoxelChunk(chunkX, chunkZ, sections, entities, biomes));
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
            ChunkStorageLayout layout,
            BlockPaletteDecoder paletteReader,
            SavedLegacyIdMap legacyIdMap,
            boolean scene) throws ChunkDecodeException {
        if (!section.keySet().contains("Y")) {
            throw new ChunkDecodeException(
                    ChunkDecodeException.Reason.INVALID_SECTION,
                    -1,
                    "section is missing Y");
        }
        int sectionY = section.getInt("Y");
        if (layout == ChunkStorageLayout.LEVEL_LEGACY) {
            if (!section.keySet().contains("Blocks")) {
                return null;
            }
            return LegacyAnvilSectionDecoder.decode(section, sectionY, legacyIdMap, scene);
        }

        ListBinaryTag palette;
        long[] data;
        if (layout == ChunkStorageLayout.MODERN_ROOT) {
            CompoundBinaryTag states = section.getCompound("block_states");
            if (states.keySet().isEmpty()) {
                return null;
            }
            palette = states.getList("palette");
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
            states.add(paletteReader.read(palette.get(i), sectionY, i));
        }
        return new ChunkSection(sectionY, states, data);
    }
}
