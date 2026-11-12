package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.ParsedChunkSections;
import com.mcworldexplorer.preview.SurfaceSampler;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.region.RegionFileReader;
import com.mcworldexplorer.region.RegionHeaderIndex;
import com.mcworldexplorer.storage.WorldCachePaths;
import com.mcworldexplorer.world.WorldInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CancellationException;

public final class MapTileGenerator {
    private static final Logger LOGGER = LoggerFactory.getLogger(MapTileGenerator.class);
    private static final long PARTIAL_INTERVAL_NANOS = 100_000_000L;
    private final SurfaceSampler surfaceSampler;
    private final PortalMarkerExtractor portalExtractor;
    private final java.util.concurrent.atomic.AtomicLong decodes = new java.util.concurrent.atomic.AtomicLong();
    long decodeCount() { return decodes.get(); }
    private final SurfaceSummaryCache summaries = new SurfaceSummaryCache();

    public MapTileGenerator() { this(new SurfaceSampler(), new PortalMarkerExtractor()); }
    MapTileGenerator(SurfaceSampler surfaceSampler, PortalMarkerExtractor portalExtractor) {
        this.surfaceSampler = surfaceSampler;
        this.portalExtractor = portalExtractor;
    }

    void clearMemory() { summaries.clear(); }
    SurfaceSummaryCache.Statistics summaryStatistics() { return summaries.statistics(); }

    public MapTileGenerationResult generate(WorldInfo world, WorldDimension dimension,
            MapTileKey key, MapTileGenerationMonitor monitor) throws IOException {
        return generate(world, dimension, key, monitor, false);
    }

    MapTileGenerationResult generate(WorldInfo world, WorldDimension dimension, MapTileKey key,
                                    MapTileGenerationMonitor monitor, boolean persistent) throws IOException {
        return generate(world, dimension, key, monitor, persistent, false);
    }

    static boolean inCenter(int x, int z, MapTileGenerationMonitor monitor) {
        return Double.isFinite(monitor.focusX()) && Double.isFinite(monitor.focusZ())
                && Math.abs((long)x - (long)Math.floor(monitor.focusX() / 16)) <= 5
                && Math.abs((long)z - (long)Math.floor(monitor.focusZ() / 16)) <= 5;
    }

    public static boolean intersectsCenter(MapTileKey key, double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) return false;
        var b = key.bounds();
        double cx = Math.floor(x / 16), cz = Math.floor(z / 16);
        return b.minX() < (cx + 6) * 16 && b.maxXExclusive() > (cx - 5) * 16
                && b.minZ() < (cz + 6) * 16 && b.maxZExclusive() > (cz - 5) * 16;
    }

    MapTileGenerationResult generate(WorldInfo world, WorldDimension dimension, MapTileKey key,
                                    MapTileGenerationMonitor monitor, boolean persistent, boolean centerOnly) throws IOException {
        validate(world, dimension, key, monitor);
        long epoch = summaries.epoch();
        checkCancelled(epoch, monitor);
        Path worldIdentity = world.getFolderPath().toRealPath();
        String generation = persistent ? MapCacheStorage.generation(world) : "memory";
        MapTileBounds bounds = key.bounds();
        int minX = Math.toIntExact(Math.floorDiv(bounds.minX(), 16));
        int minZ = Math.toIntExact(Math.floorDiv(bounds.minZ(), 16));
        int maxX = Math.toIntExact(Math.floorDiv(bounds.maxXExclusive() - 1, 16));
        int maxZ = Math.toIntExact(Math.floorDiv(bounds.maxZExclusive() - 1, 16));
        if (centerOnly) {
            int cx = Math.toIntExact((long)Math.floor(monitor.focusX() / 16));
            int cz = Math.toIntExact((long)Math.floor(monitor.focusZ() / 16));
            minX = Math.max(minX, Math.subtractExact(cx, 5)); minZ = Math.max(minZ, Math.subtractExact(cz, 5));
            maxX = Math.min(maxX, Math.addExact(cx, 5)); maxZ = Math.min(maxZ, Math.addExact(cz, 5));
            if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("No center intersection");
        }
        int total = Math.multiplyExact(maxX - minX + 1, maxZ - minZ + 1);
        List<ChunkCoordinate> chunks = new ArrayList<>();
        List<RegionHeaderIndex> indexes = new ArrayList<>();
        List<Path> absentRegions = new ArrayList<>();
        int failed = 0;
        var unreadable = new MapReadIssues();
        for (int rz = Math.floorDiv(minZ - 1, 32); rz <= Math.floorDiv(maxZ, 32); rz++) {
            for (int rx = Math.floorDiv(minX - 1, 32); rx <= Math.floorDiv(maxX, 32); rx++) {
                checkCancelled(epoch, monitor);
                if ((long) rx * 32 + 31 < minX && (long) rz * 32 + 31 < minZ) continue;
                Path path = dimension.regionDirectory().resolve("r." + rx + "." + rz + ".mca");
                RegionHeaderIndex index;
                try {
                    index = RegionHeaderIndex.read(path);
                } catch (NoSuchFileException missing) {
                    absentRegions.add(path);
                    continue;
                } catch (IOException | SecurityException failure) {
                    unreadable.add(key, Math.max(minX - 1, rx * 32), Math.max(minZ - 1, rz * 32),
                            Math.min(maxX, rx * 32 + 31), Math.min(maxZ, rz * 32 + 31),
                            path.getFileName() + ": " + failure.getMessage());
                    failed += Math.max(0, Math.min(maxX, rx * 32 + 31) - Math.max(minX, rx * 32) + 1)
                            * Math.max(0, Math.min(maxZ, rz * 32 + 31) - Math.max(minZ, rz * 32) + 1);
                    LOGGER.warn("Failed to index Region {}", path, failure);
                    continue;
                }
                indexes.add(index);
                // 只为头表中的非空位置建任务；损坏位置仍进入失败报告。
                for (int slot : index.occupiedSlots()) {
                    int x = rx * 32 + slot % 32, z = rz * 32 + slot / 32;
                    if (x >= minX - 1 && x <= maxX && z >= minZ - 1 && z <= maxZ
                            && !(x < minX && z < minZ)) {
                        chunks.add(new ChunkCoordinate(x, z, index));
                    }
                }
            }
        }
        double focusX = focusCoordinate(monitor.focusX(), bounds.minX(), bounds.maxXExclusive());
        double focusZ = focusCoordinate(monitor.focusZ(), bounds.minZ(), bounds.maxZExclusive());
        // Keep nearby pages together: bounded page residency must not cause a rewrite per chunk.
        chunks.sort(Comparator.comparingInt((ChunkCoordinate c) -> monitor.centerFirst() && inCenter(c.x(), c.z(), monitor) ? 0 : 1)
                .thenComparingDouble(c -> pageDistanceSquared(c, focusX, focusZ))
                .thenComparingInt(c -> Math.floorDiv(c.z(), 8)).thenComparingInt(c -> Math.floorDiv(c.x(), 8))
                .thenComparingDouble(c -> distanceSquared(c, focusX, focusZ))
                .thenComparingInt(ChunkCoordinate::z).thenComparingInt(ChunkCoordinate::x));
        final int insideMinX = minX, insideMinZ = minZ;
        int missing = total - (int) chunks.stream().filter(c -> c.x() >= insideMinX && c.z() >= insideMinZ).count() - failed;
        long centerRemaining = monitor.centerFirst() ? chunks.stream().filter(c -> inCenter(c.x(), c.z(), monitor)).count() : 0;
        int completed = missing + failed, sampled = 0, populated = 0, unknown = 0, lastPublished = 0;
        long lastPartial = 0;
        MapTileRenderer renderer = new MapTileRenderer();
        List<MapMarker> markers = new ArrayList<>();
        long markerBytes = 0;
        monitor.onProgress(completed, total);
        try (Readers readers = new Readers();
             SurfaceSummaryStore store = persistent ? new SurfaceSummaryStore(world, () -> summaries.isCurrent(epoch)) : null) {
            for (ChunkCoordinate coordinate : chunks) {
                checkCancelled(epoch, monitor);
                int x = coordinate.x(), z = coordinate.z();
                if (!monitor.needsChunk(x, z)) continue;
                boolean inside = x >= minX && z >= minZ;
                try {
                    if (coordinate.index().state(Math.floorMod(x, 32), Math.floorMod(z, 32))
                            == RegionHeaderIndex.State.INVALID) {
                        throw new IOException("Invalid Region location at " + x + ", " + z);
                    }
                    var summaryKey = new SurfaceSummaryCache.Key(worldIdentity, dimension.id(), x, z,
                            key.layer(), coordinate.index().signature(), "surface-v1/" + key.renderVersion() + "/" + generation);
                    ChunkSurfaceSummary summary = summaries.get(summaryKey, epoch, monitor, () -> {
                        if (store != null) {
                            var restored = store.find(summaryKey);
                            if (restored != null) return restored;
                        }
                        decodes.incrementAndGet();
                        var reader = readers.get(coordinate.index().signature().path());
                        var data = reader.readChunk(Math.floorMod(x, 32), Math.floorMod(z, 32))
                                .orElseThrow(() -> new IOException("Indexed chunk disappeared at " + x + ", " + z));
                        var parsed = ParsedChunkSections.read(data);
                        var surface = surfaceSampler.sample(parsed, key.layer());
                        List<MapMarker> extracted = List.of();
                        String diagnostic = "";
                        try {
                            extracted = portalExtractor.extract(parsed, key.layer(), dimension.id(), x, z);
                        } catch (IOException | RuntimeException failure) {
                            diagnostic = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                            LOGGER.warn("Failed to extract portal markers at {}, {}: {}", x, z, diagnostic);
                        }
                        var result = new ChunkSurfaceSummary(surface, extracted, diagnostic);
                        return result;
                    });
                    if (store != null) store.put(summaryKey, summary);
                    summary.renderInto(renderer, bounds, key.zoom(), x, z);
                    if (inside) {
                        long added = summary.markers().stream().mapToLong(m -> 192L + MapPersistence.textBytes(m.dimensionId())).sum();
                        if (added > (1L << 20) - markerBytes) throw new IOException("Map marker producer budget exceeded");
                        markerBytes += added;
                        markers.addAll(summary.markers()); sampled++;
                        populated += summary.populatedColumns(); unknown += summary.unknownColumns();
                    }
                } catch (CancellationException cancelled) {
                    throw cancelled;
                } catch (IOException | RuntimeException failure) {
                    unreadable.add(key, x, z, x, z, "区块 " + x + ", " + z + ": " + failure.getMessage());
                    if (inside) failed++;
                    LOGGER.warn("Failed to sample map chunk {}, {} from {}", x, z,
                            coordinate.index().signature().path(), failure);
                }
                if (inside) monitor.onProgress(++completed, total);
                long now = System.nanoTime();
                boolean centerFinished = monitor.centerFirst() && inCenter(x, z, monitor) && --centerRemaining == 0;
                // 中心预览已展示时，完整阶段先重建同一小片，避免用更稀疏的部分图擦掉它。
                if ((centerOnly || centerRemaining == 0) && populated > lastPublished
                        && (centerFinished || lastPartial == 0 || now - lastPartial >= PARTIAL_INTERVAL_NANOS)) {
                    if (!monitor.isObsolete()) monitor.onPartial(new MapTilePartialResult(renderer.renderPartial(),
                            MapMarkerMerger.mergePortals(markers), completed, total));
                    lastPublished = populated;
                    lastPartial = now;
                }
            }
        }
        // 批次中途变更不得作为完整图返回；旧签名摘要不会命中新来源。
        for (RegionHeaderIndex index : indexes) {
            checkCancelled(epoch, monitor);
            index.verifyUnchanged();
        }
        for (Path path : absentRegions) {
            if (Files.exists(path)) throw new IOException("Region appeared during map sampling: " + path);
        }
        checkCancelled(epoch, monitor);
        if (monitor.isObsolete()) throw new CancellationException("Terrain reused; obsolete raster discarded");
        var image = centerOnly ? renderer.renderPartial() : renderer.render();
        unreadable.mark(image);
        return new MapTileGenerationResult(image, MapMarkerMerger.mergePortals(markers),
                total, sampled, missing, failed, populated, unknown, unreadable);
    }

    private void checkCancelled(long epoch, MapTileGenerationMonitor monitor) {
        if (!summaries.isCurrent(epoch) || monitor.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Map generation cancelled");
        }
    }

    /** 每个生成任务最多持有16个reader；现有两个地图工作线程合计最多32个。 */
    private static final class Readers implements AutoCloseable {
        private final LinkedHashMap<Path, RegionFileReader> open = new LinkedHashMap<>(16, .75f, true);
        RegionFileReader get(Path path) throws IOException {
            RegionFileReader reader = open.get(path);
            if (reader != null) return reader;
            if (open.size() >= 16) {
                var iterator = open.entrySet().iterator();
                RegionFileReader eldest = iterator.next().getValue();
                iterator.remove();
                eldest.close();
            }
            reader = new RegionFileReader(path);
            open.put(path, reader);
            return reader;
        }
        public void close() throws IOException {
            IOException failure = null;
            for (var reader : open.values()) try { reader.close(); } catch (IOException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
            open.clear();
            if (failure != null) throw failure;
        }
    }

    private static double pageDistanceSquared(ChunkCoordinate c, double focusX, double focusZ) {
        double dx = (long) Math.floorDiv(c.x(), 8) * 128 + 64 - focusX;
        double dz = (long) Math.floorDiv(c.z(), 8) * 128 + 64 - focusZ;
        return dx * dx + dz * dz;
    }
    private static double distanceSquared(ChunkCoordinate c, double focusX, double focusZ) {
        double dx = (long) c.x() * 16 + 8 - focusX;
        double dz = (long) c.z() * 16 + 8 - focusZ;
        return dx * dx + dz * dz;
    }
    private static double focusCoordinate(double requested, long min, long maxExclusive) {
        return Double.isFinite(requested) ? Math.max(min, Math.min(maxExclusive - 1, requested))
                : min + ((double) maxExclusive - min) / 2;
    }

    private static void validate(
            WorldInfo world,
            WorldDimension dimension,
            MapTileKey key,
            MapTileGenerationMonitor monitor) {
        if (world == null || dimension == null || key == null || monitor == null) {
            throw new IllegalArgumentException("world, dimension, key and monitor must not be null");
        }
        if (!WorldCachePaths.worldDirectoryName(world).equals(key.worldId())
                || !dimension.id().equals(key.dimensionId())) {
            throw new IllegalArgumentException("map tile key does not match world and dimension");
        }
        Path worldDirectory = world.getFolderPath().toAbsolutePath().normalize();
        Path regionDirectory = dimension.regionDirectory().toAbsolutePath().normalize();
        if (!regionDirectory.startsWith(worldDirectory)) {
            throw new IllegalArgumentException("dimension Region directory must stay inside the world directory");
        }
    }

    private record ChunkCoordinate(int x, int z, RegionHeaderIndex index) { }
}
