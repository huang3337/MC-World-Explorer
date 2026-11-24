package com.mcworldexplorer.preview;

import java.nio.file.Path;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.regex.Pattern;

public record WorldDimension(
        String id,
        String displayName,
        Path regionDirectory,
        DimensionKind kind) {
    public static final String OVERWORLD_ID = "minecraft:overworld";
    public static final String NETHER_ID = "minecraft:the_nether";
    public static final String END_ID = "minecraft:the_end";
    private static final Pattern CANONICAL_INT = Pattern.compile("^-?(?:0|[1-9][0-9]{0,9})$");

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
                region(worldFolder, "overworld", worldFolder.resolve("region")),
                DimensionKind.OVERWORLD);
    }

    public static WorldDimension nether(Path worldFolder) {
        return new WorldDimension(
                NETHER_ID,
                "下界",
                region(worldFolder, "the_nether", worldFolder.resolve("DIM-1").resolve("region")),
                DimensionKind.NETHER);
    }

    public static WorldDimension end(Path worldFolder) {
        return new WorldDimension(
                END_ID,
                "末地",
                region(worldFolder, "the_end", worldFolder.resolve("DIM1").resolve("region")),
                DimensionKind.END);
    }

    public static String legacyModDimensionId(int dimensionNumber) {
        return "legacy:dim_" + dimensionNumber;
    }

    private static Path region(Path world, String name, Path legacy) {
        Path modern = world.resolve("dimensions").resolve("minecraft").resolve(name).resolve("region");
        return java.nio.file.Files.isDirectory(modern) ? modern : legacy;
    }

    public boolean isOverworld() {
        return kind == DimensionKind.OVERWORLD;
    }

    public static OptionalInt parseCanonicalInt(String text) {
        if (text == null || !CANONICAL_INT.matcher(text).matches() || "-0".equals(text)) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(text));
        } catch (NumberFormatException overflow) {
            return OptionalInt.empty();
        }
    }

    public static String normalizeId(String id) {
        if (id == null || id.isBlank()) {
            return OVERWORLD_ID;
        }
        String candidate = id.startsWith("DIM") ? id.substring(3) : id;
        OptionalInt parsed = parseCanonicalInt(candidate);
        if (parsed.isPresent()) {
            int dim = parsed.getAsInt();
            return switch (dim) {
                case 0 -> OVERWORLD_ID;
                case -1 -> NETHER_ID;
                case 1 -> END_ID;
                default -> legacyModDimensionId(dim);
            };
        }
        return id;
    }

    @Override
    public String toString() {
        return kind == DimensionKind.MOD ? displayName + " (" + id + ")" : displayName;
    }
}
