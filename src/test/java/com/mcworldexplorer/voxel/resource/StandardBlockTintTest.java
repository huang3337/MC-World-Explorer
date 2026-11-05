package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.io.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.mcworldexplorer.voxel.resource.ClientBlockColors.Kind;

/** Limited real-client witnesses plus arithmetic boundaries; no game classes are loaded. */
class StandardBlockTintTest {
    static JsonObject fixture()throws Exception {String path=System.getenv("MCWE_COLOR_FIXTURE");assumeTrue(path!=null,"需显式选择只读颜色样本");return JsonParser.parseString(Files.readString(Path.of(path))).getAsJsonObject();}
    static BlockState state(String id,String... values){var p=new TreeMap<String,String>();for(int i=0;i<values.length;i+=2)p.put(values[i],values[i+1]);return new BlockState(id,p);}
    @Test void stateColorsAreNotGrassAndInvalidPropertiesAreRejected()throws Exception {
        assertEquals(0x619961,BlockTintResolver.constantOrState(Kind.SPRUCE,state("fixture:tree")));
        assertEquals(0x80A755,BlockTintResolver.constantOrState(Kind.BIRCH,state("fixture:tree")));
        assertEquals(0x4C0000,BlockTintResolver.constantOrState(Kind.REDSTONE,state("fixture:wire","power","0")));
        assertEquals(0xFF3200,BlockTintResolver.constantOrState(Kind.REDSTONE,state("fixture:wire","power","15")));
        assertEquals(0x00FF00,BlockTintResolver.constantOrState(Kind.STEM,state("fixture:stem","age","0")));
        assertEquals(0xE0C71C,BlockTintResolver.constantOrState(Kind.STEM,state("fixture:stem","age","7")));
        for(String invalid:List.of("-1","16","bad","01"))assertThrows(ModelRule.Failure.class,()->BlockTintResolver.constantOrState(Kind.REDSTONE,state("fixture:wire","power",invalid)));
        assertThrows(ModelRule.Failure.class,()->BlockTintResolver.constantOrState(Kind.STEM,state("fixture:stem")));
    }
    @Test void twoSelectedClientsDeriveBindingsFromRegistrationAndHandleIndependentColorFamilies()throws Exception {
        for(var value:fixture().getAsJsonArray("clients")) {
            var row=value.getAsJsonObject();try(var source=ClientResourceArchive.open(Path.of(row.get("path").getAsString()),row.get("version").getAsString())) {
                var protocol=new ClientBlockColors(source);
                assertEquals(Optional.of(Kind.GRASS),protocol.kind(ResourceLocation.parse("minecraft:grass_block")));
                assertEquals(Optional.of(Kind.FOLIAGE),protocol.kind(ResourceLocation.parse("minecraft:oak_leaves")));
                assertEquals(Optional.of(Kind.BIRCH),protocol.kind(ResourceLocation.parse("minecraft:birch_leaves")));
                assertEquals(Optional.of(Kind.REDSTONE),protocol.kind(ResourceLocation.parse("minecraft:redstone_wire")));
                assertTrue(protocol.kind(ResourceLocation.parse("fixture:grass_block")).isEmpty());
                for(String leaf:List.of("spruce_leaves","birch_leaves")) {
                    var state=state("minecraft:"+leaf,"distance","1","persistent","false","waterlogged","false");
                    var prepared=BlockTextureSet.load(source,List.of(state),CancellationSignal.NONE);
                    assertNotNull(prepared.surfaces(state));assertTrue(prepared.notes(state).stream().noneMatch(n->n.contains("颜色未解析")),prepared.notes(state).toString());
                    var raw=TextureAtlas.decode(source.read(ResourceLocation.Kind.TEXTURE,ResourceLocation.parse("minecraft:block/"+leaf)).orElseThrow(),false);
                    int rgb=leaf.startsWith("spruce")?0x619961:0x80A755;
                    assertTile(prepared,state,BlockModelResolver.Direction.UP,BlockTextureSet.composite(raw,0x1000000|rgb,null,-1));
                }
                var wire=state("minecraft:redstone_wire","power","15","north","side","south","side","east","none","west","none");
                var ready=BlockTextureSet.load(source,List.of(wire),CancellationSignal.NONE);assertNotNull(ready.surfaces(wire));assertTrue(ready.notes(wire).stream().noneMatch(n->n.contains("颜色未解析")));
                source.validate();
            }
        }
    }
    @Test void actualSavedGrassAndLeavesAreTintedWithoutTintingDirtAndReopenIsIdentical()throws Exception {
        var f=fixture();Path world=Path.of(f.get("smallWorld").getAsString()),client=Path.of(f.get("vanillaClient").getAsString());
        var area=new VoxelAreaLoader().load(new VoxelAreaRequest(world,"minecraft:overworld",ChunkRectangle.target(0,0,0,0)),CancellationSignal.NONE);
        try(var source=InstanceResourceSource.open(world,client,"1.21.1",null,CancellationSignal.NONE,s->{})) {
            long start=System.nanoTime();var prepared=BlockTextureSet.load(source,area,CancellationSignal.NONE);var again=BlockTextureSet.load(source,area,CancellationSignal.NONE);
            assertArrayEquals(prepared.atlas().pixels(),again.atlas().pixels());
            var colors=new BlockTintResolver(source);var resolver=new BlockModelResolver(source);
            for(int[] xyz:List.of(new int[]{9,62,0},new int[]{11,64,1})) {
                var block=area.result(new ChunkCoordinate(0,0)).chunk().blockState(xyz[0],xyz[1],xyz[2]);
                var key=prepared.renderState(block,xyz[0],xyz[1],xyz[2]);assertEquals(block.name(),key.name());assertTrue(key.properties().containsKey("__mcwe_tint"));
                assertEquals(key,again.renderState(block,xyz[0],xyz[1],xyz[2]));
                assertTrue(prepared.notes(key).stream().noneMatch(n->n.contains("颜色未解析")),prepared.notes(key).toString());
                var context=new ModelBlockContext(area,new ModelBlockContext.Position(xyz[0],xyz[1],xyz[2]),CancellationSignal.NONE);
                var tint=colors.colors(block,resolver.resolve(block).model().orElseThrow(),context).values().get(0);
                if(block.name().equals("minecraft:grass_block"))assertEquals(0x179C05A,tint);
                String texture=block.name().equals("minecraft:grass_block")?"grass_block_top":"oak_leaves";
                var raw=TextureAtlas.decode(source.read(ResourceLocation.Kind.TEXTURE,ResourceLocation.parse("minecraft:block/"+texture)).orElseThrow(),false);
                assertTile(prepared,key,BlockModelResolver.Direction.UP,BlockTextureSet.composite(raw,tint,null,-1));
                if(block.name().equals("minecraft:grass_block")) {
                    assertTile(prepared,key,BlockModelResolver.Direction.DOWN,TextureAtlas.decode(source.read(ResourceLocation.Kind.TEXTURE,ResourceLocation.parse("minecraft:block/dirt")).orElseThrow(),false));
                    var side=TextureAtlas.decode(source.read(ResourceLocation.Kind.TEXTURE,ResourceLocation.parse("minecraft:block/grass_block_side")).orElseThrow(),false);
                    var overlay=TextureAtlas.decode(source.read(ResourceLocation.Kind.TEXTURE,ResourceLocation.parse("minecraft:block/grass_block_side_overlay")).orElseThrow(),false);
                    assertTile(prepared,key,BlockModelResolver.Direction.NORTH,BlockTextureSet.composite(side,-1,overlay,tint));
                }
            }
            System.out.println("two small preparations ms="+(System.nanoTime()-start)/1_000_000+" atlas="+prepared.atlas().width()+"x"+prepared.atlas().height());source.validateAll();
        }
    }
    static void assertTile(BlockTextureSet set,BlockState state,BlockModelResolver.Direction direction,java.awt.image.BufferedImage expected) {
        var face=set.surfaces(state).stream().filter(s->s.direction()==direction).findFirst().orElseThrow();var uv=face.uv();
        int x=Math.round(Collections.min(List.of(uv.get(0),uv.get(2),uv.get(4),uv.get(6)))*set.atlas().width());
        int y=Math.round(Collections.min(List.of(uv.get(1),uv.get(3),uv.get(5),uv.get(7)))*set.atlas().height());var pixels=set.atlas().pixels();
        for(int dy=0;dy<expected.getHeight();dy++)for(int dx=0;dx<expected.getWidth();dx++)assertEquals(expected.getRGB(dx,dy),pixels[(y+dy)*set.atlas().width()+x+dx],"ARGB "+dx+","+dy);
    }
}
