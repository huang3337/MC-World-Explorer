package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.RegistrationClassFile.*;

/** Versioned names of the recognized block registry API; no block IDs or default-state table. */
final class ClientBlockSymbols {
    static final String REVISION="client-block-state/4";
    private final Map<String,String> types=new HashMap<>();
    private final Map<String,List<Member>> methods=new HashMap<>();
    private final Map<String,Member> fields=new HashMap<>();
    private final Map<String,String> kernels=new HashMap<>();
    private final Map<String,String> registryTails=new HashMap<>();
    private final Map<Member,String> methodKernels=new HashMap<>();
    /** Reviewed constructor ranges which only populate shape caches, never default states.
     * Exact concrete types and class fingerprints prevent unreviewed virtual overrides. */
    record ConstructorCache(Member constructor,int begin,int end,Set<String> concreteTypes,Map<String,String> proofs) {
        ConstructorCache {concreteTypes=Set.copyOf(concreteTypes);proofs=Map.copyOf(proofs);if(begin<0||end<=begin||end>65535||concreteTypes.isEmpty()||concreteTypes.size()>16||proofs.size()>16||!proofs.keySet().containsAll(concreteTypes)||!proofs.containsKey(constructor.owner())||proofs.values().stream().anyMatch(s->!s.matches("[0-9a-f]{64}")))throw new IllegalArgumentException("构造缓存协议声明无效");}
    }
    private final List<ConstructorCache> constructorCaches=new ArrayList<>();
    private final Map<Member,Map<String,String>> itemMetadataSetters=new HashMap<>();
    ClientBlockSymbols(JsonObject json){
        for(var e:json.getAsJsonObject("types").entrySet())types.put(e.getKey(),e.getValue().getAsString());
        for(var v:json.getAsJsonArray("methods")){var a=v.getAsJsonArray();methods.computeIfAbsent(a.get(0).getAsString()+"."+a.get(1).getAsString(),key->new ArrayList<>()).add(new Member(type(a.get(0).getAsString()),a.get(2).getAsString(),a.get(3).getAsString()));}
        for(var v:json.getAsJsonArray("fields")){var a=v.getAsJsonArray();fields.put(a.get(0).getAsString()+"."+a.get(1).getAsString(),new Member(type(a.get(0).getAsString()),a.get(2).getAsString(),a.get(3).getAsString()));}
        if(json.has("kernels"))for(var e:json.getAsJsonObject("kernels").entrySet())kernels.put(type(e.getKey()),e.getValue().getAsString());
        if(json.has("registryTails"))for(var e:json.getAsJsonObject("registryTails").entrySet())registryTails.put(e.getKey(),e.getValue().getAsString());
        if(json.has("methodKernels"))for(var v:json.getAsJsonArray("methodKernels")){var a=v.getAsJsonArray();methodKernels.put(new Member(type(a.get(0).getAsString()),a.get(1).getAsString(),a.get(2).getAsString()),a.get(3).getAsString());}
        if(json.has("constructorCaches"))for(var v:json.getAsJsonArray("constructorCaches")){
            var c=v.getAsJsonObject();var concrete=new HashSet<String>();for(var x:c.getAsJsonArray("concreteTypes"))concrete.add(type(x.getAsString()));
            var proofs=new HashMap<String,String>();for(var e:c.getAsJsonObject("proofs").entrySet())proofs.put(type(e.getKey()),e.getValue().getAsString());
            var added=new ConstructorCache(new Member(type(c.get("owner").getAsString()),"<init>",c.get("descriptor").getAsString()),c.get("begin").getAsInt(),c.get("end").getAsInt(),concrete,proofs);
            if(constructorCaches.size()>=64||constructorCaches.stream().anyMatch(prior->prior.constructor().equals(added.constructor())&&!Collections.disjoint(prior.concreteTypes(),concrete)&&prior.begin()<added.end()&&added.begin()<prior.end()))throw new IllegalArgumentException("构造缓存协议重复、重叠或超限");constructorCaches.add(added);
        }
        if(json.has("itemMetadataSetters"))for(var v:json.getAsJsonArray("itemMetadataSetters")){
            var entry=v.getAsJsonObject();var method=new Member(type("Item$Properties"),entry.get("name").getAsString(),entry.get("descriptor").getAsString());
            var proofs=new HashMap<String,String>();for(var e:entry.getAsJsonObject("proofs").entrySet())proofs.put(type(e.getKey()),e.getValue().getAsString());
            if(itemMetadataSetters.size()>=16||proofs.isEmpty()||proofs.size()>16||!proofs.containsKey(method.owner())||proofs.values().stream().anyMatch(s->!s.matches("[0-9a-f]{64}"))||itemMetadataSetters.putIfAbsent(method,Map.copyOf(proofs))!=null)throw new IllegalArgumentException("物品元数据协议证明缺失、重复或超限");
        }
    }
    static ClientBlockSymbols read(BlockResourceSource source)throws IOException,ModelRule.Failure {
        String version=source.baseClientVersion().orElse("");
        if(!Set.of("1.20.1","1.21.1").contains(version))throw unsupported("方块注册协议尚未核对客户端："+version);
        try(var input=ClientBlockSymbols.class.getResourceAsStream("/model-protocols/client-blocks-"+version+".json")){
            if(input==null)throw unsupported("缺少方块注册协议符号");byte[] bytes=input.readNBytes(131073);if(bytes.length>131072)throw unsupported("方块协议符号超限");
            return new ClientBlockSymbols(BlockModelResolver.parseResourceDefinition(new String(bytes,StandardCharsets.UTF_8)));
        }
    }
    String type(String alias){return Objects.requireNonNull(types.get(alias),"缺少协议类型："+alias);}
    String descriptor(String alias){return "L"+type(alias)+";";}
    Member field(String alias){return Objects.requireNonNull(fields.get(alias),"缺少协议字段："+alias);}
    void verifyKernel(RegistrationClass value)throws ModelRule.Failure {
        String expected=kernels.get(value.internalName());if(expected==null)return;
        try{String actual=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.bytes()));if(!expected.equals(actual))throw unsupported("客户端基础状态协议指纹不匹配："+value.internalName());}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    Set<String> kernels(){return Set.copyOf(kernels.keySet());}
    boolean registryTail(String owner,String signature){return signature.equals(registryTails.get(owner));}
    Map<Member,String> methodKernels(){return Map.copyOf(methodKernels);}
    Optional<Map<String,String>> itemMetadataProof(Member method){return Optional.ofNullable(itemMetadataSetters.get(method));}
    Optional<ConstructorCache> constructorCache(Member method,String concrete,int begin){return constructorCaches.stream().filter(c->c.constructor().equals(method)&&c.begin()==begin&&c.concreteTypes().contains(concrete)).findFirst();}
    List<Member> methods(String alias){return List.copyOf(methods.getOrDefault(alias,List.of()));}
    boolean call(Member member,String alias){return methods.getOrDefault(alias,List.of()).contains(member);}
    boolean signature(Member member,String alias){return methods.getOrDefault(alias,List.of()).stream().anyMatch(m->m.name().equals(member.name())&&m.descriptor().equals(member.descriptor()));}
    Member method(String alias,String descriptor)throws ModelRule.Failure {var candidates=methods.getOrDefault(alias,List.of()).stream().filter(m->m.descriptor().equals(descriptor)).toList();if(candidates.size()!=1)throw unsupported("协议方法缺失或不唯一："+alias);return candidates.getFirst();}
    static ModelRule.Failure unsupported(String text){return new ModelRule.Failure(BlockModelResolver.Reason.UNSUPPORTED_MODEL,text);}
}
