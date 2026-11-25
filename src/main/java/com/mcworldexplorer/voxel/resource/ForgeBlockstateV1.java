package com.mcworldexplorer.voxel.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mcworldexplorer.voxel.data.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure declarative parser for 1.8–1.12.2 Forge Blockstate V1 ({@code "forge_marker": 1}).
 * Supports {@code defaults}, fully specified variants, single-property Cartesian variant maps,
 * {@code submodel} composition, and {@code x}/{@code y}/{@code uvlock} transforms without
 * executing any Mod bytecode.
 */
final class ForgeBlockstateV1 {
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of("forge_marker", "defaults", "variants");
    private static final Set<String> VARIANT_FIELDS = Set.of(
            "model", "textures", "x", "y", "uvlock", "weight", "submodel", "transform", "smooth_lighting", "gui3d");
    private static final Set<String> IDENTITY_WORLD_TRANSFORMS = Set.of(
            "forge:default-block", "forge:default-item", "forge:default-tool", "identity");

    record ResolvedModel(ResourceLocation model, int x, int y, boolean uvLock, Map<String, TextureBinding> textures) { }

    record Plan(List<ResolvedModel> models, boolean fixedFirstAlternative) { }

    private ForgeBlockstateV1() { }

    static boolean isForgeV1(JsonObject root) {
        if (!root.has("forge_marker")) return false;
        var marker = root.get("forge_marker");
        return marker.isJsonPrimitive() && marker.getAsJsonPrimitive().isNumber();
    }

    static Plan parse(JsonObject root, BlockState state) throws ModelRule.Failure {
        int marker = integerField(root.get("forge_marker"), "forge_marker");
        if (marker != 1) {
            throw new ModelRule.Failure(BlockModelResolver.Reason.UNSUPPORTED_MODEL,
                    "不支持的 forge_marker 版本：" + marker);
        }
        for (String key : root.keySet()) {
            if (!TOP_LEVEL_FIELDS.contains(key) && !ModelDefinitionProtocol.annotation(key)) {
                throw new ModelRule.Failure(BlockModelResolver.Reason.UNSUPPORTED_MODEL,
                        "Forge Blockstate V1 包含未支持顶层字段：" + key);
            }
        }
        if (!root.has("variants") || !root.get("variants").isJsonObject()) {
            throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL,
                    "Forge Blockstate V1 缺少 variants 对象");
        }
        JsonObject variants = root.getAsJsonObject("variants");
        if (variants.size() > BlockModelResolver.MAX_STATE_BRANCHES) {
            throw new ModelRule.Failure(BlockModelResolver.Reason.LIMIT_EXCEEDED,
                    "Forge Blockstate V1 variants 分支过多");
        }

        VariantSpec base = new VariantSpec();
        if (root.has("defaults")) {
            if (!root.get("defaults").isJsonObject()) {
                throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL,
                        "Forge Blockstate V1 defaults 必须为对象");
            }
            applyObject(base, root.getAsJsonObject("defaults"));
        }

        boolean[] fixedFirst = {false};
        List<JsonObject> fullMatches = new ArrayList<>();
        Map<String, JsonObject> propertyMaps = new LinkedHashMap<>();

        for (var entry : variants.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (key.equals("inventory")) continue;
            if (isPropertyValueMap(key, value)) {
                propertyMaps.put(key, value.getAsJsonObject());
            } else if (matchesFullVariant(key, state)) {
                fullMatches.add(selectVariantObject(value, fixedFirst));
            }
        }

        if (fullMatches.size() > 1) {
            throw new ModelRule.Failure(BlockModelResolver.Reason.AMBIGUOUS_VARIANT,
                    "Forge Blockstate V1 多个完整变体分支匹配");
        }

        if (!fullMatches.isEmpty()) {
            applyObject(base, fullMatches.getFirst());
        } else if (!propertyMaps.isEmpty()) {
            for (var propEntry : propertyMaps.entrySet()) {
                String propName = propEntry.getKey();
                JsonObject valueMap = propEntry.getValue();
                String actual = state.properties().get(propName);
                if (actual == null) {
                    if (valueMap.size() == 1) {
                        actual = valueMap.keySet().iterator().next();
                    } else {
                        throw new ModelRule.Failure(BlockModelResolver.Reason.UNSUPPORTED_MODEL,
                                "Forge Blockstate V1 需要运行时属性 " + propName + "（预扁平化方块未执行 Mod 类映射）");
                    }
                }
                if (!valueMap.has(actual)) {
                    throw new ModelRule.Failure(BlockModelResolver.Reason.NO_VARIANT,
                            "Forge Blockstate V1 属性 " + propName + "=" + actual + " 无匹配分支");
                }
                applyObject(base, selectVariantObject(valueMap.get(actual), fixedFirst));
            }
        } else {
            throw new ModelRule.Failure(BlockModelResolver.Reason.NO_VARIANT,
                    "Forge Blockstate V1 没有匹配的变体分支");
        }

        List<ResolvedModel> resolved = new ArrayList<>();
        collectModels(base, resolved, 0);
        if (resolved.isEmpty()) {
            throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL,
                    "Forge Blockstate V1 未声明任何有效 model 或 submodel");
        }
        return new Plan(List.copyOf(resolved), fixedFirst[0]);
    }

    private static void collectModels(VariantSpec spec, List<ResolvedModel> out, int depth) throws ModelRule.Failure {
        if (depth > 8) {
            throw new ModelRule.Failure(BlockModelResolver.Reason.LIMIT_EXCEEDED,
                    "Forge Blockstate V1 submodel 嵌套过深");
        }
        if (spec.model != null) {
            out.add(new ResolvedModel(spec.model, spec.x, spec.y, spec.uvLock, Map.copyOf(spec.textures)));
        }
        for (VariantSpec sub : spec.submodels.values()) {
            VariantSpec merged = new VariantSpec();
            merged.textures.putAll(spec.textures);
            merged.textures.putAll(sub.textures);
            merged.model = sub.model;
            merged.x = (spec.x + sub.x) % 360;
            merged.y = (spec.y + sub.y) % 360;
            merged.uvLock = spec.uvLock || sub.uvLock;
            merged.submodels.putAll(sub.submodels);
            collectModels(merged, out, depth + 1);
            if (out.size() > BlockModelResolver.MAX_VARIANTS) {
                throw new ModelRule.Failure(BlockModelResolver.Reason.LIMIT_EXCEEDED,
                        "Forge Blockstate V1 子模型数量超限");
            }
        }
    }

    private static boolean isPropertyValueMap(String key, JsonElement value) {
        if (key.isEmpty() || key.equals("normal") || key.contains("=") || key.contains(",")) return false;
        if (value == null || !value.isJsonObject()) return false;
        JsonObject obj = value.getAsJsonObject();
        if (obj.isEmpty()) return false;
        // If all keys are variant fields (e.g. "model", "textures", "x", "y"), it's a variant object, not a property map.
        for (String childKey : obj.keySet()) {
            if (!VARIANT_FIELDS.contains(childKey) && !ModelDefinitionProtocol.annotation(childKey)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesFullVariant(String selector, BlockState state) throws ModelRule.Failure {
        if (selector.isEmpty() || selector.equals("normal")) return true;
        if (!selector.contains("=")) return false;
        for (String condition : selector.split(",", -1)) {
            String[] pair = condition.split("=", -1);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) {
                throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL,
                        "Forge Blockstate V1 属性选择器无效：" + selector);
            }
            String actual = state.properties().get(pair[0]);
            if (actual == null || !actual.equals(pair[1])) return false;
        }
        return true;
    }

    private static JsonObject selectVariantObject(JsonElement value, boolean[] fixedFirst) throws ModelRule.Failure {
        if (value == null) {
            throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL, "Forge Blockstate V1 变体为空");
        }
        if (value.isJsonObject()) return value.getAsJsonObject();
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            if (array.isEmpty() || array.size() > BlockModelResolver.MAX_VARIANTS) {
                throw new ModelRule.Failure(BlockModelResolver.Reason.LIMIT_EXCEEDED,
                        "Forge Blockstate V1 候选数组为空或超限");
            }
            if (array.size() > 1) fixedFirst[0] = true;
            JsonElement first = array.get(0);
            if (!first.isJsonObject()) {
                throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL,
                        "Forge Blockstate V1 候选元素必须为对象");
            }
            return first.getAsJsonObject();
        }
        throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL,
                "Forge Blockstate V1 变体必须为对象或数组");
    }

    private static void applyObject(VariantSpec target, JsonObject obj) throws ModelRule.Failure {
        for (String key : obj.keySet()) {
            if (key.equals("custom")) {
                throw new ModelRule.Failure(BlockModelResolver.Reason.UNSUPPORTED_MODEL,
                        "Forge Blockstate V1 custom 运行时加载器数据未支持");
            }
            if (!VARIANT_FIELDS.contains(key) && !ModelDefinitionProtocol.annotation(key)) {
                throw new ModelRule.Failure(BlockModelResolver.Reason.UNSUPPORTED_MODEL,
                        "Forge Blockstate V1 未支持变体字段：" + key);
            }
        }
        if (obj.has("model")) {
            JsonElement raw = obj.get("model");
            if (raw.isJsonNull()) {
                target.model = null;
            } else if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
                target.model = resolveLegacyModelId(raw.getAsString());
            } else {
                throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL,
                        "Forge Blockstate V1 model 必须为字符串");
            }
        }
        if (obj.has("textures")) {
            if (!obj.get("textures").isJsonObject()) {
                throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL,
                        "Forge Blockstate V1 textures 必须为对象");
            }
            for (var entry : obj.getAsJsonObject("textures").entrySet()) {
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                    throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL,
                            "Forge Blockstate V1 纹理值必须为字符串：" + entry.getKey());
                }
                String ref = entry.getValue().getAsString();
                if (!ref.startsWith("#")) {
                    parseId(ref);
                }
                target.textures.put(entry.getKey(), new TextureBinding(ref, false));
                if (target.textures.size() > BlockModelResolver.MAX_TEXTURES) {
                    throw new ModelRule.Failure(BlockModelResolver.Reason.LIMIT_EXCEEDED,
                            "Forge Blockstate V1 纹理变量超过上限");
                }
            }
        }
        if (obj.has("x")) target.x = (target.x + rotationField(obj.get("x"), "x")) % 360;
        if (obj.has("y")) target.y = (target.y + rotationField(obj.get("y"), "y")) % 360;
        if (obj.has("uvlock")) {
            JsonElement raw = obj.get("uvlock");
            if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isBoolean()) {
                throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL, "uvlock 必须为布尔值");
            }
            target.uvLock = raw.getAsBoolean();
        }
        if (obj.has("weight")) {
            if (integerField(obj.get("weight"), "weight") <= 0) {
                throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL, "weight 必须为正整数");
            }
        }
        if (obj.has("transform")) {
            JsonElement transform = obj.get("transform");
            if (transform.isJsonPrimitive() && transform.getAsJsonPrimitive().isString()) {
                String name = transform.getAsString();
                if (!IDENTITY_WORLD_TRANSFORMS.contains(name)) {
                    throw new ModelRule.Failure(BlockModelResolver.Reason.UNSUPPORTED_MODEL,
                            "Forge Blockstate V1 未支持命名变换：" + name);
                }
            } else if (!transform.isJsonObject()) {
                throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL,
                        "Forge Blockstate V1 transform 格式无效");
            }
        }
        if (obj.has("submodel")) {
            JsonElement raw = obj.get("submodel");
            if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
                VariantSpec sub = new VariantSpec();
                sub.model = resolveLegacyModelId(raw.getAsString());
                target.submodels.put("default", sub);
            } else if (raw.isJsonObject()) {
                for (var entry : raw.getAsJsonObject().entrySet()) {
                    VariantSpec sub = target.submodels.computeIfAbsent(entry.getKey(), ignored -> new VariantSpec());
                    JsonElement subVal = entry.getValue();
                    if (subVal.isJsonPrimitive() && subVal.getAsJsonPrimitive().isString()) {
                        sub.model = resolveLegacyModelId(subVal.getAsString());
                    } else if (subVal.isJsonObject() || subVal.isJsonArray()) {
                        boolean[] ignoredFixed = {false};
                        applyObject(sub, selectVariantObject(subVal, ignoredFixed));
                    } else if (subVal.isJsonNull()) {
                        target.submodels.remove(entry.getKey());
                    } else {
                        throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL,
                                "Forge Blockstate V1 submodel 值无效：" + entry.getKey());
                    }
                }
            } else {
                throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL,
                        "Forge Blockstate V1 submodel 必须为字符串或对象");
            }
        }
    }

    static ResourceLocation resolveLegacyModelId(String raw) throws ModelRule.Failure {
        ResourceLocation id = parseId(raw);
        if (id.path().startsWith("block/") || id.path().startsWith("item/") || id.path().startsWith("builtin/")) {
            return id;
        }
        return new ResourceLocation(id.namespace(), "block/" + id.path());
    }

    private static ResourceLocation parseId(String raw) throws ModelRule.Failure {
        try {
            return ResourceLocation.parse(raw);
        } catch (IllegalArgumentException invalid) {
            throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL, "非法资源 ID：" + raw);
        }
    }

    private static int integerField(JsonElement value, String name) throws ModelRule.Failure {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL, name + " 必须为整数");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL, name + " 必须为整数");
        }
    }

    private static int rotationField(JsonElement value, String name) throws ModelRule.Failure {
        int degrees = integerField(value, name);
        int normalized = Math.floorMod(degrees, 360);
        if (normalized % 90 != 0) {
            throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL, name + " 旋转角度必须为 90 的倍数");
        }
        return normalized;
    }

    private static final class VariantSpec {
        ResourceLocation model;
        int x;
        int y;
        boolean uvLock;
        final Map<String, TextureBinding> textures = new LinkedHashMap<>();
        final Map<String, VariantSpec> submodels = new LinkedHashMap<>();
    }
}
