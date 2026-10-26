package com.mcworldexplorer.voxel.data;

import com.mcworldexplorer.voxel.io.ChunkDecodeException;

import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.NavigableMap;
import java.util.TreeMap;

public final class VoxelChunk {
    private final java.util.Map<Long,BlockEntityData> blockEntities;
    private final int chunkX;
    private final int chunkZ;
    private final NavigableMap<Integer, ChunkSection> sections;

    public VoxelChunk(int chunkX, int chunkZ, List<ChunkSection> sections)
            throws ChunkDecodeException {
        this(chunkX,chunkZ,sections,java.util.List.of());
    }

    public VoxelChunk(int chunkX,int chunkZ,List<ChunkSection> sections,List<BlockEntityData> entities) throws ChunkDecodeException {
        if(entities.size()>4096)throw new IllegalArgumentException("方块实体数量超过 4096");
        java.util.Map<Long,BlockEntityData> indexedEntities=new java.util.HashMap<>();
        for(var entity:entities)if(Math.floorDiv(entity.x(),16)==chunkX&&Math.floorDiv(entity.z(),16)==chunkZ)
            indexedEntities.put(entityKey(entity.x(),entity.y(),entity.z()),entity);
        this.blockEntities=java.util.Map.copyOf(indexedEntities);
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        TreeMap<Integer, ChunkSection> indexed = new TreeMap<>();
        for (ChunkSection section : List.copyOf(sections)) {
            int sectionY = section.sectionY();
            try {
                Math.addExact(Math.multiplyExact(sectionY, 16), 15);
            } catch (ArithmeticException e) {
                throw new ChunkDecodeException(
                        ChunkDecodeException.Reason.INVALID_SECTION,
                        sectionY,
                        "section Y is outside the supported integer coordinate range",
                        e);
            }
            if (indexed.putIfAbsent(sectionY, section) != null) {
                throw new ChunkDecodeException(
                        ChunkDecodeException.Reason.INVALID_SECTION,
                        sectionY,
                        "chunk contains duplicate section Y " + sectionY);
            }
        }
        this.sections = Collections.unmodifiableNavigableMap(indexed);
    }

    public java.util.Optional<BlockEntityData> blockEntity(long x,int y,long z) {
        if(Math.floorDiv(x,16)!=chunkX||Math.floorDiv(z,16)!=chunkZ)return java.util.Optional.empty();
        return java.util.Optional.ofNullable(blockEntities.get(entityKey(x,y,z)));
    }
    private static long entityKey(long x,int y,long z){return ((long)y<<8)|((z&15)<<4)|(x&15);}
    public java.util.Collection<BlockEntityData> blockEntities(){return blockEntities.values();}

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public BlockState blockState(int localX, int y, int localZ)
            throws ChunkDecodeException {
        ChunkSection section = sections.get(Math.floorDiv(y, 16));
        return section == null
                ? BlockState.AIR
                : section.blockState(localX, Math.floorMod(y, 16), localZ);
    }

    public List<ChunkSection> sections() {
        return sections.values().stream().toList();
    }

    public OptionalInt minY() {
        return sections.keySet().stream().mapToInt(value -> value * 16).min();
    }

    public OptionalInt maxY() {
        return sections.keySet().stream().mapToInt(value -> value * 16 + 15).max();
    }
}
