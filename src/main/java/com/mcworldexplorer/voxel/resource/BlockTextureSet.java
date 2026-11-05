package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;
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
    // Existing built-in models encode an explicit RGB tint above this marker.
    // Ordinary JSON tintindex values are handler indices, not colours.
    private static final int EXPLICIT_TINT_MARKER = 0x1000000;
    private static final int MAX_DIAGNOSTIC_TINT_INDICES = 8;
    public record Surface(Direction direction, List<Float> positions, List<Float> uv,
                          List<Float> normal, Direction cullFace, boolean shade,
                          com.mcworldexplorer.voxel.mesh.RenderLayer layer, int element) {
        public Surface { positions=List.copyOf(positions);uv=List.copyOf(uv);normal=List.copyOf(normal); }
    }
    private record Pending(ModelGeometry.Quad quad, TextureAtlas.Tile tile, boolean shade,
                           com.mcworldexplorer.voxel.mesh.RenderLayer layer, int element) { }
    private record Painted(ModelGeometry.Quad quad, BufferedImage image, boolean shade, int element, String renderType) { }
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
        return new BlockState(state.name(),properties);
    }

    public List<Surface> surfaces(BlockState state) { return models.get(state); }
    /** Returns the actual prepared identity, including declared context dependencies. */
    public BlockState renderState(BlockState state,long x,int y,long z) {
        if(contextArea==null||!contextualNames.contains(state.name()))return tintPreparation.state(state,x,y,z);
        var context=new ModelBlockContext(contextArea,new ModelBlockContext.Position(x,y,z),CancellationSignal.NONE);
        state=visualState(state,context.at(0,0,0).entity().orElse(null));
        var dependencies=contextDependencies.get(state);return tintPreparation.state(dependencies==null?state:contextKey(state,context,dependencies),x,y,z);
    }
    static BlockState contextKey(BlockState state,ModelBlockContext context,BlockModelResolver.Dependencies dependencies) {
        Map<String,String> properties=new TreeMap<>(state.properties());properties.put("__mcwe_context",context.signature(dependencies.neighbors(),dependencies.positionDependent(),dependencies.biomeColorDependent()));
        return new BlockState(state.name(),properties);
    }
    public String fallbackReason(BlockState state) {
        return failures.getOrDefault(state, stateLimitReached
                ? "本次不同方块状态超过 " + MAX_STATES + " 项准备上限" : "此状态未参与本次资源准备");
    }

    public static BlockTextureSet load(BlockResourceSource archive,
            VoxelAreaLoadResult area, CancellationSignal cancel)
            throws IOException {return load(archive,area,cancel,ModelRuleRegistry.defaults());}
    public static BlockTextureSet load(BlockResourceSource archive,VoxelAreaLoadResult area,CancellationSignal cancel,ModelRuleRegistry rules)throws IOException {
        Set<BlockState> states = new LinkedHashSet<>();
        Map<String,com.mcworldexplorer.voxel.data.BlockEntityData> entities=new HashMap<>();
        collect:
        for (var coordinate : area.results().keySet()) {
            check(cancel);
            var result = area.result(coordinate);
            if (result.chunk() == null) continue;
            try {
                for (var section : result.chunk().sections()) {
                    for (int y = 0; y < 16; y++) {
                        check(cancel);
                        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            long wx=(long)coordinate.x()*16+x,wz=(long)coordinate.z()*16+z;
                            if(wx<(long)area.target().minX()*16-1||wx>(long)area.target().maxX()*16+16
                                    ||wz<(long)area.target().minZ()*16-1||wz>(long)area.target().maxZ()*16+16)continue;
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
        archive=new BuiltinBlockModels(archive,entities);
        var resolver=new BlockModelResolver(archive,rules);
        Map<BlockState,BlockModelResolver.Result> resolved=new LinkedHashMap<>();Set<String> contextual=new HashSet<>();Map<BlockState,BlockModelResolver.Dependencies> dependencies=new HashMap<>();
        int[] retainedFaces={32768};
        for(var state:states){check(cancel);if(resolved.size()>=MAX_STATES)break;var result=retain(resolver.resolve(state),retainedFaces);resolved.put(state,result);if(result.reason()==BlockModelResolver.Reason.CONTEXT_REQUIRED){contextual.add(state.name());dependencies.put(state,result.dependencies());}}
        if(!contextual.isEmpty()) {
            contexts:
            for(var coordinate:area.results().keySet()) {
                var loaded=area.result(coordinate);if(loaded.chunk()==null)continue;
                try {
                    for(var section:loaded.chunk().sections())for(int y=0;y<16;y++) {
                        check(cancel);
                        for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                            var physical=section.blockState(x,y,z);if(!contextual.contains(physical.name()))continue;
                            long wx=(long)coordinate.x()*16+x,wz=(long)coordinate.z()*16+z;
                            if(wx<(long)area.target().minX()*16-1||wx>(long)area.target().maxX()*16+16||wz<(long)area.target().minZ()*16-1||wz>(long)area.target().maxZ()*16+16)continue;
                            int wy=section.sectionY()*16+y;var entity=loaded.chunk().blockEntity(wx,wy,wz).orElse(null);
                            var visual=visualState(physical,entity);var context=new ModelBlockContext(area,new ModelBlockContext.Position(wx,wy,wz),cancel);var dependency=dependencies.get(visual);if(dependency==null)continue;var key=contextKey(visual,context,dependency);
                            if(states.contains(key))continue;
                            states.add(key);if(states.size()>MAX_STATES)break contexts;
                            var actual=resolver.resolve(visual,context);
                            if(!dependency.neighbors().containsAll(actual.dependencies().neighbors())||actual.dependencies().positionDependent()&&!dependency.positionDependent()||actual.dependencies().biomeColorDependent()&&!dependency.biomeColorDependent())
                                actual=new BlockModelResolver.Result(BlockModelResolver.Reason.INVALID_MODEL,"父规则未声明所选子规则的上下文依赖",Optional.empty(),actual.sources(),actual.fingerprint(),actual.dependencies(),actual.loaders());
                            resolved.put(key,retain(actual,retainedFaces));
                        }
                    }
                }catch(ChunkDecodeException corrupt){/* Mesher diagnoses the unavailable chunk. */}
            }
        }
        var tints=BlockTintPreparation.prepare((BuiltinBlockModels)archive,area,cancel,states,resolved,dependencies);
        var prepared=loadPrepared(archive,states,cancel,rules,resolved,area,dependencies);
        prepared.tintPreparation=tints;return prepared;
    }
    private static BlockModelResolver.Result retain(BlockModelResolver.Result result,int[] budget) {
        if(!result.resolved())return result;
        int count=result.model().orElseThrow().faces().size();
        if(count>budget[0])return new BlockModelResolver.Result(BlockModelResolver.Reason.LIMIT_EXCEEDED,"区域模型暂存超过32768面预算",Optional.empty(),result.sources(),result.fingerprint(),result.dependencies(),result.loaders());
        budget[0]-=count;return result;
    }

    public static BlockTextureSet load(BlockResourceSource archive, Collection<BlockState> requested,
                                       CancellationSignal cancel) throws IOException {return load(archive,requested,cancel,ModelRuleRegistry.defaults());}
    public static BlockTextureSet load(BlockResourceSource archive,Collection<BlockState> requested,CancellationSignal cancel,ModelRuleRegistry rules)throws IOException {
        return loadPrepared(archive,requested,cancel,rules,Map.of(),null,Map.of());
    }
    private static BlockTextureSet loadPrepared(BlockResourceSource archive,Collection<BlockState> requested,CancellationSignal cancel,ModelRuleRegistry rules,
            Map<BlockState,BlockModelResolver.Result> resolved,VoxelAreaLoadResult area,Map<BlockState,BlockModelResolver.Dependencies> contextDependencies)throws IOException {
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
        var tintResolver=new BlockTintResolver(archive);
        var builder = new TextureAtlas.Builder();
        Map<ResourceLocation, BufferedImage> images = new HashMap<>();
        Map<BlockState, List<Pending>> pending = new LinkedHashMap<>();
        Map<BlockState, String> failures = new HashMap<>();
        Map<BlockState,List<String>> notes=new HashMap<>();
        Map<BlockState,List<BlockResourceDiagnostic>> diagnostics=new HashMap<>();
        int faceBudget = 32768;
        for (BlockState state : states) {
            check(cancel);
            var result = resolved.get(state);if(result==null)result=resolver.resolve(state);
            if (!result.resolved()) {
                failures.put(state, result.reason() + ": " + result.detail()+trace(archive,result));
                diagnostics.put(state,List.of(BlockResourceDiagnostic.capture(state,BlockResourceDiagnostic.Severity.FAILURE,result.reason().name(),result.detail(),archive,result)));continue;
            }
            if(area==null){var tint=tintResolver.colors(state,result.model().orElseThrow(),null);result=BlockTintResolver.apply(result,tint);BlockTintPreparation.note((BuiltinBlockModels)archive,state,tint.detail());}
            var model = result.model().orElseThrow();
            var logicalProperties=new TreeMap<>(state.properties());logicalProperties.remove("__mcwe_context");
            var stateNotes=new ArrayList<>(((BuiltinBlockModels)archive).notes(new BlockState(state.name(),logicalProperties)));
            if(model.fixedFirstAlternative())stateNotes.add("随机候选模型固定选择第一项");
            boolean bakeLighting=model.faces().stream().anyMatch(face->face.material().minimumLight()>0||face.material().shadeDirectionOverride()!=null);
            if(bakeLighting)stateNotes.add("面亮度与方向覆盖应用于静态光照；未模拟世界光照、昼夜变化与环境遮蔽");
            if(!stateNotes.isEmpty())notes.put(state,List.copyOf(stateNotes));
            String unboundNote=null;
            try {
                if(model.faces().size()>faceBudget) throw new PreparationFailure("LIMIT_EXCEEDED","本次模型面准备超过 32768 项预算");
                Map<String,Painted> painted = new LinkedHashMap<>();
                Map<String,BufferedImage> tintedImages=new HashMap<>();
                Set<Integer> unboundTintIndices = new TreeSet<>();
                long workingBytes=0;
                int elementBase=0;
                for(var part:model.parts()) {
                    for(var face:part.faces()) {
                        check(cancel);
                        var quad=ModelGeometry.bake(face,part);
                        if(quad.normal().stream().allMatch(v->Math.abs(v)<1e-6))continue;
                        int preparedTint = face.tintIndex();
                        if (unboundTintIndex(preparedTint)) {
                            unboundTintIndices.add(preparedTint);
                            preparedTint = -1;
                        }
                        BufferedImage sourceImage=image(archive,face.texture(),images);
                        long bytes=(long)sourceImage.getWidth()*sourceImage.getHeight()*4;
                        double brightness=bakeLighting?Math.max(face.material().minimumLight(),face.material().shadeDirectionOverride()!=null?overrideBrightness(face.material().shadeDirectionOverride()):face.shade()?directionalBrightness(quad.normal()):1):1;
                        String renderType=face.material().forceTranslucent()?"minecraft:translucent":part.renderType();
                        boolean shaded=bakeLighting?false:face.shade();
                        String paintKey=face.texture()+"|"+preparedTint+"|"+face.opacity()+"|"+face.material().argb()+"|"+brightness;
                        BufferedImage tinted=tintedImages.get(paintKey);
                        if(tinted==null){
                            if(workingBytes+bytes>32L*1024*1024)throw new PreparationFailure("LIMIT_EXCEEDED","单状态面纹理工作内存超过 32 MiB 预算");
                            tinted=composite(sourceImage,preparedTint,null,-1);
                            applyMaterial(tinted,face.material().argb(),face.opacity(),brightness);
                            tintedImages.put(paintKey,tinted);workingBytes+=bytes;
                        }
                        for(var tiled:UvTiling.split(quad)) {
                        BufferedImage base=tinted;quad=tiled;
                        String key=quad.positions()+"|"+quad.normal()+"|"+quad.uv()+"|"+shaded+"|"+renderType;
                        var previous=painted.get(key);
                        if(previous!=null){
                            long combinedBytes=(long)Math.max(previous.image().getWidth(),base.getWidth())*Math.max(previous.image().getHeight(),base.getHeight())*4;
                            if(workingBytes+combinedBytes>32L*1024*1024)throw new PreparationFailure("LIMIT_EXCEEDED","单状态面纹理工作内存超过 32 MiB 预算");
                            base=composite(previous.image(),-1,base,-1);workingBytes+=combinedBytes;
                        }
                        painted.put(key,new Painted(quad,base,shaded,previous==null?elementBase+face.layer():previous.element(),renderType));
                        if(painted.size()>faceBudget)throw new PreparationFailure("LIMIT_EXCEEDED","UV 展开后的模型面超过本次 32768 面预算");
                        }
                    }
                    elementBase+=part.faces().stream().mapToInt(BlockModelResolver.Face::layer).max().orElse(-1)+1;
                }
                List<Pending> surfaces=new ArrayList<>();
                Map<BufferedImage,TextureAtlas.Tile> stateTiles=new IdentityHashMap<>();
                for(var paint:painted.values()) {
                    var tile=stateTiles.get(paint.image());
                    if(tile==null){tile=builder.add(imageKey(paint.image()),paint.image());stateTiles.put(paint.image(),tile);}
                    surfaces.add(new Pending(paint.quad(),tile,paint.shade(),material(paint.image(),paint.renderType()),paint.element()));
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
                for(String note:stateNotes)stateDiagnostics.add(BlockResourceDiagnostic.capture(state,
                    note.equals(unboundNote)?BlockResourceDiagnostic.Severity.DEGRADED:BlockResourceDiagnostic.Severity.INFO,
                    note.equals(unboundNote)?"UNBOUND_TINT":"MODEL_NOTE",note,archive,result));
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
        TextureAtlas atlas=builder.build();
        Map<BlockState,List<Surface>> models=new HashMap<>();
        for(var entry:pending.entrySet()) {
            List<Surface> faces=new ArrayList<>();
            for(var p:entry.getValue()) {
                List<Float> uv=new ArrayList<>(8);
                for(int i=0;i<4;i++) {
                    uv.add((p.tile().x()+p.quad().uv().get(i*2)*p.tile().width())/atlas.width());
                    uv.add((p.tile().y()+p.quad().uv().get(i*2+1)*p.tile().height())/atlas.height());
                }
                faces.add(new Surface(p.quad().direction(),p.quad().positions(),uv,p.quad().normal(),
                        p.quad().cull(),p.shade(),p.layer(),p.element()));
            }
            models.put(entry.getKey(),List.copyOf(faces));
        }
        check(cancel);
        archive.validate();
        return new BlockTextureSet(models, failures, atlas, archive.fingerprint(), limited,notes,area,rules,contextDependencies,diagnostics);
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
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int bottom = tinted(base.getRGB(x*base.getWidth()/w, y*base.getHeight()/h), tint);
            if (overlay == null) { output.setRGB(x, y, bottom); continue; }
            int top=tinted(overlay.getRGB(x*overlay.getWidth()/w,y*overlay.getHeight()/h),overlayTint),a=top>>>24,bottomAlpha=bottom>>>24;
            int alpha=a+(bottomAlpha*(255-a)+127)/255;
            int rgb=alpha<<24;
            for(int shift:new int[]{0,8,16}) {
                int premultiplied=((top>>>shift)&255)*a+(((bottom>>>shift)&255)*bottomAlpha*(255-a)+127)/255;
                rgb|=(alpha==0?0:(premultiplied+alpha/2)/alpha)<<shift;
            }
            output.setRGB(x, y, rgb);
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
        for (int shift : new int[]{0, 8, 16}) result |= (((color >>> shift) & 255) * ((colorTint >>> shift) & 255) / 255) << shift;
        return result;
    }

    private static String imageKey(BufferedImage image) {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            for(int pixel:image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth()))
                for(int shift:new int[]{0,8,16,24})digest.update((byte)(pixel>>>shift));
            return image.getWidth()+"x"+image.getHeight()+":"+java.util.HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static com.mcworldexplorer.voxel.mesh.RenderLayer material(BufferedImage image,String renderType) {
        if("mcwe:fluid".equals(renderType))return com.mcworldexplorer.voxel.mesh.RenderLayer.FLUID;
        if("minecraft:translucent".equals(renderType))return com.mcworldexplorer.voxel.mesh.RenderLayer.TRANSLUCENT;
        if(renderType!=null&&!renderType.equals("minecraft:solid"))return com.mcworldexplorer.voxel.mesh.RenderLayer.CUTOUT;
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
