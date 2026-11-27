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

    @Test
    void classifiesPreFlatteningFluidsFoliagePortalsAndSixteenColorWool() {
        assertEquals(RenderLayer.FLUID,
                BlockClassifier.classify(new BlockState("minecraft:flowing_water", Map.of())).layer());
        assertEquals(0x3F76E4,
                BlockClassifier.classify(new BlockState("minecraft:flowing_water", Map.of())).rgb());
        assertEquals(RenderLayer.FLUID,
                BlockClassifier.classify(new BlockState("minecraft:flowing_lava", Map.of())).layer());
        assertEquals(RenderLayer.TRANSLUCENT,
                BlockClassifier.classify(new BlockState("minecraft:portal", Map.of())).layer());
        assertEquals(RenderLayer.CUTOUT,
                BlockClassifier.classify(new BlockState("minecraft:leaves", Map.of())).layer());

        var redWool = com.mcworldexplorer.voxel.io.VanillaLegacyBlocks.resolve(35, 14, null, true);
        var blueWool = com.mcworldexplorer.voxel.io.VanillaLegacyBlocks.resolve(35, 11, null, true);
        assertEquals("minecraft:red_wool", redWool.name());
        assertEquals("minecraft:blue_wool", blueWool.name());
        assertTrue(com.mcworldexplorer.preview.BlockColorPalette.resolve(redWool.name()).known());
        assertTrue(com.mcworldexplorer.preview.BlockColorPalette.resolve(blueWool.name()).known());
        assertEquals(0xB84A3E, BlockClassifier.classify(redWool).rgb());
        assertEquals(0x496EA8, BlockClassifier.classify(blueWool).rgb());
    }
}
