package com.mcworldexplorer.experimental.v04.mesh;

import com.mcworldexplorer.experimental.v04.data.VoxelBlockState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VoxelBlockClassifierTest {
    @Test
    void recognizesOnlyVanillaAirWaterAndLava() {
        assertEquals(VoxelBlockKind.AIR, classify("minecraft:air"));
        assertEquals(VoxelBlockKind.AIR, classify("minecraft:cave_air"));
        assertEquals(VoxelBlockKind.AIR, classify("minecraft:void_air"));
        assertEquals(VoxelBlockKind.WATER, classify("minecraft:water"));
        assertEquals(VoxelBlockKind.LAVA, classify("minecraft:lava"));
        assertEquals(VoxelBlockKind.SOLID, classify("example:water"));
        assertEquals(VoxelBlockKind.SOLID, classify("example:liquid_mana"));
    }

    @Test
    void treatsWaterloggedBlocksAsSolid() {
        VoxelBlockState slab = new VoxelBlockState(
                "minecraft:oak_slab",
                Map.of("type", "bottom", "waterlogged", "true"));

        assertEquals(VoxelBlockKind.SOLID, VoxelBlockClassifier.classify(slab));
    }

    private static VoxelBlockKind classify(String name) {
        return VoxelBlockClassifier.classify(new VoxelBlockState(name, Map.of()));
    }
}
