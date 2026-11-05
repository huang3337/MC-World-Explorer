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
}
