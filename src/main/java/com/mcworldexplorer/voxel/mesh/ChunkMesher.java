package com.mcworldexplorer.voxel.mesh;

import com.mcworldexplorer.voxel.resource.BlockModelResolver.Direction;
import com.mcworldexplorer.voxel.resource.BlockTextureSet;
import com.mcworldexplorer.voxel.resource.BlockResourceDiagnostic;
import com.mcworldexplorer.voxel.resource.ModelOcclusion;
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
    private final BlockTextureSet textures;
    public ChunkMesher() { this(null); }
    private final VoxelTargetIndex.Budget targetBudget;
    public ChunkMesher(BlockTextureSet textures) { this(textures,new VoxelTargetIndex.Budget()); }
    ChunkMesher(BlockTextureSet textures, VoxelTargetIndex.Budget targetBudget) { this.textures = textures; this.targetBudget = targetBudget; }
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
        Map<Object, WarningAccumulator> warnings = new LinkedHashMap<>();
        BoundsBuilder bounds = new BoundsBuilder();
        int blockCount = 0;
        var targets = new VoxelTargetIndex.Builder(targetBudget);
        Map<BlockState,List<ModelOcclusion.Box>> occlusion = new HashMap<>();
        long originX = Math.multiplyExact((long) target.x(), 16L);
        long originZ = Math.multiplyExact((long) target.z(), 16L);

        for (ChunkSection section : chunk.sections()) {
            int sectionBaseY = Math.multiplyExact(section.sectionY(), 16);
            Map<BlockState, List<BlockTextureSet.Surface>> sectionTextures = new HashMap<>();
            Map<BlockState, String> sectionFallbacks = new HashMap<>();
            Map<DiagnosticStateKey,List<WarningAccumulator>> sectionDiagnosticWarnings=new HashMap<>();
            for (int localY = 0; localY < 16; localY++) {
                if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
                    throw new java.util.concurrent.CancellationException("mesh generation cancelled");
                }
                int y = Math.addExact(sectionBaseY, localY);
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        BlockState state = section.blockState(localX, localY, localZ);
                        BlockState originalState = state;
                        if(textures!=null)state=BlockTextureSet.visualState(state,chunk.blockEntity(originX+localX,y,originZ+localZ).orElse(null));
                        if(textures!=null)state=textures.renderState(state,originX+localX,y,originZ+localZ);
                        final BlockState renderState=state;
                        BlockRenderClass classification = BlockClassifier.classify(state);
                        if (classification.isAir()) {
                            continue;
                        }
                        blockCount++;
                        if (textures != null && !sectionTextures.containsKey(state)) {
                            var faces = textures.surfaces(state);
                            sectionTextures.put(state, faces);
                            if (faces == null) sectionFallbacks.put(state, state.name() + ": " + textures.fallbackReason(state));
                        }
                        var texturedFaces = sectionTextures.get(state);
                        targets.add(localX,y,localZ,originalState,state,textures,texturedFaces);
                        if (texturedFaces == null && classification.kind() == BlockRenderClass.Kind.FALLBACK) {
                            if(textures==null){
                                sectionDiagnosticWarnings.computeIfAbsent(new DiagnosticStateKey(originalState,renderState),ignored->{
                                    var diagnostic=new BlockResourceDiagnostic(originalState,BlockResourceDiagnostic.Severity.DEGRADED,"SIMPLE_COLORS","未使用资源模型，显示简化方块",List.of(),List.of(),false);
                                    return List.of(warnings.computeIfAbsent(new DiagnosticWarningKey(MeshWarning.Code.FALLBACK_BLOCK,diagnostic),key->new WarningAccumulator(MeshWarning.Code.FALLBACK_BLOCK,target,renderState.name(),diagnostic)));
                                }).getFirst().increment();
                            }else warnings.computeIfAbsent("fallback:"+state.name(),ignored->new WarningAccumulator(MeshWarning.Code.FALLBACK_BLOCK,target,renderState.name())).increment();
                        }

                        if(textures!=null){
                            sectionDiagnosticWarnings.computeIfAbsent(new DiagnosticStateKey(originalState,renderState),ignored->textures.diagnostics(renderState).stream().map(d->{
                                var diagnostic=d.originalState(originalState);
                                var code=texturedFaces==null?MeshWarning.Code.TEXTURE_FALLBACK:MeshWarning.Code.MODEL_APPROXIMATION;
                                String detail=texturedFaces==null?sectionFallbacks.get(renderState):renderState.name()+": "+diagnostic.detail();
                                return warnings.computeIfAbsent(new DiagnosticWarningKey(code,diagnostic),key->new WarningAccumulator(code,target,detail,diagnostic));
                            }).toList()).forEach(WarningAccumulator::increment);
                        }
                        long worldX = originX + localX;
                        long worldZ = originZ + localZ;
                        if(texturedFaces!=null) {
                            List<ModelOcclusion.Box> own=occlusion.computeIfAbsent(state,ignored->ModelOcclusion.boxes(texturedFaces));
                            BatchBuilder primary=null;
                            boolean fluid=FluidGeometry.liquid(state);
                            boolean column=fluid&&FluidGeometry.same(state,neighborhood.query(worldX,Math.addExact(y,1),worldZ).state());
                            for(var originalSurface:texturedFaces) {
                                boolean liquidSurface=originalSurface.layer()==RenderLayer.FLUID;
                                var surface=column&&liquidSurface?FluidGeometry.continuousColumn(originalSurface):originalSurface;
                                MaterialKey key=new MaterialKey(surface.layer(),0xFFFFFF,1,true,surface.shade());
                                BatchBuilder builder=builders.computeIfAbsent(key,BatchBuilder::new);
                                if(primary==null){primary=builder;builder.addBlock();}
                                if(ModelOcclusion.covers(surface,own,0,0,0))continue;
                                Direction cull=surface.cullFace();
                                if(cull!=null) {
                                    Face side=Face.valueOf(cull.name());
                                    var neighbor=neighborhood.query(worldX+side.deltaX,Math.addExact(y,side.deltaY),worldZ+side.deltaZ);
                                    if(fluid&&liquidSurface&&FluidGeometry.hidden(state,neighbor.state(),cull,column))continue;
                                    var neighborState=textures.renderState(neighbor.state(),worldX+side.deltaX,Math.addExact(y,side.deltaY),worldZ+side.deltaZ);
                                    var neighborFaces=textures.surfaces(neighborState);
                                    var boxes=occlusion.computeIfAbsent(neighborState,ignored->ModelOcclusion.boxes(neighborFaces));
                                    if(ModelOcclusion.covers(surface,boxes,side.deltaX,side.deltaY,side.deltaZ))continue;
                                }
                                builder.addTexturedFace(surface,localX,y,localZ);
                                bounds.include(surface,localX,y,localZ);
                            }
                            if(primary==null)builders.computeIfAbsent(new MaterialKey(RenderLayer.OPAQUE,0xFFFFFF,1,true,true),BatchBuilder::new).addBlock();
                            continue;
                        }
                        MaterialKey key = new MaterialKey(classification.layer(),classification.rgb(),classification.alpha(),false,true);
                        BatchBuilder builder=builders.computeIfAbsent(key,BatchBuilder::new);
                        builder.addBlock();
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
                            boolean visible=visibleFace(state,classification,neighbor);
                            BlockState neighborState=textures==null?neighbor.state():textures.renderState(neighbor.state(),worldX+face.deltaX,Math.addExact(y,face.deltaY),worldZ+face.deltaZ);
                            if(textures!=null&&textures.surfaces(neighborState)!=null) {
                                var boxes=occlusion.computeIfAbsent(neighborState,ignored->ModelOcclusion.boxes(textures.surfaces(neighborState)));
                                visible=!ModelOcclusion.covers(face.surface(),boxes,face.deltaX,face.deltaY,face.deltaZ);
                            }
                            if (visible) {
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
                warnings.values().stream().map(WarningAccumulator::build).toList(), targets.build());
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

        private BlockTextureSet.Surface surface() {
            java.util.ArrayList<Float> p=new java.util.ArrayList<>();for(float value:vertices)p.add(value);
            return new BlockTextureSet.Surface(Direction.valueOf(name()),p,List.of(),List.of(normalX,normalY,normalZ),
                    Direction.valueOf(name()),true,RenderLayer.OPAQUE,0);
        }

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

    private record MaterialKey(RenderLayer layer, int rgb, float alpha, boolean textured, boolean shaded) {
    }

    private static final class BatchBuilder {
        private static final int[] FACE_INDICES = {0, 1, 2, 0, 2, 3};

        private final MaterialKey material;
        private final FloatBuffer positions = new FloatBuffer();
        private final FloatBuffer normals = new FloatBuffer();
        private final FloatBuffer uv = new FloatBuffer();
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

        private void addTexturedFace(BlockTextureSet.Surface surface, int x, int y, int z) {
            int base = positions.size() / 3;
            for (int i = 0; i < 4; i++) {
                positions.add(x + surface.positions().get(i*3));
                positions.add(y + surface.positions().get(i*3+1));
                positions.add(z + surface.positions().get(i*3+2));
                normals.add(surface.normal().get(0)); normals.add(surface.normal().get(1)); normals.add(surface.normal().get(2));
                uv.add(surface.uv().get(i*2)); uv.add(surface.uv().get(i*2+1));
            }
            for (int index : FACE_INDICES) indices.add(base + index);
        }

        private MeshBatch build() {
            return new MeshBatch(
                    material.layer(),
                    material.rgb(),
                    material.alpha(),
                    blockCount,
                    positions.toArray(),
                    normals.toArray(),
                    indices.toArray(), uv.toArray(), material.shaded());
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

        private void include(BlockTextureSet.Surface surface,int x,int y,int z) {
            for(int i=0;i<4;i++) {
                double px=x+surface.positions().get(i*3),py=y+surface.positions().get(i*3+1),pz=z+surface.positions().get(i*3+2);
                minX=Math.min(minX,px);minY=Math.min(minY,py);minZ=Math.min(minZ,pz);
                maxX=Math.max(maxX,px);maxY=Math.max(maxY,py);maxZ=Math.max(maxZ,pz);
            }
        }

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

    private record DiagnosticWarningKey(MeshWarning.Code code,BlockResourceDiagnostic diagnostic) { }
    // Render preparation may normalize internal keys; diagnostics must retain each raw saved identity.
    private record DiagnosticStateKey(BlockState original,BlockState rendered) { }

    private static final class WarningAccumulator {
        private final MeshWarning.Code code;
        private final ChunkCoordinate coordinate;
        private final String detail;
        private final BlockResourceDiagnostic diagnostic;
        private int occurrences;

        private WarningAccumulator(
                MeshWarning.Code code,
                ChunkCoordinate coordinate,
                String detail) {this(code,coordinate,detail,null);}
        private WarningAccumulator(MeshWarning.Code code,ChunkCoordinate coordinate,String detail,BlockResourceDiagnostic diagnostic) {
            this.diagnostic=diagnostic;
            this.code = code;
            this.coordinate = coordinate;
            this.detail = detail;
        }

        private void increment() {
            occurrences++;
        }

        private MeshWarning build() {
            return new MeshWarning(code, coordinate, detail, occurrences, java.util.Optional.ofNullable(diagnostic));
        }
    }
}
