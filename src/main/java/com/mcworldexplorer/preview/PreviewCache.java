package com.mcworldexplorer.preview;

import com.mcworldexplorer.storage.PortablePaths;
import com.mcworldexplorer.world.WorldInfo;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

public final class PreviewCache {
    private static final String RENDER_VERSION = "0.2.1-layer-1";

    public PreviewCacheResult store(WorldInfo world, PreviewGenerationResult result) throws IOException {
        if (world == null || result == null) {
            throw new IllegalArgumentException("world and result must not be null");
        }
        PreviewRequest request = new PreviewRequest(
                WorldDimension.overworld(world.getFolderPath()),
                result.center(),
                PreviewLayer.surfaceOverview());
        return store(world, request, result);
    }

    public PreviewCacheResult store(
            WorldInfo world,
            PreviewRequest request,
            PreviewGenerationResult result) throws IOException {
        if (world == null || result == null) {
            throw new IllegalArgumentException("world and result must not be null");
        }
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        validateRegionBoundary(world, request);
        if (!request.center().equals(result.center())) {
            throw new IllegalArgumentException("request and generation centers must match");
        }

        Path cacheDirectory = PortablePaths.cacheDirectory()
                .resolve(worldDirectoryName(world))
                .resolve(dimensionDirectoryName(request.dimension()));
        Files.createDirectories(cacheDirectory);
        Path imagePath = cacheDirectory.resolve(request.layer().cacheKey() + ".png");
        Path metadataPath = cacheDirectory.resolve(request.layer().cacheKey() + ".json");

        Path temporaryImage = Files.createTempFile(cacheDirectory, ".preview-", ".png");
        Path temporaryMetadata = Files.createTempFile(cacheDirectory, ".preview-", ".json");
        try {
            if (!ImageIO.write(result.image(), "png", temporaryImage.toFile())) {
                throw new IOException("PNG writer is unavailable");
            }
            Files.writeString(
                    temporaryMetadata,
                    metadataJson(world, request, result),
                    StandardCharsets.UTF_8);
            replace(temporaryImage, imagePath);
            replace(temporaryMetadata, metadataPath);
        } finally {
            Files.deleteIfExists(temporaryImage);
            Files.deleteIfExists(temporaryMetadata);
        }
        return new PreviewCacheResult(imagePath, metadataPath);
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

    private static String worldDirectoryName(WorldInfo world) {
        String readable = world.getLevelName()
                .replaceAll("[<>:\"/\\|?*\\p{Cntrl}]", "_")
                .strip()
                .replaceAll("[. ]+$", "");
        if (readable.isEmpty() || readable.equals(".") || readable.equals("..")) {
            readable = "world";
        }
        int codePointCount = readable.codePointCount(0, readable.length());
        if (codePointCount > 48) {
            readable = readable.substring(0, readable.offsetByCodePoints(0, 48)).stripTrailing();
        }
        return readable + "-" + worldPathHash(world.getFolderPath());
    }

    private static String dimensionDirectoryName(WorldDimension dimension) {
        String readable = dimension.id().replaceAll("[^A-Za-z0-9._-]", "_");
        if (readable.isBlank() || readable.equals(".") || readable.equals("..")) {
            readable = "dimension";
        }
        return readable + "-" + textHash(dimension.id());
    }

    private static void validateRegionBoundary(WorldInfo world, PreviewRequest request) {
        Path worldDirectory = world.getFolderPath().toAbsolutePath().normalize();
        Path regionDirectory = request.dimension().regionDirectory().toAbsolutePath().normalize();
        if (!regionDirectory.startsWith(worldDirectory)) {
            throw new IllegalArgumentException("dimension Region directory must stay inside the world directory");
        }
    }

    private static List<Path> relevantSourceFiles(WorldInfo world, PreviewRequest request) {
        List<Path> sources = new ArrayList<>();
        Path levelDat = world.getFolderPath().resolve("level.dat");
        if (Files.isRegularFile(levelDat)) {
            sources.add(levelDat);
        }

        PreviewCenter center = request.center();
        PreviewGenerator.PreviewBounds bounds = PreviewGenerator.boundsFor(center);
        for (int regionZ = bounds.minRegionZ(); regionZ <= bounds.maxRegionZ(); regionZ++) {
            for (int regionX = bounds.minRegionX(); regionX <= bounds.maxRegionX(); regionX++) {
                Path regionPath = request.dimension().regionDirectory().resolve(
                        "r." + regionX + "." + regionZ + ".mca");
                if (Files.isRegularFile(regionPath)) {
                    sources.add(regionPath);
                }
            }
        }
        return sources;
    }

    private static String worldPathHash(Path worldPath) {
        return textHash(worldPath.toAbsolutePath().normalize().toString());
    }

    private static String textHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String metadataJson(
            WorldInfo world,
            PreviewRequest request,
            PreviewGenerationResult result) throws IOException {
        Path levelDat = world.getFolderPath().resolve("level.dat");
        long levelDatSize = Files.isRegularFile(levelDat) ? Files.size(levelDat) : -1;
        long levelDatModified = Files.isRegularFile(levelDat)
                ? Files.getLastModifiedTime(levelDat).toMillis()
                : -1;
        return "{\n"
                + "  \"renderVersion\": \"" + json(RENDER_VERSION) + "\",\n"
                + "  \"generatedAt\": \"" + json(Instant.now().toString()) + "\",\n"
                + "  \"worldPath\": \"" + json(world.getFolderPath().toAbsolutePath().normalize().toString()) + "\",\n"
                + "  \"dimensionId\": \"" + json(request.dimension().id()) + "\",\n"
                + "  \"layerType\": \"" + request.layer().type() + "\",\n"
                + "  \"layerMinY\": " + request.layer().minY() + ",\n"
                + "  \"layerMaxY\": " + request.layer().maxY() + ",\n"
                + "  \"centerX\": " + result.center().x() + ",\n"
                + "  \"centerZ\": " + result.center().z() + ",\n"
                + "  \"centerSource\": \"" + result.center().source() + "\",\n"
                + "  \"blockRange\": " + PreviewGenerator.BLOCK_RANGE + ",\n"
                + "  \"imageSize\": " + PreviewGenerator.OUTPUT_SIZE + ",\n"
                + "  \"levelDatSize\": " + levelDatSize + ",\n"
                + "  \"levelDatModified\": " + levelDatModified + ",\n"
                + "  \"regionState\": \"" + regionState(request) + "\",\n"
                + "  \"sampledChunks\": " + result.sampledChunks() + ",\n"
                + "  \"missingChunks\": " + result.missingChunks() + ",\n"
                + "  \"failedChunks\": " + result.failedChunks() + ",\n"
                + "  \"unknownBlockColumns\": " + result.unknownBlockColumns() + "\n"
                + "}\n";
    }

    private static String regionState(PreviewRequest request) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }

        PreviewCenter center = request.center();
        Path regionDirectory = request.dimension().regionDirectory();
        PreviewGenerator.PreviewBounds bounds = PreviewGenerator.boundsFor(center);
        for (int regionZ = bounds.minRegionZ(); regionZ <= bounds.maxRegionZ(); regionZ++) {
            for (int regionX = bounds.minRegionX(); regionX <= bounds.maxRegionX(); regionX++) {
                String fileName = "r." + regionX + "." + regionZ + ".mca";
                Path regionPath = regionDirectory.resolve(fileName);
                String state = Files.isRegularFile(regionPath)
                        ? fileName + ":" + Files.size(regionPath)
                                + ":" + Files.getLastModifiedTime(regionPath).toMillis()
                        : fileName + ":missing";
                digest.update(state.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String json(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    public Optional<PreviewCacheResult> findReusable(WorldInfo world, PreviewCenter center) throws IOException {
        if (world == null || center == null) {
            throw new IllegalArgumentException("world and center must not be null");
        }
        return findReusable(world, new PreviewRequest(
                WorldDimension.overworld(world.getFolderPath()),
                center,
                PreviewLayer.surfaceOverview()));
    }

    public Optional<PreviewCacheResult> findReusable(
            WorldInfo world,
            PreviewRequest request) throws IOException {
        if (world == null || request == null) {
            throw new IllegalArgumentException("world and request must not be null");
        }
        validateRegionBoundary(world, request);

        PreviewCenter center = request.center();
        Path cacheDirectory = PortablePaths.cacheDirectory()
                .resolve(worldDirectoryName(world))
                .resolve(dimensionDirectoryName(request.dimension()));
        Path imagePath = cacheDirectory.resolve(request.layer().cacheKey() + ".png");
        Path metadataPath = cacheDirectory.resolve(request.layer().cacheKey() + ".json");
        if (!Files.isRegularFile(imagePath) || !Files.isRegularFile(metadataPath)) {
            return Optional.empty();
        }

        String metadata = Files.readString(metadataPath, StandardCharsets.UTF_8);
        Path levelDat = world.getFolderPath().resolve("level.dat");
        long levelDatSize = Files.isRegularFile(levelDat) ? Files.size(levelDat) : -1;
        long levelDatModified = Files.isRegularFile(levelDat)
                ? Files.getLastModifiedTime(levelDat).toMillis()
                : -1;
        if (!metadata.contains("\"renderVersion\": \"" + RENDER_VERSION + "\"")
                || !metadata.contains("\"dimensionId\": \"" + json(request.dimension().id()) + "\"")
                || !metadata.contains("\"layerType\": \"" + request.layer().type() + "\"")
                || !metadata.contains("\"layerMinY\": " + request.layer().minY() + ",")
                || !metadata.contains("\"layerMaxY\": " + request.layer().maxY() + ",")
                || !metadata.contains("\"centerX\": " + center.x() + ",")
                || !metadata.contains("\"centerZ\": " + center.z() + ",")
                || !metadata.contains("\"centerSource\": \"" + center.source() + "\"")
                || !metadata.contains("\"levelDatSize\": " + levelDatSize + ",")
                || !metadata.contains("\"levelDatModified\": " + levelDatModified + ",")
                || !metadata.contains("\"regionState\": \"" + regionState(request) + "\"")) {
            return Optional.empty();
        }

        long imageModified = Files.getLastModifiedTime(imagePath).toMillis();
        for (Path source : relevantSourceFiles(world, request)) {
            if (Files.getLastModifiedTime(source).toMillis() > imageModified) {
                return Optional.empty();
            }
        }
        if (!isReadablePreviewImage(imagePath)) {
            return Optional.empty();
        }
        return Optional.of(new PreviewCacheResult(imagePath, metadataPath));
    }

    private static boolean isReadablePreviewImage(Path imagePath) {
        try {
            BufferedImage image = ImageIO.read(imagePath.toFile());
            return image != null
                    && image.getWidth() == PreviewGenerator.OUTPUT_SIZE
                    && image.getHeight() == PreviewGenerator.OUTPUT_SIZE;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
