package com.mcworldexplorer.voxel.resource;

import com.google.gson.JsonObject;
import java.util.*;

/** Shared definition semantics. Evidence and version boundaries live in MODEL_PROTOCOL_COMPATIBILITY.md.
 * This module reads data only; it does not discover or execute the game's model loaders. */
final class ModelDefinitionProtocol {
    static final String REVISION="model-definition/3";
    private static final Set<String> CONTEXT_FIELDS=Set.of("textures","visibility","transform","render_type","render_type_fast","ambientocclusion","ambient_occlusion","gui_light","display");

    enum Scope {
        BLOCKSTATE("方块状态",true,"variants","multipart","__comment"),
        MULTIPART("multipart分支",true,"when","apply","__comment"),
        VARIANT("模型选择",true,"model","x","y","uvlock","weight"),
        MODEL("普通模型",true,"textures","elements","ambientocclusion","display","gui_light","render_type","render_type_fast","__comment","comment","texture_size","name","groups","credit","format_version","Comment","ambient_occlusion"),
        CUSTOM_MODEL("自定义加载器",false),
        ELEMENT("模型元素",true,"from","to","faces","shade","shade_direction_override","rotation","light_emission","__comment","comment","Comment","name","color","neoforge_data","forge_data"),
        ROTATION("元素旋转",true,"origin","axis","angle","rescale"),
        FACE("元素面",true,"texture","uv","rotation","tintindex","cullface","neoforge_data","forge_data"),
        FACE_DATA("扩展面数据",false,"color","block_light","sky_light","ambient_occlusion","calculate_normals"),
        TEXTURE_BINDING("纹理绑定",false,"sprite","force_translucent");

        final String description;
        final boolean annotations;
        final Set<String> fields;
        Scope(String description,boolean annotations,String... fields){this.description=description;this.annotations=annotations;this.fields=Set.of(fields);}
    }

    private ModelDefinitionProtocol() { }

    /** ObjModel.makeQuad consumes only the model state's rotation; UVs come directly from vt.
     * Keep the evidence boundary separate from resource namespace and model ownership. */
    static boolean objKeepsExplicitUv(String loader,String platform,String version){
        return loader.equals("forge:obj")&&platform.equals("forge")&&version.equals("1.20.1")
            ||loader.equals("neoforge:obj")&&platform.equals("neoforge")&&version.equals("1.21.1");
    }

    static boolean hasLoaderObject(JsonObject definition){
        return List.of("loader","porting_lib:loader").stream().anyMatch(key->definition.has(key)&&definition.get(key).isJsonObject());
    }

    /** The structured loader form is versioned independently from the loader's geometry protocol.
     * A tool registration does not prove that an optional loader exists in the game registry. */
    static JsonObject normalizeLoaderObjects(JsonObject definition,String platform,String version)throws ModelRule.Failure {
        if(!hasLoaderObject(definition))return definition;
        var normalized=definition.deepCopy();
        for(String key:List.of("loader","porting_lib:loader"))if(definition.has(key)&&definition.get(key).isJsonObject()){
            if(!key.equals("loader")||!platform.equals("neoforge")||!version.equals("1.21.1"))throw new ModelRule.Failure(BlockModelResolver.Reason.UNSUPPORTED_MODEL,"对象形式loader目前仅核对NeoForge 1.21.1；当前平台/版本证据不足");
            var value=definition.getAsJsonObject(key);
            if(!Set.of("id","optional").containsAll(value.keySet())||!value.has("id")||!value.get("id").isJsonPrimitive()||!value.getAsJsonPrimitive("id").isString())throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL,"loader对象需要字符串id且仅支持id/optional字段");
            if(value.has("optional")){
                var optional=value.get("optional");
                if(!optional.isJsonPrimitive()||!optional.getAsJsonPrimitive().isBoolean())throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL,"loader.optional必须为布尔值");
                if(optional.getAsBoolean())throw new ModelRule.Failure(BlockModelResolver.Reason.UNSUPPORTED_MODEL,"可选loader需要游戏注册存在性证据；工具规则存在或缺失均不能代替游戏注册表");
            }
            normalized.addProperty(key,value.get("id").getAsString());
        }
        return normalized;
    }

    static String unsupportedField(JsonObject definition,Scope scope,Set<String> allowed){
        for(String key:definition.keySet())if(!allowed.contains(key)&&!(scope.annotations&&annotation(key)))return key;
        return null;
    }

    /** Only complete comment/credit names, ignoring case and outer underscores. Not a wildcard prefix.
     * Never apply this to property conditions, texture variables, IDs, or a custom loader's payload. */
    static boolean annotation(String key){
        int start=0,end=key.length();
        while(start<end&&key.charAt(start)=='_')start++;
        while(end>start&&key.charAt(end-1)=='_')end--;
        String name=key.substring(start,end).toLowerCase(Locale.ROOT);
        return name.equals("comment")||name.equals("credit");
    }

    static JsonObject inherit(JsonObject parent,JsonObject child,Set<String> parentGeometryFields)throws ModelRule.Failure {
        var result=new JsonObject();for(var entry:parent.entrySet())result.add(entry.getKey(),entry.getValue());
        var geometryFields=new HashSet<>(parentGeometryFields);geometryFields.add("elements");geometryFields.removeAll(CONTEXT_FIELDS);
        if(custom(child)){
            // A new geometry is parsed from its own declaration, even when it uses the same loader.
            // Keep host context and unknown fields (which still need diagnostics), not old geometry data.
            geometryFields.forEach(result::remove);result.remove("loader");result.remove("porting_lib:loader");
        }else if(custom(parent)){
            for(String key:geometryFields)if(child.has(key))throw new ModelRule.Failure(BlockModelResolver.Reason.UNSUPPORTED_MODEL,"继承的自定义几何不接受子定义覆写字段："+key+"；此写法需独立消费语义验证");
        }
        boolean ordinary=!custom(parent)&&!custom(child);
        for(var entry:child.entrySet()){
            String key=entry.getKey();if(key.equals("parent"))continue;
            // BlockModel.getElements falls back to its parent for an empty vanilla element list.
            // Custom geometries own their element/empty behavior and must not inherit this fallback.
            if(ordinary&&key.equals("elements")&&entry.getValue().isJsonArray()&&entry.getValue().getAsJsonArray().isEmpty()&&result.has(key))continue;
            if(Set.of("textures","visibility").contains(key)&&result.has(key)&&result.get(key).isJsonObject()&&entry.getValue().isJsonObject()){
                var values=new JsonObject();for(var item:result.getAsJsonObject(key).entrySet())values.add(item.getKey(),item.getValue());
                for(var item:entry.getValue().getAsJsonObject().entrySet())values.add(item.getKey(),item.getValue());result.add(key,values);
            }else result.add(key,entry.getValue());
        }
        return result;
    }

    private static boolean custom(JsonObject definition){return definition.has("loader")||definition.has("porting_lib:loader");}
}
