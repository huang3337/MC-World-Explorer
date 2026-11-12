package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.*;
import com.mcworldexplorer.storage.WorldCachePaths;
import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class MapCenterPreviewTest {
    static { org.slf4j.LoggerFactory.getLogger(MapCenterPreviewTest.class); }
    @TempDir Path temp;
    String oldHome;
    @org.junit.jupiter.api.BeforeEach void isolate() {
        oldHome=System.getProperty("mcworldexplorer.home");
        System.setProperty("mcworldexplorer.home",temp.resolve("app").toString());
    }
    @org.junit.jupiter.api.AfterEach void restore() {
        if(oldHome==null)System.clearProperty("mcworldexplorer.home");else System.setProperty("mcworldexplorer.home",oldHome);
    }
    private MapTileKey key(WorldInfo world, int x, int z) {
        return new MapTileKey(WorldCachePaths.worldDirectoryName(world), "minecraft:overworld",
                PreviewLayer.surfaceOverview(), MapZoomLevel.BLOCKS_1, x, z, "center-test");
    }
    private MapTileGenerationMonitor monitor(List<MapTilePartialResult> frames) {
        return new MapTileGenerationMonitor() {
            public boolean centerFirst() { return true; }
            public double focusX() { return 256; }
            public double focusZ() { return 256; }
            public void onPartial(MapTilePartialResult result) { frames.add(result); }
        };
    }
    @Test void centerCrossesFourTilesAndPreservesNegativeCoordinates() {
        var w = new WorldInfo(temp);
        for (int z=0; z<2; z++) for (int x=0; x<2; x++)
            assertTrue(MapTileGenerator.intersectsCenter(key(w,x,z),256,256));
        assertFalse(MapTileGenerator.intersectsCenter(key(w,2,0),256,256));
        assertTrue(MapTileGenerator.intersectsCenter(key(w,-1,-1),-1,-1));
        assertFalse(MapTileGenerator.intersectsCenter(key(w,0,0),Double.NaN,0));
    }
    @Test void previewIsPartialNotCompleteCacheAndFinalPixelsRemainEqual() throws Exception {
        var w = new WorldInfo(temp.resolve("world")); var d = WorldDimension.overworld(w.getFolderPath());
        int[] slots = java.util.stream.IntStream.range(0,1024).toArray();
        MapSamplingFixtures.region(d.regionDirectory().resolve("r.0.0.mca"),"minecraft:stone",3,1,slots);
        var key=key(w,0,0);var frames=new ArrayList<MapTilePartialResult>();var service=new MapTileService();
        try {
            assertNull(service.previewCenter(w,d,key,monitor(frames)));
            assertFalse(frames.isEmpty());
            var center=frames.getLast().image();
            assertEquals(0,center.getRGB(0,0)>>>24);
            assertEquals(255,center.getRGB(240,240)>>>24);
            Files.createDirectories(MapCacheStorage.root(w));
            try(var files=Files.walk(MapCacheStorage.root(w))) {
                assertFalse(files.anyMatch(p->p.toString().endsWith(".png")));
            }
            frames.clear();var full=service.load(w,d,key,monitor(frames));
            for(var frame:frames) assertEquals(255,frame.image().getRGB(240,240)>>>24,"center erased");
            var reference=new MapTileGenerator().generate(w,d,key,MapTileGenerationMonitor.NONE);
            assertArrayEquals(reference.image().getRGB(0,0,256,256,null,0,256),full.image().getRGB(0,0,256,256,null,0,256));
            frames.clear();assertNotNull(service.previewCenter(w,d,key,monitor(frames)));
            assertTrue(frames.isEmpty(),"cached complete image should not wait for preview");
        } finally { assertEquals(MapTileService.ExitState.STOPPED,service.closeAndAwait(2000)); }
    }
    @Test void previewCancellationAndInvalidSourcesDoNotBecomeSuccess() throws Exception {
        var w=new WorldInfo(temp.resolve("bad"));var d=WorldDimension.overworld(w.getFolderPath());
        Files.createDirectories(d.regionDirectory());Files.write(d.regionDirectory().resolve("r.0.0.mca"),new byte[99]);
        var service=new MapTileService();
        assertNull(service.previewCenter(w,d,key(w,0,0),monitor(new ArrayList<>())));
        assertThrows(MapTileIncompleteException.class,()->service.load(w,d,key(w,0,0),monitor(new ArrayList<>())));
        assertThrows(CancellationException.class,()->service.previewCenter(w,d,key(w,0,0),new MapTileGenerationMonitor(){
            public boolean centerFirst(){return true;}public double focusX(){return 256;}public double focusZ(){return 256;}
            public boolean isCancelled(){return true;}
        }));
        assertEquals(MapTileService.ExitState.STOPPED,service.closeAndAwait(1000));
        assertEquals(0,service.activeLoads());
    }
    @Test void inFlightCancellationAndSourceChangeRejectCenterCompletion() throws Exception {
        var w=new WorldInfo(temp.resolve("changing"));var d=WorldDimension.overworld(w.getFolderPath());
        Path region=d.regionDirectory().resolve("r.0.0.mca");int[] slots=java.util.stream.IntStream.range(0,1024).toArray();
        MapSamplingFixtures.region(region,"minecraft:stone",3,1,slots);
        var cancelled=new AtomicBoolean();var service=new MapTileService();
        assertThrows(CancellationException.class,()->service.previewCenter(w,d,key(w,0,0),new MapTileGenerationMonitor(){
            public boolean centerFirst(){return true;}public double focusX(){return 256;}public double focusZ(){return 256;}
            public boolean isCancelled(){return cancelled.get();}public void onPartial(MapTilePartialResult p){cancelled.set(true);}
        }));
        assertEquals(0,service.activeLoads());service.clearMemory();var changed=new AtomicBoolean();
        assertThrows(java.io.IOException.class,()->service.previewCenter(w,d,key(w,0,0),new MapTileGenerationMonitor(){
            public boolean centerFirst(){return true;}public double focusX(){return 256;}public double focusZ(){return 256;}
            public void onPartial(MapTilePartialResult p){if(changed.compareAndSet(false,true))try{
                MapSamplingFixtures.region(region,"minecraft:gold_block",3,2,slots);
            }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
        }));
        assertTrue(changed.get());assertEquals(0,service.activeLoads());
        assertEquals(MapTileService.ExitState.STOPPED,service.closeAndAwait(1000));
    }
    @Test void centerTasksYieldWorkersAndCachedHitsBypassFullWork() throws Exception {
        var w=new WorldInfo(temp);var gate=new CountDownLatch(1);var done=new CountDownLatch(4);
        var starts=new AtomicInteger();var firstFull=new AtomicInteger(99);var errors=new ConcurrentLinkedQueue<Throwable>();
        try(var scheduler=new MapTileScheduler()) {
            var keys=new HashSet<MapTileKey>();for(int i=0;i<4;i++)keys.add(key(w,i,0));
            long request=scheduler.beginRequest(keys);
            for(var k:keys)scheduler.submitProgressive(k,0,request,c->{gate.await();starts.incrementAndGet();return null;},
                    c->{firstFull.accumulateAndGet(starts.get(),Math::min);return null;},r->done.countDown(),e->{errors.add(e);done.countDown();});
            gate.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));assertTrue(errors.isEmpty());
            assertTrue(firstFull.get()>=3);assertEquals(4,starts.get()); // 不要求等另一worker的预览结束。
            long second=scheduler.beginRequest(Set.of(key(w,0,0)));var hit=new CountDownLatch(1);var fullCalls=new AtomicInteger();
            scheduler.submitProgressive(key(w,0,0),0,second,c->new MapTileCacheResult(null,null,new java.awt.image.BufferedImage(256,256,2),List.of()),
                    c->{fullCalls.incrementAndGet();return null;},r->hit.countDown(),e->errors.add(e));
            assertTrue(hit.await(2,TimeUnit.SECONDS));assertEquals(0,fullCalls.get());assertTrue(errors.isEmpty());
        }
    }
    @Test void cancelledPreviewNeverSchedulesFullStage() throws Exception {
        var w=new WorldInfo(temp);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var exited=new CountDownLatch(1);var full=new AtomicInteger();
        try(var scheduler=new MapTileScheduler()) {
            var k=key(w,0,0);long request=scheduler.beginRequest(Set.of(k));
            scheduler.submitProgressive(k,0,request,c->{entered.countDown();release.await();exited.countDown();return null;},
                    c->{full.incrementAndGet();return null;},r->fail("stale success"),e->fail("stale failure"));
            assertTrue(entered.await(2,TimeUnit.SECONDS));scheduler.beginRequest(Set.of());release.countDown();
            assertTrue(exited.await(2,TimeUnit.SECONDS));assertEquals(0,full.get());assertEquals(0,scheduler.pendingCount());
        }
    }
}
