package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.mesh.RenderLayer;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.awt.image.BufferedImage;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeclarativeModelLoaderTest {
    private static final BlockState STATE=new BlockState("example:shape",Map.of());
    private static final String MODEL="assets/example/models/block/shape.json";
    private static final String OBJ="v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\nvn 0 0 1\nmtllib plane.mtl\nusemtl paint\nf -4/1/1 -3/2/1 -2/3/1 -1/4/1\n";
    private static BlockResourceSource source(Map<String,String> replacements)throws Exception {
        Map<String,byte[]> resources=new HashMap<>();resources.put("assets/example/blockstates/shape.json","{\"variants\":{\"\":{\"model\":\"example:block/shape\"}}}".getBytes(StandardCharsets.UTF_8));
        resources.put(MODEL,"{\"loader\":\"neoforge:obj\",\"model\":\"example:models/plane.obj\",\"flip_v\":true,\"textures\":{\"paint\":\"example:block/paint\",\"particle\":\"example:block/paint\"}}".getBytes(StandardCharsets.UTF_8));
        resources.put("assets/example/models/plane.obj",OBJ.getBytes(StandardCharsets.UTF_8));resources.put("assets/example/models/plane.mtl","newmtl paint\nmap_Kd #paint\nKd 0.5 1 1\nd 0.5\n".getBytes(StandardCharsets.UTF_8));
        var image=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<16;y++)for(int x=0;x<16;x++)image.setRGB(x,y,0xFFFFFFFF);
        var output=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",output);resources.put("assets/example/textures/block/paint.png",output.toByteArray());
        replacements.forEach((key,value)->resources.put(key,value.getBytes(StandardCharsets.UTF_8)));
        return new BlockResourceSource(){
            public ClientResourceArchive.Fingerprint fingerprint(){return new ClientResourceArchive.Fingerprint(Path.of("synthetic").toAbsolutePath(),1,1,"synthetic");}
            public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id){return Optional.ofNullable(resources.get(id.entryName(kind)));}
            public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id){return read(kind,id).map(bytes->new String(bytes,StandardCharsets.UTF_8));}
        };
    }
    @Test void objUsesResourceMaterialsNegativeIndicesTrueGeometryAndMaterialAlpha()throws Exception {
        var source=source(Map.of());var result=new BlockModelResolver(source).resolve(STATE);assertTrue(result.resolved(),result.detail());
        assertEquals(1,result.model().orElseThrow().faces().size());
        var textures=BlockTextureSet.load(source,List.of(STATE),CancellationSignal.NONE);assertNotNull(textures.surfaces(STATE),textures.fallbackReason(STATE));
        var face=textures.surfaces(STATE).getFirst();assertEquals(RenderLayer.TRANSLUCENT,face.layer());assertEquals(List.of(0f,0f,1f),face.normal());
        assertEquals(1,face.positions().stream().mapToDouble(Float::doubleValue).max().orElseThrow(),1e-6);
        assertTrue(Arrays.stream(textures.atlas().pixels()).anyMatch(pixel->pixel==0x8080FFFF));
        assertTrue(result.sources().stream().anyMatch(item->item.kind()==ResourceLocation.Kind.RAW_ASSET&&item.id().path().endsWith("plane.mtl")));
    }
    @Test void objInvalidIndicesAndEscapingMaterialReferencesAreVisibleFailures()throws Exception {
        var badIndex=new BlockModelResolver(source(Map.of("assets/example/models/plane.obj",OBJ.replace("-4/1/1","0/1/1")))).resolve(STATE);
        assertEquals(BlockModelResolver.Reason.INVALID_MODEL,badIndex.reason());
        var badPath=new BlockModelResolver(source(Map.of("assets/example/models/plane.obj",OBJ.replace("plane.mtl","../../../escape.mtl")))).resolve(STATE);
        assertEquals(BlockModelResolver.Reason.INVALID_MODEL,badPath.reason());
        var unsupported=new BlockModelResolver(source(Map.of(MODEL,"{\"loader\":\"example:runtime_renderer\"}"))).resolve(STATE);
        assertEquals(BlockModelResolver.Reason.UNSUPPORTED_MODEL,unsupported.reason());assertTrue(unsupported.detail().contains("example:runtime_renderer"));
    }
    @Test void compositeInheritsChildrenAndVisibilityWithoutLoadingHiddenParts()throws Exception {
        String element="{\"textures\":{\"p\":\"example:block/paint\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,0],\"faces\":{\"south\":{\"texture\":\"#p\"}}}]}";
        var source=source(Map.of(MODEL,"{\"parent\":\"example:block/base\",\"visibility\":{\"hidden\":false}}",
            "assets/example/models/block/base.json","{\"loader\":\"neoforge:composite\",\"children\":{\"visible\":{\"parent\":\"example:block/visible\"},\"hidden\":{\"parent\":\"example:block/absent\"}}}",
            "assets/example/models/block/visible.json",element));
        var result=new BlockModelResolver(source).resolve(STATE);assertTrue(result.resolved(),result.detail());assertEquals(1,result.model().orElseThrow().faces().size());
        assertTrue(result.sources().stream().noneMatch(item->item.id().path().contains("absent")));
    }
    @Test void repeatedUvsAndMetadataRemainGenericForUnregisteredNames()throws Exception {
        String model="{\"credit\":\"synthetic author\",\"format_version\":\"1.9.0\",\"textures\":{\"p\":\"example:block/paint\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,0],\"faces\":{\"south\":{\"texture\":\"#p\",\"uv\":[-16,0,32,16]}}}]}";
        var textures=BlockTextureSet.load(source(Map.of(MODEL,model)),List.of(STATE),CancellationSignal.NONE);
        assertNotNull(textures.surfaces(STATE),textures.fallbackReason(STATE));assertEquals(3,textures.surfaces(STATE).size());
    }
    @Test void equivalentCrossPlatformObjDeclarationsDoNotConflict()throws Exception {
        var source=source(Map.of(MODEL,"{\"loader\":\"neoforge:obj\",\"porting_lib:loader\":\"porting_lib:obj\",\"ambient_occlusion\":false,\"model\":\"example:models/plane.obj\",\"textures\":{\"paint\":\"example:block/paint\"}}"));
        var result=new BlockModelResolver(source).resolve(STATE);assertTrue(result.resolved(),result.detail());
    }
    @Test void multipartBooleanConditionsUseTheStoredPropertyValue()throws Exception {
        var source=source(Map.of("assets/example/blockstates/shape.json","{\"multipart\":[{\"when\":{\"powered\":true},\"apply\":{\"model\":\"example:block/shape\"}}]}"));
        var resolver=new BlockModelResolver(source);
        assertEquals(1,resolver.resolve(new BlockState("example:shape",Map.of("powered","true"))).model().orElseThrow().faces().size());
        assertTrue(resolver.resolve(new BlockState("example:shape",Map.of("powered","false"))).model().orElseThrow().faces().isEmpty());
    }
    @Test void compositeDefaultMaterialKeepsChildOverrides()throws Exception {
        String child="{\"parent\":\"example:block/child\"}";
        String model="{\"loader\":\"neoforge:composite\",\"render_type\":\"minecraft:translucent\",\"children\":{\"a\":"+child+",\"b\":{\"parent\":\"example:block/child\",\"render_type\":\"minecraft:cutout\"}}}";
        var result=new BlockModelResolver(source(Map.of(MODEL,model,"assets/example/models/block/child.json","{\"elements\":[]}"))).resolve(STATE);
        assertTrue(result.resolved(),result.detail());assertEquals(List.of("minecraft:translucent","minecraft:cutout"),result.model().orElseThrow().parts().stream().map(BlockModelResolver.Part::renderType).toList());
    }
}
