package com.mcworldexplorer.voxel.resource;

import java.io.IOException;
import java.util.*;

/** Expands the fluid flow barrier data tag once per resource preparation, including pack replacements. */
final class FluidFlowTag {
    private final BlockResourceSource source;
    private final Map<ResourceLocation,Optional<Set<String>>> cache=new HashMap<>();
    private int entries;
    FluidFlowTag(BlockResourceSource source){this.source=source;}
    private static boolean flag(com.google.gson.JsonObject root,String field,boolean fallback)throws IOException {
        var value=root.get(field);
        if(value==null)return fallback;
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw new IOException("方块标签布尔字段无效："+field);
        return value.getAsBoolean();
    }
    Optional<Set<String>> read(ResourceLocation id)throws IOException{return expand(id,new HashSet<>());}
    private Optional<Set<String>> expand(ResourceLocation id,Set<ResourceLocation> path)throws IOException {
        var cached=cache.get(id);
        if(cached!=null)return cached;
        if(cache.size()>=2048 || path.size()>=64 || !path.add(id))throw new IOException("流体标签循环或超出预算："+id);
        var layers=source.dataLayers(new ResourceLocation(id.namespace(),"tags/block/"+id.path()+".json"));
        List<com.google.gson.JsonElement> declarations=new ArrayList<>();
        for(String layer:layers){
            com.google.gson.JsonObject root;
            try{root=BlockModelResolver.parseResourceDefinition(layer);}
            catch(ModelRule.Failure invalid){throw new IOException("方块标签格式无效："+id,invalid);}
            if(flag(root,"replace",false))declarations.clear();
            var members=root.get("values");
            if(members==null||!members.isJsonArray())throw new IOException("方块标签缺少 values 数组："+id);
            declarations.addAll(members.getAsJsonArray().asList());
            if(declarations.size()>65536)throw new IOException("流体标签声明超限");
        }
        Set<String> values=new HashSet<>();
        for(var member:declarations){
            if(++entries>65536)throw new IOException("流体标签成员超限");
            var value=member.isJsonObject()?member.getAsJsonObject().get("id"):member;
            if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())throw new IOException("方块标签成员无效："+id);
            String name=value.getAsString();
            if(name.startsWith("#")){
                var child=expand(ResourceLocation.parse(name.substring(1)),path);
                boolean required=!member.isJsonObject()||flag(member.getAsJsonObject(),"required",true);
                if(child.isEmpty()&&required)throw new IOException("缺少流体标签引用："+name);
                child.ifPresent(values::addAll);
            }else values.add(ResourceLocation.parse(name).toString());
        }
        path.remove(id);
        var result=layers.isEmpty()?Optional.<Set<String>>empty():Optional.of(Set.copyOf(values));
        cache.put(id,result);
        return result;
    }
}
