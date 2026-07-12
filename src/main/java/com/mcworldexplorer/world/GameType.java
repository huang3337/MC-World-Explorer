package com.mcworldexplorer.world;

public enum GameType {
    SURVIVAL(0, "Survival"),
    CREATIVE(1, "Creative"),
    ADVENTURE(2, "Adventure"),
    SPECTATOR(3, "Spectator");

    private final int id;
    private final String displayName;

    GameType(int id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public int getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static GameType fromId(int id) {
        for (GameType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        return SURVIVAL;
    }
}
