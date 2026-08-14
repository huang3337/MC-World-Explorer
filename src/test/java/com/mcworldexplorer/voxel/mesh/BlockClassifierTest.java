package com.mcworldexplorer.voxel.mesh;

import com.mcworldexplorer.voxel.data.BlockState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockClassifierTest {
    @Test
    void classifiesKnownLayersAndDoesNotGuessModNames() {
        assertTrue(BlockClassifier.classify(BlockState.AIR).isAir());
        assertEquals(RenderLayer.FLUID,
                BlockClassifier.classify(new BlockState("minecraft:water", Map.of())).layer());
        assertEquals(RenderLayer.TRANSLUCENT,
                BlockClassifier.classify(new BlockState("minecraft:glass", Map.of())).layer());
        assertEquals(RenderLayer.CUTOUT,
                BlockClassifier.classify(new BlockState("minecraft:oak_leaves", Map.of())).layer());
        assertEquals(RenderLayer.OPAQUE,
                BlockClassifier.classify(new BlockState("minecraft:stone", Map.of())).layer());

        BlockRenderClass guessedWater = BlockClassifier.classify(
                new BlockState("example:magic_water_glass", Map.of()));
        assertEquals(BlockRenderClass.Kind.FALLBACK, guessedWater.kind());
        assertEquals(RenderLayer.FALLBACK, guessedWater.layer());
    }

    @Test
    void usesExplicitRegistrationInsteadOfNameGuessing() {
        assertEquals(BlockRenderClass.Kind.TRANSLUCENT,
                BlockClassifier.classify(new BlockState(
                        "minecraft:red_stained_glass", Map.of())).kind());
        assertEquals(BlockRenderClass.Kind.FALLBACK,
                BlockClassifier.classify(new BlockState(
                        "example:red_stained_glass", Map.of())).kind());
        assertEquals(BlockRenderClass.Kind.FALLBACK,
                BlockClassifier.classify(new BlockState(
                        "example:clear_water", Map.of())).kind());
    }
}
