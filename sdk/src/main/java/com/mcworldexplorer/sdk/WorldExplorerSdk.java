package com.mcworldexplorer.sdk;

import com.mcworldexplorer.nbt.LevelDatReader;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.preview.WorldDimensionDiscovery;
import com.mcworldexplorer.world.WorldInfo;
import com.mcworldexplorer.world.WorldScanner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Headless entry point for reading Minecraft Java Edition worlds, building 3D voxel meshes,
 * exporting Wavefront OBJ models, and rendering 2D top-down maps without any desktop UI dependency.
 */
public final class WorldExplorerSdk {
    private static final String BUILD_METADATA_RESOURCE = "/mcwe-build.properties";

    private WorldExplorerSdk() {
    }

    /**
     * Returns the current SDK build version read from {@code /mcwe-build.properties}.
     *
     * @return non-blank semantic version string such as {@code "0.8.1"}
     */
    public static String version() {
        try (InputStream stream = WorldExplorerSdk.class.getResourceAsStream(BUILD_METADATA_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing build metadata resource: " + BUILD_METADATA_RESOURCE);
            }
            Properties properties = new Properties();
            properties.load(stream);
            String value = properties.getProperty("version");
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Invalid SDK version in " + BUILD_METADATA_RESOURCE);
            }
            return value.trim();
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to read SDK version metadata", failure);
        }
    }

    /**
     * Opens a Minecraft Java Edition world directory in strictly read-only mode.
     *
     * @param worldDirectory path to a save folder containing {@code level.dat}
     * @return immutable read-only world session
     * @throws IOException if the directory is missing, does not contain a readable {@code level.dat}, or cannot be inspected
     */
    public static McWorld openWorld(Path worldDirectory) throws IOException {
        Objects.requireNonNull(worldDirectory, "worldDirectory");
        Path normalized = worldDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IOException("World directory does not exist: " + normalized);
        }
        Path levelDat = normalized.resolve("level.dat");
        if (!Files.isRegularFile(levelDat)) {
            throw new IOException("World directory does not contain level.dat: " + normalized);
        }
        WorldInfo info = LevelDatReader.readLevelDat(normalized);
        if (!info.isParsed()) {
            throw new IOException("Failed to parse level.dat in world directory: " + normalized);
        }
        List<WorldDimension> dimensions = WorldDimensionDiscovery.discover(info);
        return new McWorld(normalized, info, dimensions);
    }

    /**
     * Scans a world folder, {@code saves} directory, {@code .minecraft} root, or launcher instances directory
     * in read-only mode and returns all readable worlds found.
     *
     * @param rootDirectory directory to scan
     * @return immutable list of discovered worlds deduplicated by normalized folder path
     */
    public static List<WorldInfo> scanWorlds(Path rootDirectory) {
        Objects.requireNonNull(rootDirectory, "rootDirectory");
        Path normalized = rootDirectory.toAbsolutePath().normalize();
        Map<String, List<WorldInfo>> grouped = WorldScanner.scanSelectedPath(normalized);
        Map<Path, WorldInfo> unique = new LinkedHashMap<>();
        for (List<WorldInfo> worlds : grouped.values()) {
            for (WorldInfo world : worlds) {
                Path folder = world.getFolderPath().toAbsolutePath().normalize();
                unique.putIfAbsent(folder, world);
            }
        }
        return List.copyOf(new ArrayList<>(unique.values()));
    }
}
