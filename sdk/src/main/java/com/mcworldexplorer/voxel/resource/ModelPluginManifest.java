package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.util.*;

/** Inert package metadata. Reading or validating it never loads the adapter entrypoint. */
public record ModelPluginManifest(int format,String sdkRevision,Set<String> compatibleHosts,
                                  String execution,String entrypoint,ModelPlugin.Descriptor plugin) {
    public static final String ENTRY="META-INF/mcwe-model-plugin.json";
    public static final String SDK_REVISION="mcwe-model-api-1-core.1";
    public static final int MAX_JSON_CHARS=32*1024;
    public ModelPluginManifest {
        if(format!=1)throw new IllegalArgumentException("不支持的插件包格式："+format);
        if(!SDK_REVISION.equals(sdkRevision))throw new IllegalArgumentException("插件接口版本不兼容："+sdkRevision);
        compatibleHosts=Set.copyOf(compatibleHosts);
        if(compatibleHosts.isEmpty()||compatibleHosts.size()>16||compatibleHosts.stream().anyMatch(v->!v.matches("[A-Za-z0-9_.+-]{1,64}")))
            throw new IllegalArgumentException("插件兼容宿主版本列表无效");
        if(!"isolated-jvm".equals(execution))throw new IllegalArgumentException("插件必须声明独立JVM执行协议");
        if(entrypoint==null||entrypoint.length()>256||!entrypoint.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
            ||entrypoint.startsWith("java.")||entrypoint.startsWith("javax.")||entrypoint.startsWith("com.mcworldexplorer."))
            throw new IllegalArgumentException("插件入口类名无效或使用宿主保留包");
        Objects.requireNonNull(plugin);
        if(plugin.apiVersion()!=ModelPlugin.API_VERSION)throw new IllegalArgumentException("插件API不兼容："+plugin.apiVersion());
    }
    public void requireHost(String version){if(!compatibleHosts.contains(version))throw new IllegalArgumentException("插件未声明兼容当前宿主："+version);}
    public String json(){return new GsonBuilder().registerTypeAdapter(ResourceLocation.class,(JsonSerializer<ResourceLocation>)(id,type,context)->new JsonPrimitive(id.toString())).setPrettyPrinting().create().toJson(this);}
    public static ModelPluginManifest parse(String json)throws IOException {
        if(json==null||json.length()>MAX_JSON_CHARS)throw new IOException("插件清单超过32KiB字符预算或为空");
        try(var reader=new JsonReader(new StringReader(json))){
            reader.setStrictness(Strictness.STRICT);
            JsonElement value=read(reader,0,new int[]{0});
            if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IOException("插件清单包含尾随内容");
            JsonObject root=object(value,Set.of("format","sdkRevision","compatibleHosts","execution","entrypoint","plugin"));
            JsonObject plugin=object(root.get("plugin"),Set.of("id","name","version","apiVersion","description","targetMods","dependencies","capabilities"));
            // Resource IDs have a human-readable string form in package metadata, independent of Gson DTO layout.
            ResourceLocation id=ResourceLocation.parse(string(plugin.get("id")));
            Set<String> targets=strings(plugin.get("targetMods"));var dependencies=new HashMap<ResourceLocation,String>();
            if(!plugin.get("dependencies").isJsonObject())throw new IllegalArgumentException("插件依赖必须为对象");
            for(var e:plugin.getAsJsonObject("dependencies").entrySet())if(dependencies.putIfAbsent(ResourceLocation.parse(e.getKey()),string(e.getValue()))!=null)throw new IllegalArgumentException("重复的插件依赖ID");
            Set<ModelPlugin.Capability> capabilities=new HashSet<>();
            for(String capability:strings(plugin.get("capabilities")))capabilities.add(ModelPlugin.Capability.valueOf(capability));
            var descriptor=new ModelPlugin.Descriptor(id,string(plugin.get("name")),string(plugin.get("version")),integer(plugin.get("apiVersion")),string(plugin.get("description")),targets,dependencies,capabilities);
            return new ModelPluginManifest(integer(root.get("format")),string(root.get("sdkRevision")),strings(root.get("compatibleHosts")),string(root.get("execution")),string(root.get("entrypoint")),descriptor);
        }catch(IllegalArgumentException|IllegalStateException|NullPointerException exception){throw new IOException("插件清单无效："+exception.getMessage(),exception);}
    }
    private static JsonObject object(JsonElement value,Set<String> keys){
        if(value==null||!value.isJsonObject()||!value.getAsJsonObject().keySet().equals(keys))throw new IllegalArgumentException("插件清单字段缺失或未知");
        return value.getAsJsonObject();
    }
    private static String string(JsonElement value){if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("清单字段必须为字符串");return value.getAsString();}
    private static int integer(JsonElement value){
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!value.getAsString().matches("0|[1-9][0-9]{0,8}"))throw new IllegalArgumentException("清单字段必须为非负整数");
        return Integer.parseInt(value.getAsString());
    }
    private static Set<String> strings(JsonElement value){
        if(value==null||!value.isJsonArray())throw new IllegalArgumentException("清单字段必须为字符串数组");
        var result=new HashSet<String>();for(var item:value.getAsJsonArray())if(!result.add(string(item)))throw new IllegalArgumentException("清单列表包含重复项");return Set.copyOf(result);
    }
    private static JsonElement read(JsonReader reader,int depth,int[] nodes)throws IOException {
        if(depth>8||++nodes[0]>2048)throw new IOException("插件清单结构超过预算");
        return switch(reader.peek()){
            case BEGIN_OBJECT -> {var object=new JsonObject();reader.beginObject();while(reader.hasNext()){String key=reader.nextName();if(object.has(key))throw new IOException("插件清单重复字段："+key);object.add(key,read(reader,depth+1,nodes));}reader.endObject();yield object;}
            case BEGIN_ARRAY -> {var array=new JsonArray();reader.beginArray();while(reader.hasNext())array.add(read(reader,depth+1,nodes));reader.endArray();yield array;}
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> {String number=reader.nextString();try{yield new JsonPrimitive(new java.math.BigDecimal(number));}catch(NumberFormatException invalid){throw new IOException("插件清单数字无效",invalid);}}
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {reader.nextNull();yield JsonNull.INSTANCE;}
            default -> throw new IOException("插件清单结构无效");
        };
    }
}
