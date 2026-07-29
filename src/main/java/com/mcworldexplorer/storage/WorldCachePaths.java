package com.mcworldexplorer.storage;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.world.WorldInfo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class WorldCachePaths {
    private WorldCachePaths() {
    }

    public static Path worldDirectory(WorldInfo world) {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        return PortablePaths.cacheDirectory().resolve(worldDirectoryName(world)).normalize();
    }

    public static String worldDirectoryName(WorldInfo world) {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        String levelName = world.getLevelName();
        String readable = (levelName == null ? "" : levelName)
                .replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_")
                .strip()
                .replaceAll("[. ]+$", "");
        if (readable.isEmpty() || readable.equals(".") || readable.equals("..")) {
            readable = "world";
        }
        int codePointCount = readable.codePointCount(0, readable.length());
        if (codePointCount > 48) {
            readable = readable.substring(0, readable.offsetByCodePoints(0, 48)).stripTrailing();
        }
        return readable + "-" + textHash(
                world.getFolderPath().toAbsolutePath().normalize().toString());
    }

    public static String dimensionDirectoryName(WorldDimension dimension) {
        if (dimension == null) {
            throw new IllegalArgumentException("dimension must not be null");
        }
        String readable = dimension.id().replaceAll("[^A-Za-z0-9._-]", "_");
        if (readable.isBlank() || readable.equals(".") || readable.equals("..")) {
            readable = "dimension";
        }
        return readable + "-" + textHash(dimension.id());
    }

    public static String textHash(String value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
