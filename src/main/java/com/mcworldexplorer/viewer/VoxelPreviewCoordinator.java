package com.mcworldexplorer.viewer;

import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.io.ChunkLoadStatus;
import com.mcworldexplorer.voxel.io.VoxelAreaLoadResult;
import com.mcworldexplorer.voxel.io.VoxelAreaLoader;
import com.mcworldexplorer.voxel.io.VoxelAreaRequest;
import com.mcworldexplorer.voxel.io.VoxelAreaLoadMonitor;
import com.mcworldexplorer.voxel.mesh.VoxelSceneBuildMonitor;
import com.mcworldexplorer.voxel.mesh.VoxelSceneBuilder;
import com.mcworldexplorer.voxel.mesh.VoxelSceneResult;
import com.mcworldexplorer.voxel.mesh.VoxelSceneSnapshot;

import java.io.IOException;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VoxelPreviewCoordinator implements AutoCloseable {
    public static final String RENDER_VERSION = "v06-r1";

    private final ExecutorService executor;
    private final ViewerSceneCache cache;
    private final ViewerWindow window;
    private final AreaLoader areaLoader;
    private final SceneBuilder sceneBuilder;
    private final SignatureProvider signatureProvider;
    private long nextRequestId;
    private ActiveRequest active;
    private boolean closed;

    public VoxelPreviewCoordinator(ViewerWindow window, ViewerPerformanceProfile profile) {
        this(
                window,
                new ViewerSceneCache(profile),
                (request, cancellation, monitor) ->
                        new VoxelAreaLoader().load(request, cancellation, monitor),
                (area, cancellation, monitor) ->
                        new VoxelSceneBuilder().build(area, cancellation, monitor),
                RegionSourceSignature::capture,
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "voxel-preview-coordinator");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    VoxelPreviewCoordinator(
            ViewerWindow window,
            ViewerSceneCache cache,
            AreaLoader areaLoader,
            SceneBuilder sceneBuilder,
            SignatureProvider signatureProvider,
            ExecutorService executor) {
        this.window = Objects.requireNonNull(window, "window");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.areaLoader = Objects.requireNonNull(areaLoader, "areaLoader");
        this.sceneBuilder = Objects.requireNonNull(sceneBuilder, "sceneBuilder");
        this.signatureProvider = Objects.requireNonNull(signatureProvider, "signatureProvider");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public long start(ViewerRequest request, ViewerTaskListener listener) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(listener, "listener");
        ActiveRequest previous;
        ActiveRequest current;
        synchronized (this) {
            requireOpen();
            previous = active;
            if (previous != null) {
                previous.cancel();
            }
            current = new ActiveRequest(++nextRequestId, request, listener);
            active = current;
            cache.setProfile(request.profile());
            try {
                executor.execute(() -> prepare(current));
            } catch (RejectedExecutionException e) {
                active = null;
                throw e;
            }
        }
        if (previous != null) {
            previous.listener().onCancelled(previous.id());
        }
        return current.id();
    }

    public void cancel() {
        ActiveRequest cancelled;
        synchronized (this) {
            cancelled = active;
            if (cancelled == null) {
                return;
            }
            cancelled.cancel();
            active = null;
        }
        cancelled.listener().onCancelled(cancelled.id());
    }

    public synchronized void setProfile(ViewerPerformanceProfile profile) {
        requireOpen();
        cache.setProfile(profile);
    }

    public synchronized java.util.List<ViewerAreaKey> cachedAreas() {
        return cache.keys();
    }

    public List<ViewerAreaKey> validateCachedAreas() {
        List<ViewerAreaKey> valid = new ArrayList<>();
        for (ViewerAreaKey key : cache.keys()) {
            try {
                RegionSourceSignature signature = signatureProvider.capture(key);
                if (cache.get(key, signature).isPresent()) {
                    valid.add(key);
                }
            } catch (Exception e) {
                cache.remove(key);
            }
        }
        return List.copyOf(valid);
    }

    private void prepare(ActiveRequest request) {
        try {
            if (!isCurrent(request)) {
                return;
            }
            report(request, new ViewerProgress(
                    ViewerProgressStage.CHECKING_CACHE, 0, 0, "正在检查三维缓存"));
            ViewerAreaKey key = request.request().areaKey(RENDER_VERSION);
            RegionSourceSignature before = signatureProvider.capture(key);
            var cached = cache.get(key, before);
            if (cached.isPresent()) {
                show(request, cached.orElseThrow(), true, 0);
                return;
            }

            report(request, new ViewerProgress(
                    ViewerProgressStage.READING_CHUNKS, 0,
                    request.request().target().expanded(1).coordinates().size(), "正在读取区块"));
            VoxelAreaLoadResult area = areaLoader.load(
                    new VoxelAreaRequest(
                            request.request().world(),
                            request.request().dimensionId(),
                            request.request().target()),
                    request,
                    (coordinate, status, completed, total) -> report(request, new ViewerProgress(
                            ViewerProgressStage.READING_CHUNKS,
                            completed,
                            total,
                            "正在读取区块 " + completed + "/" + total)));
            if (!isCurrent(request)) {
                return;
            }

            report(request, new ViewerProgress(
                    ViewerProgressStage.BUILDING_MESH, 0,
                    request.request().target().coordinates().size(), "正在生成网格"));
            VoxelSceneResult scene = sceneBuilder.build(
                    area,
                    request,
                    (coordinate, status, completed, total) -> report(request, new ViewerProgress(
                            ViewerProgressStage.BUILDING_MESH,
                            completed,
                            total,
                            "正在生成网格 " + completed + "/" + total)));
            if (!isCurrent(request)) {
                return;
            }
            VoxelSceneSnapshot snapshot = scene.snapshot().orElseThrow(() ->
                    new IOException("所选区域没有可渲染的三维几何"));
            RegionSourceSignature after = signatureProvider.capture(key);
            if (!before.equals(after)) {
                throw new IOException("生成期间存档 Region 文件发生变化，请重新生成");
            }
            int failedChunks = (int) scene.targetStatuses().values().stream()
                    .filter(status -> status != ChunkLoadStatus.SUCCESS)
                    .count();
            cache.put(key, snapshot, after);
            show(request, snapshot, false, failedChunks);
        } catch (Throwable failure) {
            failIfCurrent(request, failure);
        }
    }

    private void show(
            ActiveRequest request,
            VoxelSceneSnapshot snapshot,
            boolean fromCache,
            int failedChunks) {
        if (!isCurrent(request)) {
            return;
        }
        report(request, new ViewerProgress(
                ViewerProgressStage.PREPARING_WINDOW, 0, 0, "正在准备三维窗口"));
        ViewerTaskResult result = new ViewerTaskResult(
                request.request(), snapshot, fromCache, failedChunks);
        window.show(new ViewerWindowContent(title(request.request()), snapshot),
                new ViewerWindow.Completion() {
                    @Override
                    public boolean isCurrent() {
                        return VoxelPreviewCoordinator.this.isCurrent(request);
                    }

                    @Override
                    public void onShown() {
                        succeedIfCurrent(request, result);
                    }

                    @Override
                    public void onFailed(Throwable failure) {
                        failIfCurrent(request, failure);
                    }
                });
    }

    private void report(ActiveRequest request, ViewerProgress progress) {
        if (isCurrent(request)) {
            request.listener().onProgress(request.id(), progress);
        }
    }

    private void succeedIfCurrent(ActiveRequest request, ViewerTaskResult result) {
        synchronized (this) {
            if (!isCurrentLocked(request)) {
                return;
            }
            active = null;
        }
        request.listener().onSucceeded(request.id(), result);
    }

    private void failIfCurrent(ActiveRequest request, Throwable failure) {
        synchronized (this) {
            if (!isCurrentLocked(request)) {
                return;
            }
            active = null;
        }
        request.listener().onFailed(request.id(), failure);
    }

    private synchronized boolean isCurrent(ActiveRequest request) {
        return isCurrentLocked(request);
    }

    private boolean isCurrentLocked(ActiveRequest request) {
        return !closed && active == request && !request.isCancelled();
    }

    private static String title(ViewerRequest request) {
        return String.format(
                "MC World Explorer - %s - %s - X %d Z %d - %s",
                request.worldName(),
                request.dimensionName(),
                request.centerBlockX(),
                request.centerBlockZ(),
                request.rangePreset());
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("voxel preview coordinator is closed");
        }
    }

    @Override
    public void close() {
        ActiveRequest cancelled;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            cancelled = active;
            active = null;
            if (cancelled != null) {
                cancelled.cancel();
            }
            cache.clear();
        }
        executor.shutdownNow();
        window.close();
        if (cancelled != null) {
            cancelled.listener().onCancelled(cancelled.id());
        }
    }

    @FunctionalInterface
    interface AreaLoader {
        VoxelAreaLoadResult load(
                VoxelAreaRequest request,
                CancellationSignal cancellation,
                VoxelAreaLoadMonitor monitor) throws Exception;
    }

    @FunctionalInterface
    interface SceneBuilder {
        VoxelSceneResult build(
                VoxelAreaLoadResult area,
                CancellationSignal cancellation,
                VoxelSceneBuildMonitor monitor) throws Exception;
    }

    @FunctionalInterface
    interface SignatureProvider {
        RegionSourceSignature capture(ViewerAreaKey key) throws Exception;
    }

    private static final class ActiveRequest implements CancellationSignal {
        private final long id;
        private final ViewerRequest request;
        private final ViewerTaskListener listener;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private ActiveRequest(long id, ViewerRequest request, ViewerTaskListener listener) {
            this.id = id;
            this.request = request;
            this.listener = listener;
        }

        private long id() {
            return id;
        }

        private ViewerRequest request() {
            return request;
        }

        private ViewerTaskListener listener() {
            return listener;
        }

        private void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}
