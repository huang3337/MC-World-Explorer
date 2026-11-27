package com.mcworldexplorer.sdk;

import com.mcworldexplorer.nbt.SavedLegacyIdMap;
import com.mcworldexplorer.preview.BlockColorPalette;
import com.mcworldexplorer.preview.ChunkSurface;
import com.mcworldexplorer.preview.SurfaceColumn;
import com.mcworldexplorer.preview.SurfaceSampler;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.region.RegionChunkData;
import com.mcworldexplorer.region.RegionFileReader;
import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.data.ChunkRectangle;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.io.VoxelAreaLoadResult;
import com.mcworldexplorer.voxel.io.VoxelAreaLoader;
import com.mcworldexplorer.voxel.io.VoxelAreaRequest;
import com.mcworldexplorer.voxel.mesh.MeshWarning;
import com.mcworldexplorer.voxel.mesh.VoxelSceneBuilder;
import com.mcworldexplorer.voxel.mesh.VoxelSceneResult;
import com.mcworldexplorer.voxel.mesh.VoxelSceneSnapshot;
import com.mcworldexplorer.voxel.resource.BlockTextureSet;
import com.mcworldexplorer.voxel.resource.InstanceResourceSource;
import com.mcworldexplorer.world.WorldInfo;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Read-only handle to an opened Minecraft Java Edition world.
 *
 * <p>All operations strictly read world and optional client/instance data without modifying
 * any files inside the save directory or Minecraft instance.</p>
 */
public final class McWorld {
    private static final int MISSING_MAP_COLOR = 0xFF20262B;

    private final Path directory;
    private final WorldInfo info;
    private final List<WorldDimension> dimensions;

    McWorld(Path directory, WorldInfo info, List<WorldDimension> dimensions) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.info = Objects.requireNonNull(info, "info");
        this.dimensions = List.copyOf(Objects.requireNonNull(dimensions, "dimensions"));
    }

    /** Returns the normalized world folder path. */
    public Path directory() {
        return directory;
    }

    /** Returns parsed {@code level.dat} metadata for this world. */
    public WorldInfo info() {
        return info;
    }

    /** Returns all discovered dimensions (vanilla and mod dimensions). */
    public List<WorldDimension> dimensions() {
        return dimensions;
    }

    /** Finds a dimension by canonical or legacy ID (for example {@code "minecraft:overworld"} or {@code "DIM-1"}). */
    public Optional<WorldDimension> findDimension(String dimensionId) {
        String normalized = WorldDimension.normalizeId(dimensionId);
        return dimensions.stream().filter(d -> d.id().equals(normalized)).findFirst();
    }

    /**
     * Returns the dimension matching {@code dimensionId}, or throws {@link IllegalArgumentException} if absent.
     */
    public WorldDimension dimension(String dimensionId) {
        String normalized = WorldDimension.normalizeId(dimensionId);
        return findDimension(normalized).orElseThrow(
                () -> new IllegalArgumentException("Dimension not found in world: " + normalized));
    }

    /**
     * Builds an overworld 3D voxel scene using built-in solid block colors without requiring any client JAR.
     *
     * @param chunks target chunk rectangle
     * @return immutable 3D voxel scene snapshot
     * @throws IOException if the target chunks cannot be read or contain no renderable geometry
     */
    public VoxelSceneSnapshot buildScene(ChunkRectangle chunks) throws IOException {
        return buildScene(SceneBuildRequest.of(chunks));
    }

    /**
     * Builds a 3D voxel scene for the configured dimension, chunk selection, and optional client/instance resources.
     *
     * @param request scene build configuration
     * @return immutable 3D voxel scene snapshot
     * @throws IOException if resources or chunks cannot be read or produce no renderable geometry
     */
    public VoxelSceneSnapshot buildScene(SceneBuildRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        WorldDimension targetDimension = dimension(request.dimensionId());
        CancellationSignal cancellation = request.cancellation();
        checkCancelled(cancellation);

        try (InstanceResourceSource archive = request.clientJar().isEmpty()
                ? null
                : InstanceResourceSource.open(
                        directory,
                        request.clientJar().orElseThrow(),
                        request.minecraftVersion().orElseThrow(),
                        request.instanceDirectory().orElse(null),
                        cancellation,
                        ignored -> {})) {
            checkCancelled(cancellation);
            VoxelAreaLoadResult area = new VoxelAreaLoader().load(
                    new VoxelAreaRequest(directory, targetDimension.id(), request.chunks()),
                    cancellation);
            checkCancelled(cancellation);

            BlockTextureSet textures = archive == null
                    ? null
                    : BlockTextureSet.load(archive, area, cancellation, request.modelRules(), (m, c, t) -> {});
            checkCancelled(cancellation);

            VoxelSceneResult result = new VoxelSceneBuilder(textures).build(area, cancellation);
            if (result.cancelled() || cancellation.isCancelled()) {
                throw new CancellationException("scene build cancelled");
            }
            VoxelSceneSnapshot snapshot = result.snapshot().orElseThrow(
                    () -> new IOException("No renderable 3D voxel geometry found in selected chunk area"));

            if (archive != null) {
                archive.validateAll();
                List<MeshWarning> warnings = new ArrayList<>(snapshot.warnings());
                ChunkCoordinate anchorChunk = new ChunkCoordinate(request.chunks().minX(), request.chunks().minZ());
                warnings.add(new MeshWarning(MeshWarning.Code.RESOURCE_CONTEXT, anchorChunk, archive.description()));
                for (String detail : archive.originSamples()) {
                    warnings.add(new MeshWarning(MeshWarning.Code.RESOURCE_ORIGIN, anchorChunk, detail));
                }
                for (String detail : archive.warnings()) {
                    warnings.add(new MeshWarning(MeshWarning.Code.RESOURCE_WARNING, anchorChunk, detail));
                }
                snapshot = new VoxelSceneSnapshot(
                        snapshot.anchorWorldX(),
                        snapshot.anchorWorldZ(),
                        snapshot.chunks(),
                        snapshot.bounds(),
                        snapshot.blockCount(),
                        snapshot.faceCount(),
                        snapshot.vertexCount(),
                        snapshot.indexCount(),
                        warnings,
                        snapshot.atlas(),
                        archive.confirmedModIds());
            }
            return snapshot;
        }
    }

    /**
     * Builds a 3D voxel scene and exports it as Wavefront {@code .obj} + {@code .mtl}
     * (plus {@code _atlas.png} when textured).
     *
     * @param request         scene build configuration
     * @param outputDirectory target directory for exported files
     * @param baseName        safe file stem without extension
     * @return export summary
     * @throws IOException if reading the world or writing output files fails
     */
    public ObjExportResult exportWavefrontObj(
            SceneBuildRequest request,
            Path outputDirectory,
            String baseName) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        WavefrontObjExporter.validateBaseName(baseName);
        VoxelSceneSnapshot snapshot = buildScene(request);
        return WavefrontObjExporter.export(snapshot, outputDirectory, baseName, request.cancellation());
    }

    /**
     * Renders a headless 2D top-down map image directly from region files without writing any disk cache.
     *
     * @param request map rendering configuration
     * @return rendered {@code TYPE_INT_ARGB} map image
     * @throws IOException if reading region files fails
     */
    public BufferedImage renderMapImage(MapRenderRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        WorldDimension targetDimension = dimension(request.dimensionId());
        Path realWorldDir = directory.toRealPath();
        Path regionDirectory = targetDimension.regionDirectory().toAbsolutePath().normalize();
        if (!regionDirectory.startsWith(directory)) {
            throw new IllegalArgumentException("dimension region directory must stay inside world directory");
        }
        CancellationSignal cancellation = request.cancellation();
        checkCancelled(cancellation);

        int width = request.imageWidth();
        int height = request.imageHeight();
        if (!Files.exists(regionDirectory)) {
            return emptyMapImage(width, height);
        }
        if (Files.isSymbolicLink(regionDirectory) || !Files.isDirectory(regionDirectory)
                || !regionDirectory.toRealPath().startsWith(realWorldDir)) {
            throw new IOException("Refusing linked or escaped dimension region directory: " + regionDirectory);
        }

        int stride = width + 1;
        int cellCount = stride * (height + 1);
        long[] redSums = new long[cellCount];
        long[] greenSums = new long[cellCount];
        long[] blueSums = new long[cellCount];
        long[] heightSums = new long[cellCount];
        int[] counts = new int[cellCount];

        int scale = request.blocksPerPixel();
        long minBlockX = request.minBlockX();
        long minBlockZ = request.minBlockZ();
        int minChunkX = request.minChunkX();
        int maxChunkX = request.maxChunkX();
        int minChunkZ = request.minChunkZ();
        int maxChunkZ = request.maxChunkZ();

        SavedLegacyIdMap legacyIdMap = SavedLegacyIdMap.readFromWorld(directory);
        SurfaceSampler sampler = new SurfaceSampler();
        Map<Path, RegionFileReader> readers = new HashMap<>();
        Set<Path> unavailableRegions = new HashSet<>();
        IOException closeFailure = null;

        try {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    checkCancelled(cancellation);
                    int regionX = Math.floorDiv(chunkX, 32);
                    int regionZ = Math.floorDiv(chunkZ, 32);
                    Path regionPath = regionDirectory.resolve("r." + regionX + "." + regionZ + ".mca");
                    if (unavailableRegions.contains(regionPath)
                            || Files.isSymbolicLink(regionPath)
                            || !Files.isRegularFile(regionPath)) {
                        continue;
                    }
                    RegionFileReader reader = readers.get(regionPath);
                    if (reader == null) {
                        try {
                            if (Files.size(regionPath) == 0L) {
                                unavailableRegions.add(regionPath);
                                continue;
                            }
                            reader = new RegionFileReader(regionPath);
                            readers.put(regionPath, reader);
                        } catch (IOException unreadable) {
                            unavailableRegions.add(regionPath);
                            continue;
                        }
                    }
                    try {
                        Optional<RegionChunkData> chunkData = reader.readChunk(
                                Math.floorMod(chunkX, 32), Math.floorMod(chunkZ, 32));
                        if (chunkData.isEmpty()) {
                            continue;
                        }
                        ChunkSurface surface = sampler.sample(chunkData.orElseThrow(), request.layer(), legacyIdMap);
                        long baseX = (long) chunkX * 16L;
                        long baseZ = (long) chunkZ * 16L;
                        for (int localZ = 0; localZ < 16; localZ++) {
                            long pz = Math.floorDiv(baseZ + localZ - minBlockZ, scale);
                            if (pz < -1 || pz >= height) {
                                continue;
                            }
                            for (int localX = 0; localX < 16; localX++) {
                                long px = Math.floorDiv(baseX + localX - minBlockX, scale);
                                if (px < -1 || px >= width || (px == -1 && pz == -1)) {
                                    continue;
                                }
                                Optional<SurfaceColumn> column = surface.getColumn(localX, localZ);
                                if (column.isEmpty()) {
                                    continue;
                                }
                                SurfaceColumn value = column.orElseThrow();
                                int rgb = BlockColorPalette.resolve(value.blockName()).rgb();
                                int idx = (int) (px + 1 + (pz + 1) * stride);
                                redSums[idx] += (rgb >>> 16) & 0xFF;
                                greenSums[idx] += (rgb >>> 8) & 0xFF;
                                blueSums[idx] += rgb & 0xFF;
                                heightSums[idx] += value.y();
                                counts[idx]++;
                            }
                        }
                    } catch (IOException | RuntimeException ignored) {
                        // Skip corrupt individual chunks while preserving readable surroundings.
                    }
                }
            }
        } finally {
            for (RegionFileReader reader : readers.values()) {
                try {
                    reader.close();
                } catch (IOException failure) {
                    if (closeFailure == null) {
                        closeFailure = failure;
                    } else {
                        closeFailure.addSuppressed(failure);
                    }
                }
            }
        }
        if (closeFailure != null) {
            throw closeFailure;
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] northHeights = new int[width];
        int[] row = new int[width];
        for (int x = 0; x < width; x++) {
            northHeights[x] = averageHeight(heightSums, counts, x + 1);
        }
        for (int z = 0; z < height; z++) {
            int westHeight = averageHeight(heightSums, counts, (z + 1) * stride);
            for (int x = 0; x < width; x++) {
                int idx = x + 1 + (z + 1) * stride;
                int count = counts[idx];
                if (count == 0) {
                    row[x] = MISSING_MAP_COLOR;
                    continue;
                }
                int avgHeight = averageHeight(heightSums, counts, idx);
                int neighborHeight = 0;
                int neighborCount = 0;
                if (counts[idx - 1] > 0) {
                    neighborHeight += westHeight;
                    neighborCount++;
                }
                if (counts[idx - stride] > 0) {
                    neighborHeight += northHeights[x];
                    neighborCount++;
                }
                double shade = 1.0;
                if (neighborCount > 0) {
                    int refHeight = Math.floorDiv(neighborHeight, neighborCount);
                    shade += Math.max(-0.22, Math.min(0.22, (avgHeight - refHeight) * 0.035));
                }
                int color = ((int) (redSums[idx] / count) << 16)
                        | ((int) (greenSums[idx] / count) << 8)
                        | (int) (blueSums[idx] / count);
                row[x] = 0xFF000000 | BlockColorPalette.shade(color, shade);
                westHeight = avgHeight;
                northHeights[x] = avgHeight;
            }
            image.getRaster().setDataElements(0, z, width, 1, row);
        }
        return image;
    }

    /**
     * Renders a 2D map image and writes it to {@code outputPngFile} as PNG.
     *
     * @param request       map rendering configuration
     * @param outputPngFile target PNG file path
     * @return normalized path of the written PNG file
     * @throws IOException if rendering or writing the PNG file fails
     */
    public Path exportMapPng(MapRenderRequest request, Path outputPngFile) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(outputPngFile, "outputPngFile");
        Path normalized = outputPngFile.toAbsolutePath().normalize();
        BufferedImage image = renderMapImage(request);
        Path parent = normalized.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!ImageIO.write(image, "png", normalized.toFile())) {
            throw new IOException("No PNG ImageIO writer available for " + normalized);
        }
        return normalized;
    }

    private static BufferedImage emptyMapImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width];
        Arrays.fill(row, MISSING_MAP_COLOR);
        for (int z = 0; z < height; z++) {
            image.getRaster().setDataElements(0, z, width, 1, row);
        }
        return image;
    }

    private static int averageHeight(long[] heightSums, int[] counts, int index) {
        return counts[index] == 0 ? 0 : Math.toIntExact(Math.floorDiv(heightSums[index], counts[index]));
    }

    private static void checkCancelled(CancellationSignal cancellation) {
        if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("SDK operation cancelled");
        }
    }
}
