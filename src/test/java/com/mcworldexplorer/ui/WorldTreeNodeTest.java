package com.mcworldexplorer.ui;

import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorldTreeNodeTest {
    @Test
    void representsGroupsAndWorldsWithoutRuntimeTypeChecks() {
        WorldInfo info = new WorldInfo(Path.of("world-folder"));

        WorldTreeNode group = WorldTreeNode.group("Default");
        WorldTreeNode world = WorldTreeNode.world(info);

        assertTrue(group.isGroup());
        assertEquals("Default", group.getGroupName());
        assertNull(group.getWorldInfo());
        assertFalse(world.isGroup());
        assertSame(info, world.getWorldInfo());
        assertNull(world.getGroupName());
    }

    @Test
    void rejectsInvalidNodeData() {
        assertThrows(NullPointerException.class, () -> WorldTreeNode.group(null));
        assertThrows(NullPointerException.class, () -> WorldTreeNode.world(null));
    }
}
