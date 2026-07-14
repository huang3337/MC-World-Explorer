package com.mcworldexplorer.preview;

import com.mcworldexplorer.storage.PortablePaths;
import com.mcworldexplorer.world.WorldInfo;

import javax.imageio.ImageIO;
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
    private static final String RENDER_VERSION = "0.2-preview-1";

    public PreviewCacheResult store(WorldInfo world, PreviewGenerationResult result) throws IOException {
        if (world == null || result == null) {
            throw new IllegalArgumentException("world and result must not be null");
        }

        Path cacheDirectory = PortablePaths.cacheDirectory().resolve(worldDirectoryName(world));
        Files.createDirectories(cacheDirectory);
        Path imagePath = cacheDirectory.resolve("world_preview.png");
        Path metadataPath = cacheDirectory.resolve("preview-metadata.json");

        Path temporaryImage = Files.createTempFile(cacheDirectory, ".world-preview-", ".png");
        Path temporaryMetadata = Files.createTempFile(cacheDirectory, ".preview-metadata-", ".json");
        try {
            if (!ImageIO.write(result.image(), "png", temporaryImage.toFile())) {
                throw new IOException("PNG writer is unavailable");
            }
            Files.writeString(
                    temporaryMetadata,
                    metadataJson(world, result),
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

    private static List<Path> relevantSourceFiles(WorldInfo world, PreviewCenter center) {
        List<Path> sources = new ArrayList<>();
        Path levelDat = world.getFolderPath().resolve("level.dat");
        if (Files.isRegularFile(levelDat)) {
            sources.add(levelDat);
        }

        int minBlockX = center.x() - PreviewGenerator.BLOCK_RANGE / 2;
        int minBlockZ = center.z() - PreviewGenerator.BLOCK_RANGE / 2;
        int maxBlockX = minBlockX + PreviewGenerator.BLOCK_RANGE - 1;
        int maxBlockZ = minBlockZ + PreviewGenerator.BLOCK_RANGE - 1;
        int minRegionX = Math.floorDiv(Math.floorDiv(minBlockX, 16), 32);
        int minRegionZ = Math.floorDiv(Math.floorDiv(minBlockZ, 16), 32);
        int maxRegionX = Math.floorDiv(Math.floorDiv(maxBlockX, 16), 32);
        int maxRegionZ = Math.floorDiv(Math.floorDiv(maxBlockZ, 16), 32);
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                Path regionPath = world.getFolderPath().resolve("region")
                        .resolve("r." + regionX + "." + regionZ + ".mca");
                if (Files.isRegularFile(regionPath)) {
                    sources.add(regionPath);
                }
            }
        }
        return sources;
    }

    private static String worldPathHash(Path worldPath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    worldPath.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String metadataJson(WorldInfo world, PreviewGenerationResult result) throws IOException {
        Path levelDat = world.getFolderPath().resolve("level.dat");
        long levelDatSize = Files.isRegularFile(levelDat) ? Files.size(levelDat) : -1;
        long levelDatModified = Files.isRegularFile(levelDat)
                ? Files.getLastModifiedTime(levelDat).toMillis()
                : -1;
        return "{\n"
                + "  \"renderVersion\": \"" + json(RENDER_VERSION) + "\",\n"
                + "  \"generatedAt\": \"" + json(Instant.now().toString()) + "\",\n"
                + "  \"worldPath\": \"" + json(world.getFolderPath().toAbsolutePath().normalize().toString()) + "\",\n"
                + "  \"centerX\": " + result.center().x() + ",\n"
                + "  \"centerZ\": " + result.center().z() + ",\n"
                + "  \"centerSource\": \"" + result.center().source() + "\",\n"
                + "  \"blockRange\": " + PreviewGenerator.BLOCK_RANGE + ",\n"
                + "  \"imageSize\": " + PreviewGenerator.OUTPUT_SIZE + ",\n"
                + "  \"levelDatSize\": " + levelDatSize + ",\n"
                + "  \"levelDatModified\": " + levelDatModified + ",\n"
                + "  \"regionState\": \"" + regionState(world, result.center()) + "\",\n"
                + "  \"sampledChunks\": " + result.sampledChunks() + ",\n"
                + "  \"missingChunks\": " + result.missingChunks() + ",\n"
                + "  \"failedChunks\": " + result.failedChunks() + ",\n"
                + "  \"unknownBlockColumns\": " + result.unknownBlockColumns() + "\n"
                + "}\n";
    }

    private static String regionState(WorldInfo world, PreviewCenter center) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }

        Path regionDirectory = world.getFolderPath().resolve("region");
        int minBlockX = center.x() - PreviewGenerator.BLOCK_RANGE / 2;
        int minBlockZ = center.z() - PreviewGenerator.BLOCK_RANGE / 2;
        int maxBlockX = minBlockX + PreviewGenerator.BLOCK_RANGE - 1;
        int maxBlockZ = minBlockZ + PreviewGenerator.BLOCK_RANGE - 1;
        int minRegionX = Math.floorDiv(Math.floorDiv(minBlockX, 16), 32);
        int minRegionZ = Math.floorDiv(Math.floorDiv(minBlockZ, 16), 32);
        int maxRegionX = Math.floorDiv(Math.floorDiv(maxBlockX, 16), 32);
        int maxRegionZ = Math.floorDiv(Math.floorDiv(maxBlockZ, 16), 32);
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
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

        Path cacheDirectory = PortablePaths.cacheDirectory().resolve(worldDirectoryName(world));
        Path imagePath = cacheDirectory.resolve("world_preview.png");
        Path metadataPath = cacheDirectory.resolve("preview-metadata.json");
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
                || !metadata.contains("\"centerX\": " + center.x() + ",")
                || !metadata.contains("\"centerZ\": " + center.z() + ",")
                || !metadata.contains("\"centerSource\": \"" + center.source() + "\"")
                || !metadata.contains("\"levelDatSize\": " + levelDatSize + ",")
                || !metadata.contains("\"levelDatModified\": " + levelDatModified + ",")
                || !metadata.contains("\"regionState\": \"" + regionState(world, center) + "\"")) {
            return Optional.empty();
        }

        long imageModified = Files.getLastModifiedTime(imagePath).toMillis();
        for (Path source : relevantSourceFiles(world, center)) {
            if (Files.getLastModifiedTime(source).toMillis() > imageModified) {
                return Optional.empty();
            }
        }
        return Optional.of(new PreviewCacheResult(imagePath, metadataPath));
    }
}
