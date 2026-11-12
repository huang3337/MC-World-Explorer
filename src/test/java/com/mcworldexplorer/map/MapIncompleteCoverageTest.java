package com.mcworldexplorer.map;

import com.mcworldexplorer.preview.*;
import com.mcworldexplorer.storage.WorldCachePaths;
import com.mcworldexplorer.world.WorldInfo;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class MapIncompleteCoverageTest {
    static { org.slf4j.LoggerFactory.getLogger(MapIncompleteCoverageTest.class); }
    @TempDir Path temp;
    private String previousHome;
    private WorldInfo world;
    private WorldDimension dimension;
    @BeforeEach void isolate() throws Exception {
        previousHome=System.getProperty("mcworldexplorer.home");
        System.setProperty("mcworldexplorer.home",temp.resolve("app").toString());
        world=new WorldInfo(temp.resolve("world"));dimension=WorldDimension.overworld(world.getFolderPath());
        MapSamplingFixtures.region(region("r.0.0.mca"),"minecraft:stone",3,1,0);
    }
    @AfterEach void restore() {
        if(previousHome==null)System.clearProperty("mcworldexplorer.home");
        else System.setProperty("mcworldexplorer.home",previousHome);
    }
    private Path region(String name){return dimension.regionDirectory().resolve(name);}
    private MapTileKey key(int zoom){return new MapTileKey(WorldCachePaths.worldDirectoryName(world),dimension.id(),
            PreviewLayer.surfaceOverview(),MapZoomLevel.fromBlocksPerPixel(zoom),0,0,"incomplete-test");}
    private MapTileIncompleteException partial(MapTileService service,int zoom){
        return assertThrows(MapTileIncompleteException.class,()->service.load(world,dimension,key(zoom),MapTileGenerationMonitor.NONE));
    }
    @Test void zoomDuringGenerationContinuesNeededTerrainAndDiscardsOldImage() throws Exception {
        MapSamplingFixtures.region(region("r.0.0.mca"),"minecraft:stone",3,1,0,1,32,33);
        var generator=new MapTileGenerator();
        var service=new MapTileService(new MapTileMemoryCache(),new MapTileCache(),generator);
        var scheduler=new MapTileScheduler();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(1);
        var obsoleteCallbacks=new AtomicInteger();var failures=new AtomicInteger();
        var old=key(1);var next=key(2);long request=scheduler.beginRequest(Set.of(old));
        try {
            scheduler.submitTerrain(old,0,request,null,c->service.load(world,dimension,old,new MapTileGenerationMonitor(){
                public boolean isCancelled(){return c.isCancelled();}
                public boolean isObsolete(){return c.isObsolete();}
                public boolean needsChunk(int x,int z){return c.needsChunk(x,z);}
                public void onProgress(int complete,int total){if(complete==253){entered.countDown();try{
                    if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("release timeout");
                    assertFalse(c.isCancelled(), "overlapping terrain must continue");
                    assertTrue(c.isObsolete(), "old raster must not be delivered");
                }catch(InterruptedException e){throw new RuntimeException(e);}}}
            }),r->obsoleteCallbacks.incrementAndGet(),e->obsoleteCallbacks.incrementAndGet());
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            long nextRequest=scheduler.beginRequest(Set.of(next));
            scheduler.submitTerrain(next,0,nextRequest,null,c->service.load(world,dimension,next,new MapTileGenerationMonitor(){
                public boolean isCancelled(){return c.isCancelled();}
                public boolean isObsolete(){return c.isObsolete();}
                public boolean needsChunk(int x,int z){return c.needsChunk(x,z);}
            }),r->done.countDown(),e->{failures.incrementAndGet();done.countDown();});
            release.countDown();assertTrue(done.await(10,TimeUnit.SECONDS));
            scheduler.close();assertEquals(MapTileService.ExitState.STOPPED,service.closeAndAwait(2000));
            assertEquals(0,failures.get());assertEquals(0,obsoleteCallbacks.get());
            assertEquals(4,generator.decodeCount());
            assertTrue(new MapTileCache().findReusable(world,dimension,old).isEmpty());
            assertTrue(new MapTileCache().findReusable(world,dimension,next).isPresent());
        } finally {release.countDown();scheduler.close();service.closeAndAwait(2000);}
    }

    @Test void centerAndMemoryOnlySummariesPersistAcrossGeneratorReplacement() throws Exception {
        MapSamplingFixtures.region(region("r.0.0.mca"),"minecraft:stone",3,1,0,1,32,33);
        var generator = new MapTileGenerator();
        var service = new MapTileService(new MapTileMemoryCache(), new MapTileCache(), generator);
        try {
            service.previewCenter(world, dimension, key(1), new MapTileGenerationMonitor() {
                public boolean centerFirst() { return true; }
                public double focusX() { return 16; }
                public double focusZ() { return 16; }
            });
            assertEquals(4, generator.decodeCount());
            var fresh = new MapTileGenerator();
            fresh.generate(world, dimension, key(2), MapTileGenerationMonitor.NONE, true);
            assertEquals(0, fresh.decodeCount(), "center results must survive an empty memory cache");
            service.forceReload(world);
            long initial = generator.decodeCount();
            var cancelled = new AtomicBoolean();
            assertThrows(CancellationException.class, () -> service.load(world, dimension, key(1),
                    new MapTileGenerationMonitor() {
                        public boolean isCancelled() { return cancelled.get(); }
                        public void onProgress(int complete, int total) {
                            if (generator.decodeCount() == initial + 1) cancelled.set(true);
                        }
                    }));
            long before = generator.decodeCount();
            service.load(world, dimension, key(2), MapTileGenerationMonitor.NONE);
            assertEquals(before + 3, generator.decodeCount());
            fresh = new MapTileGenerator();
            fresh.generate(world, dimension, key(4), MapTileGenerationMonitor.NONE, true);
            assertEquals(0, fresh.decodeCount(), "memory-only hits must be eligible for persistence");
        } finally { service.closeAndAwait(2000); }
    }

    @Test void obsoleteRasterDrainsAdmittedSummariesWithoutSavingItsImage() throws Exception {
        var generator = new MapTileGenerator();
        var service = new MapTileService(new MapTileMemoryCache(), new MapTileCache(), generator);
        try {
            assertThrows(CancellationException.class, () -> service.load(world, dimension, key(1),
                    new MapTileGenerationMonitor() { public boolean isObsolete() { return true; } }));
            assertTrue(new MapTileCache().findReusable(world, dimension, key(1)).isEmpty());
            var fresh = new MapTileGenerator();
            fresh.generate(world, dimension, key(2), MapTileGenerationMonitor.NONE, true);
            assertEquals(0, fresh.decodeCount());
        } finally { service.closeAndAwait(2000); }
    }

    @Test void emptyPlaceholdersAreBlankAndBecomeTerrainAfterSourceChange() throws Exception {
        Files.write(region("r.1.0.mca"),new byte[0]);
        var service=new MapTileService();
        try {
            var result=service.load(world,dimension,key(4),MapTileGenerationMonitor.NONE);
            assertEquals(MapTileCacheResult.SaveState.SAVED,result.saveState());
            assertTrue(result.sources().stream().noneMatch(MapTileSourceState::unreadable));
            MapSamplingFixtures.region(region("r.1.0.mca"),"minecraft:gold_block",3,1,0);
            var changed=service.load(world,dimension,key(4),MapTileGenerationMonitor.NONE);
            assertNotEquals(result.image().getRGB(129,1),changed.image().getRGB(129,1));
        } finally {assertEquals(MapTileService.ExitState.STOPPED,service.closeAndAwait(2000));}
    }
    @Test void zoomOutPreservesReadablePixelsAndMarksOnlyUnreadableCoverage() throws Exception {
        var service=new MapTileService();
        try {
            service.load(world,dimension,key(1),MapTileGenerationMonitor.NONE);
            var expected=new MapTileGenerator().generate(world,dimension,key(4),MapTileGenerationMonitor.NONE);
            Files.write(region("r.1.0.mca"),new byte[99]);
            var result=partial(service,4).partial();
            assertEquals(expected.image().getRGB(2,2),result.image().getRGB(2,2));
            assertEquals(255,result.image().getRGB(2,2)>>>24);
            assertFalse(result.unreadable().containsPixel(2,2));
            assertTrue(result.unreadable().containsPixel(128,10));
            assertFalse(result.unreadable().containsPixel(128,200));
            assertEquals(0xffdf5961,result.image().getRGB(130,10));
            assertTrue(result.unreadable().description().contains("r.1.0.mca"));
            assertEquals(1024,result.failedChunks());
            assertTrue(new MapTileCache().findReusable(world,dimension,key(4)).isEmpty());
            assertThrows(MapTileIncompleteException.class,()->new MapTileCache().store(world,dimension,key(4),result));
            Files.write(region("r.1.0.mca"),new byte[8192]);
            var fixed=service.load(world,dimension,key(4),MapTileGenerationMonitor.NONE);
            assertEquals(MapTileCacheResult.SaveState.SAVED,fixed.saveState());
            assertEquals(expected.image().getRGB(130,10),fixed.image().getRGB(130,10));
        } finally {assertEquals(MapTileService.ExitState.STOPPED,service.closeAndAwait(2000));}
    }
    @Test void truncatedHeaderAndBadBodyRemainPartialNotEmptyOrCached() throws Exception {
        var service=new MapTileService();
        try {
            Files.write(region("r.1.0.mca"),new byte[99]);
            assertTrue(partial(service,4).getMessage().contains("Truncated"));
            MapSamplingFixtures.region(region("r.1.0.mca"),"minecraft:stone",3,1,0);
            byte[] bad=Files.readAllBytes(region("r.1.0.mca"));bad[8196]=99;Files.write(region("r.1.0.mca"),bad);
            var result=partial(service,4).partial();
            assertEquals(1,result.failedChunks());assertTrue(result.unreadable().containsPixel(128,0));
            assertFalse(result.unreadable().containsPixel(132,0));
            assertEquals(255,result.image().getRGB(2,2)>>>24);
        } finally {service.closeAndAwait(2000);}
    }
    @Test void brokenShadingNeighbourDoesNotEraseTheWholeTile() throws Exception {
        Files.write(region("r.-1.0.mca"),new byte[99]);var service=new MapTileService();
        try {
            var result=partial(service,1).partial();
            assertTrue(result.unreadable().containsPixel(0,10));
            assertFalse(result.unreadable().containsPixel(1,10));
            assertEquals(255,result.image().getRGB(10,10)>>>24);
            assertEquals(0,result.failedChunks(),"only the dependency is unreadable");
        } finally {service.closeAndAwait(2000);}
    }
    @Test void schedulerAndDisplayQueueDeliverTerminalPartialOnceWithoutRetryOrSuccess() throws Exception {
        Files.write(region("r.1.0.mca"),new byte[99]);
        var service=new MapTileService();var scheduler=new MapTileScheduler();var queue=new MapDisplayQueue();
        var key=key(4);var keys=Set.of(key);long request=scheduler.beginRequest(keys);queue.retain(1,keys);
        var tracker=new MapTileLoadTracker();tracker.ensureAutomaticLoading(key);
        var done=new CountDownLatch(1);var errors=new AtomicInteger();var successes=new AtomicInteger();
        var fullCalls=new AtomicInteger();var deliveries=new AtomicInteger();
        var monitor=new MapTileGenerationMonitor(){
            public boolean centerFirst(){return true;}public double focusX(){return 512;}public double focusZ(){return 256;}
            public void onPartial(MapTilePartialResult partial){queue.offer(1,key,MapDisplayQueue.Frame.prepare(partial.image(),partial.markers()),false,f->{});}
        };
        try {
            scheduler.submitProgressive(key,0,request,c->service.previewCenter(world,dimension,key,monitor),
                c->{fullCalls.incrementAndGet();return service.load(world,dimension,key,monitor);},
                r->{successes.incrementAndGet();done.countDown();},e->{
                    if(e instanceof MapTileIncompleteException incomplete){
                        var p=incomplete.partial();var frame=new MapDisplayQueue.Frame(p.image().getRGB(0,0,256,256,null,0,256),p.markers(),e.getMessage());
                        queue.offer(1,key,frame,true,f->{tracker.recordIncomplete(key);deliveries.incrementAndGet();});
                    }else errors.incrementAndGet();done.countDown();
                });
            assertTrue(done.await(10,TimeUnit.SECONDS));assertEquals(0,errors.get());assertEquals(0,successes.get());
            var delivery=queue.poll();assertNotNull(delivery);assertTrue(delivery.terminal());assertNotNull(delivery.frame());
            assertTrue(delivery.frame().diagnostic().contains("r.1.0.mca"));delivery.apply();
            assertEquals(1,deliveries.get());assertEquals(1,fullCalls.get());assertTrue(tracker.isFailed(key));
            tracker.ensureAutomaticLoading(key);assertFalse(tracker.isLoading(key));assertTrue(tracker.beginManualRetry(key));
            assertTrue(new MapTileCache().findReusable(world,dimension,key).isEmpty());
        } finally {queue.close();scheduler.close();assertEquals(MapTileService.ExitState.STOPPED,service.closeAndAwait(2000));}
    }
    @Test void sourceRepairDuringGenerationRejectsTheOldPartialAndRefreshRecovers() throws Exception {
        Path damaged=region("r.1.0.mca");Files.write(damaged,new byte[99]);var service=new MapTileService();
        var changed=new AtomicBoolean();
        try {
            var failure=assertThrows(java.io.IOException.class,()->service.load(world,dimension,key(4),new MapTileGenerationMonitor(){
                public void onPartial(MapTilePartialResult partial){if(changed.compareAndSet(false,true))try{
                    Files.write(damaged,new byte[8192]);
                }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
            }));
            assertFalse(failure instanceof MapTileIncompleteException);assertTrue(failure.getMessage().contains("Source changed"));
            service.forceReload(world);assertEquals(MapTileCacheResult.SaveState.SAVED,service.load(world,dimension,key(4),MapTileGenerationMonitor.NONE).saveState());
        } finally {service.closeAndAwait(2000);}
    }
    @Test void cancellationAndClosedServiceDoNotBecomePartialSuccess() throws Exception {
        Files.write(region("r.1.0.mca"),new byte[99]);var service=new MapTileService();var cancel=new AtomicBoolean();
        assertThrows(CancellationException.class,()->service.load(world,dimension,key(4),new MapTileGenerationMonitor(){
            public boolean isCancelled(){return cancel.get();}
            public void onPartial(MapTilePartialResult p){cancel.set(true);}
        }));
        assertEquals(0,service.activeLoads());assertEquals(MapTileService.ExitState.STOPPED,service.closeAndAwait(2000));
        assertThrows(CancellationException.class,()->service.load(world,dimension,key(4),MapTileGenerationMonitor.NONE));
    }
}
