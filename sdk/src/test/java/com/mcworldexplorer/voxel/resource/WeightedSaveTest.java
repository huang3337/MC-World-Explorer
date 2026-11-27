package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.io.*;
import com.mcworldexplorer.voxel.mesh.VoxelSceneBuilder;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WeightedSaveTest {
    @Test void multipleModsUseTheSameStandardVariants() throws Exception {
        verify("MCWE_WEIGHTED_MOD_WORLD","1.21.1","minecraft:overworld",12,0,
                Set.of("create:asurine","biomesoplenty:lavender","farm_and_charm:wild_strawberries"));
    }
    @Test void chorusUsesRealMultipartWeightsInTheEnd() throws Exception {
        verify("MCWE_WEIGHTED_END_WORLD","1.21.1","minecraft:the_end",91,16,Set.of("minecraft:chorus_plant","betterend:lucernia_leaves","betterend:lucernia_outer_leaves"));
    }
    @Test void standardMultipartSelectsBetterNetherGeometryFromSavedProperties() throws Exception {
        verify("MCWE_WEIGHTED_END_WORLD","1.21.1","minecraft:the_nether",-18,2,Set.of("betternether:giant_lucis"));
    }
    @Test void installedMultipartHedgeUsesTheSameDrawForCenterAndArm()throws Exception {
        String location=System.getenv("MCWE_WEIGHTED_MOD_WORLD");
        assumeTrue(location!=null,"MCWE_WEIGHTED_MOD_WORLD");
        Path world=Path.of(location),instance=world.getParent().getParent();
        // A real installed declaration with controlled positions, separate from the placed-block tests.
        var state=new BlockState("refurbished_furniture:azalea_hedge",
                Map.of("north","true","east","false","south","false","west","false"));
        try(var source=InstanceResourceSource.open(world,instance.resolve(instance.getFileName()+".jar"),"1.21.1",instance,CancellationSignal.NONE,p->{})){
            var resolver=new BlockModelResolver(source);
            var styles=new HashSet<Integer>();
            var textures=new HashSet<ResourceLocation>();
            for(int x=-16;x<16;x++){
                int choice=WeightedVariantsTest.reference(WeightedVariantsTest.randomAt(x,72,5,true),new int[]{1,1,1},true)+1;
                styles.add(choice);
                var model=resolver.resolveAt(state,new ModelBlockContext.Position(x,72,5)).model().orElseThrow();
                assertFalse(model.fixedFirstAlternative());
                assertEquals(5,model.parts().size());
                var center=model.parts().getFirst();
                var arm=model.parts().getLast();
                assertEquals(ResourceLocation.parse("refurbished_furniture:block/azalea_hedge_center_style_"+choice),center.id());
                assertEquals(ResourceLocation.parse("refurbished_furniture:block/azalea_hedge_connection_style_"+choice),arm.id());
                assertEquals(180,arm.yRotation());
                for(var part:model.parts()){
                    assertFalse(part.faces().isEmpty());
                    for(var face:part.faces())textures.add(face.texture());
                }
            }
            assertEquals(Set.of(1,2,3),styles);
            for(var id:textures)assertTrue(source.read(ResourceLocation.Kind.TEXTURE,id).isPresent(),id.toString());
        }
    }
    @Test void enabledPackGrassAndModernStoneUseBoundedDraws()throws Exception {
        verify("MCWE_TINT_1215_WORLD","1.21.5","minecraft:overworld",0,0,Set.of("minecraft:grass_block","minecraft:short_grass"));
        verify("MCWE_TINT_263_WORLD","26.3","minecraft:overworld",0,7,Set.of("minecraft:stone","minecraft:grass_block"));
    }
    private void verify(String variable,String version,String dimension,int cx,int cz,Set<String> names)throws Exception {
        String location=System.getenv(variable);
        assumeTrue(location!=null && !location.isBlank(),variable);
        Path world=Path.of(location),instance=world.getParent().getParent();
        try(var source=InstanceResourceSource.open(world,instance.resolve(instance.getFileName()+".jar"),version,instance,CancellationSignal.NONE,p->{})){
            var area=new VoxelAreaLoader().load(new VoxelAreaRequest(world,dimension,ChunkRectangle.target(cx,cz,cx,cz)),CancellationSignal.NONE);
            var chunk=area.result(new ChunkCoordinate(cx,cz)).chunk();
            var prepared=BlockTextureSet.load(source,area,CancellationSignal.NONE);
            var scene=new VoxelSceneBuilder(prepared).build(area,CancellationSignal.NONE).snapshot().orElseThrow();
            assertTrue(scene.faceCount()>0);
            var resolver=new BlockModelResolver(source);
            var atlas=prepared.atlas();
            var pixels=atlas.pixels();
            var seen=new HashSet<BlockState>();
            for(var name:names) {
                var raw=source.readJson(ResourceLocation.Kind.BLOCKSTATE,ResourceLocation.parse(name)).orElseThrow();
                var definition=JsonParser.parseString(raw).getAsJsonObject();
                var counts=new TreeMap<String,Integer>();
                for(var section:chunk.sections())for(int ly=0;ly<16;ly++)for(int lz=0;lz<16;lz++)for(int lx=0;lx<16;lx++){
                    var state=section.blockState(lx,ly,lz);
                    if(!state.name().equals(name))continue;
                    int x=cx*16+lx,y=section.sectionY()*16+ly,z=cz*16+lz;
                    var parts=new ArrayList<JsonElement>();
                    boolean multipart=definition.has("multipart");
                    if(multipart) {
                        for(var element:definition.getAsJsonArray("multipart")){
                            var branch=element.getAsJsonObject();
                            if(!branch.has("when") || branch.getAsJsonObject("when").entrySet().stream()
                                    .allMatch(e->e.getValue().getAsString().equals(state.properties().get(e.getKey()))))parts.add(branch.get("apply"));
                        }
                    }else {
                        for(var entry:definition.getAsJsonObject("variants").entrySet()){
                            if(entry.getKey().isEmpty() || Arrays.stream(entry.getKey().split(",")).allMatch(selector->{
                                var property=selector.split("=",2);
                                return property[1].equals(state.properties().get(property[0]));
                            }))parts.add(entry.getValue());
                        }
                        assertEquals(1,parts.size());
                    }
                    var labels=new ArrayList<String>();
                    var models=new ArrayList<JsonObject>();
                    for(var part:parts){
                        if(!part.isJsonArray()){models.add(part.getAsJsonObject());continue;}
                        var options=part.getAsJsonArray();
                        int[] weights=new int[options.size()];
                        for(int i=0;i<weights.length;i++){var option=options.get(i).getAsJsonObject();weights[i]=option.has("weight")?option.get("weight").getAsInt():1;}
                        int index=WeightedVariantsTest.reference(WeightedVariantsTest.randomAt(x,y,z,multipart),weights,version.equals("1.21.1"));
                        if(options.size()>1)labels.add(Integer.toString(index));
                        models.add(options.get(index).getAsJsonObject());
                    }
                    String label=labels.stream().allMatch("0"::equals)?"0":String.join(".",labels);
                    var key=prepared.renderState(state,x,y,z);
                    assertEquals(label,key.properties().getOrDefault("__mcwe_variant","0"),()->name+" at "+x+","+y+","+z);
                    counts.merge(label,1,Integer::sum);
                    if(seen.add(key)){
                        var result=resolver.resolve(state,new ModelBlockContext(area,new ModelBlockContext.Position(x,y,z),CancellationSignal.NONE));
                        var model=result.model().orElseThrow();
                        assertFalse(model.fixedFirstAlternative());
                        assertEquals(models.size(),model.parts().size());
                        for(int i=0;i<models.size();i++) {
                            var expected=models.get(i);var actual=model.parts().get(i);
                            assertEquals(ResourceLocation.parse(expected.get("model").getAsString()),actual.id());
                            assertEquals(expected.has("y")?expected.get("y").getAsInt():0,actual.yRotation());
                            assertEquals(expected.has("x")?expected.get("x").getAsInt():0,actual.xRotation());
                            assertEquals(expected.has("uvlock") && expected.get("uvlock").getAsBoolean(),actual.uvLock());
                        }
                        var faces=prepared.surfaces(key);
                        assertNotNull(faces);
                        assertTrue(faces.stream().anyMatch(face->hasVisibleTexel(atlas,pixels,face)),()->"No textured surface for "+key);
                        assertTrue(prepared.diagnostics(key).stream().noneMatch(d->d.severity()==BlockResourceDiagnostic.Severity.FAILURE));
                    }
                }
                assertTrue(counts.size()>1,()->name+" "+counts);
                System.out.println(name+" "+counts+" faces="+scene.faceCount());
            }
        }
    }

    private static boolean hasVisibleTexel(TextureAtlas atlas,int[] pixels,BlockTextureSet.Surface face){
        float u0=1,u1=0,v0=1,v1=0;
        var uv=face.uv();
        for(int i=0;i<uv.size();i+=2){
            u0=Math.min(u0,uv.get(i));u1=Math.max(u1,uv.get(i));
            v0=Math.min(v0,uv.get(i+1));v1=Math.max(v1,uv.get(i+1));
        }
        int x0=Math.max(0,(int)Math.floor(u0*atlas.width())),x1=Math.min(atlas.width(),(int)Math.ceil(u1*atlas.width()));
        int y0=Math.max(0,(int)Math.floor(v0*atlas.height())),y1=Math.min(atlas.height(),(int)Math.ceil(v1*atlas.height()));
        for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++)if((pixels[y*atlas.width()+x]>>>24)!=0)return true;
        return false;
    }
}
