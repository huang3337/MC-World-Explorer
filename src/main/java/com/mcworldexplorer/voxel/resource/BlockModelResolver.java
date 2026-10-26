package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mcworldexplorer.voxel.data.BlockState;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;

/** Bounded, CPU-only resolver for the V0.7.1 full-cube subset. Does not own its archive. */
public final class BlockModelResolver {
    public static final int MAX_PARENT_DEPTH = 32;
    public static final int MAX_JSON_NODES = 16_384;
    public static final int MAX_VARIANTS = 256;
    public static final int MAX_TEXTURES = 128;
    private static final Set<String> SUPPORTED = Set.of("stone", "cobblestone", "dirt", "grass_block",
            "oak_planks", "oak_log", "bricks", "sand");

    public enum Reason { RESOLVED, UNSUPPORTED_BLOCK, MISSING_RESOURCE, INVALID_JSON,
        INVALID_MODEL, UNSUPPORTED_MODEL, NO_VARIANT, AMBIGUOUS_VARIANT, REFERENCE_CYCLE, LIMIT_EXCEEDED }
    public enum Direction { DOWN, UP, NORTH, SOUTH, WEST, EAST }
    public record Uv(double u0, double v0, double u1, double v1) { }
    /** Faces remain in model space; R4 applies blockstate x/y and face UV rotation. */
    public record Face(Direction direction, ResourceLocation texture, Uv uv, int rotation,
                       int tintIndex, Direction cullFace, int layer) { }
    public record Model(ResourceLocation id, int xRotation, int yRotation, boolean uvLock,
                        List<Face> faces, boolean fixedFirstAlternative) {
        public Model { faces = List.copyOf(faces); }
    }
    public record Source(ResourceLocation.Kind kind, ResourceLocation id) { }
    public record Result(Reason reason, String detail, Optional<Model> model, List<Source> sources,
                         ClientResourceArchive.Fingerprint fingerprint) {
        public Result { sources = List.copyOf(sources); }
        public boolean resolved() { return reason == Reason.RESOLVED; }
    }

    private final ClientResourceArchive archive;

    public BlockModelResolver(ClientResourceArchive archive) { this.archive = Objects.requireNonNull(archive); }

    /** Semantic failures return a traceable fallback; archive I/O/lifecycle failures propagate unchanged. */
    public Result resolve(BlockState state) throws IOException {
        Objects.requireNonNull(state);
        List<Source> sources = new ArrayList<>();
        try {
            ResourceLocation block = id(state.name());
            if (!block.namespace().equals("minecraft") || !SUPPORTED.contains(block.path()))
                throw fail(Reason.UNSUPPORTED_BLOCK, "方块不在 V0.7.1 支持清单：" + block);
            JsonObject root = read(ResourceLocation.Kind.BLOCKSTATE, block, sources);
            fields(root, Set.of("variants"), Reason.UNSUPPORTED_MODEL);
            JsonObject variants = object(root.get("variants"));
            if (variants.size() > MAX_VARIANTS) throw fail(Reason.LIMIT_EXCEEDED, "blockstate 分支过多");
            JsonElement selected = null;
            for (var entry : variants.entrySet()) {
                if (matches(entry.getKey(), state.properties())) {
                    if (selected != null) throw fail(Reason.AMBIGUOUS_VARIANT, "多个 blockstate 分支匹配");
                    selected = entry.getValue();
                }
            }
            if (selected == null) throw fail(Reason.NO_VARIANT, "没有匹配属性的 blockstate 分支");
            boolean fixed = selected.isJsonArray();
            if (fixed) {
                JsonArray choices = selected.getAsJsonArray();
                if (choices.isEmpty() || choices.size() > MAX_VARIANTS)
                    throw fail(Reason.LIMIT_EXCEEDED, "候选模型数量无效或超限");
                selected = choices.get(0); // Explicit deterministic approximation, never advertised as weighted random.
            }
            JsonObject variant = object(selected);
            fields(variant, Set.of("model", "x", "y", "uvlock", "weight"), Reason.UNSUPPORTED_MODEL);
            ResourceLocation modelId = id(string(variant.get("model")));
            int x = rotation(variant, "x"), y = rotation(variant, "y");
            boolean uvLock = bool(variant, "uvlock", false);
            if (variant.has("weight") && integer(variant.get("weight")) <= 0)
                throw fail(Reason.INVALID_MODEL, "weight 必须为正整数");
            Map<String, String> textures = new HashMap<>();
            JsonArray elements = inherit(modelId, textures, new LinkedHashSet<>(), sources);
            List<Face> faces = faces(elements, textures, sources);
            return new Result(Reason.RESOLVED, fixed ? "已解析；随机候选固定选择第一项" : "已解析",
                    Optional.of(new Model(modelId, x, y, uvLock, faces, fixed)), sources, archive.fingerprint());
        } catch (Failure failure) {
            return new Result(failure.reason, failure.getMessage(), Optional.empty(), sources, archive.fingerprint());
        }
    }

    private JsonArray inherit(ResourceLocation model, Map<String, String> textures,
                              Set<ResourceLocation> chain, List<Source> sources) throws IOException, Failure {
        if (!chain.add(model)) throw fail(Reason.REFERENCE_CYCLE, "父模型循环：" + model);
        if (chain.size() > MAX_PARENT_DEPTH) throw fail(Reason.LIMIT_EXCEEDED, "父模型层数超过上限");
        JsonObject json = read(ResourceLocation.Kind.MODEL, model, sources);
        fields(json, Set.of("parent", "textures", "elements", "ambientocclusion", "display", "gui_light"),
                Reason.UNSUPPORTED_MODEL);
        if (json.has("ambientocclusion") && !bool(json, "ambientocclusion", true))
            throw fail(Reason.UNSUPPORTED_MODEL, "暂不支持关闭环境遮蔽");
        JsonArray elements = null;
        if (json.has("parent")) elements = inherit(id(string(json.get("parent"))), textures, chain, sources);
        if (json.has("textures")) {
            JsonObject local = object(json.get("textures"));
            for (var entry : local.entrySet()) {
                variable(entry.getKey());
                textures.put(entry.getKey(), string(entry.getValue()));
                if (textures.size() > MAX_TEXTURES) throw fail(Reason.LIMIT_EXCEEDED, "纹理变量过多");
            }
        }
        if (json.has("elements")) {
            if (!json.get("elements").isJsonArray()) throw fail(Reason.INVALID_MODEL, "elements 必须为数组");
            elements = json.getAsJsonArray("elements");
        }
        chain.remove(model);
        return elements;
    }

    private List<Face> faces(JsonArray elements, Map<String, String> textures, List<Source> sources) throws Failure {
        if (elements == null || elements.isEmpty() || elements.size() > 2)
            throw fail(Reason.UNSUPPORTED_MODEL, "仅支持完整立方体及单层侧面覆盖");
        // Validate even unused variables, so cycles and invalid resource IDs cannot hide in particle aliases.
        for (String key : textures.keySet()) texture("#" + key, textures);
        List<Face> result = new ArrayList<>();
        for (int layer = 0; layer < elements.size(); layer++) {
            JsonObject element = object(elements.get(layer));
            fields(element, Set.of("from", "to", "faces", "shade"), Reason.UNSUPPORTED_MODEL);
            vector(element.get("from"), 0); vector(element.get("to"), 16);
            if (element.has("shade") && !bool(element, "shade", true))
                throw fail(Reason.UNSUPPORTED_MODEL, "暂不支持关闭面着色");
            JsonObject faces = object(element.get("faces"));
            if ((layer == 0 && faces.size() != 6) || (layer == 1 && faces.size() != 4))
                throw fail(Reason.UNSUPPORTED_MODEL, "基础模型需要六面；覆盖层仅支持四侧面");
            for (var entry : faces.entrySet()) {
                Direction direction = direction(entry.getKey());
                if (layer == 1 && (direction == Direction.UP || direction == Direction.DOWN))
                    throw fail(Reason.UNSUPPORTED_MODEL, "覆盖层仅支持侧面");
                JsonObject face = object(entry.getValue());
                fields(face, Set.of("texture", "uv", "rotation", "tintindex", "cullface"), Reason.UNSUPPORTED_MODEL);
                ResourceLocation texture = texture(string(face.get("texture")), textures);
                Uv uv = new Uv(0, 0, 16, 16);
                if (face.has("uv")) {
                    JsonArray a = array(face.get("uv"), 4);
                    uv = new Uv(coordinate(a.get(0)), coordinate(a.get(1)), coordinate(a.get(2)), coordinate(a.get(3)));
                }
                int tint = face.has("tintindex") ? integer(face.get("tintindex")) : -1;
                if (tint < -1 || tint > 0) throw fail(Reason.UNSUPPORTED_MODEL, "暂仅保留 tintindex 0");
                Direction cull = face.has("cullface") ? direction(string(face.get("cullface"))) : null;
                if (cull != null && cull != direction) throw fail(Reason.UNSUPPORTED_MODEL, "非同向 cullface 暂不支持");
                if (layer == 1 && (tint != 0 || cull != direction))
                    throw fail(Reason.UNSUPPORTED_MODEL, "覆盖层需要同向裁剪及 tintindex 0");
                result.add(new Face(direction, texture, uv, rotation(face, "rotation"), tint, cull, layer));
                Source source = new Source(ResourceLocation.Kind.TEXTURE, texture);
                if (!sources.contains(source)) sources.add(source); // Reference trace, not a PNG decode claim.
            }
        }
        return result;
    }

    private ResourceLocation texture(String value, Map<String, String> textures) throws Failure {
        Set<String> visited = new HashSet<>();
        while (value.startsWith("#")) {
            String key = value.substring(1); variable(key);
            if (!visited.add(key)) throw fail(Reason.REFERENCE_CYCLE, "纹理变量循环：" + key);
            if (visited.size() > MAX_TEXTURES) throw fail(Reason.LIMIT_EXCEEDED, "纹理引用链过长");
            value = textures.get(key);
            if (value == null) throw fail(Reason.MISSING_RESOURCE, "未定义纹理变量：" + key);
        }
        return id(value);
    }

    private boolean matches(String selector, Map<String, String> properties) throws Failure {
        if (selector.isEmpty()) return true;
        Set<String> keys = new HashSet<>();
        boolean matches = true;
        for (String condition : selector.split(",", -1)) {
            String[] pair = condition.split("=", -1);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank() || !keys.add(pair[0]))
                throw fail(Reason.INVALID_MODEL, "非法 blockstate 属性选择器");
            if (pair[1].contains("|")) throw fail(Reason.UNSUPPORTED_MODEL, "暂不支持组合属性选择器");
            matches &= pair[1].equals(properties.get(pair[0]));
        }
        return matches;
    }

    private JsonObject read(ResourceLocation.Kind kind, ResourceLocation id, List<Source> sources) throws IOException, Failure {
        sources.add(new Source(kind, id));
        String text = archive.readJson(kind, id).orElseThrow(() -> fail(Reason.MISSING_RESOURCE, "缺少资源：" + id.entryName(kind)));
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            reader.setNestingLimit(32);
            JsonElement value = tree(reader, new int[]{MAX_JSON_NODES});
            if (reader.peek() != JsonToken.END_DOCUMENT) throw fail(Reason.INVALID_JSON, "JSON 尾随内容");
            return object(value);
        } catch (IOException | NumberFormatException e) {
            throw fail(Reason.INVALID_JSON, "非法或嵌套超限 JSON：" + id);
        }
    }

    private JsonElement tree(JsonReader reader, int[] budget) throws IOException, Failure {
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

    private static void fields(JsonObject o, Set<String> allowed, Reason reason) throws Failure {
        for (String key : o.keySet()) if (!allowed.contains(key)) throw fail(reason, "不支持的模型字段：" + key);
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
        if (key.length() > 128 || !key.matches("[a-z0-9_]+")) throw fail(Reason.INVALID_MODEL, "非法纹理变量名");
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
    private static void vector(JsonElement e, int expected) throws Failure {
        for (JsonElement v : array(e, 3)) if (coordinate(v) != expected)
            throw fail(Reason.UNSUPPORTED_MODEL, "暂仅支持 from=0、to=16 的完整立方体");
    }
    private static Failure fail(Reason reason, String message) { return new Failure(reason, message); }
    private static final class Failure extends Exception {
        final Reason reason;
        Failure(Reason reason, String message) { super(message); this.reason = reason; }
    }
}
