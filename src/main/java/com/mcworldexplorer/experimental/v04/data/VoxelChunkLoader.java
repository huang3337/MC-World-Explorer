package com.mcworldexplorer.experimental.v04.data;

import com.mcworldexplorer.experimental.v04.V04Arguments;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.preview.WorldDimensionDiscovery;
import com.mcworldexplorer.region.RegionChunkData;
import com.mcworldexplorer.region.RegionFileReader;
import com.mcworldexplorer.world.WorldInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class VoxelChunkLoader {
    public VoxelChunkNeighborhood load(V04Arguments arguments)
            throws IOException, VoxelLoadException {
        return loadMeasured(arguments).neighborhood();
    }

    public VoxelChunkLoadResult loadMeasured(V04Arguments arguments)
            throws IOException, VoxelLoadException {
        WorldDimension dimension = findDimension(arguments);
        Path world = arguments.world().toRealPath();
        Path regionDirectory = dimension.regionDirectory().toRealPath();
        if (!regionDirectory.startsWith(world) || !Files.isDirectory(regionDirectory)) {
            throw new VoxelLoadException(
                    VoxelLoadException.Reason.REGION_DIRECTORY_INVALID,
                    arguments.chunkX(),
                    arguments.chunkZ(),
                    "dimension Region directory is invalid: " + regionDirectory);
        }

        VoxelChunk target;
        long readNanos = 0;
        long parseNanos = 0;
        try {
            TimedChunk targetResult = readChunk(
                    regionDirectory, arguments.chunkX(), arguments.chunkZ());
            readNanos += targetResult.regionReadNanos();
            parseNanos += targetResult.parseNanos();
            target = targetResult.chunk()
                    .orElseThrow(() -> new VoxelLoadException(
                            VoxelLoadException.Reason.TARGET_CHUNK_MISSING,
                            arguments.chunkX(),
                            arguments.chunkZ(),
                            "target chunk does not exist"));
        } catch (VoxelLoadException e) {
            throw e;
        } catch (IOException | VoxelDataException e) {
            throw new VoxelLoadException(
                    VoxelLoadException.Reason.TARGET_CHUNK_UNREADABLE,
                    arguments.chunkX(),
                    arguments.chunkZ(),
                    "failed to read target chunk: " + e.getMessage(),
                    e);
        }

        Map<VoxelChunkNeighborhood.Direction, Optional<VoxelChunk>> neighbors =
                new EnumMap<>(VoxelChunkNeighborhood.Direction.class);
        List<VoxelLoadWarning> warnings = new ArrayList<>();
        for (VoxelChunkNeighborhood.Direction direction : VoxelChunkNeighborhood.Direction.values()) {
            int chunkX = direction.chunkX(arguments.chunkX());
            int chunkZ = direction.chunkZ(arguments.chunkZ());
            try {
                TimedChunk result = readChunk(regionDirectory, chunkX, chunkZ);
                readNanos += result.regionReadNanos();
                parseNanos += result.parseNanos();
                neighbors.put(direction, result.chunk());
            } catch (IOException | VoxelDataException e) {
                neighbors.put(direction, Optional.empty());
                warnings.add(new VoxelLoadWarning(
                        direction,
                        chunkX,
                        chunkZ,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        return new VoxelChunkLoadResult(
                new VoxelChunkNeighborhood(target, neighbors, warnings),
                readNanos,
                parseNanos);
    }

    private static WorldDimension findDimension(V04Arguments arguments)
            throws IOException, VoxelLoadException {
        String requested = WorldDimension.normalizeId(arguments.dimensionId());
        return WorldDimensionDiscovery.discover(new WorldInfo(arguments.world())).stream()
                .filter(dimension -> dimension.id().equals(requested))
                .findFirst()
                .orElseThrow(() -> new VoxelLoadException(
                        VoxelLoadException.Reason.DIMENSION_NOT_FOUND,
                        arguments.chunkX(),
                        arguments.chunkZ(),
                        "dimension was not discovered: " + requested));
    }

    private static TimedChunk readChunk(
            Path regionDirectory,
            int chunkX,
            int chunkZ) throws IOException, VoxelDataException {
        int regionX = Math.floorDiv(chunkX, 32);
        int regionZ = Math.floorDiv(chunkZ, 32);
        Path regionPath = regionDirectory.resolve("r." + regionX + "." + regionZ + ".mca");
        if (!Files.isRegularFile(regionPath)) {
            return new TimedChunk(Optional.empty(), 0, 0);
        }
        long readStart = System.nanoTime();
        Optional<RegionChunkData> data;
        try (RegionFileReader reader = new RegionFileReader(regionPath)) {
            data = reader.readChunk(
                    Math.floorMod(chunkX, 32),
                    Math.floorMod(chunkZ, 32));
        }
        long regionReadNanos = System.nanoTime() - readStart;
        if (data.isEmpty()) {
            return new TimedChunk(Optional.empty(), regionReadNanos, 0);
        }
        long parseStart = System.nanoTime();
        VoxelChunk chunk = VoxelChunkParser.read(data.orElseThrow(), chunkX, chunkZ);
        return new TimedChunk(
                Optional.of(chunk),
                regionReadNanos,
                System.nanoTime() - parseStart);
    }

    private record TimedChunk(
            Optional<VoxelChunk> chunk,
            long regionReadNanos,
            long parseNanos) {
    }
}
