package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;

class StaticPrototypeRuleTest {
    private static final BlockState WRAPPER=state("test:wrapper");
    private static BlockState state(String id){return new BlockState(id,Map.of());}
    private static final class PrototypeRule implements ModelRule {
        public ResourceLocation id(){return ResourceLocation.parse("test:prototype");}
        public String version(){return "1";}
        public Set<ResourceLocation> loaders(){return Set.of(id());}
        public Set<String> fields(){return Set.of("target","style","repeat","children");}
        public List<Part> resolve(Context c)throws java.io.IOException,ModelRule.Failure {
            var json=c.definition();
            if(json.has("children")&&json.get("children").getAsInt()>0){json.addProperty("children",json.get("children").getAsInt()-1);return c.child(json);}
            var properties=json.has("style")?Map.of("style",json.get("style").getAsString()):Map.<String,String>of();
            var target=new BlockState(json.get("target").getAsString(),properties);var output=new ArrayList<>(c.staticPrototype(target));
            if(json.has("repeat"))output.addAll(c.staticPrototype(target));return output;
        }
    }
    private static BlockResourceSource source(Map<String,String> overrides)throws Exception {
        Map<String,String> json=new HashMap<>();
        json.put("assets/test/blockstates/wrapper.json","{\"variants\":{\"\":{\"model\":\"test:block/wrapper\",\"y\":90}}}");
        json.put("assets/test/models/block/wrapper.json","{\"loader\":\"test:prototype\",\"target\":\"other:source\",\"style\":\"saved\",\"textures\":{\"paint\":\"test:wrong\"}}");
        json.put("assets/other/blockstates/source.json","{\"variants\":{\"style=wrong\":{\"model\":\"other:block/missing\"},\"style=saved\":{\"model\":\"other:block/source\",\"y\":180}}}");
        json.put("assets/other/models/block/source.json","{\"textures\":{\"paint\":\"other:paint\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,0],\"faces\":{\"north\":{\"texture\":\"#paint\"}}}]}");
        json.putAll(overrides);
        var image=new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,0xFF123456);var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",bytes);
        return new BlockResourceSource(){
            public ClientResourceArchive.Fingerprint fingerprint(){return new ClientResourceArchive.Fingerprint(Path.of("prototype-fixture"),1,1,"fixture");}
            public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id){return kind==ResourceLocation.Kind.TEXTURE&&id.toString().equals("other:paint")?Optional.of(bytes.toByteArray()):Optional.empty();}
            public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id){return Optional.ofNullable(json.get(id.entryName(kind)));}
        };
    }
    private static ModelRuleRegistry registry(){return ModelRuleRegistry.defaults().with(new PrototypeRule());}
    @Test void exactPropertiesAndReferencedTexturesUseTheSameSourceWithoutOuterRotationOrVariables()throws Exception {
        var source=source(Map.of());var result=new BlockModelResolver(source,registry()).resolve(WRAPPER);
        assertTrue(result.resolved(),result.detail());var part=result.model().orElseThrow().parts().getFirst();assertEquals(180,part.yRotation());assertEquals("other:paint",part.faces().getFirst().texture().toString());
        assertTrue(result.sources().stream().anyMatch(s->s.kind()==ResourceLocation.Kind.BLOCKSTATE&&s.id().toString().equals("other:source")));
        var textures=BlockTextureSet.load(source,List.of(WRAPPER),CancellationSignal.NONE,registry());assertNotNull(textures.surfaces(WRAPPER),textures.fallbackReason(WRAPPER));assertTrue(Arrays.stream(textures.atlas().pixels()).anyMatch(pixel->pixel==0xFF123456));
        var noBinding=source(Map.of("assets/other/models/block/source.json","{\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,0],\"faces\":{\"north\":{\"texture\":\"#paint\"}}}]}"));
        assertEquals(Reason.MISSING_RESOURCE,new BlockModelResolver(noBinding,registry()).resolve(WRAPPER).reason(),"外层paint不能泄漏到原型");
    }
    @Test void siblingReferencesAreLegalButCrossStateCyclesFailAndDoNotPoisonTheNextCall()throws Exception {
        var sibling=source(Map.of("assets/test/models/block/wrapper.json","{\"loader\":\"test:prototype\",\"target\":\"other:source\",\"style\":\"saved\",\"repeat\":true}"));
        var ok=new BlockModelResolver(sibling,registry()).resolve(WRAPPER);assertTrue(ok.resolved(),ok.detail());assertEquals(2,ok.model().orElseThrow().parts().size());
        var cycle=source(Map.of("assets/other/models/block/source.json","{\"loader\":\"test:prototype\",\"target\":\"test:wrapper\"}"));var resolver=new BlockModelResolver(cycle,registry());
        assertEquals(Reason.REFERENCE_CYCLE,resolver.resolve(WRAPPER).reason());assertEquals(Reason.REFERENCE_CYCLE,resolver.resolve(WRAPPER).reason());
        assertEquals(Reason.MISSING_RESOURCE,resolver.resolve(state("other:not_present")).reason());
    }
    @Test void stateAndChildRecursionShareOneBoundInsteadOfResettingAtEachPrototype()throws Exception {
        Map<String,String> rows=new HashMap<>();
        for(int i=0;i<5;i++){
            rows.put("assets/chain/blockstates/n"+i+".json","{\"variants\":{\"\":{\"model\":\"chain:block/n"+i+"\"}}}");
            rows.put("assets/chain/models/block/n"+i+".json","{\"loader\":\"test:prototype\",\"target\":\"chain:n"+(i+1)+"\",\"children\":12}");
        }
        var result=new BlockModelResolver(source(rows),registry()).resolve(state("chain:n0"));assertEquals(Reason.LIMIT_EXCEEDED,result.reason(),result.detail());
        assertFalse(result.sources().stream().anyMatch(s->s.id().toString().equals("chain:n4")),"不能重置为每个状态各32层");
    }
    @Test void contextualPrototypeNeverUsesTheContainersNeighborhoodOrPretendsToBeEmpty()throws Exception {
        ModelRule contextual=new ModelRule(){
            public ResourceLocation id(){return ResourceLocation.parse("test:contextual");}public String version(){return "1";}
            public Set<ResourceLocation> loaders(){return Set.of(id());}public Set<String> fields(){return Set.of();}
            public Set<ModelBlockContext.Offset> neighbors(){return Set.of(new ModelBlockContext.Offset(1,0,0));}
            public List<Part> resolve(Context c){fail("静态原型不可获得伪造的邻域");return List.of();}
        };
        var source=source(Map.of("assets/other/models/block/source.json","{\"loader\":\"test:contextual\"}"));
        var result=new BlockModelResolver(source,registry().with(contextual)).resolve(WRAPPER);assertEquals(Reason.UNSUPPORTED_MODEL,result.reason(),result.detail());assertTrue(result.detail().contains("不能伪造"));assertTrue(result.model().isEmpty());
    }
    @Test void referencedWaterloggedModelsDoNotAddASecondContainersFluidLayer()throws Exception {
        ModelRule rule=new ModelRule(){
            public ResourceLocation id(){return ResourceLocation.parse("test:prototype");}public String version(){return "1";}
            public Set<ResourceLocation> loaders(){return Set.of(id());}public Set<String> fields(){return Set.of("target","style");}
            public List<Part> resolve(Context c)throws java.io.IOException,ModelRule.Failure{return c.staticPrototype(new BlockState("other:source",Map.of("style","saved","waterlogged","true")));}
        };
        var source=source(Map.of("assets/minecraft/blockstates/water.json","{\"variants\":{\"\":{\"model\":\"other:block/source\"}}}"));var result=new BlockModelResolver(source,new ModelRuleRegistry(List.of(rule))).resolve(WRAPPER);assertTrue(result.resolved(),result.detail());
        assertFalse(result.sources().stream().anyMatch(s->s.id().toString().equals("minecraft:water")),"引用模型不触发外容器之外的流体渲染");
        var direct=new BlockModelResolver(source).resolve(new BlockState("other:source",Map.of("style","saved","waterlogged","true")));assertTrue(direct.sources().stream().anyMatch(s->s.id().toString().equals("minecraft:water")),"真实顶层含水块保持原有路径");
    }
    @Test void explicitRootsKeepTheirStateWhileDistinctRootsAreNotConfusedWithCycles()throws Exception {
        var root=ResourceLocation.parse("other:block/source");
        ModelRule reference=new ModelRule(){
            public ResourceLocation id(){return ResourceLocation.parse("test:prototype");}public String version(){return "1";}public Set<ResourceLocation> loaders(){return Set.of(id());}public Set<String> fields(){return Set.of("target","style");}
            public List<Part> resolve(Context c)throws java.io.IOException,ModelRule.Failure{return c.staticPrototype(c.state(),root);}
        };
        var registry=new ModelRuleRegistry(List.of(reference));var result=new BlockModelResolver(source(Map.of()),registry).resolve(WRAPPER);assertTrue(result.resolved(),result.detail());assertEquals("other:paint",result.model().orElseThrow().faces().getFirst().texture().toString());assertFalse(result.sources().stream().anyMatch(s->s.kind()==ResourceLocation.Kind.BLOCKSTATE&&s.id().toString().equals("other:source")));
        var loop=source(Map.of("assets/other/models/block/source.json","{\"loader\":\"test:prototype\"}"));assertEquals(Reason.REFERENCE_CYCLE,new BlockModelResolver(loop,registry).resolve(WRAPPER).reason());
    }
    @Test void missingStateKeepsItsTraceAndSyntheticEntityIdentityCannotBeImported()throws Exception {
        var source=source(Map.of("assets/test/models/block/wrapper.json","{\"loader\":\"test:prototype\",\"target\":\"other:absent\"}"));
        var result=new BlockModelResolver(source,registry()).resolve(WRAPPER);assertEquals(Reason.MISSING_RESOURCE,result.reason());assertTrue(result.detail().contains("other:absent"));assertTrue(result.sources().stream().anyMatch(s->s.id().toString().equals("other:absent")));
        ModelRule identity=new ModelRule(){
            public ResourceLocation id(){return ResourceLocation.parse("test:prototype");}public String version(){return "1";}public Set<ResourceLocation> loaders(){return Set.of(id());}public Set<String> fields(){return Set.of("target","style");}
            public List<Part> resolve(Context c)throws java.io.IOException,ModelRule.Failure{return c.staticPrototype(new BlockState("other:source",Map.of("__mcwe_entity","fake")));}
        };
        assertEquals(Reason.INVALID_MODEL,new BlockModelResolver(source,new ModelRuleRegistry(List.of(identity))).resolve(WRAPPER).reason());
    }
}
