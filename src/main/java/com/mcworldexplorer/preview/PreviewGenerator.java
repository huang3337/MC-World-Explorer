package com.mcworldexplorer.preview;

import com.mcworldexplorer.region.RegionChunkData;
import com.mcworldexplorer.region.RegionFileReader;
import com.mcworldexplorer.world.WorldInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

public final class PreviewGenerator {
    public static final int BLOCK_RANGE = 1024;
    public static final int OUTPUT_SIZE = 512;
    private static final int BLOCKS_PER_PIXEL = BLOCK_RANGE / OUTPUT_SIZE;

    private final SurfaceSampler surfaceSampler;
    private final PreviewRenderer renderer;

    public PreviewGenerator() {
        this(new SurfaceSampler(), new PreviewRenderer());
    }

    PreviewGenerator(SurfaceSampler surfaceSampler, PreviewRenderer renderer) {
        this.surfaceSampler = surfaceSampler;
        this.renderer = renderer;
    }

    public PreviewGenerationResult generate(WorldInfo world) throws IOException {
        return generate(world, defaultRequest(world), PreviewGenerationMonitor.NONE);
    }

    public PreviewGenerationResult generate(
            WorldInfo world,
            PreviewGenerationMonitor monitor) throws IOException {
        return generate(world, defaultRequest(world), monitor);
    }

    public PreviewGenerationResult generate(
            WorldInfo world,
            PreviewRequest request) throws IOException {
        return generate(world, request, PreviewGenerationMonitor.NONE);
    }

    public PreviewGenerationResult generate(
            WorldInfo world,
            PreviewRequest request,
            PreviewGenerationMonitor monitor) throws IOException {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (monitor == null) {
            throw new IllegalArgumentException("monitor must not be null");
        }

        Path worldDirectory = world.getFolderPath().toAbsolutePath().normalize();
        Path regionDirectory = request.dimension().regionDirectory().toAbsolutePath().normalize();
        if (!regionDirectory.startsWith(worldDirectory)) {
            throw new IllegalArgumentException("dimension Region directory must stay inside the world directory");
        }

        PreviewCenter center = request.center();
        PreviewBounds bounds = boundsFor(center);
        int minChunkX = bounds.minChunkX();
        int minChunkZ = bounds.minChunkZ();
        int maxChunkX = bounds.maxChunkX();
        int maxChunkZ = bounds.maxChunkZ();
        int totalChunks = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);

        int[] blockColors = new int[BLOCK_RANGE * BLOCK_RANGE];
        int[] heights = new int[BLOCK_RANGE * BLOCK_RANGE];
        boolean[] populated = new boolean[BLOCK_RANGE * BLOCK_RANGE];
        int sampledChunks = 0;
        int missingChunks = 0;
        int failedChunks = 0;
        int populatedColumns = 0;
        int unknownBlockColumns = 0;
        int completedChunks = 0;

        Map<Path, RegionFileReader> readers = new HashMap<>();
        Set<Path> emptyRegions = new HashSet<>();
        Set<Path> unavailableRegions = new HashSet<>();
        IOException closeFailure = null;
        try {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    if (monitor.isCancelled()) {
                        throw new CancellationException("preview generation cancelled");
                    }

                    int regionX = Math.floorDiv(chunkX, 32);
                    int regionZ = Math.floorDiv(chunkZ, 32);
                    Path regionPath = regionDirectory.resolve(
                            "r." + regionX + "." + regionZ + ".mca");
                    if (!Files.isRegularFile(regionPath)) {
                        missingChunks++;
                        completedChunks++;
                        monitor.onProgress(completedChunks, totalChunks);
                        continue;
                    }
                    if (emptyRegions.contains(regionPath)) {
                        missingChunks++;
                        completedChunks++;
                        monitor.onProgress(completedChunks, totalChunks);
                        continue;
                    }
                    if (unavailableRegions.contains(regionPath)) {
                        failedChunks++;
                        completedChunks++;
                        monitor.onProgress(completedChunks, totalChunks);
                        continue;
                    }

                    RegionFileReader reader = readers.get(regionPath);
                    if (reader == null) {
                        try {
                            if (Files.size(regionPath) == 0) {
                                emptyRegions.add(regionPath);
                                missingChunks++;
                                completedChunks++;
                                monitor.onProgress(completedChunks, totalChunks);
                                continue;
                            }
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
                            ChunkSurface surface = surfaceSampler.sample(
                                    chunk.orElseThrow(),
                                    request.layer());
                            sampledChunks++;
                            for (int localZ = 0; localZ < 16; localZ++) {
                                for (int localX = 0; localX < 16; localX++) {
                                    Optional<SurfaceColumn> column = surface.getColumn(localX, localZ);
                                    if (column.isEmpty()) {
                                        continue;
                                    }
                                    long blockX = (long) chunkX * 16 + localX - bounds.minBlockX();
                                    long blockZ = (long) chunkZ * 16 + localZ - bounds.minBlockZ();
                                    if (blockX < 0 || blockX >= BLOCK_RANGE
                                            || blockZ < 0 || blockZ >= BLOCK_RANGE) {
                                        continue;
                                    }
                                    SurfaceColumn value = column.orElseThrow();
                                    BlockColorPalette.BlockColor color = BlockColorPalette.resolve(value.blockName());
                                    int index = (int) blockX + (int) blockZ * BLOCK_RANGE;
                                    blockColors[index] = color.rgb();
                                    heights[index] = value.y();
                                    populated[index] = true;
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

                    completedChunks++;
                    monitor.onProgress(completedChunks, totalChunks);
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

        return new PreviewGenerationResult(
                renderer.render(BLOCK_RANGE, BLOCKS_PER_PIXEL, blockColors, heights, populated),
                center,
                totalChunks,
                sampledChunks,
                missingChunks,
                failedChunks,
                populatedColumns,
                unknownBlockColumns);
    }

    static PreviewBounds boundsFor(PreviewCenter center) {
        if (center == null) {
            throw new IllegalArgumentException("center must not be null");
        }
        long minBlockX = (long) center.x() - BLOCK_RANGE / 2L;
        long minBlockZ = (long) center.z() - BLOCK_RANGE / 2L;
        long maxBlockX = minBlockX + BLOCK_RANGE - 1L;
        long maxBlockZ = minBlockZ + BLOCK_RANGE - 1L;
        int minChunkX = Math.toIntExact(Math.floorDiv(minBlockX, 16L));
        int minChunkZ = Math.toIntExact(Math.floorDiv(minBlockZ, 16L));
        int maxChunkX = Math.toIntExact(Math.floorDiv(maxBlockX, 16L));
        int maxChunkZ = Math.toIntExact(Math.floorDiv(maxBlockZ, 16L));
        return new PreviewBounds(
                minBlockX,
                minBlockZ,
                maxBlockX,
                maxBlockZ,
                minChunkX,
                minChunkZ,
                maxChunkX,
                maxChunkZ,
                Math.floorDiv(minChunkX, 32),
                Math.floorDiv(minChunkZ, 32),
                Math.floorDiv(maxChunkX, 32),
                Math.floorDiv(maxChunkZ, 32));
    }

    private static PreviewRequest defaultRequest(WorldInfo world) {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        return new PreviewRequest(
                WorldDimension.overworld(world.getFolderPath()),
                PreviewCenterResolver.resolve(world),
                PreviewLayer.surfaceOverview());
    }

    record PreviewBounds(
            long minBlockX,
            long minBlockZ,
            long maxBlockX,
            long maxBlockZ,
            int minChunkX,
            int minChunkZ,
            int maxChunkX,
            int maxChunkZ,
            int minRegionX,
            int minRegionZ,
            int maxRegionX,
            int maxRegionZ) {
    }
}
