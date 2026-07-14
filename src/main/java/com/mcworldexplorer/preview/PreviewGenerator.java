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
        return generate(world, PreviewGenerationMonitor.NONE);
    }

    public PreviewGenerationResult generate(
            WorldInfo world,
            PreviewGenerationMonitor monitor) throws IOException {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        if (monitor == null) {
            throw new IllegalArgumentException("monitor must not be null");
        }

        PreviewCenter center = PreviewCenterResolver.resolve(world);
        int minBlockX = center.x() - BLOCK_RANGE / 2;
        int minBlockZ = center.z() - BLOCK_RANGE / 2;
        int maxBlockX = minBlockX + BLOCK_RANGE - 1;
        int maxBlockZ = minBlockZ + BLOCK_RANGE - 1;
        int minChunkX = Math.floorDiv(minBlockX, 16);
        int minChunkZ = Math.floorDiv(minBlockZ, 16);
        int maxChunkX = Math.floorDiv(maxBlockX, 16);
        int maxChunkZ = Math.floorDiv(maxBlockZ, 16);
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
                    Path regionPath = world.getFolderPath().resolve("region")
                            .resolve("r." + regionX + "." + regionZ + ".mca");
                    if (!Files.isRegularFile(regionPath)) {
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
                            ChunkSurface surface = surfaceSampler.sample(chunk.orElseThrow());
                            sampledChunks++;
                            for (int localZ = 0; localZ < 16; localZ++) {
                                for (int localX = 0; localX < 16; localX++) {
                                    Optional<SurfaceColumn> column = surface.getColumn(localX, localZ);
                                    if (column.isEmpty()) {
                                        continue;
                                    }
                                    int blockX = chunkX * 16 + localX - minBlockX;
                                    int blockZ = chunkZ * 16 + localZ - minBlockZ;
                                    if (blockX < 0 || blockX >= BLOCK_RANGE
                                            || blockZ < 0 || blockZ >= BLOCK_RANGE) {
                                        continue;
                                    }
                                    SurfaceColumn value = column.orElseThrow();
                                    BlockColorPalette.BlockColor color = BlockColorPalette.resolve(value.blockName());
                                    int index = blockX + blockZ * BLOCK_RANGE;
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
}
