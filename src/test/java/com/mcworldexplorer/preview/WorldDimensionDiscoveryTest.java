package com.mcworldexplorer.preview;

import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldDimensionDiscoveryTest {
    @TempDir
    Path tempDir;

    @Test
    void discoversVanillaAndNamespacedDimensionsInStableOrder() throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("world"));
        Files.createDirectories(worldFolder.resolve("region"));
        Files.createDirectories(worldFolder.resolve("DIM-1/region"));
        Files.createDirectories(worldFolder.resolve("DIM1/region"));
        Files.createDirectories(worldFolder.resolve("dimensions/aether/the_aether/region"));
        Files.createDirectories(worldFolder.resolve("dimensions/example/deep/caves/region"));
        Files.createDirectories(worldFolder.resolve("dimensions/incomplete"));

        List<WorldDimension> dimensions = WorldDimensionDiscovery.discover(new WorldInfo(worldFolder));

        assertEquals(List.of(
                "minecraft:overworld",
                "minecraft:the_nether",
                "minecraft:the_end",
                "aether:the_aether",
                "example:deep/caves"),
                dimensions.stream().map(WorldDimension::id).toList());
        assertEquals(DimensionKind.MOD, dimensions.get(3).kind());
        assertTrue(dimensions.get(4).regionDirectory().startsWith(worldFolder));
    }

    @Test
    void keepsOverworldAvailableBeforeItsFirstRegionIsCreated() throws IOException {
        Path worldFolder = Files.createDirectory(tempDir.resolve("new-world"));

        List<WorldDimension> dimensions = WorldDimensionDiscovery.discover(new WorldInfo(worldFolder));

        assertEquals(1, dimensions.size());
        assertEquals(WorldDimension.OVERWORLD_ID, dimensions.getFirst().id());
        assertEquals(worldFolder.resolve("region"), dimensions.getFirst().regionDirectory());
    }
    @Test void modernVanillaDirectoriesAreRecognizedOnceWithVanillaKinds()throws IOException {
        Path world=Files.createDirectory(tempDir.resolve("modern"));Files.createDirectories(world.resolve("region"));
        for(String name:List.of("overworld","the_nether","the_end"))Files.createDirectories(world.resolve("dimensions/minecraft/"+name+"/region"));
        Files.createDirectories(world.resolve("dimensions/new_author/caves/region"));
        var dimensions=WorldDimensionDiscovery.discover(new WorldInfo(world));assertEquals(4,dimensions.size());
        assertEquals(List.of(WorldDimension.OVERWORLD_ID,WorldDimension.NETHER_ID,WorldDimension.END_ID,"new_author:caves"),dimensions.stream().map(WorldDimension::id).toList());
        assertEquals(List.of(DimensionKind.OVERWORLD,DimensionKind.NETHER,DimensionKind.END,DimensionKind.MOD),dimensions.stream().map(WorldDimension::kind).toList());
        assertEquals(world.resolve("dimensions/minecraft/overworld/region"),dimensions.getFirst().regionDirectory());
    }

    @Test
    void discoversLegacyNumericModDimensionsAndAlignsWithPlayerDimensionIds() throws IOException {
        Path world = Files.createDirectory(tempDir.resolve("legacy-mods"));
        Files.createDirectories(world.resolve("region"));
        Files.createDirectories(world.resolve("DIM0/region"));
        Files.createDirectories(world.resolve("DIM-1/region"));
        Files.createDirectories(world.resolve("DIM1/region"));
        Files.createDirectories(world.resolve("DIM7/region"));
        Files.createDirectories(world.resolve("DIM-42/region"));
        Files.createDirectories(world.resolve("DIM" + Integer.MIN_VALUE + "/region"));
        Files.createDirectories(world.resolve("DIM00/region")); // leading zero -> ignored
        Files.createDirectories(world.resolve("DIM2147483648/region")); // > Integer.MAX_VALUE -> ignored
        Files.createDirectories(world.resolve("DIM99")); // no region subdir -> ignored
        Files.createDirectories(world.resolve("DIMabc/region")); // non-integer -> ignored

        List<WorldDimension> dimensions = WorldDimensionDiscovery.discover(new WorldInfo(world));

        assertEquals(List.of(
                WorldDimension.OVERWORLD_ID,
                WorldDimension.NETHER_ID,
                WorldDimension.END_ID,
                "legacy:dim_" + Integer.MIN_VALUE,
                "legacy:dim_-42",
                "legacy:dim_7"),
                dimensions.stream().map(WorldDimension::id).toList());
        assertEquals(DimensionKind.MOD, dimensions.get(3).kind());
        assertEquals("DIM" + Integer.MIN_VALUE, dimensions.get(3).displayName());
        assertEquals(DimensionKind.MOD, dimensions.get(4).kind());
        assertEquals("DIM-42", dimensions.get(4).displayName());
        assertEquals(DimensionKind.MOD, dimensions.get(5).kind());
        assertEquals("DIM7", dimensions.get(5).displayName());

        assertEquals("legacy:dim_7", WorldDimension.normalizeId("7"));
        assertEquals("legacy:dim_7", WorldDimension.normalizeId("DIM7"));
        assertEquals("legacy:dim_-42", WorldDimension.normalizeId("-42"));
        assertEquals("legacy:dim_-42", WorldDimension.normalizeId("DIM-42"));
        assertEquals("legacy:dim_" + Integer.MIN_VALUE, WorldDimension.normalizeId(Integer.toString(Integer.MIN_VALUE)));
        assertEquals("DIM00", WorldDimension.normalizeId("DIM00"));
        assertEquals("DIM2147483648", WorldDimension.normalizeId("DIM2147483648"));
        assertEquals(WorldDimension.OVERWORLD_ID, WorldDimension.normalizeId("0"));
        assertEquals(WorldDimension.NETHER_ID, WorldDimension.normalizeId("-1"));
        assertEquals(WorldDimension.END_ID, WorldDimension.normalizeId("1"));
    }
}
