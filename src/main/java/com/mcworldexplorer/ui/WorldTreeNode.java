package com.mcworldexplorer.ui;

import com.mcworldexplorer.world.WorldInfo;

import java.util.Objects;

public final class WorldTreeNode {
    private final String groupName;
    private final WorldInfo worldInfo;

    private WorldTreeNode(String groupName, WorldInfo worldInfo) {
        this.groupName = groupName;
        this.worldInfo = worldInfo;
    }

    public static WorldTreeNode group(String groupName) {
        return new WorldTreeNode(Objects.requireNonNull(groupName, "groupName"), null);
    }

    public static WorldTreeNode world(WorldInfo worldInfo) {
        return new WorldTreeNode(null, Objects.requireNonNull(worldInfo, "worldInfo"));
    }

    public boolean isGroup() {
        return worldInfo == null;
    }

    public String getGroupName() {
        return groupName;
    }

    public WorldInfo getWorldInfo() {
        return worldInfo;
    }
}
