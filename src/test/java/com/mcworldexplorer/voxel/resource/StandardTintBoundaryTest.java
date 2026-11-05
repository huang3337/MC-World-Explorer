package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.io.*;
import com.mcworldexplorer.preview.WorldDimension;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.StandardBlockTintTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual client resources/classes with explicitly synthetic saved-biome boundary fixtures. */
class StandardTintBoundaryTest {
    private ClientResourceArchive client()throws Exception{return ClientResourceArchive.open(Path.of(fixture().get("vanillaClient").getAsString()),"1.21.1");}
    private static BlockResourceSource inputs(BlockResourceSource delegate,String mode) {
        return (BlockResourceSource)java.lang.reflect.Proxy.newProxyInstance(BlockResourceSource.class.getClassLoader(),new Class[]{BlockResourceSource.class},(proxy,method,args)->{
            switch(method.getName()) {
                case "worldSeed":return mode.equals("seed-missing")?Optional.empty():Optional.of(0L);
                case "biomeBlendRadius":return Optional.of(0);
                case "dimensionHeight":return Optional.of(new BlockResourceSource.DimensionHeight(-32,64));
                case "readJson":if(args[0]==ResourceLocation.Kind.RAW_DATA){
                    if(mode.equals("biome-missing"))return Optional.empty();
                    if(mode.equals("modifier"))return Optional.of("{\"effects\":{\"grass_color\":16711680,\"grass_color_modifier\":\"unknown\"}}");
                    String color=args[1].toString().contains("west")?"16711680":"255";
                    return Optional.of("{\"effects\":{\"grass_color\":"+color+",\"foliage_color\":"+color+",\"water_color\":"+color+"}}");
                }break;
                case "clientClass":if(mode.equals("changed-proof")&&args[0].equals("fhq")){
                    var receipt=delegate.clientClass("fhq").orElseThrow();var bytes=receipt.bytes();bytes[bytes.length-1]^=1;
                    return Optional.of(new RegistrationClass(receipt.internalName(),bytes,receipt.origin(),receipt.sourceFingerprint()));
                }else if(mode.equals("registry-corrupt")&&args[0].equals("dga")){
                    var receipt=delegate.clientClass("dga").orElseThrow();return Optional.of(new RegistrationClass("dga",new byte[]{0,1,2,3},receipt.origin(),receipt.sourceFingerprint()));
                }break;
            }
            try{return method.invoke(delegate,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        });
    }
    private static VoxelAreaLoadResult area(BlockState state)throws ChunkDecodeException {
        var target=ChunkRectangle.target(-1,0,0,0);var chunks=new HashMap<ChunkCoordinate,ChunkLoadResult>();
        for(var c:target.expanded(1).coordinates()) {
            var biomes=new HashMap<Integer,BiomeSection>();for(int y=-2;y<2;y++)biomes.put(y,biome(c.x()<0?"test:west":"test:east"));
            var chunk=new VoxelChunk(c.x(),c.z(),List.of(new ChunkSection(-1,List.of(state),new long[0])),List.of(),biomes);
            chunks.put(c,ChunkLoadResult.success(c,chunk,0,0));
        }
        var world=Path.of(".").toAbsolutePath();return new VoxelAreaLoadResult(world,WorldDimension.overworld(world),target,chunks);
    }
    @Test void moreThan4096ColoredPositionsShareVariantsAndNegativeCoordinatesDoNotAlias()throws Exception {
        var grass=state("minecraft:grass_block","snowy","false");var area=area(grass);
        try(var client=client()) {
            var source=inputs(client,"normal");var ready=BlockTextureSet.load(source,area,CancellationSignal.NONE);var keys=new HashSet<BlockState>();
            for(int y=-16;y<0;y++)for(int z=0;z<16;z++)for(int x=-16;x<16;x++) {
                var key=ready.renderState(grass,x,y,z);keys.add(key);assertNotNull(ready.surfaces(key));
                assertTrue(ready.notes(key).stream().noneMatch(n->n.contains("颜色未解析")),ready.notes(key).toString());
            }
            assertEquals(2,keys.size(),"8192个目标位置只保留两种颜色模型");
            var west=ready.renderState(grass,-10,-8,8);var east=ready.renderState(grass,10,-8,8);assertNotEquals(west,east);
            assertEquals(grass,ready.renderState(grass,100,-8,8));
            var dirt=state("minecraft:dirt");assertEquals(dirt,ready.renderState(dirt,-10,-8,8),"不能用颜色索引改写原始身份");
            var raw=TextureAtlas.decode(client.read(ResourceLocation.Kind.TEXTURE,ResourceLocation.parse("minecraft:block/grass_block_top")).orElseThrow(),false);
            assertTile(ready,west,BlockModelResolver.Direction.UP,BlockTextureSet.composite(raw,0x1FF0000,null,-1));
            assertTile(ready,east,BlockModelResolver.Direction.UP,BlockTextureSet.composite(raw,0x10000FF,null,-1));
            assertTrue(ready.atlas().height()<128);
        }
    }
    @Test void missingInputsAndUnknownModifiersKeepOriginalGeometryWithSpecificReasons()throws Exception {
        var grass=state("minecraft:grass_block","snowy","false");var area=area(grass);
        try(var client=client()) {
            for(String mode:List.of("seed-missing","biome-missing","modifier","changed-proof","registry-corrupt")) {
                var ready=BlockTextureSet.load(inputs(client,mode),area,CancellationSignal.NONE);var key=ready.renderState(grass,-10,-8,8);
                assertEquals(grass,key);assertNotNull(ready.surfaces(key));
                assertTrue(ready.notes(key).stream().anyMatch(n->n.contains("颜色未解析")),mode);
                String detail=switch(mode){case "seed-missing"->"种子";case "biome-missing"->"群系定义";case "modifier"->"未知草色";case "registry-corrupt"->"颜色注册结构不可解析";default->"指纹不匹配";};
                assertTrue(ready.notes(key).stream().anyMatch(n->n.contains(detail)),ready.notes(key).toString());
            }
        }
    }
    @Test void snowySidesAndNonzeroIndexHandlersKeepUntintedParts()throws Exception {
        var snowy=state("minecraft:grass_block","snowy","true");var area=area(snowy);
        try(var client=client()) {
            var source=inputs(client,"normal");var ready=BlockTextureSet.load(source,area,CancellationSignal.NONE);var key=ready.renderState(snowy,-10,-8,8);
            var model=new BlockModelResolver(source).resolve(snowy).model().orElseThrow();
            var side=model.faces().stream().filter(f->f.direction()==BlockModelResolver.Direction.NORTH).findFirst().orElseThrow();assertEquals(-1,side.tintIndex());
            assertTile(ready,key,BlockModelResolver.Direction.NORTH,TextureAtlas.decode(source.read(ResourceLocation.Kind.TEXTURE,side.texture()).orElseThrow(),false));
            var petals=state("minecraft:pink_petals","flower_amount","1","facing","north");var result=new BlockModelResolver(source).resolve(petals);assertTrue(result.resolved(),result.detail());
            var context=new ModelBlockContext(area,new ModelBlockContext.Position(-10,-8,8),CancellationSignal.NONE);
            var colors=new BlockTintResolver(source).colors(petals,result.model().orElseThrow(),context);
            assertEquals(0x1FF0000,colors.values().get(1));
            // A resource pack is allowed to use index 0 on this handler; it explicitly means no colour.
            var leafModel=new BlockModelResolver(source).resolve(state("minecraft:oak_leaves","distance","1","persistent","false","waterlogged","false")).model().orElseThrow();
            assertEquals(0x1FFFFFF,new BlockTintResolver(source).colors(petals,leafModel,null).values().get(0));
        }
    }
    @Test void cancellationAndClosedSourcesRemainFailures()throws Exception {
        var grass=state("minecraft:grass_block","snowy","false");var client=client();
        try{assertThrows(java.util.concurrent.CancellationException.class,()->BlockTextureSet.load(inputs(client,"normal"),area(grass),()->true));}
        finally{client.close();}
        assertThrows(java.io.IOException.class,()->BlockTextureSet.load(client,List.of(grass),CancellationSignal.NONE));
    }
    private static BiomeSection biome(String id){return BiomeSection.read(net.kyori.adventure.nbt.CompoundBinaryTag.builder().put("palette",net.kyori.adventure.nbt.ListBinaryTag.from(List.of(net.kyori.adventure.nbt.StringBinaryTag.stringBinaryTag(id)))).build());}
}
