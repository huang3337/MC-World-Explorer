package com.mcworldexplorer.voxel.resource;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TextureAtlasTest {
    @TempDir Path home;
    @Test void missingAndCorruptPngFallBackWithoutBreakingThePalette() throws Exception {
        for(String reference:List.of("block/stone","block/missing")) {
            Path jar=home.resolve(reference.endsWith("stone")?"corrupt.jar":"missing.jar");
            List<String> faces=new ArrayList<>();
            for(String d:List.of("down","up","north","south","west","east"))faces.add("\""+d+"\":{\"texture\":\"#all\"}");
            Map<String,String> entries=Map.of(
                    "version.json","{\"id\":\"1.21.1\",\"world_version\":3955,\"pack_version\":{\"resource\":34}}",
                    "assets/minecraft/blockstates/stone.json","{\"variants\":{\"\":{\"model\":\"block/stone\"}}}",
                    "assets/minecraft/models/block/stone.json","{\"textures\":{\"all\":\""+reference+"\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{"+String.join(",",faces)+"}}]}",
                    "assets/minecraft/textures/block/stone.png","deliberately invalid png");
            try(var output=new ZipOutputStream(Files.newOutputStream(jar))) {
                for(var e:entries.entrySet()){output.putNextEntry(new ZipEntry(e.getKey()));output.write(e.getValue().getBytes(StandardCharsets.UTF_8));output.closeEntry();}
            }
            try(var archive=ClientResourceArchive.open(jar,"1.21.1")) {
                var textures=BlockTextureSet.load(archive,com.mcworldexplorer.voxel.io.CancellationSignal.NONE);
                var state=new com.mcworldexplorer.voxel.data.BlockState("minecraft:stone",Map.of());
                assertNull(textures.surfaces(state));assertTrue(textures.fallbackReason(state).contains("PNG"));
            }
        }
    }
    @Test void decodesPngAndRejectsMalformedOversizedAndAnimationStrips() throws Exception {
        assertEquals(16, TextureAtlas.decode(png(16,16)).getWidth());
        assertThrows(IOException.class, () -> TextureAtlas.decode(new byte[64]));
        assertThrows(IOException.class, () -> TextureAtlas.decode(png(257,257)));
        assertThrows(IOException.class, () -> TextureAtlas.decode(png(16,32)));
        byte[] truncated = java.util.Arrays.copyOf(png(16,16), 35);
        assertThrows(IOException.class, () -> TextureAtlas.decode(truncated));
    }
    @Test void extrudesEdgesDeduplicatesAndBoundsAtlasStorage() throws Exception {
        BufferedImage image = new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0,0,0xFFFF0000); image.setRGB(1,0,0xFF00FF00);
        image.setRGB(0,1,0xFF0000FF); image.setRGB(1,1,0xFFFFFFFF);
        var builder = new TextureAtlas.Builder();
        var tile = builder.add("a", image);
        assertEquals(tile, builder.add("a", image));
        var atlas = builder.build(); int[] pixels = atlas.pixels();
        assertEquals(0xFFFF0000, pixels[0]); assertEquals(0xFF00FF00, pixels[3]);
        assertEquals(0xFF0000FF, pixels[3*atlas.width()]);
        pixels[0] = 0; assertEquals(0xFFFF0000, atlas.pixels()[0]);
        assertTrue(atlas.estimatedBytes() <= 4L*1024*1024);
        for (int i=1; i<128; i++) builder.add("x"+i,image);
        assertThrows(IOException.class, () -> builder.add("overflow",image));
        var large = new TextureAtlas.Builder();
        BufferedImage big = new BufferedImage(256,256,BufferedImage.TYPE_INT_ARGB);
        for (int i=0; i<9; i++) large.add("large"+i,big);
        assertThrows(IOException.class, () -> large.add("no-space",big));
    }
    @Test void compositesTintedGrassWithoutChangingItsOpaqueBase() throws Exception {
        BufferedImage base = new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);
        BufferedImage overlay = new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);
        base.setRGB(0,0,0xFF806040); overlay.setRGB(0,0,0xFFFFFFFF);
        assertEquals(0xFF91BD59, BlockTextureSet.composite(base,-1,overlay,0).getRGB(0,0));
        overlay.setRGB(0,0,0x00FFFFFF);
        assertEquals(0xFF806040, BlockTextureSet.composite(base,-1,overlay,0).getRGB(0,0));
        base.setRGB(0,0,0x00806040);
        assertThrows(IOException.class, () -> BlockTextureSet.composite(base,-1,null,-1));
    }
    private static byte[] png(int w,int h) throws IOException {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB),"png",output);
        return output.toByteArray();
    }
}
