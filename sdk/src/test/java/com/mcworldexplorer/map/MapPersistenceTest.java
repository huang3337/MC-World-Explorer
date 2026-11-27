package com.mcworldexplorer.map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MapPersistenceTest {
 @Test void boundedEncodingRejectsBeforeGrowingPastLimit(){var b=new MapPersistence.Bytes(1024);b.write(new byte[1024],0,1024);assertThrows(MapPersistence.LimitExceeded.class,()->b.write(1));assertEquals(1024,b.size());}
 @Test void interruptedAdmissionDoesNotConsumePermit()throws Exception{try(var held=MapPersistence.acquire()){var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();var thread=new Thread(()->{try(var lease=MapPersistence.acquire()){failure.set(new AssertionError("admitted"));}catch(Throwable e){failure.set(e);}});thread.start();thread.interrupt();thread.join(2000);assertFalse(thread.isAlive());assertInstanceOf(java.util.concurrent.CancellationException.class,failure.get());}try(var next=MapPersistence.acquire()){assertNotNull(next);}}
 @Test void imageWriteFailurePreservesImageAndReportsFailure()throws Exception {
  var directory=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("build"),"save-failure-");
  String old=System.getProperty("mcworldexplorer.home");
  try {
   System.setProperty("mcworldexplorer.home",directory.resolve("app").toString());
   var world=new com.mcworldexplorer.world.WorldInfo(java.nio.file.Files.createDirectories(directory.resolve("world")));
   var dim=com.mcworldexplorer.preview.WorldDimension.overworld(world.getFolderPath());
   MapSamplingFixtures.region(dim.regionDirectory().resolve("r.0.0.mca"),"minecraft:stone",0,1,0);
   var root=MapCacheStorage.root(world);java.nio.file.Files.createDirectories(root);java.nio.file.Files.writeString(root.resolve("images"),"not a directory");
   var key=new MapTileKey(com.mcworldexplorer.storage.WorldCachePaths.worldDirectoryName(world),dim.id(),com.mcworldexplorer.preview.PreviewLayer.surfaceOverview(),MapZoomLevel.fromBlocksPerPixel(1),0,0,"failure");
   var result=new MapTileService().load(world,dim,key,MapTileGenerationMonitor.NONE);
   assertNotNull(result.image());assertEquals(MapTileCacheResult.SaveState.IO_FAILED,result.saveState());
  } finally { if(old==null)System.clearProperty("mcworldexplorer.home");else System.setProperty("mcworldexplorer.home",old); }
 }
 @Test void cancelledWaiterLeavesWithoutWaitingForWriter()throws Exception {try(var held=MapPersistence.acquire()){var cancelled=new java.util.concurrent.atomic.AtomicBoolean();var observed=new java.util.concurrent.atomic.AtomicReference<Throwable>();var waiting=new java.util.concurrent.CountDownLatch(1);var thread=new Thread(()->{MapPersistence.begin(cancelled::get);try{waiting.countDown();try(var lease=MapPersistence.acquire()){observed.set(new AssertionError("admitted"));}}catch(Throwable e){observed.set(e);}finally{MapPersistence.end();}});thread.start();assertTrue(waiting.await(1,java.util.concurrent.TimeUnit.SECONDS));cancelled.set(true);thread.join(2000);assertFalse(thread.isAlive());assertInstanceOf(java.util.concurrent.CancellationException.class,observed.get());}}
 @Test void ledgerCountsTransientGrowthAndReturnedCopies() {
  try(var lease=MapPersistence.acquire()) {
   var bytes=new MapPersistence.Bytes(4096);bytes.write(new byte[1024],0,1024);
   long before=lease.usedBytes();bytes.write(1);
   assertTrue(lease.peakBytes()>=before+2048);
   long buffer=lease.usedBytes();byte[] copy=bytes.toByteArray();assertEquals(1025,copy.length);
   assertTrue(lease.usedBytes()>=buffer+copy.length);
   assertThrows(MapPersistence.LimitExceeded.class,()->lease.reserve(MapPersistence.ENCODING_RESERVATION_BYTES));
  }
  try(var lease=MapPersistence.acquire()){assertEquals(MapPersistence.RETAINED_AND_CODEC_GUARD_BYTES,lease.usedBytes());}
 }
 @Test void realServiceShutdownReportsTimeoutAndRejectsLaterLoads()throws Exception {
  var root=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("build"),"exit-test-");
  String old=System.getProperty("mcworldexplorer.home");var release=new java.util.concurrent.CountDownLatch(1);
  var entered=new java.util.concurrent.CountDownLatch(1);var observed=new java.util.concurrent.atomic.AtomicReference<Throwable>();
  try {
   System.setProperty("mcworldexplorer.home",root.resolve("app").toString());
   var world=new com.mcworldexplorer.world.WorldInfo(java.nio.file.Files.createDirectories(root.resolve("world")));
   var dim=com.mcworldexplorer.preview.WorldDimension.overworld(world.getFolderPath());
   MapSamplingFixtures.region(dim.regionDirectory().resolve("r.0.0.mca"),"minecraft:stone",0,1,0);
   var key=new MapTileKey(com.mcworldexplorer.storage.WorldCachePaths.worldDirectoryName(world),dim.id(),com.mcworldexplorer.preview.PreviewLayer.surfaceOverview(),MapZoomLevel.fromBlocksPerPixel(1),0,0,"exit");
   var service=new MapTileService();var worker=new Thread(()->{try{service.load(world,dim,key,new MapTileGenerationMonitor(){public void onProgress(int a,int b){entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}});}catch(Throwable e){observed.set(e);}});
   worker.start();assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
   try { assertEquals(MapTileService.ExitState.TIMED_OUT,service.closeAndAwait(20));
    assertThrows(java.util.concurrent.CancellationException.class,()->service.load(world,dim,key,MapTileGenerationMonitor.NONE));
    assertThrows(java.util.concurrent.CancellationException.class,()->service.forceReload(world));
   } finally {release.countDown();worker.join(5000);}
   assertFalse(worker.isAlive());assertEquals(0,service.activeLoads());assertInstanceOf(java.util.concurrent.CancellationException.class,observed.get());
   assertEquals(MapTileService.ExitState.STOPPED,new MapTileService().closeAndAwait(0));
  } finally {release.countDown();if(old==null)System.clearProperty("mcworldexplorer.home");else System.setProperty("mcworldexplorer.home",old);}
 }
 @Test void refreshWaitingForCommitLockIsIncludedInExit()throws Exception {
  var root=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("build"),"refresh-exit-");
  String oldHome=System.getProperty("mcworldexplorer.home");System.setProperty("mcworldexplorer.home",root.resolve("app").toString());
  var world=new com.mcworldexplorer.world.WorldInfo(root);var service=new MapTileService();
  var observed=new java.util.concurrent.atomic.AtomicReference<Throwable>();
  var thread=new Thread(()->{try{service.forceReload(world);}catch(Throwable e){observed.set(e);}});
  try {
   synchronized(MapCacheStorage.LOCK) {
    thread.start();long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
    while(service.activeLoads()==0&&System.nanoTime()<until)Thread.sleep(1);
    assertEquals(1,service.activeLoads());assertEquals(MapTileService.ExitState.TIMED_OUT,service.closeAndAwait(20));
   }
  } finally {thread.join(5000);if(oldHome==null)System.clearProperty("mcworldexplorer.home");else System.setProperty("mcworldexplorer.home",oldHome);}
  assertFalse(thread.isAlive());assertEquals(0,service.activeLoads());assertInstanceOf(java.util.concurrent.CancellationException.class,observed.get());
 }

 @Test void summaryAndImageShareFailureAndReleaseEveryOwnedByte() throws Exception {
  MapPersistence.begin();
  try {
   MapPersistence.submit(1024, () -> MapPersistence.failed(MapTileCacheResult.SaveState.IO_FAILED));
   MapPersistence.submit(2048, () -> {});
   MapPersistence.await();
   var result=new MapTileCacheResult(null,null,new java.awt.image.BufferedImage(256,256,2),java.util.List.of());
   assertEquals(MapTileCacheResult.SaveState.IO_FAILED,MapPersistence.finish(result).saveState());
   assertEquals(0,MapPersistence.pendingBytes());
  } finally {MapPersistence.end();}
 }
 @Test void sourceFailureOnWriterCannotBecomeSuccessfulDelivery() throws Exception {
  MapPersistence.begin();
  try {
   MapPersistence.submit(1024, () -> {throw new java.io.IOException("source changed");});
   assertEquals("source changed",assertThrows(java.io.IOException.class,MapPersistence::await).getMessage());
  } finally {MapPersistence.end();}
  assertEquals(0,MapPersistence.pendingBytes());
 }
 @Test void runningPayloadConsumesBudgetAndCancelledWaiterOwnsNothing() throws Exception {
  var release=new java.util.concurrent.CountDownLatch(1);
  var started=new java.util.concurrent.CountDownLatch(1);
  var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
  var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
  MapPersistence.begin();
  Thread waiter=new Thread(()->{
   MapPersistence.begin(cancelled::get);
   try {MapPersistence.submit(1024,()->fail("must not be admitted"));}
   catch(Throwable e){failure.set(e);}finally{MapPersistence.end();}
  });
  try {
   MapPersistence.submit(MapPersistence.PRODUCER_BYTES-512,()->{
    started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}
   });
   assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS));
   for(int i=0;i<3;i++)MapPersistence.submit(MapPersistence.PRODUCER_BYTES-512,()->{});
   assertEquals(MapPersistence.PENDING_LIMIT,MapPersistence.pendingBytes());
   waiter.start();cancelled.set(true);waiter.join(2000);
   assertFalse(waiter.isAlive());assertInstanceOf(java.util.concurrent.CancellationException.class,failure.get());
   assertEquals(MapPersistence.PENDING_LIMIT,MapPersistence.pendingBytes());
  } finally {release.countDown();waiter.join(2000);MapPersistence.end();}
  assertEquals(0,MapPersistence.pendingBytes());
 }

 @Test void oversizedProducerIsRejectedBeforeWaiting() throws Exception {
  MapPersistence.begin();try {
   assertThrows(MapPersistence.LimitExceeded.class,()->MapPersistence.submit(MapPersistence.PRODUCER_BYTES,()->fail()));
   assertEquals(0,MapPersistence.pendingBytes());
  }finally{MapPersistence.end();}
 }
 @Test void pngSeekCacheRejectsBeforeGrowingAndAllowsFlushedReuse() throws Exception {
  var target=new java.io.ByteArrayOutputStream();
  try(var output=new MapTileCache.PngOutput(target)) {
   output.write(new byte[65536]);
   assertThrows(MapPersistence.LimitExceeded.class,()->output.write(1));
   output.seek(0);output.write(7);output.seek(65536);output.flushBefore(65536);
   output.write(new byte[65536]);
  }
  assertEquals(131072,target.size());assertEquals(7,target.toByteArray()[0]);
 }

 @Test void sourceContextLimitIsCheckedBeforeGeneration() throws Exception {
  var root=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("build"),"producer-context-");
  String old=System.getProperty("mcworldexplorer.home");
  try {
   System.setProperty("mcworldexplorer.home",root.resolve("app").toString());
   var world=new com.mcworldexplorer.world.WorldInfo(java.nio.file.Files.createDirectories(root.resolve("world")));
   var dim=com.mcworldexplorer.preview.WorldDimension.overworld(world.getFolderPath());
   var key=new MapTileKey(com.mcworldexplorer.storage.WorldCachePaths.worldDirectoryName(world),dim.id(),com.mcworldexplorer.preview.PreviewLayer.surfaceOverview(),MapZoomLevel.fromBlocksPerPixel(1),0,0,"x".repeat(270000));
   var generator=new MapTileGenerator();var service=new MapTileService(new MapTileMemoryCache(),new MapTileCache(),generator);
   assertTrue(assertThrows(java.io.IOException.class,()->service.load(world,dim,key,MapTileGenerationMonitor.NONE)).getMessage().contains("context budget"));
   assertEquals(0,generator.decodeCount());assertEquals(0,service.activeLoads());
  }finally{if(old==null)System.clearProperty("mcworldexplorer.home");else System.setProperty("mcworldexplorer.home",old);}
 }
 @Test void producerAdmissionCanBeCancelledWithoutLeakingSlots() throws Exception {
  var cancelled=new java.util.concurrent.atomic.AtomicBoolean();var observed=new java.util.concurrent.atomic.AtomicReference<Throwable>();
  try(var first=MapPersistence.producer();var second=MapPersistence.producer()) {
   var worker=new Thread(()->{MapPersistence.begin(cancelled::get);try(var permit=MapPersistence.producer()){observed.set(new AssertionError("admitted"));}catch(Throwable e){observed.set(e);}finally{MapPersistence.end();}});
   worker.start();cancelled.set(true);worker.join(2000);assertFalse(worker.isAlive());assertInstanceOf(java.util.concurrent.CancellationException.class,observed.get());
  }
  try(var next=MapPersistence.producer()){assertNotNull(next);}
 }
}
