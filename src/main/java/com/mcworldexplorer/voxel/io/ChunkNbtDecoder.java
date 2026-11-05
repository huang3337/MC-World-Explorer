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
        var biomes=new TreeMap<Integer,com.mcworldexplorer.voxel.data.BiomeSection>();
        for (int i = 0; i < sectionTags.size(); i++) {
            var sectionTag=sectionTags.getCompound(i);
            ChunkSection section = readSection(sectionTag, layout);
            if (section != null) {
                sections.add(section);
            }
            if(layout==ChunkStorageLayout.MODERN_ROOT&&sectionTag.get("biomes")!=null){
                int y=sectionTag.getInt("Y");
                if(biomes.putIfAbsent(y,com.mcworldexplorer.voxel.data.BiomeSection.read(sectionTag.get("biomes")))!=null)
                    throw new ChunkDecodeException(ChunkDecodeException.Reason.INVALID_SECTION,y,"duplicate saved biome section");
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
        List<com.mcworldexplorer.voxel.data.BlockEntityData> entities=new ArrayList<>();
        var entityTags=coordinateContainer.getList(layout==ChunkStorageLayout.MODERN_ROOT?"block_entities":"TileEntities",BinaryTagTypes.COMPOUND);
        if(entityTags.size()>4096)throw new ChunkDecodeException(ChunkDecodeException.Reason.INVALID_NBT,-1,"方块实体数量超过 4096，无法完整准备该区块外观");
        for(int i=0;i<entityTags.size();i++)
            com.mcworldexplorer.voxel.data.BlockEntityData.read(entityTags.getCompound(i),chunkX,chunkZ).ifPresent(entities::add);
        return new DecodedChunk(layout, new VoxelChunk(chunkX, chunkZ, sections,entities,biomes));
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
            var value=palette.get(i);
            if(value instanceof net.kyori.adventure.nbt.StringBinaryTag text)states.add(stringState(text.value(),sectionY,i));
            else if(value instanceof CompoundBinaryTag compound)states.add(readState(compound,sectionY,i));
            else throw new ChunkDecodeException(ChunkDecodeException.Reason.INVALID_PALETTE,sectionY,"palette entry "+i+" is neither a block ID nor an object");
        }
        return new ChunkSection(sectionY, states, data);
    }

    private static BlockState readState(
            CompoundBinaryTag state,
            int sectionY,
            int paletteIndex) throws ChunkDecodeException {
        if(state.keySet().size()==1&&state.get("") instanceof net.kyori.adventure.nbt.StringBinaryTag text)return stringState(text.value(),sectionY,paletteIndex);
        if(state.keySet().contains("Name")&&state.keySet().contains("id")&&!state.getString("Name").equals(state.getString("id")))
            throw new ChunkDecodeException(ChunkDecodeException.Reason.INVALID_PALETTE,sectionY,"conflicting block name fields");
        String name = state.keySet().contains("id")?state.getString("id"):state.getString("Name");
        if (name == null || name.isBlank()) {
            throw new ChunkDecodeException(
                    ChunkDecodeException.Reason.INVALID_PALETTE,
                    sectionY,
                    "palette entry " + paletteIndex + " has no block name");
        }
        Map<String, String> properties = new TreeMap<>();
        if(state.keySet().contains("Properties")&&state.keySet().contains("properties")&&!state.get("Properties").equals(state.get("properties")))
            throw new ChunkDecodeException(ChunkDecodeException.Reason.INVALID_PALETTE_PROPERTY,sectionY,"conflicting property fields");
        if (state.keySet().contains("Properties")||state.keySet().contains("properties")) {
            String keyName=state.keySet().contains("properties")?"properties":"Properties";
            if(!(state.get(keyName) instanceof CompoundBinaryTag))throw new ChunkDecodeException(ChunkDecodeException.Reason.INVALID_PALETTE_PROPERTY,sectionY,"block properties must be a compound");
            CompoundBinaryTag propertyTag = state.getCompound(keyName);
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
    private static BlockState stringState(String name,int sectionY,int index)throws ChunkDecodeException {
        if(name==null||name.isBlank())throw new ChunkDecodeException(ChunkDecodeException.Reason.INVALID_PALETTE,sectionY,"palette entry "+index+" has no block name");
        return new BlockState(name,Map.of());
    }
}
