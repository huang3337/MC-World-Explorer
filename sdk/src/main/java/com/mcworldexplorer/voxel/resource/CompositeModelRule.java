package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;

/** Shared named-part geometry. Each child has its own texture and material context. */
enum CompositeModelRule implements ModelRule {
    FORGE("mcwe:forge_composite", "forge:composite"),
    NEOFORGE("mcwe:composite", "neoforge:composite");

    private final ResourceLocation id;
    private final Set<ResourceLocation> loaders;

    CompositeModelRule(String id, String loader) {
        this.id = ResourceLocation.parse(id);
        this.loaders = Set.of(ResourceLocation.parse(loader));
    }

    public ResourceLocation id(){return id;}
    public String version(){return "2";}
    public Set<ResourceLocation> loaders(){return loaders;}
    public Set<String> fields(){return Set.of("children","visibility","item_render_order");}
    public RootTransformMode rootTransformMode(){return RootTransformMode.COMPOSITE;}
    public boolean inheritRenderType(){return false;}

    public List<Part> resolve(Context context)throws IOException,Failure {
        JsonObject definition=context.definition();
        if (!definition.has("children") || !definition.get("children").isJsonObject())
            throw new Failure(Reason.INVALID_MODEL,"组合模型缺少命名子模型");
        var children=definition.getAsJsonObject("children");
        if(children.isEmpty())throw new Failure(Reason.INVALID_MODEL,"组合模型至少需要一个子项");
        if(children.size()>256)throw new Failure(Reason.LIMIT_EXCEEDED,"组合模型子项超过256项");
        if (definition.has("visibility") && !definition.get("visibility").isJsonObject())
            throw new Failure(Reason.INVALID_MODEL,"组合模型可见性需要对象");
        var visible=definition.has("visibility")?definition.getAsJsonObject("visibility"):new JsonObject();
        for(var flag:visible.entrySet())if(!flag.getValue().isJsonPrimitive()||!flag.getValue().getAsJsonPrimitive().isBoolean())
            throw new Failure(Reason.INVALID_MODEL,"组合模型可见性需要布尔值");
        if(definition.has("item_render_order")){
            var order=definition.get("item_render_order");
            if(!order.isJsonArray())throw new Failure(Reason.INVALID_MODEL,"item_render_order 需要数组");
            for (var name : order.getAsJsonArray()) {
                if (!name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString() || !children.has(name.getAsString()))
                    throw new Failure(Reason.INVALID_MODEL,"物品顺序引用了无效子模型");
                validateItemPass(name.getAsString(), visible);
            }
        } else if (this == FORGE) {
            for (var name : children.keySet()) validateItemPass(name, visible);
        }
        List<Part> parts=new ArrayList<>();int faces=0;
        for(var entry:children.entrySet()){
            if(!entry.getValue().isJsonObject())throw new Failure(Reason.INVALID_MODEL,"组合模型子项需要模型对象");
            if(visible.has(entry.getKey()) && !visible.get(entry.getKey()).getAsBoolean())continue;
            for(var part:context.independentChild(entry.getValue().getAsJsonObject())){
                faces+=part.faces().size();
                if(faces>8192||parts.size()>=4096)throw new Failure(Reason.LIMIT_EXCEEDED,"组合模型超过部件或面预算");
                parts.add(part);
            }
        }
        return parts;
    }

    private void validateItemPass(String name, JsonObject visibility) throws Failure {
        // Forge builds item passes from baked (visible) children even when baking a world model.
        if (this == FORGE && visibility.has(name) && !visibility.get(name).getAsBoolean())
            throw new Failure(Reason.INVALID_MODEL,"Forge 组合模型的物品顺序引用了隐藏子项：" + name);
    }
}
