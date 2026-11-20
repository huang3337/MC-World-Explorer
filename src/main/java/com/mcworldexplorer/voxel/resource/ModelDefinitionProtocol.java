package com.mcworldexplorer.voxel.resource;

import com.google.gson.JsonObject;
import java.util.*;

/** Shared definition semantics. Evidence and version boundaries live in MODEL_PROTOCOL_CAPABILITIES.md.
 * This module reads data only; it does not discover or execute the game's model loaders. */
final class ModelDefinitionProtocol {
    static final String REVISION="model-definition/6";
    static final int ELEMENT_EMISSION_FORMAT = 35;
    // Resource pack format 74 introduced XYZ element rotations and Z blockstate dispatch.
    static final int THREE_AXIS_ROTATION_FORMAT = 74;
    private static final Set<String> CONTEXT_FIELDS=Set.of("textures","visibility","transform","render_type","render_type_fast","ambientocclusion","ambient_occlusion","gui_light","display");

    enum Scope {
        // Standard state dispatch reads variants/multipart. Only a model definition follows a parent.
        BLOCKSTATE("方块状态",true,"variants","multipart","parent","__comment"),
        MULTIPART("multipart分支",true,"when","apply","__comment"),
        VARIANT("模型选择",true,"model","x","y","z","uvlock","weight"),
        MODEL("普通模型",true,"textures","elements","ambientocclusion","display","gui_light","render_type","render_type_fast","__comment","comment","texture_size","name","groups","credit","format_version","Comment","ambient_occlusion"),
        CUSTOM_MODEL("自定义加载器",false),
        ELEMENT("模型元素",true,"from","to","faces","shade","shade_direction_override","rotation","light_emission","__comment","comment","Comment","name","color","neoforge_data","forge_data"),
        ROTATION("元素旋转",true,"origin","axis","angle","rescale","x","y","z"),
        FACE("元素面",true,"texture","uv","shade","rotation","tintindex","cullface","neoforge_data","forge_data"),
        FACE_DATA("扩展面数据",false,"color","block_light","sky_light","ambient_occlusion","calculate_normals"),
        TEXTURE_BINDING("纹理绑定",false,"sprite","force_translucent");

        final String description;
        final boolean annotations;
        final Set<String> fields;
        Scope(String description,boolean annotations,String... fields){this.description=description;this.annotations=annotations;this.fields=Set.of(fields);}
    }

    private ModelDefinitionProtocol() { }

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
