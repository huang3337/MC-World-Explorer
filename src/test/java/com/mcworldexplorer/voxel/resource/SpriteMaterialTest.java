package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.mesh.RenderLayer;
import java.nio.file.Path;
import java.util.*;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpriteMaterialTest {
    private static final BlockState STATE=new BlockState("unregistered:fixture",Map.of());
    private static final String MODEL="assets/unregistered/models/block/fixture.json";
    private static final String ELEMENT="{\"from\":[0,0,0],\"to\":[16,16,0],\"faces\":{\"north\":{\"texture\":\"#alias\"}}}";
    private static String model(String binding,String element){return "{\"textures\":{\"alias\":\"#p\",\"p\":"+binding+"},\"elements\":["+element+"]}";}
    private static BlockResourceSource source(String model,Map<String,String> extra)throws Exception {
        var image=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,0xFFFFFFFF);var png=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",png);
        Map<String,String> entries=new HashMap<>(extra);entries.put(MODEL,model);entries.putIfAbsent("assets/unregistered/blockstates/fixture.json","{\"variants\":{\"\":{\"model\":\"unregistered:block/fixture\"}}}");
        return new BlockResourceSource(){
            public ClientResourceArchive.Fingerprint fingerprint(){return new ClientResourceArchive.Fingerprint(Path.of("sprite-fixture"),1,1,"fixture");}
            public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id){return kind==ResourceLocation.Kind.TEXTURE&&id.toString().equals("unregistered:paint")?Optional.of(png.toByteArray()):Optional.empty();}
            public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id){return Optional.ofNullable(entries.get(id.entryName(kind)));}
        };
    }
    private static int pixel(BlockTextureSet textures){var surface=textures.surfaces(STATE).getFirst();double u=0,v=0;for(int i=0;i<4;i++){u+=surface.uv().get(i*2)/4;v+=surface.uv().get(i*2+1)/4;}return textures.atlas().pixels()[(int)(v*textures.atlas().height())*textures.atlas().width()+(int)(u*textures.atlas().width())];}
    @Test void objectSpriteBindingKeepsItsForcedPassThroughAliasesAndPlainChildrenOverrideIt()throws Exception {
        String parent=model("{\"sprite\":\"unregistered:paint\",\"force_translucent\":true}",ELEMENT);
        var source=source(parent,Map.of());var result=new BlockModelResolver(source).resolve(STATE);assertTrue(result.resolved(),result.detail());assertTrue(result.model().orElseThrow().faces().getFirst().material().forceTranslucent());
        var textures=BlockTextureSet.load(source,List.of(STATE),CancellationSignal.NONE);assertEquals(RenderLayer.TRANSLUCENT,textures.surfaces(STATE).getFirst().layer());assertEquals(0xFFFFFFFF,pixel(textures));
        String child="{\"parent\":\"unregistered:block/base\",\"textures\":{\"p\":\"unregistered:paint\"}}";
        var plain=BlockTextureSet.load(source(child,Map.of("assets/unregistered/models/block/base.json",parent)),List.of(STATE),CancellationSignal.NONE);
        assertEquals(RenderLayer.OPAQUE,plain.surfaces(STATE).getFirst().layer());
    }
    @Test void openRuleContextsExposeImmutableFullBindingsAndKeepTheLegacyStringView()throws Exception {
        var rule=new ModelRule(){
            public ResourceLocation id(){return ResourceLocation.parse("unregistered:binding_rule");}
            public String version(){return "1";}
            public Set<ResourceLocation> loaders(){return Set.of(id());}
            public Set<String> fields(){return Set.of();}
            public List<BlockModelResolver.Part> resolve(Context c)throws java.io.IOException,ModelRule.Failure {
                assertEquals("unregistered:paint",c.textures().get("p"));assertTrue(c.textureBindings().get("p").forceTranslucent());
                assertThrows(UnsupportedOperationException.class,()->c.textureBindings().clear());return c.standard();
            }
        };
        String json=model("{\"sprite\":\"unregistered:paint\",\"force_translucent\":true}",ELEMENT).replace("{\"textures\":","{\"loader\":\"unregistered:binding_rule\",\"textures\":");
        var result=new BlockModelResolver(source(json,Map.of()),new ModelRuleRegistry(List.of(rule))).resolve(STATE);
        assertTrue(result.resolved(),result.detail());assertTrue(result.model().orElseThrow().faces().getFirst().material().forceTranslucent());
    }
    @Test void malformedBindingsDoNotSilentlyLoseTheirMaterialHint()throws Exception {
        for(String binding:List.of("{}","{\"sprite\":17}","{\"sprite\":\"#alias\"}","{\"sprite\":\"../escape\"}","{\"sprite\":\"unregistered:paint\",\"force_translucent\":\"true\"}"))assertEquals(BlockModelResolver.Reason.INVALID_MODEL,new BlockModelResolver(source(model(binding,ELEMENT),Map.of())).resolve(STATE).reason());
        assertEquals(BlockModelResolver.Reason.UNSUPPORTED_MODEL,new BlockModelResolver(source(model("{\"sprite\":\"unregistered:paint\",\"unknown\":true}",ELEMENT),Map.of())).resolve(STATE).reason());
    }
    @Test void directionOverridesUseStaticTerrainWeightsWithoutRotatingTheGeometryNormal()throws Exception {
        Map<String,Integer> expected=Map.of("up",255,"down",128,"north",204,"south",204,"west",153,"east",153);
        for(var entry:expected.entrySet()) {
            String element=ELEMENT.replace("\"from\":","\"shade_direction_override\":\""+entry.getKey()+"\",\"from\":");
            var textures=BlockTextureSet.load(source(model("\"unregistered:paint\"",element),Map.of()),List.of(STATE),CancellationSignal.NONE);
            assertNotNull(textures.surfaces(STATE),textures.fallbackReason(STATE));assertFalse(textures.surfaces(STATE).getFirst().shade());assertEquals(List.of(0f,0f,-1f),textures.surfaces(STATE).getFirst().normal());
            assertEquals(0xFF000000|entry.getValue()*0x010101,pixel(textures));
        }
        var light=ELEMENT.replace("\"from\":","\"shade_direction_override\":\"down\",\"neoforge_data\":{\"block_light\":15},\"from\":");
        assertEquals(0xFFFFFFFF,pixel(BlockTextureSet.load(source(model("\"unregistered:paint\"",light),Map.of()),List.of(STATE),CancellationSignal.NONE)));
        assertEquals(BlockModelResolver.Reason.INVALID_MODEL,new BlockModelResolver(source(model("\"unregistered:paint\"",ELEMENT.replace("\"from\":","\"shade_direction_override\":\"diagonal\",\"from\":")),Map.of())).resolve(STATE).reason());
    }
}
