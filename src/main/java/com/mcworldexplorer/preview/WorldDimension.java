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
                region(worldFolder,"overworld",worldFolder.resolve("region")),
                DimensionKind.OVERWORLD);
    }

    public static WorldDimension nether(Path worldFolder) {
        return new WorldDimension(
                NETHER_ID,
                "下界",
                region(worldFolder,"the_nether",worldFolder.resolve("DIM-1").resolve("region")),
                DimensionKind.NETHER);
    }

    public static WorldDimension end(Path worldFolder) {
        return new WorldDimension(
                END_ID,
                "末地",
                region(worldFolder,"the_end",worldFolder.resolve("DIM1").resolve("region")),
                DimensionKind.END);
    }
    private static Path region(Path world,String name,Path legacy) {
        Path modern=world.resolve("dimensions").resolve("minecraft").resolve(name).resolve("region");
        return java.nio.file.Files.isDirectory(modern)?modern:legacy;
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
