package com.mcworldexplorer.viewer;

import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.voxel.data.ChunkRectangle;
import com.mcworldexplorer.voxel.mesh.VoxelSceneBuilder;
import com.mcworldexplorer.voxel.resource.TexturedSceneIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TexturedPreviewCoordinatorTest {
    @TempDir Path home;
    @Test void resourceFingerprintInvalidatesCacheAndSimpleModeRemainsAvailable() throws Exception {
        String configured=System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");assumeTrue(configured!=null);
        Path copy=home.resolve("client.jar");Files.copy(Path.of(configured),copy);
        AtomicInteger loads=new AtomicInteger();
        ViewerWindow window=new ViewerWindow(){
            public void show(ViewerWindowContent content,Completion completion){completion.onShown();}
            public void close(){}
        };
        var cache=new ViewerSceneCache(ViewerPerformanceProfile.BASIC);
        try(var coordinator=new VoxelPreviewCoordinator(window,cache,
                (request,cancel,monitor)->{loads.incrementAndGet();return TexturedSceneIntegrationTest.sampleArea();},
                (area,cancel,monitor)->new VoxelSceneBuilder().build(area,cancel,monitor),
                key->new RegionSourceSignature(List.of()),Executors.newSingleThreadExecutor())) {
            coordinator.setResource(copy, "1.21.1");
            var first=start(coordinator);assertFalse(first.fromCache());assertTrue(first.snapshot().atlas().isPresent());
            assertTrue(coordinator.cachedAreas().getFirst().renderVersion().contains("sha256="));
            assertTrue(start(coordinator).fromCache());assertEquals(1,loads.get());
            assertEquals(1,coordinator.validateCachedPreviews().size());
            Files.setLastModifiedTime(copy,FileTime.fromMillis(Files.getLastModifiedTime(copy).toMillis()+10_000));
            assertTrue(coordinator.validateCachedPreviews().isEmpty());
            assertFalse(start(coordinator).fromCache());assertEquals(2,loads.get());
            coordinator.removeCachedArea(coordinator.cachedAreas().getFirst());assertTrue(cache.keys().isEmpty());
            assertFalse(start(coordinator).fromCache());
            coordinator.setResource(null, null);assertTrue(cache.keys().isEmpty());
            var simple=start(coordinator);assertTrue(simple.snapshot().atlas().isEmpty());assertFalse(simple.fromCache());
            coordinator.setResource(home.resolve("missing.jar"), "1.21.1");
            var failure=assertThrows(ExecutionException.class,()->start(coordinator));
            assertTrue(failure.getCause() instanceof com.mcworldexplorer.voxel.resource.ResourceAccessException);
        }
        assertTrue(cache.keys().isEmpty());
    }
    @Test void carriesAnUnverifiedVersionThroughMeshingAndCacheValidation() throws Exception {
        Path jar = home.resolve("client-1.20.1.jar");
        var faces = new java.util.ArrayList<String>();
        for (String face : List.of("down", "up", "north", "south", "west", "east"))
            faces.add("\"" + face + "\":{\"texture\":\"#all\",\"cullface\":\"" + face + "\"}");
        var entries = new java.util.LinkedHashMap<String, byte[]>();
        entries.put("version.json", "{\"id\":\"1.20.1\",\"world_version\":3465,\"pack_version\":{\"resource\":15}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        entries.put("assets/minecraft/blockstates/stone.json", "{\"variants\":{\"\":{\"model\":\"block/stone\"}}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String model = "{\"textures\":{\"all\":\"block/stone\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{" + String.join(",", faces) + "}}]}";
        entries.put("assets/minecraft/models/block/stone.json", model.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png);
        entries.put("assets/minecraft/textures/block/stone.png", png.toByteArray());
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        ViewerWindow window = new ViewerWindow() {
            public void show(ViewerWindowContent content, Completion completion) { completion.onShown(); }
            public void close() { }
        };
        var cache = new ViewerSceneCache(ViewerPerformanceProfile.BASIC);
        try (var coordinator = new VoxelPreviewCoordinator(window, cache,
                (request, cancel, monitor) -> TexturedSceneIntegrationTest.sampleArea(),
                (area, cancel, monitor) -> new VoxelSceneBuilder().build(area, cancel, monitor),
                key -> new RegionSourceSignature(List.of()), Executors.newSingleThreadExecutor())) {
            coordinator.setResource(jar, "1.20.1");
            var rendered = start(coordinator);
            assertTrue(rendered.snapshot().atlas().isPresent());
            assertTrue(rendered.snapshot().chunks().stream().flatMap(chunk -> chunk.batches().stream())
                    .anyMatch(com.mcworldexplorer.voxel.mesh.MeshBatch::textured));
            assertTrue(start(coordinator).fromCache());
            assertEquals(1, coordinator.validateCachedPreviews().size());
            coordinator.setResource(jar, "1.21.1");
            assertTrue(coordinator.cachedAreas().isEmpty(), "同一路径的预期版本改变也须清空缓存");
            var failure = assertThrows(ExecutionException.class, () -> start(coordinator));
            assertEquals(com.mcworldexplorer.voxel.resource.ResourceAccessException.Reason.VERSION_MISMATCH,
                    ((com.mcworldexplorer.voxel.resource.ResourceAccessException) failure.getCause()).reason());
        }
    }

    @Test void autoInstancesKeepIndependentCacheIdentitiesAndDetectModChanges()throws Exception {
        String configured=System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");assumeTrue(configured!=null);
        Path a=Files.createDirectories(home.resolve("a/saves/world")),b=Files.createDirectories(home.resolve("b/saves/world"));
        ViewerWindow window=new ViewerWindow(){public void show(ViewerWindowContent content,Completion completion){completion.onShown();}public void close(){}};
        var cache=new ViewerSceneCache(ViewerPerformanceProfile.BASIC);
        try(var coordinator=new VoxelPreviewCoordinator(window,cache,(request,cancel,monitor)->TexturedSceneIntegrationTest.sampleArea(),
            (area,cancel,monitor)->new VoxelSceneBuilder().build(area,cancel,monitor),key->new RegionSourceSignature(List.of()),Executors.newSingleThreadExecutor())) {
            coordinator.setResource(Path.of(configured),"1.21.1");
            var first=start(coordinator,a);assertFalse(first.fromCache());
            assertTrue(first.snapshot().warnings().stream().anyMatch(w->w.code()==com.mcworldexplorer.voxel.mesh.MeshWarning.Code.RESOURCE_CONTEXT&&w.detail().contains("a")));
            assertFalse(start(coordinator,b).fromCache());assertEquals(2,coordinator.validateCachedPreviews().size());assertTrue(start(coordinator,a).fromCache());
            Path mod=home.resolve("a/mods/added.jar");Files.createDirectories(mod.getParent());
            try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(mod))){zip.putNextEntry(new java.util.zip.ZipEntry("assets/example/models/probe.json"));zip.write("{}".getBytes());zip.closeEntry();}
            assertEquals(1,coordinator.validateCachedPreviews().size());assertEquals(b,coordinator.cachedAreas().getFirst().world());
            assertFalse(start(coordinator,a).fromCache());assertTrue(start(coordinator,b).fromCache());
            coordinator.setResource(Path.of(configured),"1.21.1",home.resolve("a"));assertTrue(coordinator.cachedAreas().isEmpty());
        }
    }

    private ViewerTaskResult start(VoxelPreviewCoordinator coordinator)throws Exception {return start(coordinator,home);}
    private ViewerTaskResult start(VoxelPreviewCoordinator coordinator,Path world)throws Exception {
        var result=new CompletableFuture<ViewerTaskResult>();
        coordinator.start(new ViewerRequest(world,"test","minecraft:overworld","主世界",ChunkRectangle.target(0,0,0,0),
                ViewerPerformanceProfile.BASIC,new ViewerMapReturnState(0,0,2,PreviewLayer.surfaceOverview())),new ViewerTaskListener(){
            public void onProgress(long id,ViewerProgress p){}
            public void onSucceeded(long id,ViewerTaskResult r){result.complete(r);}
            public void onFailed(long id,Throwable f){result.completeExceptionally(f);}
            public void onCancelled(long id){result.cancel(false);}
        });
        return result.get(15,TimeUnit.SECONDS);
    }
}
