package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.io.*;
import com.mcworldexplorer.voxel.mesh.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.Direction.*;

public class TexturedSceneIntegrationTest {
    @Test void buildsRealWorldWithTexturesAndKeepsExistingGeometryCounts() throws Exception {
        String world=System.getenv("MCWORLD_UI_TEST_WORLD"), jar=System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");
        assumeTrue(world!=null && jar!=null);
        try(var archive=ClientResourceArchive.open(Path.of(jar),"1.21.1")) {
            var area=new VoxelAreaLoader().load(new VoxelAreaRequest(Path.of(world),"minecraft:overworld",
                    ChunkRectangle.centered(0,0,3,3)),CancellationSignal.NONE);
            long start=System.nanoTime();
            var textures=BlockTextureSet.load(archive,area,CancellationSignal.NONE);
            long textureNanos=System.nanoTime()-start;
            var simple=new VoxelSceneBuilder().build(area,CancellationSignal.NONE).snapshot().orElseThrow();
            start=System.nanoTime();
            var scene=new VoxelSceneBuilder(textures).build(area,CancellationSignal.NONE).snapshot().orElseThrow();
            assertEquals(simple.blockCount(),scene.blockCount());assertTrue(scene.faceCount()>0);
            assertTrue(scene.chunks().stream().flatMap(c->c.batches().stream()).anyMatch(MeshBatch::textured));
            long texturedFaces=scene.chunks().stream().flatMap(c->c.batches().stream()).filter(MeshBatch::textured).mapToLong(MeshBatch::faceCount).sum();
            System.out.printf("Real 3x3 texture sample: blocks=%d faces=%d texturedFaces=%d atlas=%dx%d/%d bytes paletteMs=%.1f meshMs=%.1f%n",
                    scene.blockCount(),scene.faceCount(),texturedFaces,textures.atlas().width(),textures.atlas().height(),
                    textures.atlas().estimatedBytes(),textureNanos/1e6,(System.nanoTime()-start)/1e6);
        }
    }

    @Test void realPaletteRotatesEndsBuildsUvAndPreservesFallbackAndSource() throws Exception {
        String path = System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");
        assumeTrue(path != null && !path.isBlank());
        Path jar = Path.of(path); String before = stamp(jar);
        try (var archive = ClientResourceArchive.open(jar,"1.21.1")) {
            var textures = BlockTextureSet.load(archive,regressionStates(),CancellationSignal.NONE);
            for (var state : regressionStates()) {
                assertNotNull(textures.surfaces(state), state + ": " + textures.fallbackReason(state));
                assertEquals(6,textures.surfaces(state).size());
            }
            var y = byDirection(textures.surfaces(new BlockState("minecraft:oak_log",Map.of("axis","y"))));
            var x = byDirection(textures.surfaces(new BlockState("minecraft:oak_log",Map.of("axis","x"))));
            var z = byDirection(textures.surfaces(new BlockState("minecraft:oak_log",Map.of("axis","z"))));
            assertSameTile(y.get(UP),x.get(EAST)); assertSameTile(y.get(UP),x.get(WEST));
            assertSameTile(y.get(UP),z.get(NORTH)); assertSameTile(y.get(UP),z.get(SOUTH));
            assertNotEquals(uvBounds(y.get(UP)),uvBounds(y.get(NORTH)));
            var grass = byDirection(textures.surfaces(new BlockState("minecraft:grass_block",Map.of("snowy","false"))));
            assertNotEquals(uvBounds(grass.get(UP)),uvBounds(grass.get(DOWN)));
            assertNotEquals(uvBounds(grass.get(NORTH)),uvBounds(grass.get(DOWN)));
            assertNull(textures.surfaces(new BlockState("minecraft:stone_slab",Map.of())));
            var scene = new VoxelSceneBuilder(textures).build(sampleArea(),CancellationSignal.NONE).snapshot().orElseThrow();
            assertTrue(scene.atlas().isPresent());
            assertEquals(12,scene.blockCount()); assertEquals(72,scene.faceCount());
            assertEquals(66,scene.chunks().getFirst().batches().stream().filter(MeshBatch::textured).mapToInt(MeshBatch::faceCount).sum());
            assertEquals(1,scene.warnings().stream().filter(w -> w.code()==MeshWarning.Code.TEXTURE_FALLBACK).mapToInt(MeshWarning::occurrences).sum());
            assertThrows(java.util.concurrent.CancellationException.class, () -> BlockTextureSet.load(archive,regressionStates(),()->true));
        }
        assertEquals(before,stamp(jar));
    }
    private static Map<BlockModelResolver.Direction,BlockTextureSet.Surface> byDirection(List<BlockTextureSet.Surface> faces) {
        return faces.stream().collect(java.util.stream.Collectors.toMap(BlockTextureSet.Surface::direction,f->f));
    }
    private static List<Float> uvBounds(BlockTextureSet.Surface face) {
        List<Float> u=new ArrayList<>(),v=new ArrayList<>();
        for(int i=0;i<4;i++){u.add(face.uv().get(i*2));v.add(face.uv().get(i*2+1));}
        return List.of(Collections.min(u),Collections.max(u),Collections.min(v),Collections.max(v));
    }
    private static void assertSameTile(BlockTextureSet.Surface a,BlockTextureSet.Surface b){assertEquals(uvBounds(a),uvBounds(b));}
    private static String stamp(Path p) throws Exception { return Files.size(p)+":"+Files.getLastModifiedTime(p)+":"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))); }

    public static List<BlockState> regressionStates() {
        List<BlockState> states = new ArrayList<>();
        for (String name : List.of("stone", "cobblestone", "dirt", "oak_planks", "bricks", "sand"))
            states.add(new BlockState("minecraft:" + name, Map.of()));
        for (String snowy : List.of("false", "true")) states.add(new BlockState("minecraft:grass_block", Map.of("snowy", snowy)));
        for (String axis : List.of("x", "y", "z")) states.add(new BlockState("minecraft:oak_log", Map.of("axis", axis)));
        return List.copyOf(states);
    }

    /** Twelve isolated blocks with all supported states plus one unsupported slab. */
    public static VoxelAreaLoadResult sampleArea() throws Exception {
        List<BlockState> palette = new ArrayList<>();palette.add(BlockState.AIR);
        palette.addAll(regressionStates()); palette.add(new BlockState("minecraft:stone_slab",Map.of("type","bottom","waterlogged","false")));
        long[] data = new long[256];
        for(int i=1;i<palette.size();i++){
            int x=1+((i-1)%4)*3,z=1+((i-1)/4)*3,index=256+z*16+x;
            data[index/16] |= (long)i << ((index%16)*4);
        }
        var target=ChunkRectangle.target(0,0,0,0);var coordinate=new ChunkCoordinate(0,0);
        var chunk=new VoxelChunk(0,0,List.of(new ChunkSection(0,palette,data)));
        Map<ChunkCoordinate,ChunkLoadResult> results = new LinkedHashMap<>();
        for(var c:target.expanded(1).coordinates())results.put(c,ChunkLoadResult.withoutChunk(c,ChunkLoadStatus.MISSING,"missing",0,0));
        results.put(coordinate,ChunkLoadResult.success(coordinate,chunk,0,0));
        Path world=Path.of(".").toAbsolutePath().normalize();
        return new VoxelAreaLoadResult(world,WorldDimension.overworld(world),target,results);
    }
}
