package com.mcworldexplorer.voxel.resource;

import com.google.gson.JsonObject;
import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.io.*;
import com.mcworldexplorer.voxel.mesh.VoxelSceneBuilder;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.Direction;
import static com.mcworldexplorer.voxel.resource.ModelBlockContext.Position;

class VanillaVegetationTintTest {
    @Test void matchingVersionWithDifferentRegistrationKeepsTintUnbound() throws Exception {
        var changed=new BlockResourceSource() {
            public Optional<String> baseClientVersion(){return Optional.of("26.3");}
            public Optional<RegistrationClass> clientClass(String name){
                return Optional.of(new RegistrationClass(name,new byte[]{0},"fixture","changed-client"));
            }
            public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id){throw new AssertionError("unexpected texture access");}
            public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id){throw new AssertionError("unexpected definition access");}
            public ClientResourceArchive.Fingerprint fingerprint(){throw new AssertionError("unexpected archive access");}
        };
        var resolver=new BlockTintResolver(changed);
        assertTrue(resolver.kind(new BlockState("minecraft:grass_block",Map.of()),0).isEmpty());
    }

    @Test void blendsBothSavedBiomesPerChannelAndReportsMissingCells() throws Exception {
        var position=new Position(0,0,0);
        var center=new ModelBlockContext.Sample(Optional.of(BlockState.AIR),Optional.empty(),ChunkLoadStatus.SUCCESS,"");
        var view=new ModelBlockContext.View(position,"minecraft:overworld",Map.of(new ModelBlockContext.Offset(0,0,0),center));
        Map<Position,String> cells=new HashMap<>();
        for(int x=-2;x<=2;x++) for(int y=-2;y<=2;y++) for(int z=-2;z<=2;z++)
            cells.put(new Position(x,y,z),x<0?"fixture:red":"fixture:blue");
        var inputs=new SavedBiomeColors.Inputs("fixture",0,2,new BlockResourceSource.DimensionHeight(-64,384));
        SavedBiomeColors.DataReader definitions=id->{
            var effects=new JsonObject();
            if(id.path().endsWith("/red.json"))effects.addProperty("grass_color","#ff0000");
            else effects.addProperty("grass_color",255);
            var root=new JsonObject();root.add("effects",effects);return Optional.of(root);
        };
        var biomes=new SavedBiomeColors();
        int rgb=biomes.color(ModelRule.BiomeColor.GRASS,null,definitions,note->{},
                new ModelBlockContext(view,cells),position,new ArrayList<>(),inputs);
        // The client's eight-corner sampler with zoom seed 0 selects 18 red and 7 blue cells in this 5x5 window.
        assertEquals((18*255/25)<<16 | 7*255/25,rgb);
        assertThrows(ModelRule.Failure.class,()->biomes.color(ModelRule.BiomeColor.GRASS,null,definitions,note->{},
                new ModelBlockContext(view,Map.of()),position,new ArrayList<>(),inputs));
    }

    @Test void checksCherryGroveVegetationInSaved1215Selection() throws Exception {
        checkScene("1.21.5","MCWE_TINT_1215_WORLD",0,0,0,94,15,0xb6db61);
    }

    @Test void checksPlainsGrassInSaved263Selection() throws Exception {
        checkScene("26.3","MCWE_TINT_263_WORLD",0,7,0,59,127,0x91bd59);
    }

    @Test void foliageUsesTheSavedBiomeOrRegisteredConstantInRealForests() throws Exception {
        checkSamples("MCWE_TINT_1215_WORLD","1.21.5",7,1,
                new Sample(127,78,18,"oak_leaves",0x84a93d));
        checkSamples("MCWE_TINT_263_WORLD","26.3",-20,0,
                new Sample(-315,64,3,"oak_leaves",0x59ae30),
                new Sample(-311,67,0,"birch_leaves",0x80a755));
    }

    private record Sample(int x,int y,int z,String block,String sprite,int rgb) {
        Sample(int x,int y,int z,String block,int rgb){this(x,y,z,block,block,rgb);}
    }

    @Test void checksNewlyBoundWorldLayersAgainstSavedPositions() throws Exception {
        checkSamples("MCWE_TINT_1215_WORLD","1.21.5",4,-24,new Sample(72,64,-371,"leaf_litter",0xa37546));
        checkSamples("MCWE_TINT_1215_WORLD","1.21.5",0,-18,new Sample(4,72,-285,"sugar_cane",0x80ac33));
        checkSamples("MCWE_TINT_1215_WORLD","1.21.5",0,-19,
                new Sample(4,72,-294,"melon_stem",0xe0c71c),new Sample(7,72,-290,"pumpkin_stem","pumpkin_stem1",0xe0c71c));
        checkSamples("MCWE_TINT_1215_WORLD","1.21.5",-49,-10,
                new Sample(-771,68,-156,"redstone_wire","redstone_dust_line0",0x4c0000));
        checkSamples("MCWE_TINT_263_WORLD","26.3",-20,0,new Sample(-309,63,0,"leaf_litter",0xa36d46));
        checkSamples("MCWE_TINT_263_WORLD","26.3",7,1,new Sample(119,63,19,"sugar_cane",0x91bd59));
    }

    private static void checkSamples(String setting,String version,int cx,int cz,Sample... samples)throws Exception {
        String location=System.getenv(setting);assumeTrue(location!=null,setting);
        Path world=Path.of(location),instance=world.getParent().getParent();
        var target=ChunkRectangle.target(cx,cz,cx,cz);
        var area=new VoxelAreaLoader().load(new VoxelAreaRequest(world,WorldDimension.OVERWORLD_ID,target),CancellationSignal.NONE);
        var chunk=area.result(new ChunkCoordinate(cx,cz)).chunk();
        try(var source=InstanceResourceSource.open(world,instance.resolve(instance.getFileName()+".jar"),version,instance,CancellationSignal.NONE,s->{})){
            var prepared=BlockTextureSet.load(source,area,CancellationSignal.NONE);
            var resolver=new BlockModelResolver(source);
            var bindings=new ClientBlockColors(source);
            assertEquals(ClientBlockColors.Kind.SPRUCE,bindings.layer(ResourceLocation.parse("spruce_leaves"),0).orElseThrow());
            for(var leaf:samples){
                var raw=chunk.blockState(leaf.x()&15,leaf.y(),leaf.z()&15);
                var key=prepared.renderState(raw,leaf.x(),leaf.y(),leaf.z());
                assertEquals("minecraft:"+leaf.block(),raw.name());
                assertFalse(prepared.diagnostics(key).stream().anyMatch(d->d.reasonCode().equals("UNBOUND_TINT")));
                assertModelSprite(prepared,source,resolver,new Position(leaf.x(),leaf.y(),leaf.z()),
                        raw,key,leaf.sprite(),0,leaf.rgb());
            }
        }
    }

    private static void checkScene(String version,String setting,int cx,int cz,int x,int y,int z,int expected) throws Exception {
        String path=System.getenv(setting);assumeTrue(path!=null,"需要指定只读植被存档样本");
        Path world=Path.of(path).toAbsolutePath(), instance=world.getParent().getParent();
        Path client=instance.resolve(instance.getFileName()+".jar");
        var area=new VoxelAreaLoader().load(new VoxelAreaRequest(world,WorldDimension.OVERWORLD_ID,
                ChunkRectangle.target(cx,cz,cx,cz)),CancellationSignal.NONE);
        var chunk=area.result(new ChunkCoordinate(cx,cz)).chunk();
        try(var source=InstanceResourceSource.open(world,client,version,instance,CancellationSignal.NONE,s->{})){
            var prepared=BlockTextureSet.load(source,area,CancellationSignal.NONE);
            var scene=new VoxelSceneBuilder(prepared).build(area,CancellationSignal.NONE).snapshot().orElseThrow();
            assertTrue(scene.warnings().stream().flatMap(w->w.diagnostic().stream()).noneMatch(d->d.reasonCode().equals("UNBOUND_TINT")));
            var grass=chunk.blockState(x&15,y,z&15);
            assertEquals("minecraft:grass_block",grass.name());
            var rendered=prepared.renderState(grass,x,y,z);
            assertEquals("{0="+(0x1000000|expected)+"}",rendered.properties().get("__mcwe_tint"));
            assertSprite(prepared,source,rendered,Direction.UP,"grass_block_top",expected);
            assertSprite(prepared,source,rendered,Direction.DOWN,"dirt",0xffffff);
            var bindings=new ClientBlockColors(source);
            var standard=Map.<String, ClientBlockColors.Kind>ofEntries(
                    Map.entry("leaf_litter",ClientBlockColors.Kind.DRY_FOLIAGE),Map.entry("sugar_cane",ClientBlockColors.Kind.GRASS),
                    Map.entry("water_cauldron",ClientBlockColors.Kind.WATER),Map.entry("redstone_wire",ClientBlockColors.Kind.REDSTONE),
                    Map.entry("attached_melon_stem",ClientBlockColors.Kind.ATTACHED_STEM),Map.entry("attached_pumpkin_stem",ClientBlockColors.Kind.ATTACHED_STEM),
                    Map.entry("melon_stem",ClientBlockColors.Kind.STEM),Map.entry("pumpkin_stem",ClientBlockColors.Kind.STEM),
                    Map.entry("lily_pad",ClientBlockColors.Kind.LILY_PAD));
            standard.forEach((id,kind)->assertEquals(kind,bindings.layer(ResourceLocation.parse(id),0).orElseThrow(),id));
            for(String id:List.of("water","bubble_column"))assertEquals(version.equals("26.3")?ClientBlockColors.Kind.WHITE:ClientBlockColors.Kind.WATER,
                    bindings.layer(ResourceLocation.parse(id),0).orElseThrow(),id);
            assertTrue(bindings.layer(ResourceLocation.parse("fixture:grass_block"),0).isEmpty());
            if(version.equals("26.3"))
                assertTrue(bindings.layer(ResourceLocation.parse("grass_block"),1).isEmpty());
            if(version.equals("1.21.5")){
                var leaf=chunk.blockState(10,106,0);
                assertEquals("minecraft:cherry_leaves",leaf.name());
                assertSprite(prepared,source,prepared.renderState(leaf,10,106,0),Direction.UP,"cherry_leaves",0xffffff);
                var blade=chunk.blockState(0,96,12);
                assertEquals("minecraft:short_grass",blade.name());
                assertEquals(rendered.properties().get("__mcwe_tint"),prepared.renderState(blade,0,96,12).properties().get("__mcwe_tint"));
                var flower=chunk.blockState(0,98,11);
                assertEquals("minecraft:pink_petals",flower.name());
                assertEquals("{1="+(0x1000000|expected)+"}",prepared.renderState(flower,0,98,11).properties().get("__mcwe_tint"));
                var resolver=new BlockModelResolver(source);
                var flowerKey=prepared.renderState(flower,0,98,11);
                assertModelSprite(prepared,source,resolver,new ModelBlockContext.Position(0,98,11),flower,flowerKey,"pink_petals",-1,0xffffff);
                assertModelSprite(prepared,source,resolver,new ModelBlockContext.Position(0,98,11),flower,flowerKey,"pink_petals_stem",1,expected);
                assertModelSprite(prepared,source,resolver,new ModelBlockContext.Position(0,96,12),blade,prepared.renderState(blade,0,96,12),"short_grass3",0,expected);
            }
            source.validateAll();
        }
    }
    private static void assertSprite(BlockTextureSet prepared,BlockResourceSource source,BlockState state,
            Direction side,String sprite,int rgb) throws Exception {
        StandardBlockTintTest.assertTile(prepared,state,side,expectedSprite(source,sprite,rgb));
    }

    private static void assertModelSprite(BlockTextureSet prepared,BlockResourceSource source,BlockModelResolver resolver,
            ModelBlockContext.Position position,BlockState physical,BlockState painted,String sprite,int index,int rgb) throws Exception {
        var model=resolver.resolveAt(physical,position).model().orElseThrow();
        var expected=expectedSprite(source,sprite,rgb);
        for(var part:model.parts()) for(var face:part.faces()) {
            if(!face.texture().path().equals("block/"+sprite) || face.tintIndex()!=index) continue;
            var quad=ModelGeometry.bake(face,part);
            var surface=prepared.surfaces(painted).stream().filter(s->s.positions().equals(quad.positions())
                    && s.normal().equals(quad.normal())).findFirst().orElseThrow();
            var atlas=prepared.atlas();
            int sx=Math.round(surface.uv().get(0)*atlas.width()-quad.uv().get(0)*expected.getWidth());
            int sy=Math.round(surface.uv().get(1)*atlas.height()-quad.uv().get(1)*expected.getHeight());
            var pixels=atlas.pixels();
            for(int y=0;y<expected.getHeight();y++)for(int x=0;x<expected.getWidth();x++)
                assertEquals(expected.getRGB(x,y),pixels[(sy+y)*atlas.width()+sx+x],sprite+" "+x+","+y);
            return;
        }
        fail("模型缺少预期纹理或层："+sprite+" / "+index);
    }

    private static java.awt.image.BufferedImage expectedSprite(BlockResourceSource source,String sprite,int rgb) throws Exception {
        var image=TextureAtlas.decode(source.read(ResourceLocation.Kind.TEXTURE,ResourceLocation.parse("minecraft:block/"+sprite)).orElseThrow(),false);
        var oracle=new java.awt.image.BufferedImage(image.getWidth(),image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
        if (sprite.equals("grass_block_top")) assertEquals(0xff949494,image.getRGB(0,0),"原版 PNG 首像素的灰度样本为 148");
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){
            int original=image.getRGB(x,y),tinted=original&0xff000000;
            for(int shift=0;shift<=16;shift+=8)tinted|=(((original>>shift)&255)*((rgb>>shift)&255)/255)<<shift;
            oracle.setRGB(x,y,tinted);
        }
        return oracle;
    }
}
