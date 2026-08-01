package com.mcworldexplorer.experimental.v04;

import com.mcworldexplorer.preview.WorldDimension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public record V04Arguments(
        Path world,
        String dimensionId,
        int chunkX,
        int chunkZ,
        Optional<Path> report,
        Optional<Path> screenshot,
        int autoCloseSeconds,
        int motionAfterSeconds) {
    private static final Set<String> REQUIRED = Set.of(
            "--world", "--dimension", "--chunk-x", "--chunk-z");
    private static final Set<String> ALLOWED = Set.of(
            "--world", "--dimension", "--chunk-x", "--chunk-z", "--report",
            "--screenshot", "--auto-close-seconds", "--motion-after-seconds");

    public static V04Arguments parse(String[] arguments) {
        if (arguments == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }
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
        for (String required : REQUIRED) {
            if (!values.containsKey(required) || values.get(required).isBlank()) {
                throw new IllegalArgumentException("missing required argument " + required);
            }
        }

        Path world = canonicalWorld(values.get("--world"));
        String dimension = WorldDimension.normalizeId(values.get("--dimension"));
        int chunkX = parseChunkCoordinate(values.get("--chunk-x"), "--chunk-x");
        int chunkZ = parseChunkCoordinate(values.get("--chunk-z"), "--chunk-z");
        Optional<Path> report = outputPath(values.get("--report"), "report", world);
        Optional<Path> screenshot = outputPath(values.get("--screenshot"), "screenshot", world);
        int autoCloseSeconds = parseAutoCloseSeconds(values.get("--auto-close-seconds"));
        int motionAfterSeconds = parseMotionAfterSeconds(values.get("--motion-after-seconds"));
        if (motionAfterSeconds >= 0
                && (autoCloseSeconds == 0 || motionAfterSeconds >= autoCloseSeconds)) {
            throw new IllegalArgumentException(
                    "--motion-after-seconds requires a later --auto-close-seconds value");
        }
        return new V04Arguments(
                world, dimension, chunkX, chunkZ, report, screenshot,
                autoCloseSeconds, motionAfterSeconds);
    }

    private static int parseCoordinate(String value, String argument) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(argument + " must be an integer: " + value, e);
        }
    }

    private static int parseChunkCoordinate(String value, String argument) {
        int coordinate = parseCoordinate(value, argument);
        int minimum = Integer.MIN_VALUE / 16 + 1;
        int maximum = (Integer.MAX_VALUE - 15) / 16 - 1;
        if (coordinate < minimum || coordinate > maximum) {
            throw new IllegalArgumentException(argument + " is outside the supported range: " + value);
        }
        return coordinate;
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
                throw new IllegalArgumentException(
                        label + " path must be outside the world directory");
            }
            return Optional.of(resolved);
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to resolve " + label + " path: " + path, e);
        }
    }

    private static int parseAutoCloseSeconds(String value) {
        if (value == null) {
            return 0;
        }
        int seconds = parseCoordinate(value, "--auto-close-seconds");
        if (seconds < 1 || seconds > 3_600) {
            throw new IllegalArgumentException("--auto-close-seconds must be between 1 and 3600");
        }
        return seconds;
    }

    private static int parseMotionAfterSeconds(String value) {
        if (value == null) {
            return -1;
        }
        int seconds = parseCoordinate(value, "--motion-after-seconds");
        if (seconds < 0 || seconds > 3_599) {
            throw new IllegalArgumentException("--motion-after-seconds must be between 0 and 3599");
        }
        return seconds;
    }
}
