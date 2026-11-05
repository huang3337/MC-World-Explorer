package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;

class ObjUvLockProtocolTest {
    private static ModelRuleRegistry core(){return ModelRuleRegistry.defaults();}
    private static BlockResourceSource fixture(String namespace,String loader,String platform,String version,boolean lock,int x,int y){
        var resources=Map.of(
            "assets/"+namespace+"/blockstates/shape.json","{\"variants\":{\"\":{\"model\":\""+namespace+":shape\",\"x\":"+x+",\"y\":"+y+",\"uvlock\":"+lock+"}}}",
            "assets/"+namespace+"/models/shape.json","{\"loader\":\""+loader+"\",\"model\":\"mesh:models/plane.obj\",\"flip_v\":true}",
            "assets/mesh/models/plane.obj","mtllib plane.mtl\nv 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nvt 0.125 0.25\nvt 0.875 0.25\nvt 0.875 0.75\nvt 0.125 0.75\nusemtl paint\nf 1/1 2/2 3/3 4/4\n",
            "assets/mesh/models/plane.mtl","newmtl paint\nmap_Kd paint:block/color\n");
        return new BlockResourceSource(){
            public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id){return Optional.ofNullable(resources.get(id.entryName(kind))).map(s->s.getBytes(StandardCharsets.UTF_8));}
            public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id){return Optional.ofNullable(resources.get(id.entryName(kind)));}
            public ClientResourceArchive.Fingerprint fingerprint(){return new ClientResourceArchive.Fingerprint(Path.of("obj-uv-fixture"),1,0,"obj-uv-fixture");}
            public Optional<String> platform(){return Optional.of(platform);}
            public Optional<String> baseClientVersion(){return Optional.of(version);}
        };
    }
    @Test void verifiedObjVersionsRotateGeometryAndKeepAuthoredUvsAcrossNamespaces()throws Exception {
        for(String namespace:List.of("first_author","second_author"))for(String platform:List.of("forge","neoforge"))for(int x:List.of(0,90,180,270))for(int y:List.of(0,90,180,270)){
            String version=platform.equals("forge")?"1.20.1":"1.21.1";var state=new BlockState(namespace+":shape",Map.of());
            var locked=new BlockModelResolver(fixture(namespace,platform+":obj",platform,version,true,x,y),core()).resolve(state);
            var plain=new BlockModelResolver(fixture(namespace,platform+":obj",platform,version,false,x,y),core()).resolve(state);
            assertTrue(locked.resolved(),locked.detail());assertEquals(plain.model().orElseThrow().parts(),locked.model().orElseThrow().parts());
            var part=locked.model().orElseThrow().parts().getFirst();assertFalse(part.uvLock());assertEquals(x,part.xRotation());assertEquals(y,part.yRotation());
            var quad=ModelGeometry.bake(part.faces().getFirst(),part);
            assertEquals(List.of(.125f,.75f,.875f,.75f,.875f,.25f,.125f,.25f),quad.uv());
            if(x==0&&y==90)assertEquals(List.of(1f,0f,0f,1f,0f,1f,1f,1f,1f,1f,1f,0f),quad.positions());
        }
    }
    @Test void unrelatedLoaderOrUnverifiedVersionStillReportsUnsupported()throws Exception {
        for(var values:List.of(List.of("forge:obj","forge","1.21.1"),List.of("neoforge:obj","neoforge","26.3"),List.of("porting_lib:obj","fabric","1.20.1"),List.of("forge:obj","neoforge","1.20.1"),List.of("forge:obj","forge",""))){
            var result=new BlockModelResolver(fixture("author",values.get(0),values.get(1),values.get(2),true,0,90),core()).resolve(new BlockState("author:shape",Map.of()));
            assertEquals(Reason.UNSUPPORTED_MODEL,result.reason(),values.toString());
        }
    }
    @Test void ordinaryObjRetainsMaterialAndOptionsWithoutSpecializedCallbackPath()throws Exception {
        var original=fixture("author","forge:obj","forge","1.20.1",false,0,0);
        var source=new BlockResourceSource(){
            public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id)throws IOException {
                var bytes=original.read(kind,id);if(bytes.isEmpty())return bytes;
                String text=new String(bytes.orElseThrow(),StandardCharsets.UTF_8);
                if(id.path().endsWith(".mtl"))text+="Kd 0.2 0.4 0.6\nd 0.5\n";
                if(id.path().endsWith(".obj"))text+="l 1 2\n";
                return Optional.of(text.getBytes(StandardCharsets.UTF_8));
            }
            public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id)throws IOException{return original.readJson(kind,id);}
            public ClientResourceArchive.Fingerprint fingerprint(){return original.fingerprint();}
        };
        var definition=JsonParser.parseString("{\"model\":\"mesh:models/plane.obj\",\"flip_v\":false,\"shade_quads\":false}").getAsJsonObject();
        var faces=new ObjModelReader(source,new ArrayList<>(),Map.of()).read(definition);
        assertEquals(1,faces.size(),"polyline不新增方块面");var face=faces.getFirst();
        assertEquals(0x1336699,face.tintIndex());assertEquals(.5f,face.opacity());assertFalse(face.shade());
        assertEquals(ResourceLocation.parse("paint:block/color"),face.texture());assertEquals(.25,face.vertexUv().getFirst().v());
        definition.addProperty("flip_v",true);definition.addProperty("shade_quads",true);
        var flipped=new ObjModelReader(source,new ArrayList<>(),Map.of()).read(definition).getFirst();
        assertTrue(flipped.shade());assertEquals(.75,flipped.vertexUv().getFirst().v());
        definition.addProperty("model","mesh:models/plane.obj.ie");
        var unsupported=assertThrows(ObjModelReader.Problem.class,()->new ObjModelReader(source,new ArrayList<>(),Map.of()).read(definition));
        assertEquals(Reason.INVALID_MODEL,unsupported.reason);assertEquals("OBJ 引用必须以 .obj 结尾",unsupported.getMessage());
    }
    private static BlockResourceSource withoutUvLock(InstanceResourceSource original){
        return new BlockResourceSource(){
            public ClientResourceArchive.Fingerprint fingerprint(){return original.fingerprint();}
            public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id)throws IOException{return original.read(kind,id);}
            public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id)throws IOException{
                var json=original.readJson(kind,id);if(kind!=ResourceLocation.Kind.BLOCKSTATE||json.isEmpty())return json;
                var tree=JsonParser.parseString(json.orElseThrow());clearLock(tree);return Optional.of(tree.toString());
            }
            public void validate()throws IOException{original.validate();}
        };
    }
    private static void clearLock(JsonElement value){
        if(value.isJsonObject()){value.getAsJsonObject().remove("uvlock");for(var child:value.getAsJsonObject().entrySet())clearLock(child.getValue());}
        else if(value.isJsonArray())for(var child:value.getAsJsonArray())clearLock(child);
    }
    @Test void actualPreviousFailuresResolveAllDeclaredBranchesWithCoreProtocolOnly()throws Exception {
        String selection=System.getenv("MCWORLD_OBJ_UVLOCK_SELECTION"),baseline=System.getenv("MCWORLD_BASELINE_JSON"),destination=System.getenv("MCWE_UI_SNAPSHOT_DIR");
        assumeTrue(selection!=null&&baseline!=null&&destination!=null);
        var profiles=JsonParser.parseString(Files.readString(Path.of(baseline))).getAsJsonObject().getAsJsonArray("instances");
        var selected=JsonParser.parseString(Files.readString(Path.of(selection))).getAsJsonArray();
        Path output=Path.of(destination).resolve("obj-uvlock"),world=output.resolve("empty-world");Files.createDirectories(world);
        var reports=new ArrayList<Map<String,String>>();int total=0;
        for(var item:selected){
            var row=item.getAsJsonObject();Path instance=Path.of(row.get("instance").getAsString());
            var profile=profiles.asList().stream().map(JsonElement::getAsJsonObject).filter(v->v.get("path").getAsString().equals(instance.toString())).findFirst().orElseThrow();
            var client=profile.getAsJsonArray("clientCandidates").get(0).getAsJsonObject();Path jar=Path.of(client.getAsJsonObject("file").get("path").getAsString());String version=client.getAsJsonObject("version").get("id").getAsString();
            try(var source=InstanceResourceSource.open(world,jar,version,instance,CancellationSignal.NONE,m->{})){
                var states=new ArrayList<BlockState>();var expected=withoutUvLock(source);var actualResolver=new BlockModelResolver(source,core());var expectedResolver=new BlockModelResolver(expected,core());
                for(var name:row.getAsJsonArray("blocks")){
                    var id=ResourceLocation.parse(name.getAsString());var definition=JsonParser.parseString(source.readJson(ResourceLocation.Kind.BLOCKSTATE,id).orElseThrow()).getAsJsonObject();
                    for(var entry:definition.getAsJsonObject("variants").entrySet()){
                        assertTrue(entry.getValue().getAsJsonObject().get("uvlock").getAsBoolean());
                        var properties=new TreeMap<String,String>();for(String token:entry.getKey().split(",")){var parts=token.split("=",2);properties.put(parts[0],parts[1]);}
                        var state=new BlockState(id.toString(),properties);var actual=actualResolver.resolve(state);var reference=expectedResolver.resolve(state);
                        assertTrue(actual.resolved(),state+": "+actual.detail());assertTrue(reference.resolved(),reference.detail());assertEquals(reference.model().orElseThrow().parts(),actual.model().orElseThrow().parts());states.add(state);
                    }
                }
                var actualTextures=BlockTextureSet.load(source,states,CancellationSignal.NONE,core());var referenceTextures=BlockTextureSet.load(expected,states,CancellationSignal.NONE,core());
                for(var state:states){assertNotNull(actualTextures.surfaces(state),actualTextures.fallbackReason(state));assertEquals(referenceTextures.surfaces(state),actualTextures.surfaces(state));}
                assertArrayEquals(referenceTextures.atlas().pixels(),actualTextures.atlas().pixels());source.validateAll();total+=states.size();
                String file=String.format(Locale.ROOT,"%02d.json",reports.size());
                Files.writeString(output.resolve(file),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("scope","All actual declared variants of the six previously rejected block resources; not saved world coverage","world",world.toString(),"instance",instance.toString(),"client",jar.toString(),"version",version,"resourceFingerprint",source.fingerprint().sha256(),"rulesFingerprint",core().fingerprint(),"states",states)));
                reports.add(Map.of("report",file));
            }
        }
        assertEquals(96,total,"Frozen six-resource branch selection changed");
        Files.writeString(output.resolve("index.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("worlds",reports,"branches",total,"rulesFingerprint",core().fingerprint(),"failures",List.of())));
    }
}
