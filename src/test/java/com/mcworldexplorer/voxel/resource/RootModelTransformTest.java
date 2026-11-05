package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.mcworldexplorer.voxel.data.BlockState;
import org.joml.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;
import static org.junit.jupiter.api.Assertions.*;

/** Core protocol regression; no deferred adapters on the test classpath. */
class RootModelTransformTest {
    private static final String ELEMENTS="\"textures\":{\"p\":\"example:block/paint\"},\"elements\":[{\"from\":[0,0,0],\"to\":[4,8,0],\"faces\":{\"north\":{\"texture\":\"#p\",\"cullface\":\"north\",\"uv\":[1,2,5,10]}}}]";
    private static final BlockState STATE=new BlockState("example:probe",Map.of());
    private static Matrix4d read(String json)throws Exception{return RootModelTransform.read(JsonParser.parseString(json));}
    @Test void codecHandlesOriginsOrderMatricesAliasesAndQuaternion()throws Exception {
        assertVector(new Vector3d(-1,-1,-1),read("{\"scale\":2}").transformPosition(new Vector3d()));
        assertVector(new Vector3d(-.5,-.5,-.5),read("{\"origin\":\"center\",\"scale\":[2,2,2]}").transformPosition(new Vector3d()));
        assertVector(new Vector3d(2,1,0),read("{\"origin\":\"corner\",\"translation\":[2,0,0],\"rotation\":{\"z\":90}}").transformPosition(new Vector3d(1,0,0)));
        assertTrue(read("{\"origin\":\"corner\",\"rotation\":[45,0,-45]}").equals(new Matrix4d().rotateXYZ(java.lang.Math.PI/4,0,-java.lang.Math.PI/4),1e-8));
        var sequence=read("{\"origin\":\"corner\",\"left_rotation\":[{\"x\":45},{\"y\":90}],\"post-rotation\":{\"z\":30}}");var expectedSequence=new Matrix4d().rotateX(java.lang.Math.PI/4).rotateY(java.lang.Math.PI/2).rotateZ(java.lang.Math.PI/6);
        assertTrue(sequence.equals(expectedSequence,1e-8),()->sequence+"expected\n"+expectedSequence);
        assertTrue(read("{\"origin\":\"corner\",\"rotation\":[0,0,0,1]}").equals(new Matrix4d(),1e-8));
        assertVector(new Vector3d(3,5,7),read("{\"matrix\":[[1,0,0,2],[0,2,0,3],[0,0,3,4]]}").transformPosition(new Vector3d(1,1,1)));
        assertEquals(read("\"identity\""),read("[[1,0,0,0],[0,1,0,0],[0,0,1,0]]"));
    }
    @Test void malformedSingularAndUnboundedTransformsFailExplicitly(){
        for(String invalid:List.of("null","true","{\"scale\":0}","{\"origin\":\"guess\"}","{\"rotation\":[0,0,0,0]}","{\"scale\":\"2\"}","{\"scale\":[1,2]}","{\"rotation\":{\"x\":2,\"y\":3}}","{\"rotation\":[0,0,0],\"left_rotation\":[0,0,0]}","{\"matrix\":[[1,0,0,0],[0,1,0,0],[0,0,1,0]],\"origin\":\"center\"}","{\"translation\":[1e100,0,0]}","{\"post_rotation\":[1,2,3]}"))
            assertThrows(ModelRule.Failure.class,()->read(invalid),invalid);
        String sequence="{\"rotation\":["+String.join(",",Collections.nCopies(33,"{\"x\":1}"))+"]}";
        var over=assertThrows(ModelRule.Failure.class,()->read(sequence));assertEquals(Reason.LIMIT_EXCEEDED,over.reason());
    }
    @Test void inheritedRootRunsBeforeBlockRotationButDoesNotChangeUvLockOrCull()throws Exception {
        var base=resolve("{"+ELEMENTS+"}","\"y\":90,\"uvlock\":true");
        var source=source(Map.of("example:block/probe","{\"parent\":\"another:parent\"}","another:parent","{\"transform\":{\"origin\":\"corner\",\"translation\":[1,0,0]},"+ELEMENTS+"}"),"\"y\":90,\"uvlock\":true");
        var actual=new BlockModelResolver(source,ModelRuleRegistry.defaults()).resolve(STATE);assertTrue(actual.resolved(),actual.detail());
        var expectedPart=base.model().orElseThrow().parts().getFirst();var original=ModelGeometry.bake(expectedPart.faces().getFirst(),expectedPart);
        var part=actual.model().orElseThrow().parts().getFirst();var face=ModelGeometry.bake(part.faces().getFirst(),part);
        assertEquals(original.uv(),face.uv());assertEquals(original.cull(),face.cull());
        for(int i=0;i<4;i++){assertEquals(original.positions().get(i*3),face.positions().get(i*3),1e-6);assertEquals(original.positions().get(i*3+2)+1,face.positions().get(i*3+2),1e-6);}
        var override=source(Map.of("example:block/probe","{\"parent\":\"another:parent\",\"transform\":\"identity\"}","another:parent","{\"transform\":{\"scale\":2},"+ELEMENTS+"}"),"\"y\":90,\"uvlock\":true");
        assertEquals(base.model().orElseThrow().parts(),new BlockModelResolver(override,ModelRuleRegistry.defaults()).resolve(STATE).model().orElseThrow().parts());
    }

    @Test void platformAndVersionEvidenceAreRequiredForRealSources()throws Exception {
        String model="{\"transform\":{\"scale\":2},"+ELEMENTS+"}";
        for(String[] profile:List.of(new String[]{"fabric","1.20.1"},new String[]{"neoforge","1.20.1"},new String[]{"forge","1.21.1"},new String[]{"","1.20.1"})){
            var result=new BlockModelResolver(source(Map.of("example:block/probe",model),"",profile[0],profile[1]),ModelRuleRegistry.defaults()).resolve(STATE);
            assertEquals(Reason.UNSUPPORTED_MODEL,result.reason(),Arrays.toString(profile));
        }
        for(String[] profile:List.of(new String[]{"forge","1.20.1"},new String[]{"neoforge","1.21.1"}))assertTrue(new BlockModelResolver(source(Map.of("example:block/probe",model),"",profile[0],profile[1]),ModelRuleRegistry.defaults()).resolve(STATE).resolved());
    }
    @Test void reflectedFacesKeepUvAndNormalsAndOutputBudget()throws Exception {
        var actual=resolve("{\"transform\":{\"origin\":\"center\",\"scale\":[-1,1,1]},"+ELEMENTS+"}","");assertTrue(actual.resolved(),actual.detail());
        var part=actual.model().orElseThrow().parts().getFirst();var quad=ModelGeometry.bake(part.faces().getFirst(),part);assertEquals(List.of(0f,0f,-1f),quad.normal());assertEquals(Direction.NORTH,quad.cull());
        assertEquals(4,part.faces().getFirst().vertexUv().size());
        assertEquals(Reason.LIMIT_EXCEEDED,resolve("{\"transform\":{\"translation\":[100,0,0]},"+ELEMENTS+"}","").reason());
    }
    @Test void rootCapabilityIsFrozenAndParticipatesInRegistryIdentity()throws Exception {
        class Extension implements ModelRule {
            RootTransformMode mode=RootTransformMode.NONE;
            public ResourceLocation id(){return ResourceLocation.parse("independent:plane");}public String version(){return "1";}
            public Set<ResourceLocation> loaders(){return Set.of(id());}public Set<String> fields(){return Set.of();}
            public RootTransformMode rootTransformMode(){return mode;}public List<Part> resolve(Context context)throws java.io.IOException,Failure{return context.standard();}
        }
        var extension=new Extension();var before=new ModelRuleRegistry(List.of(extension));extension.mode=ModelRule.RootTransformMode.ELEMENTS;var after=new ModelRuleRegistry(List.of(extension));
        assertEquals(ModelRule.RootTransformMode.NONE,before.rules().getFirst().rootTransformMode());assertNotEquals(before.fingerprint(),after.fingerprint());
        var json="{\"loader\":\"independent:plane\",\"transform\":{\"translation\":[1,0,0]},"+ELEMENTS+"}";
        var source=source(Map.of("example:block/probe",json),"");assertFalse(new BlockModelResolver(source,before).resolve(STATE).resolved());assertTrue(new BlockModelResolver(source,after).resolve(STATE).resolved());
    }
    private static void assertVector(Vector3d expected,Vector3d actual){assertTrue(expected.equals(actual,1e-7),()->expected+" != "+actual);}
    private static Result resolve(String json,String state)throws java.io.IOException{return new BlockModelResolver(source(Map.of("example:block/probe",json),state),ModelRuleRegistry.defaults()).resolve(STATE);}
    private static BlockResourceSource source(Map<String,String> models,String state){
        return source(models,state,null,null);
    }
    private static BlockResourceSource source(Map<String,String> models,String state,String platform,String version){
        return new BlockResourceSource(){
            public Optional<String> platform(){return Optional.ofNullable(platform);}
            public Optional<String> baseClientVersion(){return Optional.ofNullable(version);}
            public ClientResourceArchive.Fingerprint fingerprint(){return new ClientResourceArchive.Fingerprint(Path.of("fixture").toAbsolutePath(),1,1,"root-transform");}
            public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id){return Optional.empty();}
            public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id){return kind==ResourceLocation.Kind.BLOCKSTATE?Optional.of("{\"variants\":{\"\":{\"model\":\"example:block/probe\""+(state.isEmpty()?"":","+state)+"}}}"):Optional.ofNullable(models.get(id.toString()));}
        };
    }
}
