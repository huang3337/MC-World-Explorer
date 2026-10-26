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
    public static final int MAX_TEXTURES = 128;

    public enum Reason { RESOLVED, MISSING_RESOURCE, INVALID_JSON,
        INVALID_MODEL, UNSUPPORTED_MODEL, NO_VARIANT, AMBIGUOUS_VARIANT, REFERENCE_CYCLE, LIMIT_EXCEEDED }
    public enum Direction { DOWN, UP, NORTH, SOUTH, WEST, EAST }
    public record Uv(double u0, double v0, double u1, double v1) { }
    public record Vec3(double x, double y, double z) { }
    public record ElementRotation(Vec3 origin, String axis, double angle, boolean rescale) { }
    public record Face(Direction direction, ResourceLocation texture, Uv uv, int rotation,
                       int tintIndex, Direction cullFace, int layer, Vec3 from, Vec3 to,
                       ElementRotation elementRotation, boolean shade) { }
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
    public record Result(Reason reason, String detail, Optional<Model> model, List<Source> sources,
                         ClientResourceArchive.Fingerprint fingerprint) {
        public Result { sources = List.copyOf(sources); }
        public boolean resolved() { return reason == Reason.RESOLVED; }
    }

    private final BlockResourceSource archive;
    private record CachedJson(JsonObject value,int nodes,int chars) { }
    private final Map<Source,CachedJson> parsedJson=new LinkedHashMap<>(16,.75f,true);
    private int cachedNodes;
    private int cachedChars;
    private static final int MAX_CACHED_NODES=131_072;
    private static final int MAX_CACHED_CHARS=8*1024*1024;


    public BlockModelResolver(BlockResourceSource archive) { this.archive = archive instanceof BuiltinBlockModels ? archive : new BuiltinBlockModels(Objects.requireNonNull(archive),Map.of()); }

    /** Semantic failures return a traceable fallback; archive I/O/lifecycle failures propagate unchanged. */
    public Result resolve(BlockState state) throws IOException {
        Objects.requireNonNull(state);
        archive.validate();
        List<Source> sources = new ArrayList<>();
        try {
            ResourceLocation block = id(state.name());
            JsonObject root = read(ResourceLocation.Kind.BLOCKSTATE, block, sources);
            fields(root, Set.of("variants", "multipart", "__comment"), Reason.UNSUPPORTED_MODEL);
            if (root.has("variants") == root.has("multipart"))
                throw fail(Reason.INVALID_MODEL, "blockstate 必须恰含 variants 或 multipart");
            List<JsonElement> applications = new ArrayList<>();
            if (root.has("variants")) {
                JsonObject variants = object(root.get("variants"));
                if (variants.size() > MAX_VARIANTS) throw fail(Reason.LIMIT_EXCEEDED, "blockstate 分支过多");
                for (var entry : variants.entrySet()) if (matches(entry.getKey(), state.properties())) {
                    if (!applications.isEmpty()) throw fail(Reason.AMBIGUOUS_VARIANT, "多个 blockstate 分支匹配");
                    applications.add(entry.getValue());
                }
            } else {
                if (!root.get("multipart").isJsonArray()) throw fail(Reason.INVALID_MODEL, "multipart 必须为数组");
                var multipart = root.getAsJsonArray("multipart");
                if (multipart.size() > MAX_VARIANTS) throw fail(Reason.LIMIT_EXCEEDED, "multipart 部件过多");
                for (var value : multipart) {
                    var item = object(value);
                    fields(item, Set.of("when", "apply"), Reason.UNSUPPORTED_MODEL);
                    if (!item.has("when") || condition(item.get("when"), state.properties(), 0))
                        applications.add(item.get("apply"));
                }
            }
            if (applications.isEmpty()) {
                if(root.has("variants"))throw fail(Reason.NO_VARIANT,"没有匹配属性的模型分支");
            }
            List<Part> parts = new ArrayList<>();
            boolean fixed = false;
            for (JsonElement application : applications) {
                if (application == null) throw fail(Reason.INVALID_MODEL, "部件缺少 apply");
                if (application.isJsonArray()) {
                    var choices = application.getAsJsonArray();
                    if (choices.isEmpty() || choices.size() > MAX_VARIANTS)
                        throw fail(Reason.LIMIT_EXCEEDED, "候选模型数量无效或超限");
                    application = choices.get(0); fixed = true;
                }
                var variant = object(application);
                fields(variant, Set.of("model", "x", "y", "uvlock", "weight"), Reason.UNSUPPORTED_MODEL);
                ResourceLocation modelId = id(string(variant.get("model")));
                int x = rotation(variant, "x"), y = rotation(variant, "y");
                if (variant.has("weight") && integer(variant.get("weight")) <= 0)
                    throw fail(Reason.INVALID_MODEL, "weight 必须为正整数");
                Map<String, String> textures = new HashMap<>();
                String[] renderType={null};
                var elements = inherit(modelId, textures, new LinkedHashSet<>(), sources, renderType);
                if(renderType[0]!=null&&!Set.of("minecraft:solid","minecraft:cutout","minecraft:cutout_mipped","minecraft:translucent","minecraft:tripwire").contains(renderType[0]))
                    throw fail(Reason.UNSUPPORTED_MODEL,"自定义材质类型尚未适配："+renderType[0]);
                if(elements==null) {
                    Optional<List<Part>> builtin;
                    try{builtin=((BuiltinBlockModels)archive).resolve(state,textures);}
                    catch(ResourceAccessException sourceFailure){throw sourceFailure;}
                    catch(IOException invalidAppearance){throw fail(Reason.INVALID_MODEL,"特殊外观准备失败："+invalidAppearance.getMessage());}
                    if(builtin.isEmpty())builtin=Optional.of(List.of(new Part(modelId,x,y,bool(variant,"uvlock",false),List.of(),renderType[0])));
                    parts.addAll(builtin.orElseThrow());
                    for(var face:builtin.orElseThrow().stream().flatMap(part->part.faces().stream()).toList()){var reference=new Source(ResourceLocation.Kind.TEXTURE,face.texture());if(!sources.contains(reference))sources.add(reference);}
                } else parts.add(new Part(modelId, x, y, bool(variant, "uvlock", false), faces(elements, textures, sources), renderType[0]));
                if(parts.stream().mapToInt(part->part.faces().size()).sum()>1024)throw fail(Reason.LIMIT_EXCEEDED,"组合模型面数超过 1024");
            }
            if(parts.isEmpty())parts.add(new Part(block,0,0,false,List.of(),null));
            if("true".equals(state.properties().get("waterlogged"))) {
                var water=resolve(new BlockState("minecraft:water",Map.of("level","0")));
                if(water.resolved()) {
                    parts.addAll(water.model().orElseThrow().parts());sources.addAll(water.sources());
                    ((BuiltinBlockModels)archive).note(state,"含水模型叠加静态水面，水色与流向使用固定近似");
                }else ((BuiltinBlockModels)archive).note(state,"含水模型的水资源不可用："+water.detail());
            }
            Part first = parts.getFirst();
            var allFaces = parts.stream().flatMap(part -> part.faces().stream()).toList();
            if (allFaces.size() > 1024) throw fail(Reason.LIMIT_EXCEEDED, "组合模型面数超过 1024");
            return new Result(Reason.RESOLVED, fixed ? "已解析；随机候选固定选择第一项" : "已解析",
                    Optional.of(new Model(first.id(), first.xRotation(), first.yRotation(), first.uvLock(), allFaces, fixed, parts)),
                    sources, archive.fingerprint());
        } catch (Failure failure) {
            return new Result(failure.reason, failure.getMessage(), Optional.empty(), sources, archive.fingerprint());
        } finally { archive.validate(); }
    }

    private JsonArray inherit(ResourceLocation model, Map<String, String> textures,
                              Set<ResourceLocation> chain, List<Source> sources, String[] renderType) throws IOException, Failure {
        if (model.namespace().equals("minecraft") && model.path().startsWith("builtin/"))
            throw fail(Reason.UNSUPPORTED_MODEL, "需要内置特殊渲染：" + model);
        if (!chain.add(model)) throw fail(Reason.REFERENCE_CYCLE, "父模型循环：" + model);
        if (chain.size() > MAX_PARENT_DEPTH) throw fail(Reason.LIMIT_EXCEEDED, "父模型层数超过上限");
        JsonObject json = read(ResourceLocation.Kind.MODEL, model, sources);
        fields(json, Set.of("parent", "textures", "elements", "ambientocclusion", "display", "gui_light", "render_type", "__comment", "texture_size", "name", "groups"),
                Reason.UNSUPPORTED_MODEL);
        bool(json, "ambientocclusion", true); // Existing preview uses directional light, not Minecraft AO.
        JsonArray elements = null;
        if (json.has("parent")) elements = inherit(id(string(json.get("parent"))), textures, chain, sources, renderType);
        if (json.has("render_type"))renderType[0]=id(string(json.get("render_type"))).toString();
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
        if (elements == null) throw fail(Reason.UNSUPPORTED_MODEL, "资源未声明元素几何，需要特殊渲染或空模型语义");
        if (elements.size() > 128) throw fail(Reason.LIMIT_EXCEEDED, "模型元素超过 128 项");
        for (String key : textures.keySet()) texture("#" + key, textures);
        List<Face> result = new ArrayList<>();
        for (int layer = 0; layer < elements.size(); layer++) {
            JsonObject element = object(elements.get(layer));
            fields(element, Set.of("from", "to", "faces", "shade", "rotation", "light_emission", "__comment", "name"), Reason.UNSUPPORTED_MODEL);
            Vec3 from = vector(element.get("from")), to = vector(element.get("to"));
            boolean shade = bool(element, "shade", true);
            ElementRotation transform = null;
            if (element.has("rotation")) {
                var rotation = object(element.get("rotation"));
                fields(rotation, Set.of("origin", "axis", "angle", "rescale"), Reason.UNSUPPORTED_MODEL);
                String axis = string(rotation.get("axis"));
                if (!Set.of("x", "y", "z").contains(axis)) throw fail(Reason.INVALID_MODEL, "元素旋转轴无效");
                double angle = number(rotation.get("angle"));
                if (Math.abs(angle) > 180) throw fail(Reason.LIMIT_EXCEEDED, "元素旋转角度超限");
                boolean rescale = bool(rotation, "rescale", false);
                if (rescale && Math.abs(Math.cos(Math.toRadians(angle))) < .1)
                    throw fail(Reason.UNSUPPORTED_MODEL, "rescale 旋转接近奇点");
                transform = new ElementRotation(vector(rotation.get("origin")), axis, angle, rescale);
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
                fields(face, Set.of("texture", "uv", "rotation", "tintindex", "cullface"), Reason.UNSUPPORTED_MODEL);
                ResourceLocation texture = texture(string(face.get("texture")), textures);
                Uv uv = defaultUv(direction, from, to);
                if (face.has("uv")) {
                    JsonArray a = array(face.get("uv"), 4);
                    uv = new Uv(coordinate(a.get(0)), coordinate(a.get(1)), coordinate(a.get(2)), coordinate(a.get(3)));
                }
                for (double coordinate : new double[]{uv.u0(), uv.v0(), uv.u1(), uv.v1()})
                    if (coordinate < 0 || coordinate > 16) throw fail(Reason.UNSUPPORTED_MODEL, "UV 需要纹理重复采样，尚未支持");
                int tint = face.has("tintindex") ? integer(face.get("tintindex")) : -1;
                if (tint < -1 || tint > 255) throw fail(Reason.LIMIT_EXCEEDED, "tintindex 超出范围");
                Direction cull=null;
                if(face.has("cullface")) {
                    String hint=string(face.get("cullface"));
                    // Vanilla treats unrecognised optional cull directions as absent, retaining the face.
                    for(Direction candidate:Direction.values())if(candidate.name().equalsIgnoreCase(hint))cull=candidate;
                }
                result.add(new Face(direction, texture, uv, rotation(face, "rotation"), tint, cull, layer,
                        from, to, transform, shade));
                Source source = new Source(ResourceLocation.Kind.TEXTURE, texture);
                if (!sources.contains(source)) sources.add(source);
            }
        }
        return result;
    }

    private ResourceLocation texture(String value, Map<String, String> textures) throws Failure {
        Set<String> visited = new HashSet<>();
        if(textures.containsKey(value))value="#"+value; // Legacy bare aliases occur in declared client models.
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
            } else match &= alternatives(string(entry.getValue()), properties.get(entry.getKey()));
        }
        return match;
    }

    private JsonObject read(ResourceLocation.Kind kind, ResourceLocation id, List<Source> sources) throws IOException, Failure {
        Source key=new Source(kind,id);sources.add(key);
        var cached=parsedJson.get(key);if(cached!=null)return cached.value();
        String text = archive.readJson(kind, id).orElseThrow(() -> fail(Reason.MISSING_RESOURCE, "缺少资源：" + id.entryName(kind)));
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
    private static double number(JsonElement e) throws Failure {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
            throw fail(Reason.INVALID_MODEL, "需要有限数值");
        double value = e.getAsDouble();
        if (!Double.isFinite(value)) throw fail(Reason.INVALID_MODEL, "数值不是有限值");
        return value;
    }
    private static Vec3 vector(JsonElement e) throws Failure {
        var a = array(e, 3);
        double x = number(a.get(0)), y = number(a.get(1)), z = number(a.get(2));
        if (Math.min(x, Math.min(y,z)) < -16 || Math.max(x,Math.max(y,z)) > 32)
            throw fail(Reason.LIMIT_EXCEEDED, "元素坐标超出 -16..32");
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
