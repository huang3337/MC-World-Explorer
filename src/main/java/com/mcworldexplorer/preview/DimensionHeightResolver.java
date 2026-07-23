package com.mcworldexplorer.preview;

import com.mcworldexplorer.region.RegionChunkData;
import com.mcworldexplorer.region.RegionFileReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

public final class DimensionHeightResolver {
    private static final DimensionHeightRange OVERWORLD_RANGE = new DimensionHeightRange(-64, 319);
    private static final DimensionHeightRange NETHER_RANGE = new DimensionHeightRange(0, 255);
    private static final DimensionHeightRange END_RANGE = new DimensionHeightRange(0, 255);
    private static final int MAX_SAMPLED_CHUNKS = 8;

    private DimensionHeightResolver() {
    }

    public static DimensionHeightRange resolve(WorldDimension dimension) throws IOException {
        if (dimension == null) {
            throw new IllegalArgumentException("dimension must not be null");
        }
        return switch (dimension.kind()) {
            case OVERWORLD -> OVERWORLD_RANGE;
            case NETHER -> NETHER_RANGE;
            case END -> END_RANGE;
            case MOD -> resolveFromRegions(dimension);
        };
    }

    private static DimensionHeightRange resolveFromRegions(WorldDimension dimension) throws IOException {
        List<Path> regionFiles;
        if (!Files.isDirectory(dimension.regionDirectory())) {
            throw new IOException("dimension Region directory does not exist: " + dimension.regionDirectory());
        }
        try (Stream<Path> paths = Files.list(dimension.regionDirectory())) {
            regionFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".mca"))
                    .sorted()
                    .toList();
        }

        SurfaceSampler sampler = new SurfaceSampler();
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int sampledChunks = 0;
        IOException lastFailure = null;
        for (Path regionFile : regionFiles) {
            checkCancelled();
            try (RegionFileReader reader = new RegionFileReader(regionFile)) {
                for (int localZ = 0; localZ < 32 && sampledChunks < MAX_SAMPLED_CHUNKS; localZ++) {
                    for (int localX = 0; localX < 32 && sampledChunks < MAX_SAMPLED_CHUNKS; localX++) {
                        checkCancelled();
                        try {
                            Optional<RegionChunkData> chunk = reader.readChunk(localX, localZ);
                            if (chunk.isEmpty()) {
                                continue;
                            }
                            Optional<DimensionHeightRange> range = sampler.sectionRange(chunk.orElseThrow());
                            if (range.isPresent()) {
                                minY = Math.min(minY, range.orElseThrow().minY());
                                maxY = Math.max(maxY, range.orElseThrow().maxY());
                                sampledChunks++;
                            }
                        } catch (IOException e) {
                            lastFailure = e;
                        }
                    }
                }
            } catch (IOException e) {
                lastFailure = e;
            }
            if (sampledChunks >= MAX_SAMPLED_CHUNKS) {
                break;
            }
        }

        if (sampledChunks == 0) {
            IOException failure = new IOException(
                    "unable to determine actual height range for dimension " + dimension.id());
            if (lastFailure != null) {
                failure.addSuppressed(lastFailure);
            }
            throw failure;
        }
        return new DimensionHeightRange(minY, maxY);
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("dimension height scan cancelled");
        }
    }
}
