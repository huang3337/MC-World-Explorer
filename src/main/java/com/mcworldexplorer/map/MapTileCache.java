package com.mcworldexplorer.map;

import com.google.gson.Gson;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.region.RegionHeaderIndex;
import com.mcworldexplorer.storage.WorldCachePaths;
import com.mcworldexplorer.world.WorldInfo;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class MapTileCache {
    private static final Gson JSON = new Gson();
    record Snapshot(String identity, List<MapTileSourceState> sources) { }
    private record Metadata(int format, MapTileKey key, Snapshot snapshot, String pngHash, List<MapMarker> markers) { }
    static Snapshot snapshot(WorldInfo world, WorldDimension dimension, MapTileKey key) throws IOException {
        validate(world, dimension, key);
        return new Snapshot(world.getFolderPath().toRealPath() + "/" + MapCacheStorage.generation(world),
                sourceStates(dimension, key.bounds()));
    }
    public MapTileCacheResult store(WorldInfo world, WorldDimension dimension, MapTileKey key, BufferedImage image) throws IOException {
        return store(world, dimension, key, image, List.of(), snapshot(world, dimension, key));
    }
    public MapTileCacheResult store(WorldInfo world, WorldDimension dimension, MapTileKey key, MapTileGenerationResult result) throws IOException {
        return store(world, dimension, key, result, snapshot(world, dimension, key));
    }
    MapTileCacheResult store(WorldInfo world, WorldDimension dimension, MapTileKey key,
                            MapTileGenerationResult result, Snapshot expected) throws IOException {
        if (result.failedChunks() > 0 || !result.unreadable().isEmpty()) throw new MapTileIncompleteException(result);
        return store(world, dimension, key, result.image(), result.markers(), expected);
    }
    private MapTileCacheResult store(WorldInfo world, WorldDimension dimension, MapTileKey key,
                                     BufferedImage image, List<MapMarker> markers, Snapshot expected) throws IOException {
        validate(world, dimension, key);
        if (expected.sources().stream().anyMatch(MapTileSourceState::unreadable))
            throw new IOException("Cannot cache incomplete source coverage");
        if (image.getWidth() != 256 || image.getHeight() != 256) throw new IllegalArgumentException("Map tile must be 256 x 256");
        try (var encoding = MapPersistence.acquire()) {
        var bytes = new MapPersistence.Bytes(2 << 20);
        encodePng(image, bytes, encoding);
        byte[] png = bytes.toByteArray();
        Path imagePath = path(world, dimension, key, ".png"), metaPath = path(world, dimension, key, ".json");
        var metadata = new Metadata(3, key, expected, MapCacheStorage.hash(png), markers);
        var saveState = MapTileCacheResult.SaveState.SAVED;
        synchronized (MapCacheStorage.LOCK) {
            if (!expected.equals(snapshot(world, dimension, key))) throw new IOException("Map source changed before cache commit");
            var files = new LinkedHashMap<Path, byte[]>(); files.put(imagePath, png);
            var encoded = new MapPersistence.Bytes(4 << 20);
            try (var writer = new OutputStreamWriter(encoded, StandardCharsets.UTF_8)) { JSON.toJson(metadata, writer); }
            files.put(metaPath, encoded.toByteArray());
            MapPersistence.checkCancelled();
            try { if (!MapCacheStorage.write(world, files)) saveState = MapTileCacheResult.SaveState.CAPACITY_LIMIT; }
            catch (IOException unavailable) {
                saveState = MapTileCacheResult.SaveState.IO_FAILED;
                MapCacheStorageNotice.unavailable(world);
                org.slf4j.LoggerFactory.getLogger(MapTileCache.class).warn("图像缓存无法写入，继续内存浏览", unavailable);
            }
            if (!expected.equals(snapshot(world, dimension, key))) throw new IOException("Map source changed during cache commit");
        }
        return new MapTileCacheResult(imagePath, metaPath, image, markers, expected.sources(), expected.identity(), saveState);
        } catch (MapPersistence.LimitExceeded limit) {
            if (!expected.equals(snapshot(world, dimension, key))) throw new IOException("Map source changed during encoding");
            MapCacheStorageNotice.unavailable(world);
            return new MapTileCacheResult(path(world, dimension, key, ".png"), path(world, dimension, key, ".json"), image, markers, expected.sources(), expected.identity(), MapTileCacheResult.SaveState.ENCODING_LIMIT);
        }
    }
    /** Fixed-size JDK PNG writer, with an explicitly bounded seek cache rather than ImageIO's default cache policy. */
    private static void encodePng(BufferedImage image, OutputStream target, MapPersistence.Lease lease) throws IOException {
        lease.reserve(MapPersistence.PNG_WORKSPACE_BYTES);
        try {
        var writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) throw new IOException("PNG writer unavailable");
        var writer = writers.next();
        try {
            if (!writer.getClass().getName().equals("com.sun.imageio.plugins.png.PNGImageWriter"))
                throw new IOException("PNG writer has no verified memory bound");
            if (image.getColorModel() instanceof java.awt.image.IndexColorModel indexed && indexed.getMapSize() > 256)
                throw new IOException("Unsupported PNG palette size");
            int bands = image.getSampleModel().getNumBands();
            if (bands < 1 || bands > 4) throw new IOException("Unsupported PNG band count");
            for (int bits : image.getSampleModel().getSampleSize())
                if (bits < 1 || bits > 16) throw new IOException("Unsupported PNG sample size");
            try (var output = new PngOutput(target)) {
                writer.setOutput(output); writer.write(image);
            }
        } finally { writer.dispose(); }
        } finally { lease.release(MapPersistence.PNG_WORKSPACE_BYTES); }
    }
    // OpenJDK's PNG writer flushes every 32768-byte IDAT chunk. Allow header/backpatch slack;
    // MemoryCache uses 8192-byte blocks, so a <=64 KiB live range retains at most 72 KiB of blocks.
    static final class PngOutput extends javax.imageio.stream.MemoryCacheImageOutputStream {
        private static final int MAX_PENDING = 64 << 10;
        PngOutput(OutputStream target) { super(target); }
        private void capacity(int count) throws IOException {
            MapPersistence.checkCancelled();
            if (count < 0 || getStreamPosition() - getFlushedPosition() + count > MAX_PENDING)
                throw new MapPersistence.LimitExceeded();
        }
        @Override public void write(int value) throws IOException { capacity(1); super.write(value); }
        @Override public void write(byte[] value, int offset, int count) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, count, value.length);
            capacity(count); super.write(value, offset, count);
        }
    }
    static long retainedBytes(BufferedImage image, List<MapMarker> markers, Snapshot snapshot, MapTileKey key) {
        long bytes = contextBytes(snapshot, key) + (long)image.getWidth() * image.getHeight() * 4;
        for (var marker : markers) bytes += 192 + MapPersistence.textBytes(marker.dimensionId());
        return bytes;
    }
    static long contextBytes(Snapshot snapshot, MapTileKey key) {
        long bytes = 4096L + MapPersistence.textBytes(snapshot.identity())
                + MapPersistence.textBytes(key.worldId()) + MapPersistence.textBytes(key.dimensionId())
                + MapPersistence.textBytes(key.renderVersion());
        for (var source : snapshot.sources()) bytes += 128 + MapPersistence.textBytes(source.signature()) + MapPersistence.textBytes(source.failure());
        return bytes;
    }
    public Optional<MapTileCacheResult> findReusable(WorldInfo world, WorldDimension dimension, MapTileKey key) throws IOException {
        Snapshot expected;
        try { expected = snapshot(world, dimension, key); } catch (IOException unavailable) { return Optional.empty(); }
        return findReusable(world, dimension, key, expected);
    }
    // The caller already acquired the start snapshot. Revalidate after decoding below.
    Optional<MapTileCacheResult> findReusable(WorldInfo world, WorldDimension dimension,
                                             MapTileKey key, Snapshot expected) throws IOException {
        Path imagePath = path(world, dimension, key, ".png"), metaPath = path(world, dimension, key, ".json");
        synchronized (MapCacheStorage.LOCK) {
            if (!Files.isRegularFile(imagePath) || !Files.isRegularFile(metaPath)) return Optional.empty();
            try {
                if (Files.size(metaPath) > (4 << 20) || Files.size(imagePath) > (2 << 20)) throw new IOException("Oversized tile cache");
                Metadata m = JSON.fromJson(Files.readString(metaPath), Metadata.class);
                if (m.format() != 3 || !key.equals(m.key()) || !expected.equals(m.snapshot())) return Optional.empty();
                byte[] png = Files.readAllBytes(imagePath);
                if (!MapCacheStorage.hash(png).equals(m.pngHash())) throw new IOException("Mismatched image/metadata pair");
                // Check PNG dimensions before decoding an untrusted derived file.
                if (png.length < 24 || java.nio.ByteBuffer.wrap(png, 16, 8).getInt() != 256
                        || java.nio.ByteBuffer.wrap(png, 20, 4).getInt() != 256) throw new IOException("Invalid PNG dimensions");
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
                if (image == null) throw new IOException("Invalid PNG");
                if (!expected.equals(snapshot(world, dimension, key))) return Optional.empty();
                return Optional.of(new MapTileCacheResult(imagePath, metaPath, image, m.markers(), expected.sources(), expected.identity()));
            } catch (IOException | RuntimeException corrupt) {
                try { Files.deleteIfExists(imagePath); Files.deleteIfExists(metaPath); }
                catch (IOException unavailable) {
                MapCacheStorageNotice.unavailable(world);
                    org.slf4j.LoggerFactory.getLogger(MapTileCache.class).warn("无法清理损坏图像缓存，将重新生成", unavailable);
                }
                return Optional.empty();
            }
        }
    }
    private static Path path(WorldInfo world, WorldDimension dimension, MapTileKey key, String extension) {
        return MapCacheStorage.root(world).resolve("images").resolve(WorldCachePaths.dimensionDirectoryName(dimension))
                .resolve(key.layer().cacheKey()).resolve("bpp-" + key.zoom().blocksPerPixel())
                .resolve("x-" + key.tileX()).resolve("tile-" + key.tileX() + "-" + key.tileZ() + extension);
    }
    private static void validate(WorldInfo world, WorldDimension dimension, MapTileKey key) {
        if (world == null || dimension == null || key == null) throw new IllegalArgumentException("Missing map context");
        if (!WorldCachePaths.worldDirectoryName(world).equals(key.worldId()) || !dimension.id().equals(key.dimensionId()))
            throw new IllegalArgumentException("Map key belongs to another context");
        if (!dimension.regionDirectory().toAbsolutePath().normalize().startsWith(world.getFolderPath().toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Region outside world");
    }
    static List<MapTileSourceState> sourceStates(WorldDimension dimension, MapTileBounds bounds) throws IOException {
        int minX = Math.toIntExact(Math.floorDiv(bounds.minX() - 1, 512));
        int minZ = Math.toIntExact(Math.floorDiv(bounds.minZ() - 1, 512));
        int maxX = Math.toIntExact(Math.floorDiv(bounds.maxXExclusive() - 1, 512));
        int maxZ = Math.toIntExact(Math.floorDiv(bounds.maxZExclusive() - 1, 512));
        // A far-away tile often references mostly absent Regions. One bounded directory
        // snapshot avoids a failed open/real-path resolution for each absent file. Existing
        // sources still receive the full header and before/after attribute verification.
        Set<Path> present = null;
        var requested = new HashSet<Path>();
        for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++)
            requested.add(Path.of("r." + x + "." + z + ".mca"));
        var found = new HashSet<Path>();
        boolean complete = true;
        if (requested.size() > 9) {
        try (var entries = Files.newDirectoryStream(dimension.regionDirectory())) {
            int visited = 0;
            for (Path entry : entries) {
                if (++visited > 4096) { complete = false; break; }
                if (requested.contains(entry.getFileName())) found.add(entry.getFileName());
            }
        } catch (NoSuchFileException absentDirectory) {
            // Missing directory has the same empty-source semantics as missing Regions.
        }
        if (complete) present = found;
        }
        var states = new ArrayList<MapTileSourceState>();
        for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
            if ((long)(x + 1) * 512 <= bounds.minX() && (long)(z + 1) * 512 <= bounds.minZ()) continue;
            Path path = dimension.regionDirectory().resolve("r." + x + "." + z + ".mca");
            if (present != null && !present.contains(path.getFileName())) {
                states.add(new MapTileSourceState(x, z, false, "")); continue;
            }
            try {
                var source = RegionHeaderIndex.readSignature(path);
                states.add(new MapTileSourceState(x, z, true, source.toString()));
            } catch (NoSuchFileException absent) { states.add(new MapTileSourceState(x, z, false, "")); }
            catch (IOException | SecurityException failure) {
                String reason = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                states.add(new MapTileSourceState(x, z, true, "", reason.substring(0, Math.min(256, reason.length()))));
            }
        }
        return List.copyOf(states);
    }
}
