package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.SavedFluid;
import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.mesh.RenderLayer;
import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.data.ChunkRectangle;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.io.ChunkDecodeException;
import com.mcworldexplorer.voxel.io.VoxelAreaLoadResult;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.Direction;

/** Prepares only requested states; bounded model geometry and texture resources are prepared on demand. */
public final class BlockTextureSet {
    public static final int MAX_STATES = 4096;
    @FunctionalInterface
    public interface PreparationProgress {
        PreparationProgress NONE = (message, completed, total) -> {};

        void update(String message, int completed, int total);

        default void states(String message, int completed, int total) {
            // Bound UI notifications even when the area contains thousands of cheap states.
            if (completed == total || completed % Math.max(1, (total + 99) / 100) == 0) {
                update(message, completed, total);
            }
        }
    }
    // Existing built-in models encode an explicit RGB tint above this marker.
    // Ordinary JSON tintindex values are handler indices, not colours.
    private static final int EXPLICIT_TINT_MARKER = 0x1000000;
    private static final int MAX_DIAGNOSTIC_TINT_INDICES = 8;
    public record Surface(Direction direction, List<Float> positions, List<Float> uv,
                          List<Float> normal, Direction cullFace, boolean shade,
                          RenderLayer layer, int element, boolean fluid,
                          int tintIndex, int staticRgb,
                          List<Float> overlayUv, int overlayTintIndex, int overlayStaticRgb) {
        public Surface(Direction direction, List<Float> positions, List<Float> uv, List<Float> normal, Direction cullFace,
                       boolean shade, RenderLayer layer, int element) {
            this(direction, positions, uv, normal, cullFace, shade, layer, element, false, -1, 0xFFFFFF, List.of(), -1, 0xFFFFFF);
        }
        public Surface(Direction direction, List<Float> positions, List<Float> uv, List<Float> normal, Direction cullFace,
                       boolean shade, RenderLayer layer, int element, boolean fluid) {
            this(direction, positions, uv, normal, cullFace, shade, layer, element, fluid, -1, 0xFFFFFF, List.of(), -1, 0xFFFFFF);
        }
        public Surface {
            positions = List.copyOf(positions);
            uv = List.copyOf(uv);
            normal = List.copyOf(normal);
            staticRgb &= 0xFFFFFF;
            overlayUv = List.copyOf(overlayUv);
            overlayStaticRgb &= 0xFFFFFF;
        }
        public boolean hasOverlay() { return !overlayUv.isEmpty(); }
        public Surface withFluidGeometry(List<Float> newPositions, List<Float> newUv) {
            return new Surface(direction, newPositions, newUv, normal, cullFace, shade, layer, element, true,
                    tintIndex, staticRgb, overlayUv, overlayTintIndex, overlayStaticRgb);
        }
    }
    private record Pending(ModelGeometry.Quad quad, TextureAtlas.Tile tile, boolean shade,
                           RenderLayer layer, int element, boolean fluid,
                           int tintIndex, int staticRgb,
                           List<Float> overlayQuadUv, TextureAtlas.Tile overlayTile, int overlayTintIndex, int overlayStaticRgb) { }
    // Mesh preparation needs the target blocks and one adjacent block on each side for occlusion.
    record LocalBounds(int minX, int minZ, int maxX, int maxZ) {
        static LocalBounds forNeighbor(ChunkRectangle target, ChunkCoordinate chunk) {
            return new LocalBounds(chunk.x()<target.minX()?15:0, chunk.z()<target.minZ()?15:0,
                    chunk.x()>target.maxX()?0:15, chunk.z()>target.maxZ()?0:15);
        }
    }
    private record PaintKey(ResourceLocation texture, int tint, float opacity, int argb, double brightness) { }
    private record SurfaceKey(List<Float> positions, List<Float> normal, boolean shaded, boolean fluid) { }
    private record Painted(ModelGeometry.Quad quad, BufferedImage image, boolean shade, int element, String renderType, boolean fluid,
                           int tintIndex, int staticRgb,
                           List<Float> overlayUv, BufferedImage overlayImage, String overlayRenderType, int overlayTintIndex, int overlayStaticRgb) { }
    private final Map<BlockState, List<Surface>> models;
    private final VoxelAreaLoadResult contextArea;
    private final ModelRuleRegistry rules;
    private final Set<String> contextualNames;
    private final Map<BlockState,BlockModelResolver.Dependencies> contextDependencies;
    private final Map<BlockState, String> failures;
    private final Map<BlockState,List<String>> notes;
    private final Map<BlockState,List<BlockResourceDiagnostic>> diagnostics;
    private final TextureAtlas atlas;
    private final boolean stateLimitReached;
    private final ClientResourceArchive.Fingerprint fingerprint;
    private Optional<Set<String>> fluidBarriers=Optional.empty();
    public Optional<Set<String>> fluidBarriers(){return fluidBarriers;}
    private AreaModelVariants weighted=new AreaModelVariants();
    private BlockTintPreparation tintPreparation=new BlockTintPreparation();

    private BlockTextureSet(Map<BlockState, List<Surface>> models, Map<BlockState, String> failures,
                            TextureAtlas atlas, ClientResourceArchive.Fingerprint fingerprint, boolean stateLimitReached,Map<BlockState,List<String>> notes,VoxelAreaLoadResult area,ModelRuleRegistry rules,Map<BlockState,BlockModelResolver.Dependencies> contextDependencies,Map<BlockState,List<BlockResourceDiagnostic>> diagnostics) {
        this.contextArea=area;this.rules=rules;this.contextDependencies=Map.copyOf(contextDependencies);this.contextualNames=contextDependencies.keySet().stream().map(BlockState::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.notes=Map.copyOf(notes);this.diagnostics=Map.copyOf(diagnostics);
        this.models = Map.copyOf(models); this.failures = Map.copyOf(failures);
        this.atlas = atlas; this.fingerprint = fingerprint; this.stateLimitReached = stateLimitReached;
    }
    public List<String> notes(BlockState state){return notes.getOrDefault(state,List.of());}
    public List<BlockResourceDiagnostic> diagnostics(BlockState state){
        var known=diagnostics.get(state);if(known!=null)return known;
        if(models.containsKey(state))return List.of();
        return List.of(new BlockResourceDiagnostic(state,BlockResourceDiagnostic.Severity.FAILURE,
            stateLimitReached?"PREPARATION_LIMIT":"STATE_NOT_PREPARED",fallbackReason(state),List.of(),List.of(),false));
    }
    public TextureAtlas atlas() { return atlas; }
    public ClientResourceArchive.Fingerprint fingerprint() { return fingerprint; }
    public static BlockState visualState(BlockState state,com.mcworldexplorer.voxel.data.BlockEntityData entity) {
        if(entity==null||entity.visual().keySet().stream().allMatch(key->key.equals("id")))return state;
        Map<String,String> properties=new TreeMap<>(state.properties());properties.put("__mcwe_entity",entity.fingerprint());
        return state.withProperties(properties);
    }

    public List<Surface> surfaces(BlockState state) { return models.get(state); }

    public int tintColor(long x, int y, long z, int tintIndex, int fallbackRgb) {
        return tintPreparation.color(x, y, z, tintIndex, fallbackRgb & 0xFFFFFF);
    }

    public int surfaceColor(Surface surface, long x, int y, long z) {
        return surface.tintIndex() >= 0
                ? tintPreparation.color(x, y, z, surface.tintIndex(), surface.staticRgb())
                : surface.staticRgb();
    }

    public int overlayColor(Surface surface, long x, int y, long z) {
        if (!surface.hasOverlay()) return -1;
        return surface.overlayTintIndex() >= 0
                ? tintPreparation.color(x, y, z, surface.overlayTintIndex(), surface.overlayStaticRgb())
                : surface.overlayStaticRgb();
    }

    /** Returns the actual prepared identity, including declared context dependencies. */
    public BlockState renderState(BlockState state, long x, int y, long z) {
        if (contextArea == null || !contextualNames.contains(state.name())) return weighted.state(state, x, y, z);
        var context = new ModelBlockContext(contextArea, new ModelBlockContext.Position(x, y, z), CancellationSignal.NONE);
        state = visualState(state, context.at(0, 0, 0).entity().orElse(null));
        state = weighted.state(state, x, y, z);
        var dependencies = contextDependencies.get(state);
        return dependencies == null ? state : contextKey(state, context, dependencies);
    }
    static BlockState contextKey(BlockState state,ModelBlockContext context,BlockModelResolver.Dependencies dependencies) {
        Map<String,String> properties=new TreeMap<>(state.properties());properties.put("__mcwe_context",context.signature(dependencies.neighbors(),dependencies.positionDependent(),dependencies.biomeColorDependent()));
        return state.withProperties(properties);
    }
    public String fallbackReason(BlockState state) {
        return failures.getOrDefault(state, stateLimitReached
                ? "本次不同方块状态超过 " + MAX_STATES + " 项准备上限" : "此状态未参与本次资源准备");
    }

    public static BlockTextureSet load(BlockResourceSource archive,
            VoxelAreaLoadResult area, CancellationSignal cancel)
            throws IOException {return load(archive,area,cancel,ModelRuleRegistry.defaults());}
    public static BlockTextureSet load(BlockResourceSource archive,VoxelAreaLoadResult area,CancellationSignal cancel,ModelRuleRegistry rules)throws IOException {
        return load(archive, area, cancel, rules, PreparationProgress.NONE);
    }
    public static BlockTextureSet load(BlockResourceSource archive, VoxelAreaLoadResult area, CancellationSignal cancel,
                                       ModelRuleRegistry rules, PreparationProgress progress) throws IOException {
        try(var batch=archive instanceof InstanceResourceSource instance ? instance.modelBatch() : null) {
            return loadArea(archive,area,cancel,rules,progress);
        }
    }
    private static BlockTextureSet loadArea(BlockResourceSource archive,VoxelAreaLoadResult area,CancellationSignal cancel,ModelRuleRegistry rules,PreparationProgress progress)throws IOException {
        progress.update("正在收集选区方块状态", 0, 0);
        Set<BlockState> states = new LinkedHashSet<>();
        Map<String,com.mcworldexplorer.voxel.data.BlockEntityData> entities=new HashMap<>();
        var neighborhood = area.target().expanded(1);
        collect:
        for (var coordinate : area.results().keySet()) {
            if (!neighborhood.contains(coordinate)) continue;
            check(cancel);
            var result = area.result(coordinate);
            if (result.chunk() == null) continue;
            try {
                var bounds=LocalBounds.forNeighbor(area.target(),coordinate);
                boolean hasEntities=!result.chunk().blockEntities().isEmpty();
                for (var section : result.chunk().sections()) {
                    var uniform=section.uniformState();
                    if(!hasEntities && uniform.isPresent()){
                        check(cancel);
                        states.add(uniform.get());
                        if(states.size()>MAX_STATES)break collect;
                        continue;
                    }
                    for (int y = 0; y < 16; y++) {
                        check(cancel);
                        for (int z=bounds.minZ(); z<=bounds.maxZ(); z++) for (int x=bounds.minX(); x<=bounds.maxX(); x++) {
                            long wx=(long)coordinate.x()*16+x,wz=(long)coordinate.z()*16+z;
                            int worldY=section.sectionY()*16+y;
                            var entity=result.chunk().blockEntity(wx,worldY,wz).orElse(null);
                            BlockState state=visualState(section.blockState(x,y,z),entity);
                            if(entity!=null&&state.properties().containsKey("__mcwe_entity"))entities.put(entity.fingerprint(),entity);
                            states.add(state);
                            // One extra actual state records overflow without retaining the whole area palette.
                            if (states.size() > MAX_STATES) break collect;
                        }
                    }
                }
            } catch (ChunkDecodeException corrupt) {
                // Meshing reports this chunk as CORRUPT; keep other target chunks usable.
            }
        }
        var builtin=new BuiltinBlockModels(archive,entities);
        archive=builtin;
        var resolver=new BlockModelResolver(archive,rules);
        Map<BlockState,BlockModelResolver.Result> resolved=new LinkedHashMap<>();Set<String> contextual=new HashSet<>();Map<BlockState,BlockModelResolver.Dependencies> dependencies=new HashMap<>();
        int[] retainedFaces={32768};
        var weighted=new AreaModelVariants();
        int modelCount = Math.min(states.size(), MAX_STATES);
        progress.states("正在解析方块模型", 0, modelCount);
        for(var state:states){
            check(cancel);
            if(resolved.size()>=MAX_STATES)break;
            var result=resolver.prepareVariants(state,plan->weighted.register(state,plan));
            resolved.put(state,retain(result,retainedFaces));
            progress.states("正在解析方块模型", resolved.size(), modelCount);
        }
        progress.update("正在匹配模型变体与群系颜色", 0, 0);
        weighted.prepare(area,cancel,resolver,states,resolved,retainedFaces);
        for(var entry:resolved.entrySet())if(entry.getValue().reason()==BlockModelResolver.Reason.CONTEXT_REQUIRED){
            contextual.add(entry.getKey().name());dependencies.put(entry.getKey(),entry.getValue().dependencies());
        }
        if(!contextual.isEmpty()) {
            contexts:
            for(var coordinate:area.results().keySet()) {
                if (!neighborhood.contains(coordinate)) continue;
                var bounds=LocalBounds.forNeighbor(area.target(),coordinate);
                var loaded=area.result(coordinate);if(loaded.chunk()==null)continue;
                try {
                    for(var section:loaded.chunk().sections()) {
                        if(!section.containsAny(contextual))continue;
                        for(int y=0;y<16;y++) {
                            check(cancel);
                            for(int z=bounds.minZ();z<=bounds.maxZ();z++)for(int x=bounds.minX();x<=bounds.maxX();x++) {
                                var physical=section.blockState(x,y,z);if(!contextual.contains(physical.name()))continue;
                                long wx=(long)coordinate.x()*16+x,wz=(long)coordinate.z()*16+z;
                                int wy=section.sectionY()*16+y;var entity=loaded.chunk().blockEntity(wx,wy,wz).orElse(null);
                                var visual=visualState(physical,entity);var context=new ModelBlockContext(area,new ModelBlockContext.Position(wx,wy,wz),cancel);var selected=weighted.state(visual,wx,wy,wz);var dependency=dependencies.get(selected);if(dependency==null)continue;var key=contextKey(selected,context,dependency);
                                if(states.contains(key))continue;
                                states.add(key);if(states.size()>MAX_STATES)break contexts;
                                var actual=builtin.recordNotes(visual,selected,()->resolver.resolve(visual,context));
                                if(!dependency.neighbors().containsAll(actual.dependencies().neighbors())||actual.dependencies().positionDependent()&&!dependency.positionDependent()||actual.dependencies().biomeColorDependent()&&!dependency.biomeColorDependent())
                                    actual=new BlockModelResolver.Result(BlockModelResolver.Reason.INVALID_MODEL,"父规则未声明所选子规则的上下文依赖",Optional.empty(),actual.sources(),actual.fingerprint(),actual.dependencies(),actual.loaders());
                                resolved.put(key,retain(actual,retainedFaces));
                            }
                        }
                    }
                }catch(ChunkDecodeException corrupt){/* Mesher diagnoses the unavailable chunk. */}
            }
        }
        Optional<Set<String>> flowTag=Optional.empty();
        if(states.stream().anyMatch(state->SavedFluid.of(state)!=SavedFluid.NONE)){
            String problem=null;
            try {flowTag=new FluidFlowTag(archive).read(ResourceLocation.parse("minecraft:blocks_fluid_flow"));}
            catch(ResourceAccessException failure){
                if(failure.scope()!=ResourceAccessException.Scope.RESOURCE)throw failure;
                problem=failure.getMessage();
            }catch(IOException | IllegalArgumentException | IllegalStateException invalid){archive.validate();problem=invalid.getMessage();}
            if(problem!=null)for(var state:states)if(SavedFluid.of(state)!=SavedFluid.NONE)
                ((BuiltinBlockModels)archive).note(state,"流体阻挡标签不可用，按相邻静态模型估算："+problem);
        }
        var tints=BlockTintPreparation.prepare((BuiltinBlockModels)archive,area,cancel,states,resolved,dependencies,weighted,rules);
        var prepared=loadPrepared(archive,states,cancel,rules,resolved,area,dependencies,tints,progress);
        prepared.fluidBarriers=flowTag;
        prepared.weighted=weighted;prepared.tintPreparation=tints;return prepared;
    }
    static BlockModelResolver.Result retain(BlockModelResolver.Result result,int[] budget) {
        if(!result.resolved())return result;
        int count=result.model().orElseThrow().faces().size();
        if(count>budget[0])return new BlockModelResolver.Result(BlockModelResolver.Reason.LIMIT_EXCEEDED,"区域模型暂存超过32768面预算",Optional.empty(),result.sources(),result.fingerprint(),result.dependencies(),result.loaders());
        budget[0]-=count;return result;
    }

    public static BlockTextureSet load(BlockResourceSource archive, Collection<BlockState> requested,
                                       CancellationSignal cancel) throws IOException {return load(archive,requested,cancel,ModelRuleRegistry.defaults());}
    public static BlockTextureSet load(BlockResourceSource archive,Collection<BlockState> requested,CancellationSignal cancel,ModelRuleRegistry rules)throws IOException {
        return loadPrepared(archive,requested,cancel,rules,Map.of(),null,Map.of(),new BlockTintPreparation(),PreparationProgress.NONE);
    }
    private static BlockTextureSet loadPrepared(BlockResourceSource archive,Collection<BlockState> requested,CancellationSignal cancel,ModelRuleRegistry rules,
            Map<BlockState,BlockModelResolver.Result> resolved,VoxelAreaLoadResult area,Map<BlockState,BlockModelResolver.Dependencies> contextDependencies,
            BlockTintPreparation tintPreparation,PreparationProgress progress)throws IOException {
        Objects.requireNonNull(rules);
        Objects.requireNonNull(archive); Objects.requireNonNull(requested); Objects.requireNonNull(cancel);
        if(!(archive instanceof BuiltinBlockModels))archive=new BuiltinBlockModels(archive,Map.of());
        Set<BlockState> states = new LinkedHashSet<>();
        boolean limited = false;
        for (BlockState state : requested) {
            check(cancel);
            Objects.requireNonNull(state);
            if (states.contains(state)) continue;
            if (states.size() >= MAX_STATES) { limited = true; break; }
            states.add(state);
        }
        var resolver = new BlockModelResolver(archive,rules);
        var tintResolver=new BlockTintResolver(archive,rules);
        var builder = new TextureAtlas.Builder();
        Map<TextureAtlas.Tile,RenderLayer> alphaLayers=new HashMap<>();
        Map<ResourceLocation, BufferedImage> images = new HashMap<>();
        Map<BufferedImage, TextureAtlas.Tile> atlasTiles = new IdentityHashMap<>();
        Map<BlockState, List<Pending>> pending = new LinkedHashMap<>();
        Map<BlockState, String> failures = new HashMap<>();
        Map<BlockState,List<String>> notes=new HashMap<>();
        Map<BlockState,List<BlockResourceDiagnostic>> diagnostics=new HashMap<>();
        int faceBudget = 32768;
        int completedStates = 0;
        for (BlockState state : states) {
            check(cancel);
            progress.states("正在对齐纹理（方块状态）", completedStates++, states.size());
            var result = resolved.get(state);if(result==null)result=resolver.resolve(state);
            if (!result.resolved()) {
                failures.put(state, result.reason() + ": " + result.detail()+trace(archive,result));
                diagnostics.put(state,List.of(BlockResourceDiagnostic.capture(state,BlockResourceDiagnostic.Severity.FAILURE,result.reason().name(),result.detail(),archive,result)));continue;
            }
            if(area==null){var tint=tintResolver.colors(state,result.model().orElseThrow(),null);result=BlockTintResolver.apply(result,tint);BlockTintPreparation.note((BuiltinBlockModels)archive,state,tint.detail());}
            var model = result.model().orElseThrow();
            var logicalProperties=new TreeMap<>(state.properties());logicalProperties.remove("__mcwe_context");
            var stateNotes=new ArrayList<>(((BuiltinBlockModels)archive).notes(state.withProperties(logicalProperties)));
            if(model.fixedFirstAlternative())stateNotes.add("随机候选模型固定选择第一项");
            boolean bakeLighting=model.faces().stream().anyMatch(face->face.material().minimumLight()>0||face.material().shadeDirectionOverride()!=null);
            if(bakeLighting)stateNotes.add("面亮度与方向覆盖应用于静态光照；未模拟世界光照、昼夜变化与环境遮蔽");
            if(!stateNotes.isEmpty())notes.put(state,List.copyOf(stateNotes));
            String unboundNote=null;
            try {
                if(model.faces().size()>faceBudget) throw new PreparationFailure("LIMIT_EXCEEDED","本次模型面准备超过 32768 项预算");
                Map<SurfaceKey,Painted> painted = new LinkedHashMap<>();
                List<Painted> extraPainted = new ArrayList<>();
                Map<PaintKey,BufferedImage> tintedImages=new HashMap<>();
                Set<Integer> unboundTintIndices = new TreeSet<>();
                long workingBytes=0;
                int elementBase=0;
                for(var part:model.parts()) {
                    boolean fluid=("mcwe:fluid".equals(part.renderType())||"mcwe:lava".equals(part.renderType()))
                            && part.id().namespace().equals("minecraft")
                            && Set.of("water","bubble_column","lava").contains(part.id().path())
                            && SavedFluid.of(state)!=SavedFluid.NONE;
                    for(var face:part.faces()) {
                        check(cancel);
                        var quad=ModelGeometry.bake(face,part);
                        if(quad.normal().stream().allMatch(v->Math.abs(v)<1e-6))continue;
                        int rawTint = face.tintIndex();
                        int cpuTint = -1;
                        int dynamicTint = -1;
                        int staticRgb = 0xFFFFFF;
                        if (rawTint >= EXPLICIT_TINT_MARKER) {
                            if (bakeLighting) {
                                cpuTint = rawTint;
                            } else {
                                staticRgb = rawTint & 0xFFFFFF;
                            }
                        } else if (rawTint >= 0) {
                            if (tintPreparation.isDynamicIndex(state, rawTint)) {
                                dynamicTint = rawTint;
                            } else {
                                unboundTintIndices.add(rawTint);
                            }
                        }
                        BufferedImage sourceImage=image(archive,face.texture(),images);
                        long bytes=(long)sourceImage.getWidth()*sourceImage.getHeight()*4;
                        double brightness=bakeLighting?Math.max(face.material().minimumLight(),face.material().shadeDirectionOverride()!=null?overrideBrightness(face.material().shadeDirectionOverride()):face.shade()?directionalBrightness(quad.normal()):1):1;
                        String renderType=face.material().forceTranslucent()?"minecraft:translucent":part.renderType();
                        boolean shaded=bakeLighting?false:face.shade();
                        var paintKey=new PaintKey(face.texture(),cpuTint,face.opacity(),face.material().argb(),brightness);
                        BufferedImage preparedImage=tintedImages.get(paintKey);
                        if(preparedImage==null){
                            if(workingBytes+bytes>32L*1024*1024)throw new PreparationFailure("LIMIT_EXCEEDED","单状态面纹理工作内存超过 32 MiB 预算");
                            boolean originalColor=cpuTint<0 || (cpuTint & 0xffffff)==0xffffff;
                            if(originalColor && face.material().argb()==-1 && face.opacity()==1 && brightness==1) {
                                preparedImage=sourceImage;
                            } else {
                                preparedImage=composite(sourceImage,cpuTint,null,-1);
                                applyMaterial(preparedImage,face.material().argb(),face.opacity(),brightness);
                            }
                            tintedImages.put(paintKey,preparedImage);workingBytes+=bytes;
                        }
                        for(var tiled:UvTiling.split(quad)) {
                            BufferedImage base=preparedImage;
                            quad=tiled;
                            var key=new SurfaceKey(quad.positions(),quad.normal(),shaded,fluid);
                            var previous=painted.get(key);
                            if(previous==null){
                                painted.put(key,new Painted(quad,base,shaded,elementBase+face.layer(),renderType,fluid,
                                        dynamicTint,staticRgb,List.of(),null,null,-1,0xFFFFFF));
                            } else if (bakeLighting && previous.overlayImage() == null && previous.tintIndex() < 0 && dynamicTint < 0
                                    && previous.quad().uv().equals(quad.uv())) {
                                long combinedBytes=(long)Math.max(previous.image().getWidth(),base.getWidth())*Math.max(previous.image().getHeight(),base.getHeight())*4;
                                if(workingBytes+combinedBytes>32L*1024*1024)throw new PreparationFailure("LIMIT_EXCEEDED","单状态面纹理工作内存超过 32 MiB 预算");
                                BufferedImage combined=composite(previous.image(),-1,base,-1);workingBytes+=combinedBytes;
                                String combinedRenderType = isSolidRenderType(previous.renderType()) && alphaLayer(previous.image()) == RenderLayer.OPAQUE
                                        ? previous.renderType()
                                        : isSolidRenderType(renderType) && alphaLayer(base) == RenderLayer.OPAQUE ? renderType
                                        : "minecraft:translucent".equals(previous.renderType()) || "minecraft:translucent".equals(renderType) ? "minecraft:translucent"
                                        : previous.renderType() != null ? previous.renderType() : renderType;
                                painted.put(key,new Painted(quad,combined,shaded,previous.element(),combinedRenderType,fluid,
                                        -1,0xFFFFFF,List.of(),null,null,-1,0xFFFFFF));
                            } else if (previous.overlayImage() == null) {
                                painted.put(key,new Painted(previous.quad(),previous.image(),previous.shade(),previous.element(),
                                        previous.renderType(),previous.fluid(),previous.tintIndex(),previous.staticRgb(),
                                        quad.uv(),base,renderType,dynamicTint,staticRgb));
                            } else {
                                String extraRenderType = isSolidRenderType(renderType) ? "minecraft:cutout" : renderType;
                                extraPainted.add(new Painted(quad,base,shaded,-1,extraRenderType,fluid,
                                        dynamicTint,staticRgb,List.of(),null,null,-1,0xFFFFFF));
                            }
                            if(painted.size()+extraPainted.size()>faceBudget)throw new PreparationFailure("LIMIT_EXCEEDED","UV 展开后的模型面超过本次 32768 面预算");
                        }
                    }
                    elementBase+=part.faces().stream().mapToInt(BlockModelResolver.Face::layer).max().orElse(-1)+1;
                }
                List<Pending> surfaces=new ArrayList<>(painted.size()+extraPainted.size());
                List<Painted> allPainted = new ArrayList<>(painted.values());
                allPainted.addAll(extraPainted);
                for(var paint:allPainted) {
                    var tile=atlasTiles.get(paint.image());
                    if(tile==null){tile=builder.add(imageKey(paint.image()),paint.image());atlasTiles.put(paint.image(),tile);}
                    TextureAtlas.Tile overlayTile = null;
                    if (paint.overlayImage() != null) {
                        overlayTile = atlasTiles.get(paint.overlayImage());
                        if (overlayTile == null) {
                            overlayTile = builder.add(imageKey(paint.overlayImage()), paint.overlayImage());
                            atlasTiles.put(paint.overlayImage(), overlayTile);
                        }
                    }
                    RenderLayer layer = material(paint.image(), paint.overlayImage(), paint.renderType(), paint.overlayRenderType(), tile, overlayTile, alphaLayers);
                    surfaces.add(new Pending(paint.quad(),tile,paint.shade(),layer,paint.element(),paint.fluid(),
                            paint.tintIndex(),paint.staticRgb(),paint.overlayUv(),overlayTile,paint.overlayTintIndex(),paint.overlayStaticRgb()));
                }
                faceBudget-=surfaces.size();
                pending.put(state,surfaces);
                if (!unboundTintIndices.isEmpty()) {
                    String indices = unboundTintIndices.stream().limit(MAX_DIAGNOSTIC_TINT_INDICES)
                            .map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
                    if (unboundTintIndices.size() > MAX_DIAGNOSTIC_TINT_INDICES)
                        indices += " 等 " + unboundTintIndices.size() + " 项";
                    unboundNote="颜色未解析：tintindex [" + indices
                            + "] 未绑定颜色处理器；保留几何与原始纹理，未应用索引染色" + trace(archive,result);
                    stateNotes.add(unboundNote);
                    notes.put(state,List.copyOf(stateNotes));
                }
                var stateDiagnostics=new ArrayList<BlockResourceDiagnostic>();
                for(String note:stateNotes) {
                    boolean isUnbound = note.equals(unboundNote);
                    boolean unresolvedTint = isUnbound || note.startsWith("颜色未解析：");
                    stateDiagnostics.add(BlockResourceDiagnostic.capture(state,
                            unresolvedTint ? BlockResourceDiagnostic.Severity.DEGRADED : BlockResourceDiagnostic.Severity.INFO,
                            isUnbound ? "UNBOUND_TINT" : unresolvedTint ? "TINT_UNRESOLVED" : "MODEL_NOTE", note, archive, result));
                }
                if(!stateDiagnostics.isEmpty())diagnostics.put(state,List.copyOf(stateDiagnostics));
            } catch (ResourceAccessException e) {
                if(e.scope()!=ResourceAccessException.Scope.RESOURCE&&e.reason()!=ResourceAccessException.Reason.INVALID_METADATA)throw e;
                failures.put(state,e.reason()+": "+e.getMessage()+trace(archive,result));
                diagnostics.put(state,List.of(BlockResourceDiagnostic.capture(state,BlockResourceDiagnostic.Severity.FAILURE,e.reason().name(),e.getMessage(),archive,result)));
            } catch (IOException e) {
                failures.put(state,e.getMessage()+trace(archive,result));
                diagnostics.put(state,List.of(BlockResourceDiagnostic.capture(state,BlockResourceDiagnostic.Severity.FAILURE,
                    e instanceof PreparationFailure failure?failure.code:"TEXTURE_PREPARATION_FAILED",e.getMessage(),archive,result)));
            }
        }
        check(cancel);
        progress.states("正在对齐纹理（方块状态）", completedStates, states.size());
        progress.update("正在生成纹理图集", 0, 0);
        TextureAtlas atlas=builder.build();
        Map<BlockState,List<Surface>> models=new HashMap<>();
        for(var entry:pending.entrySet()) {
            List<Surface> faces=new ArrayList<>();
            for(var p:entry.getValue()) {
                List<Float> uv = atlasUv(p.quad().uv(), p.tile(), atlas);
                List<Float> overlayUv = p.overlayTile() == null ? List.of() : atlasUv(p.overlayQuadUv(), p.overlayTile(), atlas);
                faces.add(new Surface(p.quad().direction(),p.quad().positions(),uv,p.quad().normal(),
                        p.quad().cull(),p.shade(),p.layer(),p.element(),p.fluid(),
                        p.tintIndex(),p.staticRgb(),overlayUv,p.overlayTintIndex(),p.overlayStaticRgb()));
            }
            models.put(entry.getKey(),List.copyOf(faces));
        }
        check(cancel);
        archive.validate();
        return new BlockTextureSet(models, failures, atlas, archive.fingerprint(), limited,notes,area,rules,contextDependencies,diagnostics);
    }

    private static List<Float> atlasUv(List<Float> quadUv, TextureAtlas.Tile tile, TextureAtlas atlas) {
        List<Float> uv = new ArrayList<>(8);
        for (int i = 0; i < 4; i++) {
            uv.add((tile.x() + quadUv.get(i * 2) * tile.width()) / atlas.width());
            uv.add((tile.y() + quadUv.get(i * 2 + 1) * tile.height()) / atlas.height());
        }
        return uv;
    }

    private static final class PreparationFailure extends IOException {
        private final String code;
        PreparationFailure(String code,String detail){super(detail);this.code=code;}
    }
    private static String trace(BlockResourceSource source,BlockModelResolver.Result result) {
        var reference=result.sources().stream().filter(item->item.kind()==ResourceLocation.Kind.MODEL).findFirst().or(()->result.sources().stream().findFirst());
        if(reference.isEmpty())return "";var entry=reference.orElseThrow();
        return "（资源 "+entry.id()+"；来源 "+source.origin(entry.kind(),entry.id()).orElse("未记录或缺失对应来源")+"）";
    }

    private static BufferedImage image(BlockResourceSource archive, ResourceLocation id,
                                        Map<ResourceLocation, BufferedImage> images) throws IOException {
        BufferedImage image = images.get(id);
        if (image == null) {
            if (images.size() >= TextureAtlas.MAX_TILES) throw new PreparationFailure("LIMIT_EXCEEDED","解码纹理数量超限");
            byte[] data = archive.read(ResourceLocation.Kind.TEXTURE, id)
                    .orElseThrow(() -> new PreparationFailure("MISSING_TEXTURE","缺少 PNG：" + id));
            image = TextureAtlas.decodeResource(data,archive.readJson(ResourceLocation.Kind.TEXTURE_META,id));
            long decoded=images.values().stream().mapToLong(value->(long)value.getWidth()*value.getHeight()*4).sum();
            if(decoded+(long)image.getWidth()*image.getHeight()*4>64L*1024*1024)throw new PreparationFailure("LIMIT_EXCEEDED","解码纹理内存超过 64 MiB 预算");
            images.put(id, image);
        }
        return image;
    }

    static BufferedImage composite(BufferedImage base, int tint, BufferedImage overlay, int overlayTint) throws IOException {
        if (unboundTintIndex(tint) || overlay != null && unboundTintIndex(overlayTint))
            throw new IOException("未绑定的 tintindex 不能直接作为颜色烘焙");
        int w=overlay==null?base.getWidth():Math.max(base.getWidth(),overlay.getWidth());
        int h=overlay==null?base.getHeight():Math.max(base.getHeight(),overlay.getHeight());
        BufferedImage output = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] pixels=((java.awt.image.DataBufferInt)output.getRaster().getDataBuffer()).getData();
        int[] baseRow=new int[base.getWidth()];
        int[] overlayRow=overlay==null?null:new int[overlay.getWidth()];
        for(int y=0;y<h;y++){
            base.getRGB(0,y*base.getHeight()/h,baseRow.length,1,baseRow,0,baseRow.length);
            if(overlay!=null)overlay.getRGB(0,y*overlay.getHeight()/h,overlayRow.length,1,overlayRow,0,overlayRow.length);
            for(int x=0;x<w;x++){
                int bottom=tinted(baseRow[x*baseRow.length/w],tint);
                if(overlay==null){pixels[y*w+x]=bottom;continue;}
                int top=tinted(overlayRow[x*overlayRow.length/w],overlayTint),a=top>>>24,bottomAlpha=bottom>>>24;
                int alpha=a+(bottomAlpha*(255-a)+127)/255;
                int rgb=alpha<<24;
                for(int shift=0;shift<=16;shift+=8) {
                    int premultiplied=((top>>>shift)&255)*a+(((bottom>>>shift)&255)*bottomAlpha*(255-a)+127)/255;
                    rgb|=(alpha==0?0:(premultiplied+alpha/2)/alpha)<<shift;
                }
                pixels[y*w+x]=rgb;
            }
        }
        return output;
    }
    // Matches the fixed direction/ambient balance in voxel.frag. Baking before overlays keeps
    // an emissive transparent layer from brightening the underlying material or z-fighting it.
    static double directionalBrightness(List<Float> normal) {
        double x=normal.get(0),y=normal.get(1),z=normal.get(2),length=Math.sqrt(x*x+y*y+z*z);
        return .55+.45*Math.max(0,Math.min(1,(-x+y-.75*z)/(length*Math.sqrt(2.5625))));
    }
    private static double overrideBrightness(BlockModelResolver.Direction direction) {
        return switch(direction){case UP->1;case DOWN->.5;case NORTH,SOUTH->.8;case WEST,EAST->.6;};
    }
    private static void applyMaterial(BufferedImage image,int argb,float opacity,double brightness) {
        if(argb==0xFFFFFFFF&&opacity==1&&brightness==1)return;
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++) {
            int color=image.getRGB(x,y),result=Math.round((color>>>24)*(argb>>>24)/255f*opacity)<<24;
            for(int shift=0;shift<=16;shift+=8)result|=(int)Math.round(((color>>>shift)&255)*((argb>>>shift)&255)/255d*brightness)<<shift;
            image.setRGB(x,y,result);
        }
    }
    private static boolean unboundTintIndex(int tint) {
        return tint >= 0 && tint < EXPLICIT_TINT_MARKER;
    }
    private static int tinted(int color, int tint) {
        if (tint < 0) return color;
        int colorTint=tint&0xFFFFFF;
        int result = color & 0xFF000000;
        for (int shift=0;shift<=16;shift+=8) result |= (((color >>> shift) & 255) * ((colorTint >>> shift) & 255) / 255) << shift;
        return result;
    }

    private static String imageKey(BufferedImage image) {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            int width=image.getWidth();
            int[] row=new int[width];
            byte[] bytes=new byte[width*4];
            for(int y=0;y<image.getHeight();y++) {
                image.getRGB(0,y,width,1,row,0,width);
                for(int x=0;x<width;x++)
                    for(int channel=0;channel<4;channel++) bytes[x*4+channel]=(byte)(row[x]>>>(channel*8));
                digest.update(bytes);
            }
            return image.getWidth()+"x"+image.getHeight()+":"+java.util.HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static boolean isSolidRenderType(String renderType) {
        return renderType == null || "minecraft:solid".equals(renderType);
    }
    private static RenderLayer material(BufferedImage image, BufferedImage overlayImage, String renderType, String overlayRenderType,
                                        TextureAtlas.Tile tile, TextureAtlas.Tile overlayTile,
                                        Map<TextureAtlas.Tile, RenderLayer> alphaLayers) {
        RenderLayer base = singleMaterial(image, renderType, tile, alphaLayers);
        if (overlayImage == null || overlayTile == null) return base;
        RenderLayer overlay = singleMaterial(overlayImage, overlayRenderType, overlayTile, alphaLayers);
        if (base == RenderLayer.FLUID || overlay == RenderLayer.FLUID) return RenderLayer.FLUID;
        if (base == RenderLayer.OPAQUE || overlay == RenderLayer.OPAQUE) return RenderLayer.OPAQUE;
        if (base == RenderLayer.TRANSLUCENT || overlay == RenderLayer.TRANSLUCENT) return RenderLayer.TRANSLUCENT;
        return RenderLayer.CUTOUT;
    }
    private static RenderLayer singleMaterial(BufferedImage image, String renderType, TextureAtlas.Tile tile,
                                              Map<TextureAtlas.Tile, RenderLayer> alphaLayers) {
        if ("mcwe:lava".equals(renderType)) return RenderLayer.OPAQUE;
        if ("mcwe:fluid".equals(renderType)) return com.mcworldexplorer.voxel.mesh.RenderLayer.FLUID;
        if ("minecraft:translucent".equals(renderType)) return com.mcworldexplorer.voxel.mesh.RenderLayer.TRANSLUCENT;
        if (renderType != null && !renderType.equals("minecraft:solid")) return com.mcworldexplorer.voxel.mesh.RenderLayer.CUTOUT;
        return alphaLayers.computeIfAbsent(tile, ignored -> alphaLayer(image));
    }
    private static RenderLayer alphaLayer(BufferedImage image){
        boolean cutout=false;
        for(int pixel:image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth())) {
            int a=pixel>>>24;
            if(a>0&&a<255)return com.mcworldexplorer.voxel.mesh.RenderLayer.TRANSLUCENT;
            if(a==0)cutout=true;
        }
        return cutout?com.mcworldexplorer.voxel.mesh.RenderLayer.CUTOUT:com.mcworldexplorer.voxel.mesh.RenderLayer.OPAQUE;
    }
    private static void check(CancellationSignal cancel) {
        if (cancel.isCancelled() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
    }
}
