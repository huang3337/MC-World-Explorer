package com.mcworldexplorer.experimental.v04.data;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;

public final class VoxelChunk {
    private final int chunkX;
    private final int chunkZ;
    private final Map<Integer, VoxelSection> sections;

    public VoxelChunk(int chunkX, int chunkZ, List<VoxelSection> sections)
            throws VoxelDataException {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        TreeMap<Integer, VoxelSection> indexed = new TreeMap<>();
        for (VoxelSection section : List.copyOf(sections)) {
            int sectionY = section.sectionY();
            try {
                Math.addExact(Math.multiplyExact(sectionY, 16), 15);
            } catch (ArithmeticException e) {
                throw new VoxelDataException(
                        VoxelDataException.Reason.INVALID_SECTION,
                        sectionY,
                        "section Y is outside the supported integer coordinate range",
                        e);
            }
            if (indexed.putIfAbsent(sectionY, section) != null) {
                throw new VoxelDataException(
                        VoxelDataException.Reason.INVALID_SECTION,
                        sectionY,
                        "chunk contains duplicate section Y " + sectionY);
            }
        }
        this.sections = Map.copyOf(indexed);
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public VoxelBlockState blockState(int localX, int y, int localZ)
            throws VoxelDataException {
        VoxelSection section = sections.get(Math.floorDiv(y, 16));
        return section == null
                ? VoxelBlockState.AIR
                : section.blockState(localX, Math.floorMod(y, 16), localZ);
    }

    public OptionalInt minY() {
        return sections.keySet().stream().mapToInt(value -> value * 16).min();
    }

    public OptionalInt maxY() {
        return sections.keySet().stream().mapToInt(value -> value * 16 + 15).max();
    }

    public List<Integer> sectionYs() {
        return sections.keySet().stream().sorted().toList();
    }
}
