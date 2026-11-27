package com.mcworldexplorer.voxel.data;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockStateTest {
    @Test
    void keepsPropertiesSortedAndImmutable() {
        BlockState state = new BlockState(
                "minecraft:oak_log",
                Map.of("waterlogged", "false", "axis", "y"));

        assertEquals("minecraft:oak_log", state.name());
        assertEquals(java.util.List.of("axis", "waterlogged"),
                new ArrayList<>(state.properties().keySet()));
        assertThrows(UnsupportedOperationException.class,
                () -> state.properties().put("axis", "x"));
    }

    @Test
    void rejectsBlankNamesAndProperties() {
        assertThrows(IllegalArgumentException.class, () -> new BlockState(" ", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new BlockState("minecraft:stone", Map.of("axis", "")));
    }
}
