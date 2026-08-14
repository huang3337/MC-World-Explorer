package com.mcworldexplorer.viewer;

import com.mcworldexplorer.preview.PreviewLayer;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.data.ChunkRectangle;
import com.mcworldexplorer.voxel.io.ChunkLoadResult;
import com.mcworldexplorer.voxel.io.ChunkLoadStatus;
import com.mcworldexplorer.voxel.io.VoxelAreaLoadResult;
import com.mcworldexplorer.voxel.mesh.MeshBounds;
import com.mcworldexplorer.voxel.mesh.VoxelSceneResult;
import com.mcworldexplorer.voxel.mesh.VoxelSceneSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoxelPreviewCoordinatorTest {
    private static final RegionSourceSignature SIGNATURE = new RegionSourceSignature(List.of());
    private static final VoxelSceneSnapshot SCENE = new VoxelSceneSnapshot(
            0, 0, List.of(), new MeshBounds(0, 0, 0, 0, 0, 0),
            0, 0, 0, 0, List.of());

    private VoxelPreviewCoordinator coordinator;

    @AfterEach
    void closeCoordinator() {
        if (coordinator != null) {
            coordinator.close();
        }
    }

    @Test
    void completesCurrentRequestAndReportsRealStages() throws Exception {
        FakeWindow window = new FakeWindow();
        RecordingListener listener = new RecordingListener();
        coordinator = coordinator(window, (request, cancellation, monitor) -> area(request.target()));

        coordinator.start(request(0), listener);

        ViewerTaskResult result = listener.success.get(3, TimeUnit.SECONDS);
        assertFalse(result.fromCache());
        assertEquals(0, result.failedChunks());
        assertEquals(1, window.shown.get());
        assertTrue(listener.stages.contains(ViewerProgressStage.CHECKING_CACHE));
        assertTrue(listener.stages.contains(ViewerProgressStage.READING_CHUNKS));
        assertTrue(listener.stages.contains(ViewerProgressStage.BUILDING_MESH));
        assertTrue(listener.stages.contains(ViewerProgressStage.PREPARING_WINDOW));
    }

    @Test
    void cachedSceneSkipsAreaLoader() throws Exception {
        FakeWindow window = new FakeWindow();
        ViewerSceneCache cache = new ViewerSceneCache(ViewerPerformanceProfile.BASIC);
        ViewerRequest request = request(0);
        cache.put(request.areaKey(VoxelPreviewCoordinator.RENDER_VERSION), SCENE, SIGNATURE);
        AtomicInteger loads = new AtomicInteger();
        ExecutorService executor = singleExecutor();
        coordinator = new VoxelPreviewCoordinator(
                window,
                cache,
                (areaRequest, cancellation, monitor) -> {
                    loads.incrementAndGet();
                    return area(areaRequest.target());
                },
                (area, cancellation, monitor) -> scene(area.target()),
                key -> SIGNATURE,
                executor);
        RecordingListener listener = new RecordingListener();

        coordinator.start(request, listener);

        assertTrue(listener.success.get(3, TimeUnit.SECONDS).fromCache());
        assertEquals(0, loads.get());
    }

    @Test
    void newerRequestCancelsAndSuppressesOlderResult() throws Exception {
        FakeWindow window = new FakeWindow();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        coordinator = coordinator(window, (request, cancellation, monitor) -> {
            if (request.target().minX() == 0) {
                firstEntered.countDown();
                releaseFirst.await(3, TimeUnit.SECONDS);
            }
            return area(request.target());
        });
        RecordingListener first = new RecordingListener();
        RecordingListener second = new RecordingListener();

        coordinator.start(request(0), first);
        assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
        coordinator.start(request(1), second);
        releaseFirst.countDown();

        assertEquals(1, first.cancelled.get(3, TimeUnit.SECONDS));
        assertEquals(1, second.success.get(3, TimeUnit.SECONDS).request().target().minX());
        assertFalse(first.success.isDone());
        assertEquals(1, window.shown.get());
    }

    @Test
    void queuedWindowResultKnowsWhenItsRequestIsNoLongerCurrent() throws Exception {
        DeferredWindow window = new DeferredWindow();
        coordinator = coordinator(window, (request, cancellation, monitor) -> area(request.target()));
        RecordingListener first = new RecordingListener();

        coordinator.start(request(0), first);
        ViewerWindow.Completion stale = window.completion.get(3, TimeUnit.SECONDS);
        coordinator.start(request(1), new RecordingListener());

        assertFalse(stale.isCurrent());
        assertEquals(1, first.cancelled.get(3, TimeUnit.SECONDS));
    }

    @Test
    void rejectsNewRequestsAfterClose() {
        FakeWindow window = new FakeWindow();
        coordinator = coordinator(window, (request, cancellation, monitor) -> area(request.target()));
        coordinator.close();

        assertThrows(IllegalStateException.class,
                () -> coordinator.start(request(0), new RecordingListener()));
        assertTrue(window.closed);
    }

    private VoxelPreviewCoordinator coordinator(
            ViewerWindow window,
            VoxelPreviewCoordinator.AreaLoader loader) {
        return new VoxelPreviewCoordinator(
                window,
                new ViewerSceneCache(ViewerPerformanceProfile.BASIC),
                loader,
                (area, cancellation, monitor) -> scene(area.target()),
                key -> SIGNATURE,
                singleExecutor());
    }

    private static ExecutorService singleExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "coordinator-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static ViewerRequest request(int chunkX) {
        return new ViewerRequest(
                Path.of("world"), "Test", WorldDimension.OVERWORLD_ID, "主世界",
                ChunkRectangle.target(chunkX, 0, chunkX, 0),
                ViewerPerformanceProfile.BASIC,
                new ViewerMapReturnState(0, 0, 2, PreviewLayer.surfaceOverview()));
    }

    private static VoxelAreaLoadResult area(ChunkRectangle target) {
        Map<ChunkCoordinate, ChunkLoadResult> results = new LinkedHashMap<>();
        for (ChunkCoordinate coordinate : target.expanded(1).coordinates()) {
            results.put(coordinate, ChunkLoadResult.withoutChunk(
                    coordinate, ChunkLoadStatus.MISSING, "missing", 0, 0));
        }
        Path world = Path.of(".").toAbsolutePath().normalize();
        return new VoxelAreaLoadResult(
                world, WorldDimension.overworld(world), target, results);
    }

    private static VoxelSceneResult scene(ChunkRectangle target) {
        Map<ChunkCoordinate, ChunkLoadStatus> statuses = new LinkedHashMap<>();
        target.coordinates().forEach(coordinate ->
                statuses.put(coordinate, ChunkLoadStatus.SUCCESS));
        return new VoxelSceneResult(SCENE, statuses, 0, false);
    }

    private static final class FakeWindow implements ViewerWindow {
        private final AtomicInteger shown = new AtomicInteger();
        private volatile boolean closed;

        @Override
        public void show(ViewerWindowContent content, Completion completion) {
            shown.incrementAndGet();
            completion.onShown();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class DeferredWindow implements ViewerWindow {
        private final CompletableFuture<Completion> completion = new CompletableFuture<>();

        @Override
        public void show(ViewerWindowContent content, Completion completion) {
            this.completion.complete(completion);
        }

        @Override
        public void close() {
        }
    }

    private static final class RecordingListener implements ViewerTaskListener {
        private final List<ViewerProgressStage> stages =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private final CompletableFuture<ViewerTaskResult> success = new CompletableFuture<>();
        private final CompletableFuture<Long> cancelled = new CompletableFuture<>();

        @Override
        public void onProgress(long requestId, ViewerProgress progress) {
            stages.add(progress.stage());
        }

        @Override
        public void onSucceeded(long requestId, ViewerTaskResult result) {
            success.complete(result);
        }

        @Override
        public void onFailed(long requestId, Throwable failure) {
            success.completeExceptionally(failure);
        }

        @Override
        public void onCancelled(long requestId) {
            cancelled.complete(requestId);
        }
    }
}
