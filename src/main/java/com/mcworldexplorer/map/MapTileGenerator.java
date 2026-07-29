package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.BlockColorPalette;
import com.mcworldexplorer.preview.ChunkSurface;
import com.mcworldexplorer.preview.ParsedChunkSections;
import com.mcworldexplorer.preview.SurfaceColumn;
import com.mcworldexplorer.preview.SurfaceSampler;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.region.RegionChunkData;
import com.mcworldexplorer.region.RegionFileReader;
import com.mcworldexplorer.storage.WorldCachePaths;
import com.mcworldexplorer.world.WorldInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.Comparator;

public final class MapTileGenerator {
    private static final Logger LOGGER = LoggerFactory.getLogger(MapTileGenerator.class);
    private static final long PARTIAL_INTERVAL_NANOS = 100_000_000L;

    private final SurfaceSampler surfaceSampler;
    private final PortalMarkerExtractor portalExtractor;

    public MapTileGenerator() {
        this(new SurfaceSampler(), new PortalMarkerExtractor());
    }

    MapTileGenerator(
            SurfaceSampler surfaceSampler,
            PortalMarkerExtractor portalExtractor) {
        this.surfaceSampler = surfaceSampler;
        this.portalExtractor = portalExtractor;
    }

    public MapTileGenerationResult generate(
            WorldInfo world,
            WorldDimension dimension,
            MapTileKey key,
            MapTileGenerationMonitor monitor) throws IOException {
        validate(world, dimension, key, monitor);
        MapTileBounds bounds = key.bounds();
        int minChunkX = Math.toIntExact(Math.floorDiv(bounds.minX(), 16));
        int minChunkZ = Math.toIntExact(Math.floorDiv(bounds.minZ(), 16));
        int maxChunkX = Math.toIntExact(Math.floorDiv(bounds.maxXExclusive() - 1, 16));
        int maxChunkZ = Math.toIntExact(Math.floorDiv(bounds.maxZExclusive() - 1, 16));
        int totalChunks = Math.multiplyExact(
                maxChunkX - minChunkX + 1,
                maxChunkZ - minChunkZ + 1);

        MapTileRenderer renderer = new MapTileRenderer();
        List<MapMarker> markers = new ArrayList<>();
        int sampledChunks = 0;
        int missingChunks = 0;
        int failedChunks = 0;
        int populatedColumns = 0;
        int unknownBlockColumns = 0;
        int completedChunks = 0;
        int lastPublishedColumns = 0;
        long lastPartialNanos = 0;
        Map<Path, RegionFileReader> readers = new HashMap<>();
        Set<Path> unavailableRegions = new HashSet<>();
        Map<Path, RegionStatus> regionStatuses = inspectRegions(
                dimension,
                minChunkX,
                minChunkZ,
                maxChunkX,
                maxChunkZ);
        List<ChunkCoordinate> chunks = prioritizedChunks(
                minChunkX,
                minChunkZ,
                maxChunkX,
                maxChunkZ,
                focusCoordinate(monitor.focusX(), bounds.minX(), bounds.maxXExclusive()),
                focusCoordinate(monitor.focusZ(), bounds.minZ(), bounds.maxZExclusive()));
        IOException closeFailure = null;
        try {
            for (ChunkCoordinate coordinate : chunks) {
                if (monitor.isCancelled()) {
                    throw new CancellationException("map tile generation cancelled");
                }
                int chunkX = coordinate.x();
                int chunkZ = coordinate.z();
                Path regionPath = regionPath(dimension, chunkX, chunkZ);
                RegionStatus status = regionStatuses.getOrDefault(
                        regionPath,
                        RegionStatus.MISSING);
                if (status == RegionStatus.MISSING || status == RegionStatus.EMPTY) {
                    missingChunks++;
                } else if (status == RegionStatus.UNAVAILABLE
                        || unavailableRegions.contains(regionPath)) {
                    failedChunks++;
                } else {
                    RegionFileReader reader = readers.get(regionPath);
                    if (reader == null) {
                        try {
                            reader = new RegionFileReader(regionPath);
                            readers.put(regionPath, reader);
                        } catch (IOException e) {
                            unavailableRegions.add(regionPath);
                            failedChunks++;
                            completedChunks++;
                            monitor.onProgress(completedChunks, totalChunks);
                            continue;
                        }
                    }
                    try {
                        Optional<RegionChunkData> chunk = reader.readChunk(
                                Math.floorMod(chunkX, 32),
                                Math.floorMod(chunkZ, 32));
                        if (chunk.isEmpty()) {
                            missingChunks++;
                        } else {
                            ParsedChunkSections parsed = ParsedChunkSections.read(chunk.orElseThrow());
                            ChunkSurface surface = surfaceSampler.sample(parsed, key.layer());
                            try {
                                markers.addAll(portalExtractor.extract(
                                        parsed,
                                        key.layer(),
                                        dimension.id(),
                                        chunkX,
                                        chunkZ));
                            } catch (RuntimeException markerFailure) {
                                LOGGER.warn(
                                        "Failed to extract portal markers from chunk {}, {}",
                                        chunkX,
                                        chunkZ,
                                        markerFailure);
                            }
                            sampledChunks++;
                            for (int localZ = 0; localZ < 16; localZ++) {
                                for (int localX = 0; localX < 16; localX++) {
                                    Optional<SurfaceColumn> column = surface.getColumn(localX, localZ);
                                    if (column.isEmpty()) {
                                        continue;
                                    }
                                    SurfaceColumn value = column.orElseThrow();
                                    BlockColorPalette.BlockColor color =
                                            BlockColorPalette.resolve(value.blockName());
                                    renderer.accept(
                                            bounds,
                                            key.zoom(),
                                            (long) chunkX * 16 + localX,
                                            (long) chunkZ * 16 + localZ,
                                            color.rgb(),
                                            value.y());
                                    populatedColumns++;
                                    if (!color.known()) {
                                        unknownBlockColumns++;
                                    }
                                }
                            }
                        }
                    } catch (IOException | RuntimeException e) {
                        failedChunks++;
                    }
                }
                completedChunks++;
                monitor.onProgress(completedChunks, totalChunks);
                long now = System.nanoTime();
                if (populatedColumns > lastPublishedColumns
                        && (lastPartialNanos == 0
                                || now - lastPartialNanos >= PARTIAL_INTERVAL_NANOS)) {
                    monitor.onPartial(new MapTilePartialResult(
                            renderer.renderPartial(),
                            MapMarkerMerger.mergePortals(markers),
                            completedChunks,
                            totalChunks));
                    lastPublishedColumns = populatedColumns;
                    lastPartialNanos = now;
                }
            }
        } finally {
            for (RegionFileReader reader : readers.values()) {
                try {
                    reader.close();
                } catch (IOException e) {
                    if (closeFailure == null) {
                        closeFailure = e;
                    } else {
                        closeFailure.addSuppressed(e);
                    }
                }
            }
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
        return new MapTileGenerationResult(
                renderer.render(),
                MapMarkerMerger.mergePortals(markers),
                totalChunks,
                sampledChunks,
                missingChunks,
                failedChunks,
                populatedColumns,
                unknownBlockColumns);
    }

    private static Map<Path, RegionStatus> inspectRegions(
            WorldDimension dimension,
            int minChunkX,
            int minChunkZ,
            int maxChunkX,
            int maxChunkZ) {
        Map<Path, RegionStatus> statuses = new HashMap<>();
        int minRegionX = Math.floorDiv(minChunkX, 32);
        int minRegionZ = Math.floorDiv(minChunkZ, 32);
        int maxRegionX = Math.floorDiv(maxChunkX, 32);
        int maxRegionZ = Math.floorDiv(maxChunkZ, 32);
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                Path path = dimension.regionDirectory().resolve(
                        "r." + regionX + "." + regionZ + ".mca");
                RegionStatus status;
                try {
                    if (!Files.isRegularFile(path)) {
                        status = RegionStatus.MISSING;
                    } else if (Files.size(path) == 0) {
                        status = RegionStatus.EMPTY;
                    } else {
                        status = RegionStatus.AVAILABLE;
                    }
                } catch (IOException | SecurityException e) {
                    status = RegionStatus.UNAVAILABLE;
                }
                statuses.put(path, status);
            }
        }
        return statuses;
    }

    private static List<ChunkCoordinate> prioritizedChunks(
            int minChunkX,
            int minChunkZ,
            int maxChunkX,
            int maxChunkZ,
            double focusX,
            double focusZ) {
        List<ChunkCoordinate> chunks = new ArrayList<>(
                Math.multiplyExact(
                        maxChunkX - minChunkX + 1,
                        maxChunkZ - minChunkZ + 1));
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                chunks.add(new ChunkCoordinate(chunkX, chunkZ));
            }
        }
        chunks.sort(Comparator
                .comparingDouble((ChunkCoordinate chunk) ->
                        distanceSquared(chunk, focusX, focusZ))
                .thenComparingInt(ChunkCoordinate::z)
                .thenComparingInt(ChunkCoordinate::x));
        return chunks;
    }

    private static double distanceSquared(
            ChunkCoordinate chunk,
            double focusX,
            double focusZ) {
        double centerX = (long) chunk.x() * 16 + 8;
        double centerZ = (long) chunk.z() * 16 + 8;
        double deltaX = centerX - focusX;
        double deltaZ = centerZ - focusZ;
        return deltaX * deltaX + deltaZ * deltaZ;
    }

    private static double focusCoordinate(
            double requested,
            long min,
            long maxExclusive) {
        if (Double.isFinite(requested)) {
            return Math.max(min, Math.min(maxExclusive - 1, requested));
        }
        return min + ((double) maxExclusive - min) / 2;
    }

    private static Path regionPath(
            WorldDimension dimension,
            int chunkX,
            int chunkZ) {
        return dimension.regionDirectory().resolve(
                "r." + Math.floorDiv(chunkX, 32)
                        + "." + Math.floorDiv(chunkZ, 32) + ".mca");
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

    private record ChunkCoordinate(int x, int z) {
    }

    private enum RegionStatus {
        MISSING,
        EMPTY,
        AVAILABLE,
        UNAVAILABLE
    }
}
