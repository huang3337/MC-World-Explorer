package com.mcworldexplorer.voxel.resource;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Host semantics shared by declarative loader formats across resource namespaces. */
final class ModelLoaderProtocol {
    static final List<String> KEYS = List.of("loader", "porting_lib:loader");

    private ModelLoaderProtocol() {}

    private static String platform(BlockResourceSource source, ResourceLocation model) throws IOException {
        var owner = source.platform(model.namespace());
        return owner.isPresent() ? owner.get() : source.platform().orElse("");
    }

    static ModelRule.RootTransformMode rootMode(BlockResourceSource source, ResourceLocation model,
            ResourceLocation loader, ModelRule.RootTransformMode declared) throws IOException, ModelRule.Failure {
        String platform = platform(source, model);
        String version = source.baseClientVersion().orElse("");
        String namespace = loader == null ? "" : loader.namespace();
        if (platform.equals("neoforge") || namespace.equals("neoforge")) {
            if (!version.isEmpty() && !version.equals("1.21.1")) {
                throw new ModelRule.Failure(BlockModelResolver.Reason.UNSUPPORTED_MODEL, "当前NeoForge根变换仅核对1.21.1烘焙语义");
            }
            return ModelRule.RootTransformMode.COMPOSITE;
        }
        boolean forge = platform.equals("forge") || namespace.equals("forge");
        if ((!platform.isEmpty() && !platform.equals("forge")) || namespace.equals("porting_lib")
                || (!version.isEmpty() && (!forge || !version.startsWith("1.20.")))) {
            throw new ModelRule.Failure(BlockModelResolver.Reason.UNSUPPORTED_MODEL, "当前Forge根变换仅核对1.20.x烘焙语义，其他平台/版本需独立验证");
        }
        return declared;
    }

    /** ObjModel.makeQuad consumes only the model state's rotation; UVs come directly from vt.
     * Keep the evidence boundary separate from resource namespace and model ownership. */
    static boolean objKeepsExplicitUv(String loader,String platform,String version){
        return loader.equals("forge:obj")&&platform.equals("forge")&&version.equals("1.20.1")
            ||loader.equals("neoforge:obj")&&platform.equals("neoforge")&&version.equals("1.21.1");
    }

    /** The structured loader form is versioned independently from the loader's geometry protocol.
     * A tool registration does not prove that an optional loader exists in the game registry. */
    static JsonObject normalize(JsonObject definition, BlockResourceSource archive, ResourceLocation model) throws IOException, ModelRule.Failure {
        JsonObject normalized = definition;
        for (String key : KEYS) {
            if (!definition.has(key) || !definition.get(key).isJsonObject()) continue;
            if (!key.equals("loader") || !platform(archive, model).equals("neoforge")
                    || !archive.baseClientVersion().orElse("").equals("1.21.1")) {
                throw new ModelRule.Failure(BlockModelResolver.Reason.UNSUPPORTED_MODEL,
                        "对象形式loader目前仅核对NeoForge 1.21.1；当前平台/版本证据不足");
            }
            var value=definition.getAsJsonObject(key);
            var id = value.get("id");
            if (!Set.of("id","optional").containsAll(value.keySet()) || id == null
                    || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) {
                throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL,
                        "loader对象需要字符串id且仅支持id/optional字段");
            }
            if (value.has("optional")) {
                var optional = value.get("optional");
                if (!optional.isJsonPrimitive() || !optional.getAsJsonPrimitive().isBoolean()) {
                    throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL, "loader.optional必须为布尔值");
                }
                if (optional.getAsBoolean()) {
                    throw new ModelRule.Failure(BlockModelResolver.Reason.UNSUPPORTED_MODEL,
                            "可选loader需要游戏注册存在性证据；工具规则存在或缺失均不能代替游戏注册表");
                }
            }
            normalized = definition.deepCopy();
            normalized.addProperty(key,id.getAsString());
        }
        return normalized;
    }
}
