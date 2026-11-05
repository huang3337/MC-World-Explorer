package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FaceMaterialTest {
    private static final BlockState STATE=new BlockState("unknown_author:painted",Map.of());
    private static BlockResourceSource source(String elements)throws Exception {
        var image=new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<2;y++)for(int x=0;x<2;x++)image.setRGB(x,y,0xFFFFFFFF);var png=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",png);
        Map<String,byte[]> entries=Map.of("assets/unknown_author/blockstates/painted.json",bytes("{\"variants\":{\"\":{\"model\":\"unknown_author:block/painted\"}}}"),"assets/unknown_author/models/block/painted.json",bytes("{\"textures\":{\"p\":\"unknown_author:block/paint\"},\"elements\":"+elements+"}"),"assets/unknown_author/textures/block/paint.png",png.toByteArray());
        return new BlockResourceSource(){public ClientResourceArchive.Fingerprint fingerprint(){return new ClientResourceArchive.Fingerprint(Path.of("synthetic"),1,1,"synthetic");}public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id){return Optional.ofNullable(entries.get(id.entryName(kind)));}public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id){return read(kind,id).map(data->new String(data,StandardCharsets.UTF_8));}};
    }
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static String element(String extra,String faceExtra){return "{\"from\":[0,0,0],\"to\":[16,16,0],"+extra+"\"faces\":{\"north\":{\"texture\":\"#p\""+faceExtra+"}}}";}
    private static int pixel(BlockTextureSet set,int surface) {var face=set.surfaces(STATE).get(surface);double u=0,v=0;for(int i=0;i<4;i++){u+=face.uv().get(i*2)/4;v+=face.uv().get(i*2+1)/4;}return set.atlas().pixels()[(int)(v*set.atlas().height())*set.atlas().width()+(int)(u*set.atlas().width())];}
    @Test void elementAndFaceDataHaveWholeObjectOverrideSemantics()throws Exception {
        String extra="\"forge_data\":{\"color\":\"80FF0000\",\"block_light\":15},";
        var source=source("["+element(extra,",\"neoforge_data\":{\"color\":\"FF00FF00\"}")+"]");
        var face=new BlockModelResolver(source).resolve(STATE).model().orElseThrow().faces().getFirst();assertEquals(new FaceMaterial(0xFF00FF00,0,0,true),face.material());
        var texture=BlockTextureSet.load(source,List.of(STATE),CancellationSignal.NONE);assertEquals(0xFF00FF00,pixel(texture,0));assertTrue(texture.surfaces(STATE).getFirst().shade());
        var inherited=BlockTextureSet.load(source("["+element(extra,"")+"]"),List.of(STATE),CancellationSignal.NONE);assertEquals(0x80FF0000,pixel(inherited,0));assertFalse(inherited.surfaces(STATE).getFirst().shade());
    }
    @Test void translucentEmissiveOverlayCompositesWithoutBrighteningTheUnderlyingFace()throws Exception {
        String base=element("\"forge_data\":{\"color\":\"FFFF0000\"},","");
        String overlay=element("\"neoforge_data\":{\"color\":\"8000FF00\",\"block_light\":15},","");
        var texture=BlockTextureSet.load(source("["+base+","+overlay+"]"),List.of(STATE),CancellationSignal.NONE);assertNotNull(texture.surfaces(STATE),texture.fallbackReason(STATE));assertEquals(1,texture.surfaces(STATE).size());assertFalse(texture.surfaces(STATE).getFirst().shade());
        int red=(int)Math.round(255*BlockTextureSet.directionalBrightness(List.of(0f,0f,-1f)));int expectedRed=((red*127+127)/255);assertEquals(0xFF000000|(expectedRed<<16)|(128<<8),pixel(texture,0));
    }
    @Test void invalidDataAndUnknownFieldsRemainFailuresAndArgbRepresentationsAgree()throws Exception {
        for(String argb:List.of("\"FFFFFFFF\"","\"0xFFFFFFFF\"","-1","4294967295")){
            var result=new BlockModelResolver(source("["+element("\"neoforge_data\":{\"color\":"+argb+"},","")+"]")).resolve(STATE);assertTrue(result.resolved(),result.detail());assertEquals(FaceMaterial.DEFAULT,result.model().orElseThrow().faces().getFirst().material());
        }
        for(String data:List.of("{\"block_light\":16}","{\"sky_light\":-1}","{\"color\":4294967296}","{\"color\":\"oops\"}","{\"color\":1.5}","{\"ambient_occlusion\":\"false\"}"))assertEquals(BlockModelResolver.Reason.INVALID_MODEL,new BlockModelResolver(source("["+element("\"forge_data\":"+data+",","")+"]")).resolve(STATE).reason());
        assertEquals(BlockModelResolver.Reason.UNSUPPORTED_MODEL,new BlockModelResolver(source("["+element("\"forge_data\":{\"unimplemented_material\":true},","")+"]")).resolve(STATE).reason());
        assertEquals(BlockModelResolver.Reason.LIMIT_EXCEEDED,new BlockModelResolver(source("["+element("\"forge_data\":{\"color\":1"+"0".repeat(64)+"},","")+"]")).resolve(STATE).reason());
        assertThrows(IllegalArgumentException.class,()->new FaceMaterial(-1,16,0,true));
    }
}
