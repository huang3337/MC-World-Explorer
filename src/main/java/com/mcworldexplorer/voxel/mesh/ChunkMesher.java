package com.mcworldexplorer.voxel.mesh;

import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.data.ChunkSection;
import com.mcworldexplorer.voxel.data.VoxelChunk;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.io.ChunkDecodeException;
import com.mcworldexplorer.voxel.io.ChunkLoadResult;
import com.mcworldexplorer.voxel.io.ChunkLoadStatus;
import com.mcworldexplorer.voxel.io.VoxelAreaLoadResult;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChunkMesher {
    public ChunkMeshSnapshot build(
            VoxelAreaLoadResult area,
            ChunkCoordinate target,
            CancellationSignal cancellation) throws ChunkDecodeException {
        if (area == null || target == null || cancellation == null) {
            throw new IllegalArgumentException("area, target and cancellation must not be null");
        }
        if (!area.target().contains(target)) {
            throw new IllegalArgumentException("target is outside the target rectangle");
        }
        ChunkLoadResult targetResult = area.result(target);
        if (targetResult.status() != ChunkLoadStatus.SUCCESS) {
            throw new IllegalArgumentException("target chunk is not available: " + targetResult.status());
        }

        VoxelChunk chunk = targetResult.chunk();
        BlockNeighborhood neighborhood = new BlockNeighborhood(area);
        Map<MaterialKey, BatchBuilder> builders = new HashMap<>();
        Map<String, WarningAccumulator> warnings = new LinkedHashMap<>();
        BoundsBuilder bounds = new BoundsBuilder();
        int blockCount = 0;
        long originX = Math.multiplyExact((long) target.x(), 16L);
        long originZ = Math.multiplyExact((long) target.z(), 16L);

        for (ChunkSection section : chunk.sections()) {
            int sectionBaseY = Math.multiplyExact(section.sectionY(), 16);
            for (int localY = 0; localY < 16; localY++) {
                if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException("mesh generation cancelled");
                }
                int y = Math.addExact(sectionBaseY, localY);
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        BlockState state = section.blockState(localX, localY, localZ);
                        BlockRenderClass classification = BlockClassifier.classify(state);
                        if (classification.isAir()) {
                            continue;
                        }
                        blockCount++;
                        if (classification.kind() == BlockRenderClass.Kind.FALLBACK) {
                            warnings.computeIfAbsent(
                                    "fallback:" + state.name(),
                                    ignored -> new WarningAccumulator(
                                            MeshWarning.Code.FALLBACK_BLOCK,
                                            target,
                                            state.name()))
                                    .increment();
                        }
                        MaterialKey key = new MaterialKey(
                                classification.layer(), classification.rgb(), classification.alpha());
                        BatchBuilder builder = builders.computeIfAbsent(key, BatchBuilder::new);
                        builder.addBlock();
                        long worldX = originX + localX;
                        long worldZ = originZ + localZ;
                        for (Face face : Face.values()) {
                            BlockNeighborhood.Query neighbor = neighborhood.query(
                                    worldX + face.deltaX,
                                    Math.addExact(y, face.deltaY),
                                    worldZ + face.deltaZ);
                            if (neighbor.unavailableFailure()) {
                                String warningKey = "neighbor:" + neighbor.sourceCoordinate();
                                warnings.computeIfAbsent(
                                        warningKey,
                                        ignored -> new WarningAccumulator(
                                                MeshWarning.Code.NEIGHBOR_UNAVAILABLE,
                                                neighbor.sourceCoordinate(),
                                                neighbor.sourceStatus() + ": " + neighbor.detail()))
                                        .increment();
                            }
                            if (visibleFace(state, classification, neighbor)) {
                                builder.addFace(face, localX, y, localZ);
                                bounds.include(localX, y, localZ);
                            }
                        }
                    }
                }
            }
        }

        List<MeshBatch> batches = builders.values().stream().map(BatchBuilder::build).toList();
        int faceCount = batches.stream().mapToInt(MeshBatch::faceCount).sum();
        int vertexCount = batches.stream().mapToInt(MeshBatch::vertexCount).sum();
        int indexCount = batches.stream().mapToInt(batch -> batch.indices().length).sum();
        MeshBounds meshBounds = bounds.empty()
                ? new MeshBounds(0, 0, 0, 0, 0, 0)
                : bounds.build();
        return new ChunkMeshSnapshot(
                target,
                originX,
                originZ,
                batches,
                meshBounds,
                blockCount,
                faceCount,
                vertexCount,
                indexCount,
                warnings.values().stream().map(WarningAccumulator::build).toList());
    }

    private static boolean visibleFace(
            BlockState currentState,
            BlockRenderClass current,
            BlockNeighborhood.Query neighborQuery) {
        if (neighborQuery.unavailableFailure()) {
            return true;
        }
        BlockState neighborState = neighborQuery.state();
        BlockRenderClass neighbor = BlockClassifier.classify(neighborState);
        if (current.isFluid()) {
            return neighbor.isAir();
        }
        if (neighbor.isAir() || neighbor.isFluid()) {
            return true;
        }
        if (current.kind() == BlockRenderClass.Kind.OPAQUE
                || current.kind() == BlockRenderClass.Kind.FALLBACK) {
            return neighbor.kind() != BlockRenderClass.Kind.OPAQUE
                    && neighbor.kind() != BlockRenderClass.Kind.FALLBACK;
        }
        return !currentState.equals(neighborState);
    }

    private enum Face {
        WEST(-1, 0, 0, -1, 0, 0, new float[]{
                0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0}),
        EAST(1, 0, 0, 1, 0, 0, new float[]{
                1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1}),
        DOWN(0, -1, 0, 0, -1, 0, new float[]{
                0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 1}),
        UP(0, 1, 0, 0, 1, 0, new float[]{
                0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0}),
        NORTH(0, 0, -1, 0, 0, -1, new float[]{
                1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0}),
        SOUTH(0, 0, 1, 0, 0, 1, new float[]{
                0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1});

        private final int deltaX;
        private final int deltaY;
        private final int deltaZ;
        private final float normalX;
        private final float normalY;
        private final float normalZ;
        private final float[] vertices;

        Face(
                int deltaX,
                int deltaY,
                int deltaZ,
                float normalX,
                float normalY,
                float normalZ,
                float[] vertices) {
            this.deltaX = deltaX;
            this.deltaY = deltaY;
            this.deltaZ = deltaZ;
            this.normalX = normalX;
            this.normalY = normalY;
            this.normalZ = normalZ;
            this.vertices = vertices;
        }
    }

    private record MaterialKey(RenderLayer layer, int rgb, float alpha) {
    }

    private static final class BatchBuilder {
        private static final int[] FACE_INDICES = {0, 1, 2, 0, 2, 3};

        private final MaterialKey material;
        private final FloatBuffer positions = new FloatBuffer();
        private final FloatBuffer normals = new FloatBuffer();
        private final IntBuffer indices = new IntBuffer();
        private int blockCount;

        private BatchBuilder(MaterialKey material) {
            this.material = material;
        }

        private void addBlock() {
            blockCount++;
        }

        private void addFace(Face face, int x, int y, int z) {
            int baseVertex = positions.size() / 3;
            for (int index = 0; index < face.vertices.length; index += 3) {
                positions.add(x + face.vertices[index]);
                positions.add(y + face.vertices[index + 1]);
                positions.add(z + face.vertices[index + 2]);
                normals.add(face.normalX);
                normals.add(face.normalY);
                normals.add(face.normalZ);
            }
            for (int index : FACE_INDICES) {
                indices.add(baseVertex + index);
            }
        }

        private MeshBatch build() {
            return new MeshBatch(
                    material.layer(),
                    material.rgb(),
                    material.alpha(),
                    blockCount,
                    positions.toArray(),
                    normals.toArray(),
                    indices.toArray());
        }
    }

    private static final class FloatBuffer {
        private float[] values = new float[256];
        private int size;

        private void add(float value) {
            if (size == values.length) {
                values = java.util.Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = value;
        }

        private int size() {
            return size;
        }

        private float[] toArray() {
            return java.util.Arrays.copyOf(values, size);
        }
    }

    private static final class IntBuffer {
        private int[] values = new int[128];
        private int size;

        private void add(int value) {
            if (size == values.length) {
                values = java.util.Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = value;
        }

        private int[] toArray() {
            return java.util.Arrays.copyOf(values, size);
        }
    }

    private static final class BoundsBuilder {
        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double minZ = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;
        private double maxZ = Double.NEGATIVE_INFINITY;

        private void include(int x, int y, int z) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x + 1.0);
            maxY = Math.max(maxY, y + 1.0);
            maxZ = Math.max(maxZ, z + 1.0);
        }

        private boolean empty() {
            return !Double.isFinite(minX);
        }

        private MeshBounds build() {
            return new MeshBounds(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private static final class WarningAccumulator {
        private final MeshWarning.Code code;
        private final ChunkCoordinate coordinate;
        private final String detail;
        private int occurrences;

        private WarningAccumulator(
                MeshWarning.Code code,
                ChunkCoordinate coordinate,
                String detail) {
            this.code = code;
            this.coordinate = coordinate;
            this.detail = detail;
        }

        private void increment() {
            occurrences++;
        }

        private MeshWarning build() {
            return new MeshWarning(code, coordinate, detail, occurrences);
        }
    }
}
