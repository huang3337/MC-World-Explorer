package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.mcworldexplorer.voxel.data.BlockState;
import java.io.IOException;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;

/** Explicitly registered, trusted data interpreter. Mod archives are never loaded as Java classes. */
public interface ModelRule {
    int API_VERSION=1;
    ResourceLocation id();
    String version();
    default int apiVersion(){return API_VERSION;}
    Set<ResourceLocation> loaders();
    Set<String> fields();
    /** Opt-in to host root-transform application. ELEMENTS follows the source platform's baking protocol.
     * Combined-state UV-lock (composite / NeoForge elements) remains explicitly unsupported. */
    enum RootTransformMode { NONE, ELEMENTS, COMPOSITE }
    /** Shared game biome resolvers; selecting a block's handler belongs to registration protocols. */
    enum BiomeColor { GRASS, FOLIAGE, WATER }
    default RootTransformMode rootTransformMode(){return RootTransformMode.NONE;}
    /** Whether the host fills unset part material hints from this model's context. Composite children
     * bake in their own model context and must opt out instead of receiving the outer hint. */
    default boolean inheritRenderType(){return true;}
    /** JSON keys that select this rule at blockstate level, in addition to model loaders. */
    default Set<String> stateSelectors(){return Set.of();}
    /** Explicit protocol namespaces used only when the blockstate resource is absent. */
    default Set<String> generatedNamespaces(){return Set.of();}
    default Set<ModelBlockContext.Offset> neighbors(){return Set.of();}
    default boolean positionDependent(){return false;}
    /** Uses saved quart biomes, world seed and the selected client's blend setting at the center.
     * The host includes these inputs and absolute position in contextual cache identity. */
    default boolean biomeColorDependent(){return false;}
    /** Complete saved center entity NBT is required, even without position or neighbor dependencies. */
    default boolean savedEntityDependent(){return false;}
    List<Part> resolve(Context context)throws IOException,Failure;

    /** Per-call read-only resources and bounded recursive services; definitions are defensive copies. */
    interface Context {
        ResourceLocation model();
        BlockState state();
        /** Empty during static preparation; contextual rules must explicitly reject unavailable input. */
        Optional<ModelBlockContext> blockContext();
        JsonObject definition();
        Map<String,String> textures();
        /** Full bindings; legacy contexts expose plain references with no forced pass. */
        default Map<String,TextureBinding> textureBindings(){
            Map<String,TextureBinding> result=new HashMap<>();textures().forEach((key,value)->result.put(key,new TextureBinding(value,false)));return Map.copyOf(result);
        }
        int xRotation();
        int yRotation();
        boolean uvLock();
        String renderType();
        BlockResourceSource resources();
        List<Part> standard()throws IOException,Failure;
        List<Part> child(JsonObject definition)throws IOException,Failure;
        /** Resolve an embedded model in its own texture/material context, retaining state transforms
         * and the same bounded resource/dependency session. */
        default List<Part> independentChild(JsonObject definition)throws IOException,Failure{throw new Failure(Reason.UNSUPPORTED_MODEL,"宿主未提供独立子模型上下文");}
        /** Resolve a nested model with explicit block rotations; used by split loaders baking at identity first. */
        default List<Part> child(JsonObject definition,int x,int y,boolean uvLock)throws IOException,Failure{throw new Failure(Reason.UNSUPPORTED_MODEL,"宿主未提供独立子模型烘焙变换");}
        List<Face> elements(JsonArray elements)throws Failure;
        /** Bounded traced blockstate JSON; the returned value is a defensive copy. */
        default JsonObject blockstate(ResourceLocation id)throws IOException,Failure{throw new Failure(Reason.UNSUPPORTED_MODEL,"宿主未提供生成资源读取服务");}
        /** Standard multipart condition against this context's exact state properties. */
        default boolean matches(JsonElement condition)throws Failure{throw new Failure(Reason.UNSUPPORTED_MODEL,"宿主未提供条件匹配服务");}
        /** A standard apply/variant, using its own absolute rotations and fixed-first random policy. */
        default List<Part> apply(JsonElement application)throws IOException,Failure{throw new Failure(Reason.UNSUPPORTED_MODEL,"宿主未提供variant解析服务");}
        /** Resolve an embedded ordinary blockstate using this state/context and shared bounds. */
        default List<Part> blockstateParts(JsonObject definition)throws IOException,Failure{throw new Failure(Reason.UNSUPPORTED_MODEL,"宿主未提供普通blockstate组合服务");}
        /** Resolve an exact saved state in the same source, without inheriting outer textures, entity or neighbors.
         * Context-dependent prototypes fail explicitly; nested calls share the host recursion/cycle bounds. */
        default List<Part> staticPrototype(BlockState state)throws IOException,Failure{throw new Failure(Reason.UNSUPPORTED_MODEL,"宿主未提供静态原型解析服务");}
        /** An explicit model root evaluated using the referenced state's properties and no outer input. */
        default List<Part> staticPrototype(BlockState state,ResourceLocation model)throws IOException,Failure{throw new Failure(Reason.UNSUPPORTED_MODEL,"宿主未提供指定模型根的静态原型服务");}
        /** Shared saved-biome foliage color at the center; requires biomeColorDependent.
         * Missing saved data, unsupported client semantics and unavailable color resources fail explicitly. */
        default int foliageColor()throws IOException,Failure{throw new Failure(Reason.UNSUPPORTED_MODEL,"宿主未提供保存群系叶色服务");}
        /** Saved center position, actual dimension height and effective biome/colormap resources.
         * Requires biomeColorDependent; does not infer which resolver belongs to a Mod block. */
        default int biomeColor(BiomeColor kind)throws IOException,Failure{throw new Failure(Reason.UNSUPPORTED_MODEL,"宿主未提供保存群系颜色服务");}
        /** Complete center NBT, available only to a rule declaring savedEntityDependent. */
        default net.kyori.adventure.nbt.CompoundBinaryTag savedEntity()throws Failure{throw new Failure(Reason.UNSUPPORTED_MODEL,"宿主未提供保存实体读取服务");}
        /** Optional bounded asset JSON ({@code assets/<namespace>/<path>}), with normal source tracing. */
        default Optional<JsonObject> assetJson(ResourceLocation id)throws IOException,Failure{throw new Failure(Reason.UNSUPPORTED_MODEL,"宿主未提供资产JSON读取服务");}
        /** Same bounded parser/tracing for a selected data registry JSON. */
        default Optional<JsonObject> dataJson(ResourceLocation id)throws IOException,Failure{throw new Failure(Reason.UNSUPPORTED_MODEL,"宿主未提供数据JSON读取服务");}
        void note(String text);
    }
    final class Failure extends Exception {
        private final Reason reason;
        public Failure(Reason reason,String message){super(message);this.reason=Objects.requireNonNull(reason);if(reason==Reason.RESOLVED)throw new IllegalArgumentException("成功不能作为失败原因");}
        public Reason reason(){return reason;}
    }
}
