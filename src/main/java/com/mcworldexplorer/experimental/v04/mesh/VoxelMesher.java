package com.mcworldexplorer.experimental.v04.mesh;

import com.mcworldexplorer.experimental.v04.data.VoxelBlockState;
import com.mcworldexplorer.experimental.v04.data.VoxelChunk;
import com.mcworldexplorer.experimental.v04.data.VoxelChunkNeighborhood;
import com.mcworldexplorer.experimental.v04.data.VoxelDataException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class VoxelMesher {
    public RenderSnapshot build(VoxelChunkNeighborhood neighborhood) throws VoxelDataException {
        if (neighborhood == null) {
            throw new IllegalArgumentException("neighborhood must not be null");
        }
        VoxelChunk target = neighborhood.target();
        int defaultX = Math.addExact(Math.multiplyExact(target.chunkX(), 16), 8);
        int defaultZ = Math.addExact(Math.multiplyExact(target.chunkZ(), 16), 8);
        if (target.minY().isEmpty()) {
            return new RenderSnapshot(
                    List.of(),
                    new MeshBounds(defaultX, 0, defaultZ, defaultX, 0, defaultZ),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    neighborhood.warnings());
        }

        Map<Integer, BatchBuilder> builders = new HashMap<>();
        BoundsBuilder bounds = new BoundsBuilder();
        int blockCount = 0;
        int solidBlockCount = 0;
        int fluidBlockCount = 0;
        int fluidFaceCount = 0;
        int baseX = Math.multiplyExact(target.chunkX(), 16);
        int baseZ = Math.multiplyExact(target.chunkZ(), 16);
        for (int sectionY : target.sectionYs()) {
            int sectionBaseY = Math.multiplyExact(sectionY, 16);
            for (int localY = 0; localY < 16; localY++) {
                int y = sectionBaseY + localY;
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        VoxelBlockState state = target.blockState(localX, y, localZ);
                        VoxelBlockKind kind = VoxelBlockClassifier.classify(state);
                        if (kind == VoxelBlockKind.AIR) {
                            continue;
                        }
                        blockCount++;
                        if (kind.isFluid()) {
                            fluidBlockCount++;
                        } else {
                            solidBlockCount++;
                        }
                        int worldX = baseX + localX;
                        int worldZ = baseZ + localZ;
                        BatchBuilder builder = builders.computeIfAbsent(
                                VoxelColorResolver.resolve(state),
                                BatchBuilder::new);
                        for (Face face : Face.values()) {
                            VoxelBlockState neighbor = neighborhood.blockState(
                                    worldX + face.deltaX,
                                    y + face.deltaY,
                                    worldZ + face.deltaZ);
                            if (VoxelBlockClassifier.classify(neighbor) == VoxelBlockKind.AIR) {
                                builder.addFace(face, worldX, y, worldZ);
                                bounds.include(worldX, y, worldZ);
                                if (kind.isFluid()) {
                                    fluidFaceCount++;
                                }
                            }
                        }
                    }
                }
            }
        }

        List<MeshBatch> batches = builders.values().stream()
                .map(BatchBuilder::build)
                .toList();
        int faces = batches.stream().mapToInt(MeshBatch::faceCount).sum();
        int vertices = batches.stream().mapToInt(MeshBatch::vertexCount).sum();
        int indices = batches.stream().mapToInt(batch -> batch.indices().length).sum();
        MeshBounds meshBounds = bounds.empty()
                ? new MeshBounds(defaultX, 0, defaultZ, defaultX, 0, defaultZ)
                : bounds.build();
        return new RenderSnapshot(
                batches,
                meshBounds,
                blockCount,
                solidBlockCount,
                fluidBlockCount,
                faces,
                fluidFaceCount,
                vertices,
                indices,
                neighborhood.warnings());
    }

    private enum Face {
        WEST(-1, 0, 0, -1, 0, 0, new float[]{
                0, 0, 0,  0, 0, 1,  0, 1, 1,  0, 1, 0}),
        EAST(1, 0, 0, 1, 0, 0, new float[]{
                1, 0, 1,  1, 0, 0,  1, 1, 0,  1, 1, 1}),
        DOWN(0, -1, 0, 0, -1, 0, new float[]{
                0, 0, 1,  0, 0, 0,  1, 0, 0,  1, 0, 1}),
        UP(0, 1, 0, 0, 1, 0, new float[]{
                0, 1, 0,  0, 1, 1,  1, 1, 1,  1, 1, 0}),
        NORTH(0, 0, -1, 0, 0, -1, new float[]{
                1, 0, 0,  0, 0, 0,  0, 1, 0,  1, 1, 0}),
        SOUTH(0, 0, 1, 0, 0, 1, new float[]{
                0, 0, 1,  1, 0, 1,  1, 1, 1,  0, 1, 1});

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

    private static final class BatchBuilder {
        private static final int[] FACE_INDICES = {0, 1, 2, 0, 2, 3};

        private final int rgb;
        private final List<Float> positions = new ArrayList<>();
        private final List<Float> normals = new ArrayList<>();
        private final List<Integer> indices = new ArrayList<>();

        private BatchBuilder(int rgb) {
            this.rgb = rgb;
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
            float[] positionArray = new float[positions.size()];
            float[] normalArray = new float[normals.size()];
            int[] indexArray = new int[indices.size()];
            for (int index = 0; index < positions.size(); index++) {
                positionArray[index] = positions.get(index);
                normalArray[index] = normals.get(index);
            }
            for (int index = 0; index < indices.size(); index++) {
                indexArray[index] = indices.get(index);
            }
            return new MeshBatch(rgb, positionArray, normalArray, indexArray);
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
}
