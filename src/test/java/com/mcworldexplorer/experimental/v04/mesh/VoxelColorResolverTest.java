package com.mcworldexplorer.experimental.v04.mesh;

import com.mcworldexplorer.experimental.v04.data.VoxelBlockState;
import com.mcworldexplorer.preview.BlockColorPalette;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VoxelColorResolverTest {
    @Test
    void reusesKnownColors() {
        VoxelBlockState water = new VoxelBlockState("minecraft:water", Map.of());

        assertEquals(BlockColorPalette.resolve("minecraft:water").rgb(),
                VoxelColorResolver.resolve(water));
    }

    @Test
    void overridesExistingHashFallbackWithoutChangingPalette() {
        String unknown = "example:unmapped_block";
        assertFalse(BlockColorPalette.resolve(unknown).known());

        assertEquals(VoxelColorResolver.UNKNOWN_RGB,
                VoxelColorResolver.resolve(new VoxelBlockState(unknown, Map.of())));
        assertEquals(BlockColorPalette.resolve(unknown), BlockColorPalette.resolve(unknown));
    }
}
