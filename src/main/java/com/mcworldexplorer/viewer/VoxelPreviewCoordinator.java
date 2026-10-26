package com.mcworldexplorer.viewer;

import java.nio.file.Path;
import com.mcworldexplorer.voxel.resource.ResourceLocation;
import com.mcworldexplorer.voxel.resource.ClientResourceArchive;
import com.mcworldexplorer.voxel.resource.InstanceResourceSource;
import com.mcworldexplorer.voxel.resource.BlockResourceSource;
import com.mcworldexplorer.voxel.resource.BlockTextureSet;
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
    public static final String RENDER_VERSION = "v073-instance-resources-r1";

    private final ExecutorService executor;
    private final ViewerSceneCache cache;
    private final ViewerWindow window;
    private final AreaLoader areaLoader;
    private final SceneBuilder sceneBuilder;
    private final SignatureProvider signatureProvider;
    private long nextRequestId;
    private ActiveRequest active;
    private boolean closed;
    private Path resourcePath;
    private String resourceVersion;
    private Path resourceInstance;
    private long resourceRevision;

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
            current = new ActiveRequest(++nextRequestId, request, listener, resourcePath, resourceVersion,resourceInstance);
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

    public synchronized void removeCachedArea(ViewerAreaKey key) {
        requireOpen();
        Objects.requireNonNull(key, "key");
        if (active != null && key.equals(active.key)) cancel();
        cache.remove(key);
    }

    public synchronized void setResource(Path path,String version) {setResource(path,version,null);}
    public synchronized void setResource(Path path, String version,Path instance) {
        requireOpen();
        Path selected = path == null ? null : path.toAbsolutePath().normalize();
        if (selected != null && (version == null || version.isBlank()))
            throw new IllegalArgumentException("需要存档版本以校验客户端资源");
        String selectedVersion = selected == null ? null : version;
        Path selectedInstance=instance==null?null:instance.toAbsolutePath().normalize();
        if (Objects.equals(selected, resourcePath) && Objects.equals(selectedVersion, resourceVersion)&&Objects.equals(selectedInstance,resourceInstance)) return;
        cancel();
        resourcePath = selected;
        resourceVersion = selectedVersion;
        resourceInstance=selectedInstance;
        resourceRevision++;
        cache.clear();
    }

    private static String renderIdentity(BlockResourceSource archive) {
        return archive == null ? RENDER_VERSION : RENDER_VERSION + "|" + archive.fingerprint();
    }

    public List<ViewerAreaKey> validateCachedAreas() {
        List<ViewerAreaKey> valid = new ArrayList<>();
        Path path;
        String version;
        long revision;Path instance;
        synchronized (this) { path = resourcePath; version = resourceVersion; revision = resourceRevision;instance=resourceInstance; }
        java.util.Map<Path,String> identities=new java.util.HashMap<>();
        for (ViewerAreaKey key : cache.keys()) {
            RegionSourceSignature signature = null;
            try {
                String identity=identities.get(key.world());
                if(identity==null)try(var archive=path==null?null:InstanceResourceSource.open(key.world(),path,version,instance,CancellationSignal.NONE,message->{})) {
                    identity=renderIdentity(archive);identities.put(key.world(),identity);
                }
                if (key.renderVersion().equals(identity)) signature = signatureProvider.capture(key);
            }
            catch (Exception ignored) { /* Remove invalid entries only if this resource selection is still current. */ }
            synchronized (this) {
                if (closed || revision != resourceRevision) return List.of();
                if (signature != null && cache.get(key, signature).isPresent()) valid.add(key);
                else cache.remove(key);
            }
        }
        return List.copyOf(valid);
    }

    public List<ViewerCachedArea> validateCachedPreviews() {
        var valid = java.util.Set.copyOf(validateCachedAreas());
        return cache.previews().stream().filter(preview -> valid.contains(preview.key())).toList();
    }

    private void prepare(ActiveRequest request) {
        if (!isCurrent(request)) return;
        try (var archive = request.resourcePath == null ? null
                : InstanceResourceSource.open(request.request().world(),request.resourcePath,request.resourceVersion,request.resourceInstance,request,
                    message->report(request,new ViewerProgress(ViewerProgressStage.CHECKING_CACHE,0,0,message)))) {
            if (!isCurrent(request)) {
                return;
            }
            report(request, new ViewerProgress(
                    ViewerProgressStage.CHECKING_CACHE, 0, 0, "正在检查三维缓存"));
            ViewerAreaKey key = request.request().areaKey(renderIdentity(archive));
            synchronized (this) {
                if (!isCurrentLocked(request)) return;
                request.key = key;
                for (ViewerAreaKey previous : cache.keys())
                    if (previous.world().equals(key.world())&&!previous.renderVersion().equals(key.renderVersion())) cache.remove(previous);
            }
            RegionSourceSignature before = signatureProvider.capture(key);
            var cached = cache.get(key, before);
            if (cached.isPresent()) {
                if(archive!=null)archive.validateAll();
                int failedChunks = cache.previews().stream().filter(preview -> preview.key().equals(key))
                        .mapToInt(ViewerCachedArea::failedChunks).findFirst().orElse(0);
                show(request, cached.orElseThrow(), true, failedChunks);
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
            var textures = archive == null ? null : BlockTextureSet.load(archive, area, request);
            SceneBuilder selectedBuilder = textures == null ? sceneBuilder : (input, cancellation, monitor) ->
                    new VoxelSceneBuilder(textures).build(input, cancellation, monitor);
            VoxelSceneResult scene = selectedBuilder.build(
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
            ViewerAreaThumbnail thumbnail = ViewerAreaThumbnail.fromArea(area, request);
            if(archive!=null) {
                archive.validateAll();
                var notes=new ArrayList<>(snapshot.warnings());
                var coordinate=new com.mcworldexplorer.voxel.data.ChunkCoordinate(key.target().minX(),key.target().minZ());
                notes.add(new com.mcworldexplorer.voxel.mesh.MeshWarning(com.mcworldexplorer.voxel.mesh.MeshWarning.Code.RESOURCE_CONTEXT,coordinate,archive.description()));
                for(String detail:archive.originSamples())notes.add(new com.mcworldexplorer.voxel.mesh.MeshWarning(com.mcworldexplorer.voxel.mesh.MeshWarning.Code.RESOURCE_ORIGIN,coordinate,detail));
                for(String detail:archive.warnings())notes.add(new com.mcworldexplorer.voxel.mesh.MeshWarning(com.mcworldexplorer.voxel.mesh.MeshWarning.Code.RESOURCE_WARNING,coordinate,detail));
                snapshot=new VoxelSceneSnapshot(snapshot.anchorWorldX(),snapshot.anchorWorldZ(),snapshot.chunks(),snapshot.bounds(),snapshot.blockCount(),snapshot.faceCount(),snapshot.vertexCount(),snapshot.indexCount(),notes,snapshot.atlas());
            }
            RegionSourceSignature after = signatureProvider.capture(key);
            if (!before.equals(after)) {
                throw new IOException("生成期间存档 Region 文件发生变化，请重新生成");
            }
            int failedChunks = (int) scene.targetStatuses().values().stream()
                    .filter(status -> status != ChunkLoadStatus.SUCCESS)
                    .count();
            synchronized (this) {
                if (!isCurrentLocked(request)) return;
                cache.put(key, snapshot, after, java.util.Optional.of(thumbnail), failedChunks);
            }
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
                request.rangeDescription());
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
        private final Path resourcePath;
        private final String resourceVersion;
        private final Path resourceInstance;
        private volatile ViewerAreaKey key;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private ActiveRequest(long id, ViewerRequest request, ViewerTaskListener listener, Path resourcePath, String resourceVersion,Path resourceInstance) {
            this.id = id;
            this.request = request;
            this.listener = listener;
            this.resourcePath = resourcePath;
            this.resourceVersion = resourceVersion;
            this.resourceInstance=resourceInstance;
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
