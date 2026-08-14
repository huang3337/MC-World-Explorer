package com.mcworldexplorer.experimental.v05;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.voxel.data.ChunkRectangle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public record V05Arguments(
        Path world,
        String dimensionId,
        ChunkRectangle target,
        Optional<Path> report,
        Optional<Path> screenshot,
        int durationSeconds) {
    private static final Set<String> ALLOWED = Set.of(
            "--world", "--dimension",
            "--center-chunk-x", "--center-chunk-z", "--width", "--depth",
            "--min-chunk-x", "--min-chunk-z", "--max-chunk-x", "--max-chunk-z",
            "--report", "--screenshot", "--duration-seconds");

    public static V05Arguments parse(String[] arguments) {
        if (arguments == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }
        Map<String, String> values = pairs(arguments);
        Path world = canonicalWorld(required(values, "--world"));
        String dimension = WorldDimension.normalizeId(required(values, "--dimension"));
        ChunkRectangle target = target(values);
        Optional<Path> report = outputPath(values.get("--report"), "report", world);
        Optional<Path> screenshot = outputPath(values.get("--screenshot"), "screenshot", world);
        int duration = positive(values.get("--duration-seconds"), "--duration-seconds", 0, 3_600);
        return new V05Arguments(world, dimension, target, report, screenshot, duration);
    }

    public static String usage() {
        return "V0.5 Internal Validation\n"
                + "Center form: --world <path> --dimension <id> "
                + "--center-chunk-x <x> --center-chunk-z <z> [--width 3] [--depth 3]\n"
                + "Rectangle form: --world <path> --dimension <id> "
                + "--min-chunk-x <x> --min-chunk-z <z> --max-chunk-x <x> --max-chunk-z <z>\n"
                + "Optional: --report <json> --screenshot <png> --duration-seconds <1..3600>";
    }

    private static Map<String, String> pairs(String[] arguments) {
        Map<String, String> values = new HashMap<>();
        for (int index = 0; index < arguments.length; index += 2) {
            if (index + 1 >= arguments.length) {
                throw new IllegalArgumentException("missing value for " + arguments[index]);
            }
            String key = arguments[index];
            if (!ALLOWED.contains(key)) {
                throw new IllegalArgumentException("unknown argument " + key);
            }
            if (values.putIfAbsent(key, arguments[index + 1]) != null) {
                throw new IllegalArgumentException("duplicate argument " + key);
            }
        }
        return values;
    }

    private static ChunkRectangle target(Map<String, String> values) {
        boolean centerForm = values.keySet().stream().anyMatch(key -> key.startsWith("--center-"));
        boolean rectangleForm = values.keySet().stream().anyMatch(key -> key.startsWith("--min-"))
                || values.keySet().stream().anyMatch(key -> key.startsWith("--max-"));
        if (centerForm == rectangleForm) {
            throw new IllegalArgumentException(
                    "provide either center coordinates or explicit rectangle bounds");
        }
        if (centerForm) {
            int centerX = integer(required(values, "--center-chunk-x"), "--center-chunk-x");
            int centerZ = integer(required(values, "--center-chunk-z"), "--center-chunk-z");
            int width = positive(values.get("--width"), "--width", 3, 8);
            int depth = positive(values.get("--depth"), "--depth", 3, 8);
            return ChunkRectangle.centered(centerX, centerZ, width, depth);
        }
        return ChunkRectangle.target(
                integer(required(values, "--min-chunk-x"), "--min-chunk-x"),
                integer(required(values, "--min-chunk-z"), "--min-chunk-z"),
                integer(required(values, "--max-chunk-x"), "--max-chunk-x"),
                integer(required(values, "--max-chunk-z"), "--max-chunk-z"));
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required argument " + key);
        }
        return value;
    }

    private static int positive(String value, String name, int defaultValue, int maximum) {
        if (value == null) {
            return defaultValue;
        }
        int parsed = integer(value, name);
        if (parsed < 1 || parsed > maximum) {
            throw new IllegalArgumentException(name + " must be between 1 and " + maximum);
        }
        return parsed;
    }

    private static int integer(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer: " + value, e);
        }
    }

    private static Path canonicalWorld(String value) {
        Path world = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(world) || !Files.isReadable(world)) {
            throw new IllegalArgumentException("world directory is not readable: " + world);
        }
        try {
            return world.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to resolve world directory: " + world, e);
        }
    }

    private static Optional<Path> outputPath(String value, String label, Path world) {
        if (value == null) {
            return Optional.empty();
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (Files.isDirectory(path)) {
            throw new IllegalArgumentException(label + " path is a directory: " + path);
        }
        Path existing = path;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        try {
            Path resolved = existing == null
                    ? path
                    : existing.toRealPath().resolve(existing.relativize(path)).normalize();
            if (resolved.startsWith(world)) {
                throw new IllegalArgumentException(label + " path must be outside the world directory");
            }
            return Optional.of(resolved);
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to resolve " + label + " path: " + path, e);
        }
    }
}
