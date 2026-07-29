package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.storage.WorldCachePaths;
import com.mcworldexplorer.world.WorldInfo;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MapTileCache {
    private static final int REGION_BLOCKS = 512;
    private static final Pattern STRING_FIELD = Pattern.compile(
            "\"([A-Za-z]+)\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");
    private static final Pattern NUMBER_FIELD = Pattern.compile(
            "\"([A-Za-z]+)\"\\s*:\\s*(-?\\d+)");
    private static final Pattern REGION_STATE = Pattern.compile(
            "\\{\"x\":(-?\\d+),\"z\":(-?\\d+),\"exists\":(true|false),"
                    + "\"size\":(-?\\d+),\"modified\":(-?\\d+)\\}");
    private static final Pattern MARKER = Pattern.compile(
            "\\{\"type\":\"([A-Z_]+)\",\"dimension\":\"((?:\\\\.|[^\"])*)\","
                    + "\"x\":(-?\\d+),\"y\":(-?\\d+),\"z\":(-?\\d+),"
                    + "\"minX\":(-?\\d+),\"minY\":(-?\\d+),\"minZ\":(-?\\d+),"
                    + "\"maxX\":(-?\\d+),\"maxY\":(-?\\d+),\"maxZ\":(-?\\d+)\\}");

    public MapTileCacheResult store(
            WorldInfo world,
            WorldDimension dimension,
            MapTileKey key,
            BufferedImage image) throws IOException {
        return store(world, dimension, key, image, List.of());
    }

    public MapTileCacheResult store(
            WorldInfo world,
            WorldDimension dimension,
            MapTileKey key,
            MapTileGenerationResult result) throws IOException {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        return store(world, dimension, key, result.image(), result.markers());
    }

    private MapTileCacheResult store(
            WorldInfo world,
            WorldDimension dimension,
            MapTileKey key,
            BufferedImage image,
            List<MapMarker> markers) throws IOException {
        validate(world, dimension, key);
        if (image == null || markers == null) {
            throw new IllegalArgumentException("image and markers must not be null");
        }
        if (image.getWidth() != MapTileBounds.TILE_PIXELS
                || image.getHeight() != MapTileBounds.TILE_PIXELS) {
            throw new IllegalArgumentException("map tile image must be 256 x 256");
        }

        Paths paths = paths(world, dimension, key);
        Files.createDirectories(paths.directory());
        List<MapTileSourceState> states = sourceStates(dimension, key.bounds());
        Path temporaryImage = Files.createTempFile(paths.directory(), ".tile-", ".png");
        Path temporaryMetadata = Files.createTempFile(paths.directory(), ".tile-", ".json");
        try {
            if (!ImageIO.write(image, "png", temporaryImage.toFile())) {
                throw new IOException("PNG writer is unavailable");
            }
            Files.writeString(
                    temporaryMetadata,
                    metadataJson(key, states, markers),
                    StandardCharsets.UTF_8);
            replace(temporaryImage, paths.image());
            replace(temporaryMetadata, paths.metadata());
        } finally {
            Files.deleteIfExists(temporaryImage);
            Files.deleteIfExists(temporaryMetadata);
        }
        return new MapTileCacheResult(paths.image(), paths.metadata(), image, markers);
    }

    public Optional<MapTileCacheResult> findReusable(
            WorldInfo world,
            WorldDimension dimension,
            MapTileKey key) throws IOException {
        validate(world, dimension, key);
        Paths paths = paths(world, dimension, key);
        if (!Files.isRegularFile(paths.image()) || !Files.isRegularFile(paths.metadata())) {
            return Optional.empty();
        }

        Metadata metadata;
        try {
            metadata = parseMetadata(Files.readString(paths.metadata(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            deleteTileFiles(paths);
            return Optional.empty();
        }
        if (!metadata.matches(key)
                || !metadata.states().equals(sourceStates(dimension, key.bounds()))) {
            deleteTileFiles(paths);
            return Optional.empty();
        }

        BufferedImage image;
        try {
            image = ImageIO.read(paths.image().toFile());
        } catch (RuntimeException e) {
            image = null;
        }
        if (image == null
                || image.getWidth() != MapTileBounds.TILE_PIXELS
                || image.getHeight() != MapTileBounds.TILE_PIXELS) {
            deleteTileFiles(paths);
            return Optional.empty();
        }
        return Optional.of(new MapTileCacheResult(
                paths.image(),
                paths.metadata(),
                image,
                metadata.markers()));
    }

    private static Paths paths(
            WorldInfo world,
            WorldDimension dimension,
            MapTileKey key) {
        Path directory = WorldCachePaths.worldDirectory(world)
                .resolve("map-v03")
                .resolve(WorldCachePaths.dimensionDirectoryName(dimension))
                .resolve(key.layer().cacheKey())
                .resolve("bpp-" + key.zoom().blocksPerPixel())
                .resolve("x-" + key.tileX())
                .normalize();
        Path worldRoot = WorldCachePaths.worldDirectory(world).toAbsolutePath().normalize();
        Path absoluteDirectory = directory.toAbsolutePath().normalize();
        if (!absoluteDirectory.startsWith(worldRoot)) {
            throw new IllegalArgumentException("map tile cache must stay inside the world cache directory");
        }
        String baseName = "tile-" + key.tileX() + "-" + key.tileZ();
        return new Paths(
                absoluteDirectory,
                absoluteDirectory.resolve(baseName + ".png"),
                absoluteDirectory.resolve(baseName + ".json"));
    }

    private static void validate(
            WorldInfo world,
            WorldDimension dimension,
            MapTileKey key) {
        if (world == null || dimension == null || key == null) {
            throw new IllegalArgumentException("world, dimension and key must not be null");
        }
        String expectedWorldId = WorldCachePaths.worldDirectoryName(world);
        if (!expectedWorldId.equals(key.worldId())) {
            throw new IllegalArgumentException("map tile key does not belong to the selected world");
        }
        if (!dimension.id().equals(key.dimensionId())) {
            throw new IllegalArgumentException("map tile key does not belong to the selected dimension");
        }
        Path worldDirectory = world.getFolderPath().toAbsolutePath().normalize();
        Path regionDirectory = dimension.regionDirectory().toAbsolutePath().normalize();
        if (!regionDirectory.startsWith(worldDirectory)) {
            throw new IllegalArgumentException("dimension Region directory must stay inside the world directory");
        }
    }

    static List<MapTileSourceState> sourceStates(
            WorldDimension dimension,
            MapTileBounds bounds) throws IOException {
        int minRegionX = Math.toIntExact(Math.floorDiv(bounds.minX(), REGION_BLOCKS));
        int maxRegionX = Math.toIntExact(Math.floorDiv(bounds.maxXExclusive() - 1, REGION_BLOCKS));
        int minRegionZ = Math.toIntExact(Math.floorDiv(bounds.minZ(), REGION_BLOCKS));
        int maxRegionZ = Math.toIntExact(Math.floorDiv(bounds.maxZExclusive() - 1, REGION_BLOCKS));
        List<MapTileSourceState> states = new ArrayList<>();
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                Path path = dimension.regionDirectory().resolve(
                        "r." + regionX + "." + regionZ + ".mca");
                boolean exists = Files.isRegularFile(path);
                states.add(new MapTileSourceState(
                        regionX,
                        regionZ,
                        exists,
                        exists ? Files.size(path) : -1,
                        exists ? Files.getLastModifiedTime(path).toMillis() : -1));
            }
        }
        return List.copyOf(states);
    }

    private static String metadataJson(
            MapTileKey key,
            List<MapTileSourceState> states,
            List<MapMarker> markers) {
        StringBuilder json = new StringBuilder(512);
        json.append("{\n")
                .append("\"worldId\":\"").append(escape(key.worldId())).append("\",\n")
                .append("\"dimensionId\":\"").append(escape(key.dimensionId())).append("\",\n")
                .append("\"layerType\":\"").append(key.layer().type()).append("\",\n")
                .append("\"layerMinY\":").append(key.layer().minY()).append(",\n")
                .append("\"layerMaxY\":").append(key.layer().maxY()).append(",\n")
                .append("\"blocksPerPixel\":").append(key.zoom().blocksPerPixel()).append(",\n")
                .append("\"tileX\":").append(key.tileX()).append(",\n")
                .append("\"tileZ\":").append(key.tileZ()).append(",\n")
                .append("\"renderVersion\":\"").append(escape(key.renderVersion())).append("\",\n")
                .append("\"regions\":[");
        for (int i = 0; i < states.size(); i++) {
            MapTileSourceState state = states.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"x\":").append(state.regionX())
                    .append(",\"z\":").append(state.regionZ())
                    .append(",\"exists\":").append(state.exists())
                    .append(",\"size\":").append(state.size())
                    .append(",\"modified\":").append(state.modifiedMillis())
                    .append('}');
        }
        json.append("],\n\"markers\":[");
        for (int i = 0; i < markers.size(); i++) {
            MapMarker marker = markers.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"type\":\"").append(marker.type())
                    .append("\",\"dimension\":\"").append(escape(marker.dimensionId()))
                    .append("\",\"x\":").append(marker.x())
                    .append(",\"y\":").append(marker.y())
                    .append(",\"z\":").append(marker.z())
                    .append(",\"minX\":").append(marker.minX())
                    .append(",\"minY\":").append(marker.minY())
                    .append(",\"minZ\":").append(marker.minZ())
                    .append(",\"maxX\":").append(marker.maxX())
                    .append(",\"maxY\":").append(marker.maxY())
                    .append(",\"maxZ\":").append(marker.maxZ())
                    .append('}');
        }
        return json.append("]\n}\n").toString();
    }

    private static Metadata parseMetadata(String json) {
        java.util.Map<String, String> strings = new java.util.HashMap<>();
        Matcher stringMatcher = STRING_FIELD.matcher(json);
        while (stringMatcher.find()) {
            strings.put(stringMatcher.group(1), unescape(stringMatcher.group(2)));
        }
        java.util.Map<String, Long> numbers = new java.util.HashMap<>();
        Matcher numberMatcher = NUMBER_FIELD.matcher(json);
        while (numberMatcher.find()) {
            numbers.put(numberMatcher.group(1), Long.parseLong(numberMatcher.group(2)));
        }
        List<MapTileSourceState> states = new ArrayList<>();
        Matcher regionMatcher = REGION_STATE.matcher(json);
        while (regionMatcher.find()) {
            states.add(new MapTileSourceState(
                    Integer.parseInt(regionMatcher.group(1)),
                    Integer.parseInt(regionMatcher.group(2)),
                    Boolean.parseBoolean(regionMatcher.group(3)),
                    Long.parseLong(regionMatcher.group(4)),
                    Long.parseLong(regionMatcher.group(5))));
        }
        List<MapMarker> markers = new ArrayList<>();
        Matcher markerMatcher = MARKER.matcher(json);
        while (markerMatcher.find()) {
            markers.add(new MapMarker(
                    MapMarkerType.valueOf(markerMatcher.group(1)),
                    unescape(markerMatcher.group(2)),
                    Integer.parseInt(markerMatcher.group(3)),
                    Integer.parseInt(markerMatcher.group(4)),
                    Integer.parseInt(markerMatcher.group(5)),
                    Integer.parseInt(markerMatcher.group(6)),
                    Integer.parseInt(markerMatcher.group(7)),
                    Integer.parseInt(markerMatcher.group(8)),
                    Integer.parseInt(markerMatcher.group(9)),
                    Integer.parseInt(markerMatcher.group(10)),
                    Integer.parseInt(markerMatcher.group(11))));
        }
        return new Metadata(
                required(strings, "worldId"),
                required(strings, "dimensionId"),
                required(strings, "layerType"),
                Math.toIntExact(required(numbers, "layerMinY")),
                Math.toIntExact(required(numbers, "layerMaxY")),
                Math.toIntExact(required(numbers, "blocksPerPixel")),
                required(numbers, "tileX"),
                required(numbers, "tileZ"),
                required(strings, "renderVersion"),
                List.copyOf(states),
                List.copyOf(markers));
    }

    private static <T> T required(java.util.Map<String, T> fields, String name) {
        T value = fields.get(name);
        if (value == null) {
            throw new IllegalArgumentException("metadata is missing " + name);
        }
        return value;
    }

    private static void deleteTileFiles(Paths paths) throws IOException {
        Files.deleteIfExists(paths.image());
        Files.deleteIfExists(paths.metadata());
    }

    private static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (escaped) {
                result.append(current);
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else {
                result.append(current);
            }
        }
        if (escaped) {
            throw new IllegalArgumentException("invalid escaped metadata string");
        }
        return result.toString();
    }

    private record Paths(Path directory, Path image, Path metadata) {
    }

    private record Metadata(
            String worldId,
            String dimensionId,
            String layerType,
            int layerMinY,
            int layerMaxY,
            int blocksPerPixel,
            long tileX,
            long tileZ,
            String renderVersion,
            List<MapTileSourceState> states,
            List<MapMarker> markers) {
        boolean matches(MapTileKey key) {
            return worldId.equals(key.worldId())
                    && dimensionId.equals(key.dimensionId())
                    && layerType.equals(key.layer().type().name())
                    && layerMinY == key.layer().minY()
                    && layerMaxY == key.layer().maxY()
                    && blocksPerPixel == key.zoom().blocksPerPixel()
                    && tileX == key.tileX()
                    && tileZ == key.tileZ()
                    && renderVersion.equals(key.renderVersion());
        }
    }
}
