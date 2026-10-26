package com.mcworldexplorer.voxel.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResourceLocationTest {
    @Test void resolvesDefaultAndModNamespacesWithinTheExpectedAssetFolder() {
        assertEquals("data/example/banner_pattern/cross.json",ResourceLocation.parse("example:cross").entryName(ResourceLocation.Kind.BANNER_PATTERN));
        assertEquals("minecraft:block/stone", ResourceLocation.parse("block/stone").toString());
        assertEquals("assets/example/textures/block/blue_planks.png",
                ResourceLocation.parse("example:block/blue_planks").entryName(ResourceLocation.Kind.TEXTURE));
        assertEquals("assets/minecraft/blockstates/oak_log.json",
                ResourceLocation.parse("oak_log").entryName(ResourceLocation.Kind.BLOCKSTATE));
    }

    @Test void rejectsAliasesTraversalAbsolutePathsAndNonCanonicalNames() {
        for (String input : new String[]{"", ":stone", "minecraft:", "Minecraft:stone", "minecraft:../stone",
                "minecraft:block/./stone", "minecraft:block//stone", "minecraft:/stone", "minecraft:stone/",
                "minecraft:block\\stone", "file:C:/stone", "a:b:c", "..:stone", "minecraft:stone?x=1",
                "minecraft:#all", " minecraft:stone", "minecraft:stone\u0000", "minecraft:" + "x".repeat(513)}) {
            assertThrows(IllegalArgumentException.class, () -> ResourceLocation.parse(input), input);
        }
    }
}
