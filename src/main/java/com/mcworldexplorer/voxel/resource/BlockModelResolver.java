package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mcworldexplorer.voxel.data.BlockState;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;

/** Bounded, CPU-only resolver for the standard block models and renderer-family adapters. Does not own its archive. */
public final class BlockModelResolver {
    public static final int MAX_PARENT_DEPTH = 32;
    public static final int MAX_JSON_NODES = 16_384;
    public static final int MAX_VARIANTS = 256;
    public static final int MAX_STATE_BRANCHES = 4096;
    public static final int MAX_ELEMENTS = 256;
    public static final int MAX_TEXTURES = 128;
    static final Set<String> MULTIPART_FIELDS=ModelDefinitionProtocol.Scope.MULTIPART.fields;

    public enum Reason { RESOLVED, MISSING_RESOURCE, INVALID_JSON,
        INVALID_MODEL, UNSUPPORTED_MODEL, CONTEXT_REQUIRED, NO_VARIANT, AMBIGUOUS_VARIANT, REFERENCE_CYCLE, LIMIT_EXCEEDED }
    public enum Direction { DOWN, UP, NORTH, SOUTH, WEST, EAST }
    public record Uv(double u0, double v0, double u1, double v1) { }
    public record Vec3(double x, double y, double z) { }
    public record ElementRotation(Vec3 origin, String axis, double angle, boolean rescale) { }
    public record TexCoord(double u,double v) { }
    public record Face(Direction direction, ResourceLocation texture, Uv uv, int rotation,
                       int tintIndex, Direction cullFace, int layer, Vec3 from, Vec3 to,
                       ElementRotation elementRotation, boolean shade,List<Vec3> vertices,List<TexCoord> vertexUv,float opacity,FaceMaterial material) {
        public Face(Direction direction,ResourceLocation texture,Uv uv,int rotation,int tintIndex,Direction cullFace,int layer,Vec3 from,Vec3 to,ElementRotation elementRotation,boolean shade,List<Vec3> vertices,List<TexCoord> vertexUv,float opacity) {
            this(direction,texture,uv,rotation,tintIndex,cullFace,layer,from,to,elementRotation,shade,vertices,vertexUv,opacity,FaceMaterial.DEFAULT);
        }
        public Face(Direction direction,ResourceLocation texture,Uv uv,int rotation,int tintIndex,Direction cullFace,int layer,Vec3 from,Vec3 to,ElementRotation elementRotation,boolean shade) {
            this(direction,texture,uv,rotation,tintIndex,cullFace,layer,from,to,elementRotation,shade,List.of(),List.of(),1);
        }
        public Face {vertices=List.copyOf(vertices);vertexUv=List.copyOf(vertexUv);material=Objects.requireNonNull(material);}
    }
    public record Part(ResourceLocation id, int xRotation, int yRotation, boolean uvLock, List<Face> faces, String renderType, Vec3 offset) {
        public Part(ResourceLocation id,int x,int y,boolean uvLock,List<Face> faces,String renderType) {
            this(id,x,y,uvLock,faces,renderType,new Vec3(0,0,0));
        }
        public Part { faces = List.copyOf(faces); }
    }
    public record Model(ResourceLocation id, int xRotation, int yRotation, boolean uvLock,
                        List<Face> faces, boolean fixedFirstAlternative, List<Part> parts) {
        public Model { faces = List.copyOf(faces); parts = List.copyOf(parts); }
    }
    public record Source(ResourceLocation.Kind kind, ResourceLocation id) { }
    public record Dependencies(Set<ModelBlockContext.Offset> neighbors,boolean positionDependent,boolean biomeColorDependent) {
        public Dependencies(Set<ModelBlockContext.Offset> neighbors,boolean positionDependent){this(neighbors,positionDependent,false);}
        public Dependencies {neighbors=Set.copyOf(neighbors);positionDependent|=biomeColorDependent;}
        static final Dependencies NONE=new Dependencies(Set.of(),false);
    }
    public record Result(Reason reason, String detail, Optional<Model> model, List<Source> sources,
                         ClientResourceArchive.Fingerprint fingerprint,Dependencies dependencies,Set<ResourceLocation> loaders) {
        public Result(Reason reason,String detail,Optional<Model> model,List<Source> sources,ClientResourceArchive.Fingerprint fingerprint){this(reason,detail,model,sources,fingerprint,Dependencies.NONE,Set.of());}
        public Result(Reason reason,String detail,Optional<Model> model,List<Source> sources,ClientResourceArchive.Fingerprint fingerprint,Dependencies dependencies){this(reason,detail,model,sources,fingerprint,dependencies,Set.of());}
        public Result { sources = List.copyOf(sources);loaders=Set.copyOf(loaders); }
        public boolean resolved() { return reason == Reason.RESOLVED; }
    }

    private final BlockResourceSource archive;
    private final ModelRuleRegistry rules;
    private final SavedBiomeColors biomeColors=new SavedBiomeColors();
    private record CachedJson(JsonObject value,int nodes,int chars) { }
    private final Map<Source,CachedJson> parsedJson=new LinkedHashMap<>(16,.75f,true);
    private int cachedNodes;
    private int cachedChars;
    private static final int MAX_CACHED_NODES=131_072;
    private static final int MAX_CACHED_CHARS=8*1024*1024;
    private record StateReference(BlockState state,ResourceLocation model) { }
    private static final class ResolutionContext {
        final int baseDepth;final Set<StateReference> stateChain;
        final Set<ResourceLocation> loaders=new LinkedHashSet<>();
        final ModelBlockContext blockContext;final Set<ModelBlockContext.Offset> neighbors=new HashSet<>();boolean positionDependent,biomeColorDependent,required;
        ResolutionContext(ModelBlockContext value,int baseDepth,Set<StateReference> stateChain){blockContext=value;this.baseDepth=baseDepth;this.stateChain=stateChain;}
        Dependencies dependencies(){return new Dependencies(neighbors,positionDependent,biomeColorDependent);}
    }


    public BlockModelResolver(BlockResourceSource archive) {this(archive,ModelRuleRegistry.defaults());}
    public BlockModelResolver(BlockResourceSource archive,ModelRuleRegistry rules) {
        this.archive=archive instanceof BuiltinBlockModels?archive:new BuiltinBlockModels(Objects.requireNonNull(archive),Map.of());this.rules=Objects.requireNonNull(rules);
    }

    /** Semantic failures return a traceable fallback; archive I/O/lifecycle failures propagate unchanged. */
    public Result resolve(BlockState state) throws IOException {
        return resolve(state, null);
    }
    public Result resolve(BlockState state, ModelBlockContext blockContext) throws IOException {
        return resolve(state,blockContext,0,new HashSet<>());
    }
    private Result resolve(BlockState state,ModelBlockContext blockContext,int baseDepth,Set<StateReference> stateChain)throws IOException {
        return resolve(state,blockContext,baseDepth,stateChain,null);
    }
    private Result resolve(BlockState state,ModelBlockContext blockContext,int baseDepth,Set<StateReference> stateChain,ResourceLocation rootModel)throws IOException {
        var context=new ResolutionContext(blockContext,baseDepth,stateChain);
        Objects.requireNonNull(state);var reference=new StateReference(state,rootModel);boolean entered=false;
        archive.validate();
        List<Source> sources = new ArrayList<>();
        try {
            if(baseDepth>=MAX_PARENT_DEPTH)throw fail(Reason.LIMIT_EXCEEDED,"状态/组合原型引用超过 32 层");
            if(!stateChain.add(reference))throw fail(Reason.REFERENCE_CYCLE,"状态原型引用循环："+state.name());
            entered=true;
            ResourceLocation block = id(state.name());
            JsonObject root;JsonObject ruleDefinition=null;
            if(rootModel!=null){
                root=new JsonObject();var selected=new JsonObject();selected.addProperty("model",rootModel.toString());var variants=new JsonObject();variants.add("",selected);root.add("variants",variants);
            }else root=read(ResourceLocation.Kind.BLOCKSTATE,block,sources);
            for(String selector:rules.stateSelectors())if(root.has(selector)) {
                if(ruleDefinition!=null)throw fail(Reason.INVALID_MODEL,"blockstate规则选择声明冲突");
                var loader=id(string(root.get(selector)));context.loaders.add(loader);var rule=rules.find(loader).orElse(null);
                if(rule==null||!rule.stateSelectors().contains(selector))throw fail(Reason.UNSUPPORTED_MODEL,"尚未支持的blockstate规则："+loader);
                ruleDefinition=root.deepCopy();ruleDefinition.remove(selector);ruleDefinition.addProperty("loader",loader.toString());
            }
            if(ruleDefinition==null)fields(root,ModelDefinitionProtocol.Scope.BLOCKSTATE);
            if (ruleDefinition==null&&root.has("variants") == root.has("multipart"))
                throw fail(Reason.INVALID_MODEL, "blockstate 必须恰含 variants 或 multipart");
            List<JsonElement> applications = new ArrayList<>();
            if (ruleDefinition==null&&root.has("variants")) {
                JsonObject variants = object(root.get("variants"));
                if (variants.size() > MAX_STATE_BRANCHES) throw fail(Reason.LIMIT_EXCEEDED, "blockstate 分支过多");
                for (var entry : variants.entrySet()) if (matches(entry.getKey(), state.properties())) {
                    if (!applications.isEmpty()) throw fail(Reason.AMBIGUOUS_VARIANT, "多个 blockstate 分支匹配");
                    applications.add(entry.getValue());
                }
            } else if(ruleDefinition==null) {
                if (!root.get("multipart").isJsonArray()) throw fail(Reason.INVALID_MODEL, "multipart 必须为数组");
                var multipart = root.getAsJsonArray("multipart");
                if (multipart.size() > MAX_VARIANTS) throw fail(Reason.LIMIT_EXCEEDED, "multipart 部件过多");
                for (var value : multipart) {
                    var item = object(value);
                    fields(item,ModelDefinitionProtocol.Scope.MULTIPART);
                    if (!item.has("when") || condition(item.get("when"), state.properties(), 0))
                        applications.add(item.get("apply"));
                }
            }
            if (applications.isEmpty()) {
                if(ruleDefinition==null&&root.has("variants"))throw fail(Reason.NO_VARIANT,"没有匹配属性的模型分支");
            }
            List<Part> parts = new ArrayList<>();
            if(ruleDefinition!=null)parts.addAll(resolveParts(block,ruleDefinition,state,0,0,false,0,new LinkedHashSet<>(),sources,Map.of(),context));
            boolean fixed = false;
            for (JsonElement application : applications) {
                var selected=application(application,state,0,new LinkedHashSet<>(),sources,Map.of(),context);
                parts.addAll(selected.parts());fixed|=selected.fixed();
                if(parts.stream().mapToInt(part->part.faces().size()).sum()>8192)throw fail(Reason.LIMIT_EXCEEDED,"组合模型面数超过 8192");
            }
            if(parts.isEmpty())parts.add(new Part(block,0,0,false,List.of(),null));
            if(baseDepth==0&&"true".equals(state.properties().get("waterlogged"))) {
                var water=resolve(new BlockState("minecraft:water",Map.of("level","0")),blockContext,baseDepth+1,stateChain);
                context.loaders.addAll(water.loaders());context.neighbors.addAll(water.dependencies().neighbors());context.positionDependent|=water.dependencies().positionDependent();context.biomeColorDependent|=water.dependencies().biomeColorDependent();context.required|=water.reason()==Reason.CONTEXT_REQUIRED;
                if(water.resolved()) {
                    parts.addAll(water.model().orElseThrow().parts());sources.addAll(water.sources());
                    ((BuiltinBlockModels)archive).note(state,"含水模型叠加静态水面，水色与流向使用固定近似");
                }else ((BuiltinBlockModels)archive).note(state,"含水模型的水资源不可用："+water.detail());
            }
            Part first = parts.getFirst();
            var allFaces = parts.stream().flatMap(part -> part.faces().stream()).toList();
            if (allFaces.size() > 8192) throw fail(Reason.LIMIT_EXCEEDED, "组合模型面数超过 8192");
            if(context.required)return new Result(Reason.CONTEXT_REQUIRED,"规则需要保存实体或位置/邻域上下文",Optional.empty(),sources,archive.fingerprint(),context.dependencies(),context.loaders);
            return new Result(Reason.RESOLVED, fixed ? "已解析；随机候选固定选择第一项" : "已解析",
                    Optional.of(new Model(first.id(), first.xRotation(), first.yRotation(), first.uvLock(), allFaces, fixed, parts)),
                    sources, archive.fingerprint(),context.dependencies(),context.loaders);
        } catch (Failure failure) {
            return new Result(failure.reason, failure.getMessage(), Optional.empty(), sources, archive.fingerprint(),context.dependencies(),context.loaders);
        } finally { if(entered)stateChain.remove(reference);archive.validate(); }
    }

    private record Application(List<Part> parts,boolean fixed) { }
    private Application application(JsonElement application,BlockState state,int depth,Set<ResourceLocation> chain,List<Source> sources,Map<String,TextureBinding> textures,ResolutionContext context)throws IOException,Failure {
        if(application==null)throw fail(Reason.INVALID_MODEL,"部件缺少apply");boolean fixed=false;
        if(application.isJsonArray()) {
            var choices=application.getAsJsonArray();if(choices.isEmpty()||choices.size()>MAX_VARIANTS)throw fail(Reason.LIMIT_EXCEEDED,"候选模型数量无效或超限");
            application=choices.get(0);fixed=true;
        }
        var variant=object(application);fields(variant,ModelDefinitionProtocol.Scope.VARIANT);
        var model=id(string(variant.get("model")));int x=variantRotation(variant,"x"),y=variantRotation(variant,"y");
        if(variant.has("weight")&&integer(variant.get("weight"))<=0)throw fail(Reason.INVALID_MODEL,"weight必须为正整数");
        return new Application(resolveParts(model,null,state,x,y,compatibleBoolean(variant,"uvlock",false),depth,chain,sources,textures,context),fixed);
    }

    private static final Set<String> MODEL_FIELDS=ModelDefinitionProtocol.Scope.MODEL.fields;
    private JsonObject inherit(ResourceLocation model,Set<ResourceLocation> chain,List<Source> sources,ResolutionContext resolution)throws IOException,Failure {
        if(model.namespace().equals("minecraft")&&model.path().startsWith("builtin/"))throw fail(Reason.UNSUPPORTED_MODEL,"需要内置特殊渲染："+model);
        if(!chain.add(model))throw fail(Reason.REFERENCE_CYCLE,"父模型循环："+model);
        if(chain.size()>MAX_PARENT_DEPTH)throw fail(Reason.LIMIT_EXCEEDED,"父模型层数超过上限");
        var json=normalizeLoaderObjects(read(ResourceLocation.Kind.MODEL,model,sources),model);recordDeclaredLoaders(json,resolution);var parent=new JsonObject();
        if(json.has("parent"))parent=inherit(id(string(json.get("parent"))),chain,sources,resolution);
        chain.remove(model);return merge(parent,json);
    }
    private static void recordDeclaredLoaders(JsonObject definition,ResolutionContext resolution){
        for(String key:List.of("loader","porting_lib:loader"))if(definition.has(key))try{resolution.loaders.add(id(string(definition.get(key))));}
        catch(Failure invalid){/* Keep normal parsing responsible for malformed declarations. */}
    }
    private JsonObject normalizeLoaderObjects(JsonObject definition,ResourceLocation model)throws IOException,Failure {
        if(!ModelDefinitionProtocol.hasLoaderObject(definition))return definition;
        var ownerPlatform=archive.platform(model.namespace());
        String platform=ownerPlatform.isPresent()?ownerPlatform.orElseThrow():archive.platform().orElse("");
        try{return ModelDefinitionProtocol.normalizeLoaderObjects(definition,platform,archive.baseClientVersion().orElse(""));}
        catch(ModelRule.Failure invalid){throw fail(invalid.reason(),invalid.getMessage());}
    }
    private JsonObject merge(JsonObject parent,JsonObject child)throws Failure {
        Set<String> geometryFields=Set.of();
        for(String key:List.of("loader","porting_lib:loader"))if(parent.has(key)){
            var loader=id(string(parent.get(key)));var rule=rules.find(loader).orElse(null);
            if(rule==null)throw fail(Reason.UNSUPPORTED_MODEL,"尚未支持的模型 loader："+loader+"（父链）");
            if(parent.has("loader")&&parent.has("porting_lib:loader")&&!rules.equivalent(id(string(parent.get("loader"))),id(string(parent.get("porting_lib:loader")))))throw fail(Reason.INVALID_MODEL,"父模型 loader 声明冲突");
            geometryFields=rule.fields();
        }
        try{return ModelDefinitionProtocol.inherit(parent,child,geometryFields);}
        catch(ModelRule.Failure invalid){throw fail(invalid.reason(),invalid.getMessage());}
    }
    private List<Part> resolveParts(ResourceLocation model,JsonObject inline,BlockState state,int x,int y,boolean uvLock,
            int depth,Set<ResourceLocation> chain,List<Source> sources,Map<String,TextureBinding> inheritedTextures,ResolutionContext resolution)throws IOException,Failure {
        if(depth+resolution.baseDepth>=MAX_PARENT_DEPTH)throw fail(Reason.LIMIT_EXCEEDED,"状态/组合模型引用超过 32 层");
        ResourceLocation tracked=inline==null?model:inline.has("parent")?id(string(inline.get("parent"))):null;
        if(tracked!=null&&!chain.add(tracked))throw fail(Reason.REFERENCE_CYCLE,"组合模型引用循环："+tracked);
        try {
            JsonObject definition;
            if(inline==null)definition=inherit(model,new LinkedHashSet<>(),sources,resolution);
            else {
                var parent=tracked==null?new JsonObject():inherit(tracked,new LinkedHashSet<>(),sources,resolution);
                var normalized=normalizeLoaderObjects(inline,model);recordDeclaredLoaders(normalized,resolution);definition=merge(parent,normalized);
            }
            String loader=definition.has("loader")?string(definition.get("loader")):definition.has("porting_lib:loader")?string(definition.get("porting_lib:loader")):null;
            if(loader!=null)resolution.loaders.add(id(loader));
            if(definition.has("porting_lib:loader"))resolution.loaders.add(id(string(definition.get("porting_lib:loader"))));
            if(definition.has("loader")&&definition.has("porting_lib:loader")&&!rules.equivalent(id(loader),id(string(definition.get("porting_lib:loader")))))throw fail(Reason.INVALID_MODEL,"模型 loader 声明冲突");
            Set<String> allowed=new HashSet<>(MODEL_FIELDS);ModelRule rule=null;
            if(loader!=null) {
                allowed.add("loader");allowed.add("porting_lib:loader");
                rule=rules.find(id(loader)).orElse(null);
                if(rule==null)throw fail(Reason.UNSUPPORTED_MODEL,"尚未支持的模型 loader："+loader);
                allowed.addAll(rule.fields());
            }
            var rootMode=rule==null?ModelRule.RootTransformMode.ELEMENTS:rule.rootTransformMode();
            if(rootMode!=ModelRule.RootTransformMode.NONE)allowed.add("transform");
            fields(definition,rule==null?ModelDefinitionProtocol.Scope.MODEL:ModelDefinitionProtocol.Scope.CUSTOM_MODEL,allowed);compatibleBoolean(definition,"ambientocclusion",true);bool(definition,"ambient_occlusion",true);
            org.joml.Matrix4d rootTransform=null;
            if(definition.has("transform")&&rootMode!=ModelRule.RootTransformMode.NONE)try{rootTransform=RootModelTransform.read(definition.get("transform"));}catch(ModelRule.Failure invalid){throw fail(invalid.reason(),invalid.getMessage());}
            if(rootTransform!=null&&!rootTransform.equals(new org.joml.Matrix4d(),1e-12)) {
                String platform=archive.platform(model.namespace()).orElse(archive.platform().orElse(""));
                String version=archive.baseClientVersion().orElse("");
                boolean neo=platform.equals("neoforge")||loader!=null&&id(loader).namespace().equals("neoforge");
                boolean forge=platform.equals("forge")||loader!=null&&id(loader).namespace().equals("forge");
                if(neo){
                    if(!version.isEmpty()&&!version.equals("1.21.1"))throw fail(Reason.UNSUPPORTED_MODEL,"当前NeoForge根变换仅核对1.21.1烘焙语义");
                    // NeoForge 1.21.1 ElementsModel composes the root into ModelState, like composite.
                    rootMode=ModelRule.RootTransformMode.COMPOSITE;
                }else if(!platform.isEmpty()&&!platform.equals("forge")||loader!=null&&id(loader).namespace().equals("porting_lib")||!version.isEmpty()&&(!forge||!version.startsWith("1.20.")))
                    throw fail(Reason.UNSUPPORTED_MODEL,"当前Forge根变换仅核对1.20.x烘焙语义，其他平台/版本需独立验证");
            }
            Map<String,TextureBinding> textures=new HashMap<>(inheritedTextures);
            if(definition.has("textures"))for(var entry:object(definition.get("textures")).entrySet()){variable(entry.getKey());textures.put(entry.getKey(),binding(entry.getValue()));if(textures.size()>MAX_TEXTURES)throw fail(Reason.LIMIT_EXCEEDED,"纹理变量过多");}
            // The preview uses the Fancy branch. Fast is a separate hint, never a fallback for Fancy.
            if(definition.has("render_type_fast")) {
                id(string(definition.get("render_type_fast")));
                ((BuiltinBlockModels)archive).note(state,"材质使用精细画质分支；快速画质专用的render_type_fast不用于当前预览");
            }
            String renderType=definition.has("render_type")?id(string(definition.get("render_type"))).toString():null;
            if(renderType!=null&&!Set.of("minecraft:solid","minecraft:cutout","minecraft:cutout_mipped","minecraft:translucent","minecraft:tripwire").contains(renderType))throw fail(Reason.UNSUPPORTED_MODEL,"自定义材质类型尚未适配："+renderType);
            if(rule!=null) {
                resolution.neighbors.addAll(rule.neighbors());resolution.positionDependent|=rule.positionDependent();resolution.biomeColorDependent|=rule.biomeColorDependent();
                if(resolution.blockContext==null&&(!rule.neighbors().isEmpty()||rule.positionDependent()||rule.biomeColorDependent()||rule.savedEntityDependent())){resolution.required=true;return List.of(new Part(model,x,y,uvLock,List.of(),renderType));}
                final ModelRule selectedRule=rule;
                final BlockResourceSource traced=new BlockResourceSource() {
                    public ClientResourceArchive.Fingerprint fingerprint(){return archive.fingerprint();}
                    public void validate()throws IOException{archive.validate();}
                    public Optional<String> origin(ResourceLocation.Kind kind,ResourceLocation id){return archive.origin(kind,id);}
                    public Set<ResourceLocation> jsonFiles(ResourceLocation.Kind kind,String folder)throws IOException{return archive.jsonFiles(kind,folder);}

                    public Optional<Boolean> hasMod(String id)throws IOException{return archive.hasMod(id);}
                    public Optional<Boolean> baseDataRegistry(String directory)throws IOException{return archive.baseDataRegistry(directory);}
                    public Optional<String> platform()throws IOException{return archive.platform();}
                    public Optional<String> platform(String modId)throws IOException{return archive.platform(modId);}


                    public Optional<String> baseClientVersion(){return archive.baseClientVersion();}
                    public Optional<Long> worldSeed()throws IOException{return archive.worldSeed();}
                    public Optional<Integer> biomeBlendRadius()throws IOException{return archive.biomeBlendRadius();}
                    public Optional<DimensionHeight> dimensionHeight(ResourceLocation dimension)throws IOException{return archive.dimensionHeight(dimension);}
                    public Optional<RegistrationClass> clientClass(String name)throws IOException{
                        var result=archive.clientClass(name);result.ifPresent(value->protocolNote(state,"客户端静态模型元信息："+value.internalName()+" ← "+value.origin()+"；来源指纹 "+value.sourceFingerprint()));return result;
                    }
                    public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id)throws IOException{sources.add(new Source(kind,id));return archive.read(kind,id);}
                    public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id)throws IOException{sources.add(new Source(kind,id));return archive.readJson(kind,id);}
                };
                var context=new ModelRule.Context() {
                    public ResourceLocation model(){return model;}
                    public BlockState state(){return state;}
                    public Optional<ModelBlockContext> blockContext(){return Optional.ofNullable(resolution.blockContext).map(value->value.restricted(selectedRule));}
                    public net.kyori.adventure.nbt.CompoundBinaryTag savedEntity()throws ModelRule.Failure {
                        if(!selectedRule.savedEntityDependent())throw new ModelRule.Failure(Reason.INVALID_MODEL,"规则未声明保存实体依赖");
                        if(resolution.blockContext==null)throw new ModelRule.Failure(Reason.CONTEXT_REQUIRED,"规则需要实际保存实体");
                        var sample=resolution.blockContext.at(0,0,0);
                        if(!sample.available())throw new ModelRule.Failure(Reason.CONTEXT_REQUIRED,"保存实体位置不可用："+sample.detail());
                        if(sample.state().isEmpty()||!sample.state().orElseThrow().name().equals(state.name()))throw new ModelRule.Failure(Reason.INVALID_MODEL,"保存实体位置与目标状态不一致");
                        var entity=sample.entity().orElseThrow(()->new ModelRule.Failure(Reason.INVALID_MODEL,"目标位置缺少保存实体，不能构造完整外观"));
                        if(!entity.modelData().complete())throw new ModelRule.Failure(Reason.LIMIT_EXCEEDED,"保存实体NBT不完整或超过预算");
                        return entity.modelData().values();
                    }
                    public int foliageColor()throws IOException,ModelRule.Failure {
                        return biomeColor(ModelRule.BiomeColor.FOLIAGE);
                    }
                    public int biomeColor(ModelRule.BiomeColor kind)throws IOException,ModelRule.Failure {
                        if(!selectedRule.biomeColorDependent())throw new ModelRule.Failure(Reason.INVALID_MODEL,"规则未声明保存群系颜色依赖");
                        if(resolution.blockContext==null)throw new ModelRule.Failure(Reason.CONTEXT_REQUIRED,"群系颜色需要实际保存位置");
                        return biomeColors.color(Objects.requireNonNull(kind),this,resolution.blockContext,sources);
                    }
                    public JsonObject definition(){return definition.deepCopy();}
                    public JsonObject blockstate(ResourceLocation id)throws IOException,ModelRule.Failure {
                        try{return read(ResourceLocation.Kind.BLOCKSTATE,id,sources).deepCopy();}catch(Failure invalid){throw new ModelRule.Failure(invalid.reason,invalid.getMessage());}
                    }
                    public Optional<JsonObject> assetJson(ResourceLocation id)throws IOException,ModelRule.Failure {
                        try{return Optional.of(read(ResourceLocation.Kind.RAW_ASSET,id,sources).deepCopy());}
                        catch(Failure invalid){if(invalid.reason==Reason.MISSING_RESOURCE)return Optional.empty();throw new ModelRule.Failure(invalid.reason,invalid.getMessage());}
                    }
                    public Optional<JsonObject> dataJson(ResourceLocation id)throws IOException,ModelRule.Failure {
                        try{return Optional.of(read(ResourceLocation.Kind.RAW_DATA,id,sources).deepCopy());}
                        catch(Failure invalid){if(invalid.reason==Reason.MISSING_RESOURCE)return Optional.empty();throw new ModelRule.Failure(invalid.reason,invalid.getMessage());}
                    }


                    public boolean matches(JsonElement value)throws ModelRule.Failure {
                        try{return condition(value,state.properties(),0);}catch(Failure invalid){throw new ModelRule.Failure(invalid.reason,invalid.getMessage());}
                    }
                    public List<Part> apply(JsonElement value)throws IOException,ModelRule.Failure {
                        try{var selected=application(value,state,depth+1,chain,sources,textures,resolution);if(selected.fixed())note("随机候选模型固定选择第一项");return selected.parts();}
                        catch(Failure invalid){throw new ModelRule.Failure(invalid.reason,invalid.getMessage());}
                    }
                    public List<Part> blockstateParts(JsonObject raw)throws IOException,ModelRule.Failure {
                        try{
                            var value=raw.deepCopy();fields(value,ModelDefinitionProtocol.Scope.BLOCKSTATE);
                            if(value.has("variants")==value.has("multipart"))throw fail(Reason.INVALID_MODEL,"嵌入blockstate必须恰含variants或multipart");
                            List<JsonElement> selected=new ArrayList<>();
                            if(value.has("variants")){
                                var variants=object(value.get("variants"));if(variants.size()>MAX_STATE_BRANCHES)throw fail(Reason.LIMIT_EXCEEDED,"blockstate分支过多");
                                for(var entry:variants.entrySet())if(BlockModelResolver.this.matches(entry.getKey(),state.properties()))selected.add(entry.getValue());
                                if(selected.size()>1)throw fail(Reason.AMBIGUOUS_VARIANT,"多个blockstate分支匹配");if(selected.isEmpty())throw fail(Reason.NO_VARIANT,"没有匹配属性的模型分支");
                            }else{
                                var multipart=value.get("multipart");if(!multipart.isJsonArray())throw fail(Reason.INVALID_MODEL,"multipart必须为数组");
                                if(multipart.getAsJsonArray().size()>MAX_VARIANTS)throw fail(Reason.LIMIT_EXCEEDED,"multipart部件过多");
                                for(var element:multipart.getAsJsonArray()){var item=object(element);fields(item,ModelDefinitionProtocol.Scope.MULTIPART);if(!item.has("when")||condition(item.get("when"),state.properties(),0))selected.add(item.get("apply"));}
                            }
                            List<Part> parts=new ArrayList<>();int faces=0;
                            for(var application:selected){var added=apply(application);faces+=added.stream().mapToInt(p->p.faces().size()).sum();if(faces>8192||parts.size()+added.size()>4096)throw fail(Reason.LIMIT_EXCEEDED,"嵌入blockstate几何超限");parts.addAll(added);}
                            return List.copyOf(parts);
                        }catch(Failure failure){throw new ModelRule.Failure(failure.reason,failure.getMessage());}
                    }
                    public List<Part> staticPrototype(BlockState prototype)throws IOException,ModelRule.Failure {return prototype(prototype,null);}
                    public List<Part> staticPrototype(BlockState prototype,ResourceLocation rootModel)throws IOException,ModelRule.Failure {return prototype(prototype,Objects.requireNonNull(rootModel));}






                    private List<Part> prototype(BlockState prototype,ResourceLocation rootModel)throws IOException,ModelRule.Failure {
                        Objects.requireNonNull(prototype);
                        if(prototype.properties().size()>128||prototype.properties().entrySet().stream().anyMatch(entry->entry.getKey().startsWith("__mcwe_")||entry.getKey().length()>256||entry.getValue().length()>256))
                            throw new ModelRule.Failure(Reason.INVALID_MODEL,"静态原型属性无效、超限或含内部外观身份");
                        var result=BlockModelResolver.this.resolve(prototype,null,resolution.baseDepth+depth+1,resolution.stateChain,rootModel);
                        sources.addAll(result.sources());resolution.loaders.addAll(result.loaders());
                        if(result.reason()==Reason.CONTEXT_REQUIRED)throw new ModelRule.Failure(Reason.UNSUPPORTED_MODEL,"被引用原型需要实体或邻域上下文，静态原型服务不能伪造："+prototype.name());
                        if(!result.resolved())throw new ModelRule.Failure(result.reason(),"静态原型 "+prototype.name()+"："+result.detail());
                        var model=result.model().orElseThrow();if(model.fixedFirstAlternative())note("静态原型随机候选模型固定选择第一项");
                        for(String message:((BuiltinBlockModels)archive).notes(prototype))note("静态原型 "+prototype.name()+"："+message);
                        return model.parts();
                    }
                    public Map<String,String> textures(){return references(textures);}
                    public Map<String,TextureBinding> textureBindings(){return Map.copyOf(textures);}
                    public int xRotation(){return x;}
                    public int yRotation(){return y;}
                    public boolean uvLock(){return uvLock;}
                    public String renderType(){return renderType;}
                    public BlockResourceSource resources(){return traced;}
                    public void note(String text){protocolNote(state,text);}






                    public List<Face> elements(JsonArray elements)throws ModelRule.Failure {
                        try{return faces(elements,textures,sources);}catch(Failure failure){throw new ModelRule.Failure(failure.reason,failure.getMessage());}
                    }
                    public List<Part> standard()throws ModelRule.Failure {
                        JsonElement elements=definition.get("elements");
                        if(elements==null)return List.of(new Part(model,x,y,uvLock,List.of(),renderType));
                        if(!elements.isJsonArray())throw new ModelRule.Failure(Reason.INVALID_MODEL,"模型元素必须为数组");
                        return List.of(new Part(model,x,y,uvLock,elements(elements.getAsJsonArray()),renderType));
                    }
                    public List<Part> child(JsonObject child)throws IOException,ModelRule.Failure {
                        return child(child,x,y,uvLock);
                    }
                    public List<Part> independentChild(JsonObject child)throws IOException,ModelRule.Failure {
                        try{return resolveParts(model,child.deepCopy(),state,x,y,uvLock,depth+1,chain,sources,Map.of(),resolution);}
                        catch(Failure failure){throw new ModelRule.Failure(failure.reason,failure.getMessage());}
                    }
                    public List<Part> child(JsonObject child,int childX,int childY,boolean childUvLock)throws IOException,ModelRule.Failure {
                        if(childX<0||childX>270||childY<0||childY>270||childX%90!=0||childY%90!=0)throw new ModelRule.Failure(Reason.INVALID_MODEL,"子模型旋转须为0/90/180/270");
                        try{return defaultRenderType(resolveParts(model,child.deepCopy(),state,childX,childY,childUvLock,depth+1,chain,sources,textures,resolution),renderType);}
                        catch(Failure failure){throw new ModelRule.Failure(failure.reason,failure.getMessage());}
                    }
                };
                try {
                    List<Part> parts=validateRuleParts(rule.resolve(context));
                    for(var part:parts)for(var face:part.faces()){var reference=new Source(ResourceLocation.Kind.TEXTURE,face.texture());if(!sources.contains(reference))sources.add(reference);}
                    return applyRootTransform(rule.inheritRenderType()?defaultRenderType(parts,renderType):parts,rootTransform,x,y,uvLock,rootMode);
                }catch(ModelRule.Failure failure){throw fail(failure.reason(),failure.getMessage());}
                catch(ResourceAccessException invalid){if(invalid.reason()==ResourceAccessException.Reason.INVALID_METADATA)throw fail(Reason.INVALID_JSON,invalid.getMessage());if(invalid.scope()==ResourceAccessException.Scope.RESOURCE)throw fail(Reason.LIMIT_EXCEEDED,invalid.getMessage());throw invalid;}
                catch(IOException invalid){throw fail(Reason.INVALID_MODEL,"模型规则读取失败："+invalid.getMessage());}
                catch(RuntimeException invalid){if(invalid instanceof java.util.concurrent.CancellationException)throw invalid;throw fail(Reason.INVALID_MODEL,"模型规则输出无效："+invalid.getMessage());}
            }
            JsonElement elements=definition.get("elements");
            if(elements!=null){if(!elements.isJsonArray())throw fail(Reason.INVALID_MODEL,"模型元素必须为数组");return applyRootTransform(List.of(new Part(model,x,y,uvLock,faces(elements.getAsJsonArray(),textures,sources),renderType)),rootTransform,x,y,uvLock,rootMode);}
            if(rootTransform!=null)throw fail(Reason.UNSUPPORTED_MODEL,"无标准元素的特殊模型尚未声明根变换语义");
            if(depth==0&&loader==null) {
                Optional<List<Part>> builtin;
                if(textures.values().stream().anyMatch(TextureBinding::forceTranslucent))throw fail(Reason.UNSUPPORTED_MODEL,"内置特殊几何的强制透明绑定尚需材质适配");
                try{builtin=((BuiltinBlockModels)archive).resolve(state,references(textures));}
                catch(ResourceAccessException sourceFailure){if(sourceFailure.scope()==ResourceAccessException.Scope.RESOURCE)throw fail(Reason.LIMIT_EXCEEDED,sourceFailure.getMessage());throw sourceFailure;}
                catch(IOException invalid){throw fail(Reason.INVALID_MODEL,"特殊外观准备失败："+invalid.getMessage());}
                if(builtin.isPresent()) {
                    for(var part:builtin.orElseThrow())for(var face:part.faces()){var reference=new Source(ResourceLocation.Kind.TEXTURE,face.texture());if(!sources.contains(reference))sources.add(reference);}
                    return builtin.orElseThrow();
                }
                if(!id(state.name()).namespace().equals("minecraft"))((BuiltinBlockModels)archive).note(state,"资源未提供静态元素几何；若依赖运行时渲染器需额外适配："+model);
            }
            return List.of(new Part(model,x,y,uvLock,List.of(),renderType));
        }catch(IllegalStateException|UnsupportedOperationException|NumberFormatException invalid){if(invalid instanceof java.util.concurrent.CancellationException)throw invalid;throw fail(Reason.INVALID_MODEL,"模型字段数据无效："+invalid.getMessage());}
        finally{if(tracked!=null)chain.remove(tracked);}
    }

    private static List<Part> validateRuleParts(List<Part> input)throws Failure {
        List<Part> parts=List.copyOf(input);if(parts.size()>4096)throw fail(Reason.LIMIT_EXCEEDED,"模型规则部件超过4096项");
        int count=0;
        for(var part:parts) {
            Objects.requireNonNull(part.id());if(part.xRotation()<0||part.xRotation()>270||part.xRotation()%90!=0||part.yRotation()<0||part.yRotation()>270||part.yRotation()%90!=0)throw fail(Reason.INVALID_MODEL,"规则部件旋转必须为直角");
            bounded(part.offset(),32);
            if(part.renderType()!=null&&!Set.of("minecraft:solid","minecraft:cutout","minecraft:cutout_mipped","minecraft:translucent","minecraft:tripwire","mcwe:fluid").contains(part.renderType()))throw fail(Reason.UNSUPPORTED_MODEL,"规则输出未知材质类型："+part.renderType());
            for(var face:part.faces()) {
                if(++count>8192)throw fail(Reason.LIMIT_EXCEEDED,"模型规则面超过8192项");
                if(face.layer()<0||face.layer()>=8192)throw fail(Reason.LIMIT_EXCEEDED,"规则元素层编号超限");
                Objects.requireNonNull(face.direction());Objects.requireNonNull(face.texture());Objects.requireNonNull(face.uv());bounded(face.from(),512);bounded(face.to(),512);
                if(!Float.isFinite(face.opacity())||face.opacity()<0||face.opacity()>1)throw fail(Reason.INVALID_MODEL,"规则透明度无效");
                if(face.rotation()<0||face.rotation()>270||face.rotation()%90!=0)throw fail(Reason.INVALID_MODEL,"规则面UV旋转无效");
                if(!face.vertices().isEmpty()&&face.vertices().size()!=4||!face.vertexUv().isEmpty()&&face.vertexUv().size()!=4)throw fail(Reason.INVALID_MODEL,"规则面必须恰含4个显式顶点/UV");
                for(var vertex:face.vertices())bounded(vertex,512);
                for(double value:new double[]{face.uv().u0(),face.uv().v0(),face.uv().u1(),face.uv().v1()})if(!Double.isFinite(value)||Math.abs(value)>256)throw fail(Reason.LIMIT_EXCEEDED,"规则UV超出预算");
                for(var uv:face.vertexUv())if(!Double.isFinite(uv.u())||!Double.isFinite(uv.v())||Math.abs(uv.u())>16||Math.abs(uv.v())>16)throw fail(Reason.LIMIT_EXCEEDED,"规则顶点UV超出预算");
                var rotation=face.elementRotation();if(rotation!=null){bounded(rotation.origin(),512);if(!Set.of("x","y","z").contains(rotation.axis())||!Double.isFinite(rotation.angle())||Math.abs(rotation.angle())>180||rotation.rescale()&&Math.abs(Math.cos(Math.toRadians(rotation.angle())))<.1)throw fail(Reason.INVALID_MODEL,"规则元素旋转无效");}
            }
        }
        return parts;
    }
    private static List<Part> applyRootTransform(List<Part> parts,org.joml.Matrix4d transform,int x,int y,boolean uvLock,ModelRule.RootTransformMode mode)throws Failure {
        if(transform==null)return parts;
        try{return validateRuleParts(RootModelTransform.apply(parts,transform,x,y,uvLock,mode));}
        catch(ModelRule.Failure invalid){throw fail(invalid.reason(),invalid.getMessage());}
    }
    private static void bounded(Vec3 vector,double maximum)throws Failure {if(!Double.isFinite(vector.x())||!Double.isFinite(vector.y())||!Double.isFinite(vector.z())||Math.max(Math.abs(vector.x()),Math.max(Math.abs(vector.y()),Math.abs(vector.z())))>maximum)throw fail(Reason.LIMIT_EXCEEDED,"规则坐标超出预算");}

    private static List<Part> defaultRenderType(List<Part> parts,String type) {
        if(type==null)return parts;
        return parts.stream().map(part->part.renderType()!=null?part:new Part(part.id(),part.xRotation(),part.yRotation(),part.uvLock(),part.faces(),type,part.offset())).toList();
    }

    private List<Face> faces(JsonArray elements, Map<String, TextureBinding> textures, List<Source> sources) throws Failure {
        if (elements == null) throw fail(Reason.UNSUPPORTED_MODEL, "资源未声明元素几何，需要特殊渲染或空模型语义");
        if (elements.size() > MAX_ELEMENTS) throw fail(Reason.LIMIT_EXCEEDED, "模型元素超过 " + MAX_ELEMENTS + " 项");
        List<Face> result = new ArrayList<>();
        for (int layer = 0; layer < elements.size(); layer++) {
            JsonObject element = object(elements.get(layer));
            // Blockbench's element color is an editor marker, unlike neoforge_data.color.
            fields(element,ModelDefinitionProtocol.Scope.ELEMENT);
            FaceMaterial elementMaterial=faceMaterial(element,FaceMaterial.DEFAULT);
            Vec3 from = vector(element.get("from")), to = vector(element.get("to"));
            boolean shade = bool(element, "shade", true);
            Direction shadeDirection=element.has("shade_direction_override")?direction(string(element.get("shade_direction_override"))):null;
            ElementRotation transform = null;
            if (element.has("rotation")) {
                var rotation = object(element.get("rotation"));
                fields(rotation,ModelDefinitionProtocol.Scope.ROTATION);
                String axis = string(rotation.get("axis"));
                if (!Set.of("x", "y", "z").contains(axis)) throw fail(Reason.INVALID_MODEL, "元素旋转轴无效");
                double angle = number(rotation.get("angle"));
                if (Math.abs(angle) > 180) throw fail(Reason.LIMIT_EXCEEDED, "元素旋转角度超限");
                boolean rescale = compatibleBoolean(rotation, "rescale", false);
                if (rescale && Math.abs(Math.cos(Math.toRadians(angle))) < .1)
                    throw fail(Reason.UNSUPPORTED_MODEL, "rescale 旋转接近奇点");
                transform = new ElementRotation(rotation.has("origin") ? vector(rotation.get("origin"), -512, 512) : new Vec3(0,0,0), axis, angle, rescale);
            }
            if (element.has("light_emission")) {
                int light = integer(element.get("light_emission"));
                if (light < 0 || light > 15) throw fail(Reason.INVALID_MODEL, "light_emission 超出范围");
                // Dynamic lighting remains outside the static preview renderer.
            }
            JsonObject faceMap = object(element.get("faces"));
            if (faceMap.size() > 6) throw fail(Reason.INVALID_MODEL, "元素面数超过六面");
            for (var entry : faceMap.entrySet()) {
                Direction direction = direction(entry.getKey());
                JsonObject face = object(entry.getValue());
                fields(face,ModelDefinitionProtocol.Scope.FACE);
                FaceMaterial material=faceMaterial(face,elementMaterial);
                // Exactly collapsed in-plane dimensions have no rasterized area, including after rotation.
                // Do not require an unused material for these editor-generated boundary faces.
                boolean collapsed=switch(direction) {
                    case UP,DOWN -> from.x()==to.x()||from.z()==to.z();
                    case NORTH,SOUTH -> from.x()==to.x()||from.y()==to.y();
                    case EAST,WEST -> from.y()==to.y()||from.z()==to.z();
                };
                if(collapsed)continue;
                TextureBinding binding = texture(string(face.get("texture")), textures);
                ResourceLocation texture = id(binding.reference());
                material=new FaceMaterial(material.argb(),material.blockLight(),material.skyLight(),material.ambientOcclusion(),binding.forceTranslucent(),shadeDirection);
                Uv uv = defaultUv(direction, from, to);
                if (face.has("uv")) {
                    JsonArray a = array(face.get("uv"), 4);
                    uv = new Uv(number(a.get(0)), number(a.get(1)), number(a.get(2)), number(a.get(3)));
                }
                for (double coordinate : new double[]{uv.u0(), uv.v0(), uv.u1(), uv.v1()})
                    if (Math.abs(coordinate)>256) throw fail(Reason.LIMIT_EXCEEDED,"UV 坐标超出 -256..256 预算");
                int tint = face.has("tintindex") ? integer(face.get("tintindex")) : -1;
                if (tint < -1 || tint > 255) throw fail(Reason.LIMIT_EXCEEDED, "tintindex 超出范围");
                Direction cull=null;
                if(face.has("cullface")) {
                    String hint=string(face.get("cullface"));
                    // Vanilla treats unrecognised optional cull directions as absent, retaining the face.
                    for(Direction candidate:Direction.values())if(candidate.name().equalsIgnoreCase(hint))cull=candidate;
                }
                result.add(new Face(direction, texture, uv, rotation(face, "rotation"), tint, cull, layer,
                        from, to, transform, shade,List.of(),List.of(),1,material));
                Source source = new Source(ResourceLocation.Kind.TEXTURE, texture);
                if (!sources.contains(source)) sources.add(source);
            }
        }
        return result;
    }

    private static FaceMaterial faceMaterial(JsonObject owner,FaceMaterial fallback)throws Failure {
        if(!owner.has("neoforge_data")&&!owner.has("forge_data"))return fallback;
        if(owner.has("neoforge_data")&&owner.has("forge_data")&&!owner.get("neoforge_data").equals(owner.get("forge_data")))throw fail(Reason.INVALID_MODEL,"扩展面数据声明冲突");
        var data=object(owner.get(owner.has("neoforge_data")?"neoforge_data":"forge_data"));
        fields(data,ModelDefinitionProtocol.Scope.FACE_DATA);
        if(data.has("calculate_normals")&&!bool(data,"calculate_normals",true))throw fail(Reason.UNSUPPORTED_MODEL,"calculate_normals=false需要保留声明法线的材质管线");
        int block=data.has("block_light")?integer(data.get("block_light")):0,sky=data.has("sky_light")?integer(data.get("sky_light")):0;
        if(block<0||block>15||sky<0||sky>15)throw fail(Reason.INVALID_MODEL,"面亮度超出0..15");
        return new FaceMaterial(data.has("color")?argb(data.get("color")):0xFFFFFFFF,block,sky,bool(data,"ambient_occlusion",true));
    }
    private static int argb(JsonElement value)throws Failure {
        if(value==null||!value.isJsonPrimitive())throw fail(Reason.INVALID_MODEL,"面颜色需要ARGB整数或十六进制字符串");
        try {
            var primitive=value.getAsJsonPrimitive();
            if(primitive.isString()) {
                String text=primitive.getAsString();if(text.startsWith("0x")||text.startsWith("0X"))text=text.substring(2);
                if(!text.matches("[0-9a-fA-F]{1,8}"))throw new IllegalArgumentException();return (int)Long.parseLong(text,16);
            }
            if(!primitive.isNumber())throw new IllegalArgumentException();
            if(primitive.getAsString().length()>64)throw fail(Reason.LIMIT_EXCEEDED,"ARGB数值文本超过64字符预算");
            long number=primitive.getAsBigDecimal().longValueExact();
            if(number<Integer.MIN_VALUE||number>0xFFFFFFFFL)throw new IllegalArgumentException();return (int)number;
        }catch(RuntimeException invalid){throw fail(Reason.INVALID_MODEL,"面颜色不是32位ARGB值");}
    }
    private static Map<String,String> references(Map<String,TextureBinding> bindings) {
        Map<String,String> result=new HashMap<>();bindings.forEach((key,value)->result.put(key,value.reference()));return Map.copyOf(result);
    }
    private static TextureBinding binding(JsonElement value)throws Failure {
        if(value!=null&&value.isJsonObject()) {
            var object=value.getAsJsonObject();fields(object,ModelDefinitionProtocol.Scope.TEXTURE_BINDING);
            String sprite=string(object.get("sprite"));id(sprite);
            return new TextureBinding(sprite,bool(object,"force_translucent",false));
        }
        try{return new TextureBinding(string(value),false);}catch(IllegalArgumentException invalid){throw fail(Reason.INVALID_MODEL,"非法纹理引用");}
    }
    private TextureBinding texture(String value, Map<String, TextureBinding> textures) throws Failure {
        Set<String> visited = new HashSet<>();
        if(textures.containsKey(value))value="#"+value; // Legacy bare aliases occur in declared client models.
        while (value.startsWith("#")) {
            String key = value.substring(1); variable(key);
            if (!visited.add(key)) throw fail(Reason.REFERENCE_CYCLE, "纹理变量循环：" + key);
            if (visited.size() > MAX_TEXTURES) throw fail(Reason.LIMIT_EXCEEDED, "纹理引用链过长");
            var next = textures.get(key);
            if (next == null) throw fail(Reason.MISSING_RESOURCE, "未定义纹理变量：" + key);
            if(!next.reference().startsWith("#")){id(next.reference());return next;}
            value=next.reference();
        }
        id(value);return new TextureBinding(value,false);
    }

    private boolean matches(String selector, Map<String, String> properties) throws Failure {
        if (selector.isEmpty()) return true;
        Set<String> keys = new HashSet<>();
        boolean matches = true;
        for (String condition : selector.split(",", -1)) {
            String[] pair = condition.split("=", -1);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank() || !keys.add(pair[0]))
                throw fail(Reason.INVALID_MODEL, "非法 blockstate 属性选择器");
            matches &= alternatives(pair[1], properties.get(pair[0]));
        }
        return matches;
    }

    private boolean alternatives(String expected, String actual) throws Failure {
        String[] values = expected.split("\\|", -1);
        if (Arrays.stream(values).anyMatch(String::isBlank)) throw fail(Reason.INVALID_MODEL, "空属性候选值");
        return Arrays.asList(values).contains(actual);
    }

    private boolean condition(JsonElement value, Map<String, String> properties, int depth) throws Failure {
        if (depth >= 32) throw fail(Reason.LIMIT_EXCEEDED, "条件嵌套过深");
        var condition = object(value);
        boolean match = true;
        for (var entry : condition.entrySet()) {
            if (entry.getKey().equals("OR") || entry.getKey().equals("AND")) {
                if (!entry.getValue().isJsonArray() || entry.getValue().getAsJsonArray().isEmpty())
                    throw fail(Reason.INVALID_MODEL, "OR/AND 需要非空条件数组");
                boolean aggregate = entry.getKey().equals("AND");
                for (var child : entry.getValue().getAsJsonArray()) {
                    boolean result = condition(child, properties, depth + 1);
                    if (entry.getKey().equals("OR")) aggregate |= result; else aggregate &= result;
                }
                match &= aggregate;
            } else match &= alternatives(propertyValue(entry.getValue()), properties.get(entry.getKey()));
        }
        return match;
    }

    private void protocolNote(BlockState state,String text){
        ((BuiltinBlockModels)archive).note(state,Objects.requireNonNull(text));
    }

    private JsonObject read(ResourceLocation.Kind kind, ResourceLocation id, List<Source> sources) throws IOException, Failure {
        Source key=new Source(kind,id);sources.add(key);
        var cached=parsedJson.get(key);if(cached!=null)return cached.value();
        String text;
        try{text=archive.readJson(kind,id).orElseThrow(()->fail(Reason.MISSING_RESOURCE,"缺少资源："+id.entryName(kind)));}
        catch(ResourceAccessException invalid){if(invalid.reason()==ResourceAccessException.Reason.INVALID_METADATA)throw fail(Reason.INVALID_JSON,invalid.getMessage());if(invalid.scope()==ResourceAccessException.Scope.RESOURCE)throw fail(Reason.LIMIT_EXCEEDED,invalid.getMessage());throw invalid;}
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            reader.setNestingLimit(32);
            int[] remaining={MAX_JSON_NODES};
            JsonElement value = tree(reader,remaining);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw fail(Reason.INVALID_JSON, "JSON 尾随内容");
            JsonObject object=object(value);int nodes=MAX_JSON_NODES-remaining[0];
            while(!parsedJson.isEmpty()&&(cachedNodes+nodes>MAX_CACHED_NODES||cachedChars+text.length()>MAX_CACHED_CHARS||parsedJson.size()>=2048)) {
                var oldest=parsedJson.entrySet().iterator();var previous=oldest.next();cachedNodes-=previous.getValue().nodes();cachedChars-=previous.getValue().chars();oldest.remove();
            }
            if(text.length()<=MAX_CACHED_CHARS){parsedJson.put(key,new CachedJson(object,nodes,text.length()));cachedNodes+=nodes;cachedChars+=text.length();}
            return object;
        } catch (IOException | NumberFormatException e) {
            throw fail(Reason.INVALID_JSON, "非法或嵌套超限 JSON：" + id);
        }
    }

    /** Shared bounded parser for data-only providers, before a resolver context exists. */
    static JsonObject parseResourceDefinition(String text)throws ModelRule.Failure {
        if(text.length()>1024*1024)throw new ModelRule.Failure(Reason.LIMIT_EXCEEDED,"注册资源JSON超过1MiB字符");
        try(var reader=new JsonReader(new StringReader(text))){reader.setStrictness(Strictness.STRICT);reader.setNestingLimit(32);var value=tree(reader,new int[]{MAX_JSON_NODES});if(reader.peek()!=JsonToken.END_DOCUMENT)throw fail(Reason.INVALID_JSON,"注册资源JSON尾随内容");return object(value);}
        catch(Failure invalid){throw new ModelRule.Failure(invalid.reason,invalid.getMessage());}
        catch(IOException|NumberFormatException invalid){throw new ModelRule.Failure(Reason.INVALID_JSON,"注册资源JSON无效或超限");}
    }
    private static JsonElement tree(JsonReader reader, int[] budget) throws IOException, Failure {
        if (--budget[0] < 0) throw fail(Reason.LIMIT_EXCEEDED, "JSON 节点超过上限");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); JsonObject object = new JsonObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw fail(Reason.INVALID_JSON, "JSON 字段重复：" + key);
                    object.add(key, tree(reader, budget));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); JsonArray array = new JsonArray();
                while (reader.hasNext()) array.add(tree(reader, budget));
                reader.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw fail(Reason.INVALID_JSON, "缺少 JSON 值");
        };
    }

    private static void fields(JsonObject value,ModelDefinitionProtocol.Scope scope)throws Failure {fields(value,scope,scope.fields);}
    private static void fields(JsonObject value,ModelDefinitionProtocol.Scope scope,Set<String> allowed)throws Failure {
        String unsupported=ModelDefinitionProtocol.unsupportedField(value,scope,allowed);
        if(unsupported!=null)throw fail(Reason.UNSUPPORTED_MODEL,"不支持的模型字段："+unsupported+"（定义层："+scope.description+"）");
    }
    private static JsonObject object(JsonElement e) throws Failure {
        if (e == null || !e.isJsonObject()) throw fail(Reason.INVALID_MODEL, "需要 JSON 对象");
        return e.getAsJsonObject();
    }
    private static String string(JsonElement e) throws Failure {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
            throw fail(Reason.INVALID_MODEL, "需要字符串");
        return e.getAsString();
    }
    private static String propertyValue(JsonElement value)throws Failure {
        if(value==null||!value.isJsonPrimitive())throw fail(Reason.INVALID_MODEL,"属性条件必须为字符串或基本值");
        String result=value.getAsString();if(result.length()>256)throw fail(Reason.LIMIT_EXCEEDED,"属性条件过长");return result;
    }
    private static int integer(JsonElement e) throws Failure {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
            throw fail(Reason.INVALID_MODEL, "需要整数");
        try { return e.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException ex) { throw fail(Reason.INVALID_MODEL, "整数超限或包含小数"); }
    }
    private static int rotation(JsonObject o, String key) throws Failure {
        int value = o.has(key) ? integer(o.get(key)) : 0;
        if (value < 0 || value > 270 || value % 90 != 0) throw fail(Reason.UNSUPPORTED_MODEL, "仅支持 0/90/180/270 度旋转");
        return value;
    }
    private static int variantRotation(JsonObject object,String key)throws Failure {
        int value=object.has(key)?integer(object.get(key)):0;
        if(value%90!=0)throw fail(Reason.UNSUPPORTED_MODEL,"blockstate旋转须为90度的整数倍");
        return Math.floorMod(value,360);
    }
    private static boolean bool(JsonObject o, String key, boolean fallback) throws Failure {
        if (!o.has(key)) return fallback;
        JsonElement e = o.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw fail(Reason.INVALID_MODEL, "需要布尔值");
        return e.getAsBoolean();
    }
    private static ResourceLocation id(String text) throws Failure {
        try { return ResourceLocation.parse(text); }
        catch (IllegalArgumentException e) { throw fail(Reason.INVALID_MODEL, "非法资源 ID：" + text); }
    }
    private static void variable(String key) throws Failure {
        // Aliases are map keys, not resource paths. The resolved value is validated separately.
        if (key.isBlank() || key.length() > 128 || key.chars().anyMatch(Character::isISOControl)) throw fail(Reason.INVALID_MODEL, "非法纹理变量名");
    }
    /** Vanilla GsonHelper permits boolean strings here; element shade and extension codecs stay strict. */
    private static boolean compatibleBoolean(JsonObject object,String key,boolean fallback)throws Failure {
        if(!object.has(key))return fallback;var value=object.get(key);
        if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()) {
            String text=value.getAsString();if(text.equalsIgnoreCase("true"))return true;if(text.equalsIgnoreCase("false"))return false;
        }
        return bool(object,key,fallback);
    }
    private static Direction direction(String value) throws Failure {
        for (Direction d : Direction.values()) if (d.name().toLowerCase(Locale.ROOT).equals(value)) return d;
        throw fail(Reason.INVALID_MODEL, "未知模型面：" + value);
    }
    private static JsonArray array(JsonElement e, int size) throws Failure {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != size) throw fail(Reason.INVALID_MODEL, "坐标数量无效");
        return e.getAsJsonArray();
    }
    private static double coordinate(JsonElement e) throws Failure {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw fail(Reason.INVALID_MODEL, "坐标必须是数值");
        double value = e.getAsDouble();
        if (!Double.isFinite(value) || value < 0 || value > 16) throw fail(Reason.UNSUPPORTED_MODEL, "坐标超出 0..16");
        return value;
    }
    private static double number(JsonElement e) throws Failure {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
            throw fail(Reason.INVALID_MODEL, "需要有限数值");
        double value = e.getAsDouble();
        if (!Double.isFinite(value)) throw fail(Reason.INVALID_MODEL, "数值不是有限值");
        return value;
    }
    private static Vec3 vector(JsonElement e) throws Failure {
        return vector(e,-16,32);
    }
    private static Vec3 vector(JsonElement e,double minimum,double maximum) throws Failure {
        var a = array(e, 3);
        double x = number(a.get(0)), y = number(a.get(1)), z = number(a.get(2));
        if (Math.min(x, Math.min(y,z)) < minimum || Math.max(x,Math.max(y,z)) > maximum)
            throw fail(Reason.LIMIT_EXCEEDED, "模型坐标超出 " + minimum + ".." + maximum);
        return new Vec3(x,y,z);
    }
    private static Uv defaultUv(Direction direction, Vec3 a, Vec3 b) {
        return switch (direction) {
            case DOWN -> new Uv(a.x(), 16-b.z(), b.x(), 16-a.z());
            case UP -> new Uv(a.x(), a.z(), b.x(), b.z());
            case NORTH -> new Uv(16-b.x(), 16-b.y(), 16-a.x(), 16-a.y());
            case SOUTH -> new Uv(a.x(), 16-b.y(), b.x(), 16-a.y());
            case WEST -> new Uv(a.z(), 16-b.y(), b.z(), 16-a.y());
            case EAST -> new Uv(16-b.z(), 16-b.y(), 16-a.z(), 16-a.y());
        };
    }
    private static Failure fail(Reason reason, String message) { return new Failure(reason, message); }
    private static final class Failure extends Exception {
        final Reason reason;
        Failure(Reason reason, String message) { super(message); this.reason = reason; }
    }
}
