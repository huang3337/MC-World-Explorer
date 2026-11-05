package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.RegistrationClassFile.*;
import static com.mcworldexplorer.voxel.resource.ClientBlockSymbols.unsupported;

/** Bounded vanilla registration protocol. Reads inert class data; never links or invokes game code. */
final class ClientBlockColors {
    enum Kind { DOUBLE_GRASS, GRASS, GRASS_NONZERO, SPRUCE, BIRCH, FOLIAGE, WATER, REDSTONE, ATTACHED_STEM, STEM, LILY_PAD }
    private final Map<ResourceLocation,Kind> bindings;
    private final String provenance;
    private final Map<Member,Kind> unresolvedBindings;

    ClientBlockColors(BlockResourceSource source) throws IOException, ModelRule.Failure {
        String version=source.baseClientVersion().orElse("");
        if(!Set.of("1.20.1","1.21.1").contains(version))throw unsupported("标准颜色协议尚未核对此客户端："+version);
        JsonObject profile;
        try(var input=getClass().getResourceAsStream("/model-protocols/client-block-colors-"+version+".json")) {
            if(input==null)throw unsupported("缺少标准颜色协议证明");
            byte[] bytes=input.readNBytes(32769);if(bytes.length>32768)throw unsupported("标准颜色协议证明超限");
            profile=BlockModelResolver.parseResourceDefinition(new String(bytes,StandardCharsets.UTF_8));
        }
        var classes=profile.getAsJsonArray("classes");if(classes.size()!=5)throw unsupported("颜色协议证明数量不符");
        RegistrationClassFile colors=null;var receipts=new ArrayList<String>();
        for(var item:classes) {
            var proof=item.getAsJsonObject();String name=proof.get("name").getAsString();
            var value=source.clientClass(name).orElseThrow(()->unsupported("缺少标准颜色协议类："+name));
            if(!digest(value.bytes()).equals(proof.get("sha256").getAsString()))throw unsupported("标准颜色协议指纹不匹配："+name);
            receipts.add(name+" ← "+value.origin()+"；"+value.sourceFingerprint());
            if(proof.get("type").getAsString().endsWith(".BlockColors"))colors=RegistrationClassFile.read(value);
        }
        if(colors==null)throw unsupported("缺少BlockColors证明");
        var handlers=new HashMap<String,Kind>();
        for(var item:profile.getAsJsonArray("handlers")) {
            var h=item.getAsJsonObject();handlers.put(h.get("method").getAsString(),Kind.valueOf(h.get("kind").getAsString()));
        }
        if(handlers.size()!=12)throw unsupported("颜色处理器证明数量不符");
        var registry=new ClientBlockRegistration(source,ClientBlockSymbols.read(source));
        String owner=colors.name();
        var factories=colors.methods().stream().filter(m->m.descriptor().equals("()L"+owner+";")&&(m.access()&8)!=0).toList();
        if(factories.size()!=1)throw unsupported("颜色注册入口不唯一");
        var code=colors.instructions(factories.getFirst());var result=new LinkedHashMap<ResourceLocation,Kind>();var unresolved=new LinkedHashMap<Member,Kind>();int registrations=0;
        for(int i=0;i<code.size();i++) {
            if(code.get(i).opcode()!=186)continue;
            if(!(code.get(i).operand() instanceof Lambda lambda)||!lambda.implementation().owner().equals(owner))throw unsupported("未知颜色注册回调");
            Kind kind=handlers.get(lambda.implementation().name());if(kind==null)throw unsupported("未核实的颜色处理器");
            int p=i+1;
            if(p+1>=code.size()||!(code.get(p).operand() instanceof Integer count)||count<1||count>64
                    ||code.get(p+1).opcode()!=189||!code.get(p+1).operand().equals(new Type(registry.symbols.type("Block"))))throw unsupported("颜色绑定不是有界静态方块数组");
            p+=2;
            for(int n=0;n<count;n++,p+=4) {
                if(p+3>=code.size()||code.get(p).opcode()!=89||!Objects.equals(code.get(p+1).operand(),n)
                        ||code.get(p+2).opcode()!=178||!(code.get(p+2).operand() instanceof Member field)||code.get(p+3).opcode()!=83)throw unsupported("颜色绑定数组含动态或重复元素");
                var fieldReference=(Member)code.get(p+2).operand();
                var referenced=registry.referencedBlock(fieldReference);
                // A ResourceKey/other unsupported registry expression must not disable known bindings.
                if(referenced.isEmpty()){unresolved.put(fieldReference,kind);continue;}
                var block=referenced.orElseThrow();
                if(result.putIfAbsent(block.id(),kind)!=null)throw unsupported("重复颜色绑定："+block.id());
            }
            if(p>=code.size()||code.get(p).opcode()!=182||!(code.get(p).operand() instanceof Member register)||!register.owner().equals(owner)
                    ||!register.descriptor().endsWith("[L"+registry.symbols.type("Block")+";)V"))throw unsupported("颜色绑定没有调用注册入口");
            registrations++;
        }
        if(registrations!=12||result.size()>64)throw unsupported("标准颜色注册数量不符或超限");
        bindings=Map.copyOf(result);
        unresolvedBindings=Map.copyOf(unresolved);
        provenance="标准颜色绑定："+version+"；已选客户端静态注册及5类指纹核对；"+String.join("；",receipts);
    }
    Optional<Kind> kind(ResourceLocation block){return Optional.ofNullable(bindings.get(block));}
    Map<ResourceLocation,Kind> bindings(){return bindings;}
    Map<Member,Kind> unresolvedBindings(){return unresolvedBindings;}
    String provenance(){return provenance;}
    static String digest(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
