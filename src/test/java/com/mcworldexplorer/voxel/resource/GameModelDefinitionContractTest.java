package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent behavioral contracts from the game's model definition layer; no game classes run. */
/** Core protocol regression; no deferred adapters on the test classpath. */
class GameModelDefinitionContractTest {
    private static final String PLANE="\"textures\":{\"Credit\":\"unseen:block/paint\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,0],\"faces\":{\"north\":{\"texture\":\"#Credit\"}}}]";
    private static final BlockState STATE=new BlockState("unseen:probe",Map.of());

    @Test void emptyOrdinaryElementsInheritWhileNonemptyElementsReplace()throws Exception {
        for(String namespace:List.of("unseen","independent_author")){
            String parent=namespace+":block/base";
            var models=Map.of("unseen:block/probe","{\"parent\":\""+parent+"\",\"elements\":[]}",parent,"{"+PLANE+"}");
            var resolved=new BlockModelResolver(source(models,null),ModelRuleRegistry.defaults()).resolve(STATE);
            assertTrue(resolved.resolved(),resolved.detail());assertEquals(1,resolved.model().orElseThrow().faces().size());
            var overridden=new HashMap<>(models);overridden.put("unseen:block/probe","{\"parent\":\""+parent+"\",\"elements\":[{\"from\":[0,0,0],\"to\":[8,8,0],\"faces\":{\"north\":{\"texture\":\"#Credit\"}}}]}");
            assertEquals(8,new BlockModelResolver(source(overridden,null),ModelRuleRegistry.defaults()).resolve(STATE).model().orElseThrow().faces().getFirst().to().x());
        }
    }

    @Test void annotationSpellingDoesNotChangeStandardModelOrStateGeometry()throws Exception {
        var plain=resolve("{"+PLANE+"}",null).model().orElseThrow().parts();
        for(String key:List.of("__COMMENT","Credit","comment_","__cReDiT__")){
            var result=resolve("{\""+key+"\":\"editor annotation\","+PLANE+"}","{\""+key+"\":\"state annotation\",\"variants\":{\"\":{\"model\":\"unseen:block/probe\"}}}");
            assertTrue(result.resolved(),result.detail());assertEquals(plain,result.model().orElseThrow().parts());
        }
    }

    @Test void annotationsNeverRewriteStatePropertiesTextureVariablesOrUnknownSemantics()throws Exception {
        var source=source(Map.of("unseen:block/probe","{"+PLANE+"}"),"{\"multipart\":[{\"when\":{\"comment\":\"enabled\"},\"apply\":{\"model\":\"unseen:block/probe\"}}]}");
        var resolver=new BlockModelResolver(source,ModelRuleRegistry.defaults());
        assertEquals(1,resolver.resolve(new BlockState("unseen:probe",Map.of("comment","enabled"))).model().orElseThrow().faces().size());
        assertTrue(resolver.resolve(new BlockState("unseen:probe",Map.of("comment","disabled"))).model().orElseThrow().faces().isEmpty());
        for(String key:List.of("animations","future:surface","translucent","comment_color","_"))assertEquals(Reason.UNSUPPORTED_MODEL,resolve("{\""+key+"\":true,"+PLANE+"}",null).reason(),key);
    }



    @Test void nestedDefinitionScopesPreserveGeometryAndLocateUnknownFields()throws Exception {
        String model="{"+PLANE+"}";
        var annotated=com.google.gson.JsonParser.parseString(model).getAsJsonObject();
        var element=annotated.getAsJsonArray("elements").get(0).getAsJsonObject();element.addProperty("__Credit","author");
        var face=element.getAsJsonObject("faces").getAsJsonObject("north");face.addProperty("COMMENT_","annotation");
        element.add("rotation",com.google.gson.JsonParser.parseString("{\"origin\":[8,8,8],\"axis\":\"y\",\"angle\":0,\"Credit\":\"annotation\"}"));
        var plain=annotated.deepCopy();var plainElement=plain.getAsJsonArray("elements").get(0).getAsJsonObject();plainElement.remove("__Credit");plainElement.getAsJsonObject("faces").getAsJsonObject("north").remove("COMMENT_");plainElement.getAsJsonObject("rotation").remove("Credit");
        String state="{\"multipart\":[{\"comment_\":\"annotation\",\"apply\":{\"__COMMENT\":\"annotation\",\"model\":\"unseen:block/probe\"}}]}";
        var result=resolve(annotated.toString(),state);assertTrue(result.resolved(),result.detail());assertEquals(resolve(plain.toString(),null).model().orElseThrow().parts(),result.model().orElseThrow().parts());
        face.addProperty("future:material",true);var invalid=resolve(annotated.toString(),state);assertEquals(Reason.UNSUPPORTED_MODEL,invalid.reason());assertTrue(invalid.detail().contains("定义层：元素面"),invalid.detail());
        var stateFailure=resolve(model,"{\"future:selector\":true,\"variants\":{\"\":{\"model\":\"unseen:block/probe\"}}}");assertEquals(Reason.UNSUPPORTED_MODEL,stateFailure.reason());assertTrue(stateFailure.detail().contains("定义层：方块状态"),stateFailure.detail());
    }

    @Test void customLoaderCanExplicitlyOwnAnAnnotationShapedField()throws Exception {
        ModelRule rule=new ModelRule(){
            public ResourceLocation id(){return ResourceLocation.parse("external:owned");}public String version(){return "1";}
            public Set<ResourceLocation> loaders(){return Set.of(id());}public Set<String> fields(){return Set.of("__COMMENT");}
            public List<Part> resolve(Context c)throws java.io.IOException,Failure{assertTrue(c.definition().getAsJsonObject("__COMMENT").get("customSemantic").getAsBoolean());return c.standard();}
        };
        var external=source(Map.of("unseen:block/probe","{\"loader\":\"external:owned\",\"__COMMENT\":{\"customSemantic\":true},"+PLANE+"}"),null);
        var result=new BlockModelResolver(external,new ModelRuleRegistry(List.of(rule))).resolve(STATE);assertTrue(result.resolved(),result.detail());assertEquals(1,result.model().orElseThrow().faces().size());
    }

    private static Result resolve(String model,String state)throws Exception{return new BlockModelResolver(source(Map.of("unseen:block/probe",model),state),ModelRuleRegistry.defaults()).resolve(STATE);}
    private static BlockResourceSource source(Map<String,String> models,String state){
        return new BlockResourceSource(){
            public ClientResourceArchive.Fingerprint fingerprint(){return new ClientResourceArchive.Fingerprint(Path.of("game-definition-contract").toAbsolutePath(),1,1,"definition-contract");}
            public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id){return Optional.empty();}
            public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id){
                if(kind==ResourceLocation.Kind.BLOCKSTATE)return Optional.ofNullable(state==null?"{\"variants\":{\"\":{\"model\":\"unseen:block/probe\"}}}":state);
                return kind==ResourceLocation.Kind.MODEL?Optional.ofNullable(models.get(id.toString())):Optional.empty();
            }
        };
    }
}
