package com.mcworldexplorer.preview;

import java.nio.file.Path;
import java.util.Objects;

public record WorldDimension(
        String id,
        String displayName,
        Path regionDirectory,
        DimensionKind kind) {
    public static final String OVERWORLD_ID = "minecraft:overworld";
    public static final String NETHER_ID = "minecraft:the_nether";
    public static final String END_ID = "minecraft:the_end";

    public WorldDimension {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("dimension id must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("dimension display name must not be blank");
        }
        Objects.requireNonNull(regionDirectory, "regionDirectory");
        Objects.requireNonNull(kind, "kind");
    }

    public static WorldDimension overworld(Path worldFolder) {
        return new WorldDimension(
                OVERWORLD_ID,
                "主世界",
                worldFolder.resolve("region"),
                DimensionKind.OVERWORLD);
    }

    public static WorldDimension nether(Path worldFolder) {
        return new WorldDimension(
                NETHER_ID,
                "下界",
                worldFolder.resolve("DIM-1").resolve("region"),
                DimensionKind.NETHER);
    }

    public static WorldDimension end(Path worldFolder) {
        return new WorldDimension(
                END_ID,
                "末地",
                worldFolder.resolve("DIM1").resolve("region"),
                DimensionKind.END);
    }

    public boolean isOverworld() {
        return kind == DimensionKind.OVERWORLD;
    }

    public static String normalizeId(String id) {
        if (id == null || id.isBlank() || "0".equals(id)) {
            return OVERWORLD_ID;
        }
        return switch (id) {
            case "-1" -> NETHER_ID;
            case "1" -> END_ID;
            default -> id;
        };
    }

    @Override
    public String toString() {
        return kind == DimensionKind.MOD ? displayName + " (" + id + ")" : displayName;
    }
}
