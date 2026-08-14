package com.mcworldexplorer.voxel.io;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.preview.WorldDimensionDiscovery;
import com.mcworldexplorer.region.RegionChunkData;
import com.mcworldexplorer.region.RegionFileReader;
import com.mcworldexplorer.region.RegionReadException;
import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.world.WorldInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class VoxelAreaLoader {
    public VoxelAreaLoadResult load(VoxelAreaRequest request, CancellationSignal cancellation)
            throws IOException {
        if (request == null || cancellation == null) {
            throw new IllegalArgumentException("request and cancellation must not be null");
        }
        Path world = request.world().toRealPath();
        if (!Files.isDirectory(world)) {
            throw new IOException("world path is not a directory: " + world);
        }
        WorldDimension dimension = findDimension(world, request.dimensionId());
        Path regionDirectory = dimension.regionDirectory().toRealPath();
        if (!regionDirectory.startsWith(world) || !Files.isDirectory(regionDirectory)) {
            throw new IOException("dimension Region directory is invalid: " + regionDirectory);
        }

        List<ChunkCoordinate> coordinates = request.target().expanded(1).coordinates();
        int workers = Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        ExecutorService executor = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "voxel-area-loader");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Callable<ChunkLoadResult>> jobs = coordinates.stream()
                    .<Callable<ChunkLoadResult>>map(coordinate ->
                            () -> readChunk(regionDirectory, coordinate, cancellation))
                    .toList();
            List<Future<ChunkLoadResult>> futures;
            try {
                futures = executor.invokeAll(jobs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Map<ChunkCoordinate, ChunkLoadResult> cancelled = new LinkedHashMap<>();
                for (ChunkCoordinate coordinate : coordinates) {
                    cancelled.put(coordinate, ChunkLoadResult.withoutChunk(
                            coordinate,
                            ChunkLoadStatus.CANCELLED,
                            "loading interrupted",
                            0,
                            0));
                }
                return new VoxelAreaLoadResult(world, dimension, request.target(), cancelled);
            }
            Map<ChunkCoordinate, ChunkLoadResult> results = new LinkedHashMap<>();
            for (int index = 0; index < coordinates.size(); index++) {
                ChunkCoordinate coordinate = coordinates.get(index);
                try {
                    results.put(coordinate, futures.get(index).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.put(coordinate, ChunkLoadResult.withoutChunk(
                            coordinate, ChunkLoadStatus.CANCELLED, "loading interrupted", 0, 0));
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    throw new IOException(
                            "unexpected chunk load failure at " + coordinate,
                            cause);
                }
            }
            return new VoxelAreaLoadResult(world, dimension, request.target(), results);
        } finally {
            executor.shutdownNow();
        }
    }

    private static WorldDimension findDimension(Path world, String dimensionId) throws IOException {
        return WorldDimensionDiscovery.discover(new WorldInfo(world)).stream()
                .filter(dimension -> dimension.id().equals(dimensionId))
                .findFirst()
                .orElseThrow(() -> new IOException("dimension was not discovered: " + dimensionId));
    }

    private static ChunkLoadResult readChunk(
            Path regionDirectory,
            ChunkCoordinate coordinate,
            CancellationSignal cancellation) {
        if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
            return ChunkLoadResult.withoutChunk(
                    coordinate, ChunkLoadStatus.CANCELLED, "loading cancelled", 0, 0);
        }
        int regionX = Math.floorDiv(coordinate.x(), 32);
        int regionZ = Math.floorDiv(coordinate.z(), 32);
        Path regionPath = regionDirectory.resolve("r." + regionX + "." + regionZ + ".mca");
        if (!Files.isRegularFile(regionPath)) {
            return ChunkLoadResult.withoutChunk(
                    coordinate, ChunkLoadStatus.MISSING, "Region file does not exist", 0, 0);
        }

        long readStart = System.nanoTime();
        Optional<RegionChunkData> data;
        try (RegionFileReader reader = new RegionFileReader(regionPath)) {
            data = reader.readChunk(
                    Math.floorMod(coordinate.x(), 32),
                    Math.floorMod(coordinate.z(), 32));
        } catch (RegionReadException e) {
            ChunkLoadStatus status = e.getReason() == RegionReadException.Reason.EXTERNAL_CHUNK_UNSUPPORTED
                    ? ChunkLoadStatus.UNSUPPORTED
                    : ChunkLoadStatus.CORRUPT;
            return ChunkLoadResult.withoutChunk(
                    coordinate, status, e.getMessage(), System.nanoTime() - readStart, 0);
        } catch (IOException | RuntimeException e) {
            return ChunkLoadResult.withoutChunk(
                    coordinate,
                    ChunkLoadStatus.CORRUPT,
                    message(e),
                    System.nanoTime() - readStart,
                    0);
        }
        long readNanos = System.nanoTime() - readStart;
        if (data.isEmpty()) {
            return ChunkLoadResult.withoutChunk(
                    coordinate, ChunkLoadStatus.MISSING, "chunk is not present", readNanos, 0);
        }
        if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
            return ChunkLoadResult.withoutChunk(
                    coordinate, ChunkLoadStatus.CANCELLED, "loading cancelled", readNanos, 0);
        }

        long parseStart = System.nanoTime();
        try {
            return ChunkLoadResult.success(
                    coordinate,
                    ChunkNbtDecoder.read(data.orElseThrow(), coordinate.x(), coordinate.z()).chunk(),
                    readNanos,
                    System.nanoTime() - parseStart);
        } catch (ChunkDecodeException e) {
            ChunkLoadStatus status = e.reason() == ChunkDecodeException.Reason.UNSUPPORTED_CHUNK_LAYOUT
                    ? ChunkLoadStatus.UNSUPPORTED
                    : ChunkLoadStatus.CORRUPT;
            return ChunkLoadResult.withoutChunk(
                    coordinate, status, e.getMessage(), readNanos, System.nanoTime() - parseStart);
        }
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }
}
