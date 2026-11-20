package com.mcworldexplorer.voxel.resource;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.ClientBlockColors.Kind;
import static com.mcworldexplorer.voxel.resource.ClientBlockSymbols.unsupported;

/** World-renderer registrations normalized to face indices. Data is bundled, never taken from item models. */
record VanillaTintLayers(Map<ResourceLocation,List<Kind>> blocks, boolean repeatLast, boolean floatSwampNoise, String provenance) {
    private static final class Profiles {
        static final JsonObject DATA = load();
        static final Set<ResourceLocation> BASE_TEXTURES = DATA.getAsJsonArray("verifiedBaseTextureAtIndexZero")
                .asList().stream().map(value->ResourceLocation.parse(value.getAsString()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        private static JsonObject load() {
            try (var input = VanillaTintLayers.class.getResourceAsStream("/model-protocols/vanilla-tint-layers.json")) {
                if (input == null) throw new IllegalStateException("缺少原版着色协议数据");
                return com.google.gson.JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (IOException invalid) {throw new IllegalStateException(invalid);}
        }
    }

    static Optional<VanillaTintLayers> find(BlockResourceSource source, String version) throws IOException, ModelRule.Failure {
        if (ClientBlockColors.hasLegacyRegistry(version)) return Optional.empty();
        var profiles=Profiles.DATA.getAsJsonObject("profiles");
        var preferred=profiles.getAsJsonObject(version);
        if(preferred!=null)return match(source,version,preferred);
        // A renamed or repackaged client can reuse any verified registration layout.
        ModelRule.Failure mismatch=null;
        for(var entry:profiles.entrySet()){
            try{
                var match=match(source,version,entry.getValue().getAsJsonObject());
                if(match.isPresent())return match;
            }catch(ModelRule.Failure unverified){mismatch=unverified;}
        }
        if(mismatch!=null)throw mismatch;
        return Optional.empty();
    }
    private static Optional<VanillaTintLayers> match(BlockResourceSource source,String version,JsonObject profile)throws IOException,ModelRule.Failure {
        var proofs=profile.getAsJsonObject("classes");
        var anchor=proofs.entrySet().iterator().next();
        var registration=source.clientClass(anchor.getKey());
        if(registration.isEmpty())return Optional.empty();
        var receipt=registration.get();
        String origin=receipt.origin();
        JsonObject verified=profile;
        boolean matches=receipt.sourceFingerprint().equals(profile.get("clientSha256").getAsString());
        var alternatives=profile.getAsJsonArray("compatibleBuilds");
        if(!matches && alternatives!=null)for(var item:alternatives){
            var alternative=item.getAsJsonObject();
            if(receipt.sourceFingerprint().equals(alternative.get("clientSha256").getAsString())){verified=alternative;matches=true;break;}
        }
        if(!matches){
            if(!ClientBlockColors.digest(receipt.bytes()).equals(anchor.getValue().getAsString()))
                throw unsupported("着色注册结构未核实："+anchor.getKey());
            var checked=new HashMap<String,String>();
            checked.put(anchor.getKey(),anchor.getValue().getAsString());
            matches=matchesClasses(source,proofs,new JsonObject(),checked);
            if(!matches && alternatives!=null)for(var item:alternatives)
                if(matchesClasses(source,proofs,item.getAsJsonObject().getAsJsonObject("changedClasses"),checked)){verified=item.getAsJsonObject();matches=true;break;}
            if(!matches)throw unsupported("着色注册、处理器或群系采样结构未核实："+version);
        }
        var groups = new HashMap<ResourceLocation,List<Kind>>();
        var definitions = Profiles.DATA.getAsJsonObject("groups");
        for (var name : profile.getAsJsonArray("groups")) {
            var group = definitions.getAsJsonObject(name.getAsString());
            var layers = group.getAsJsonArray("layers").asList().stream().map(v -> Kind.valueOf(v.getAsString())).toList();
            for (var id : group.getAsJsonArray("blocks")) groups.put(ResourceLocation.parse(id.getAsString()), layers);
        }
        return Optional.of(new VanillaTintLayers(Map.copyOf(groups), profile.has("repeatLast") && profile.get("repeatLast").getAsBoolean(),
                verified.has("floatSwampNoise") && verified.get("floatSwampNoise").getAsBoolean(),
                "原版世界颜色层：" + version + "；已核对客户端颜色注册与群系采样 ← " + origin));
    }

    private static boolean matchesClasses(BlockResourceSource source,JsonObject base,JsonObject changes,Map<String,String> checked)throws IOException{
        for(var entry:base.entrySet()){
            var change=changes.getAsJsonObject(entry.getKey());
            String name=change==null?entry.getKey():change.get("name").getAsString();
            String expected=change==null?entry.getValue().getAsString():change.get("sha256").getAsString();
            String actual=checked.get(name);
            if(actual==null){
                var read=source.clientClass(name);
                actual=read.isEmpty()?"":ClientBlockColors.digest(read.get().bytes());
                checked.put(name,actual);
            }
            if(!expected.equals(actual))return false;
        }
        return true;
    }

    static Set<ResourceLocation> verifiedBaseTextures(){
        return Profiles.BASE_TEXTURES;
    }

    Optional<Kind> at(ResourceLocation block, int index) {
        var layers = blocks.get(block);
        if (layers == null || index < 0 || (!repeatLast && index >= layers.size())) return Optional.empty();
        return Optional.of(layers.get(Math.min(index, layers.size()-1)));
    }
}
