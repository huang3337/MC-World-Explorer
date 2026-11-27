package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.mesh.RenderLayer;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StandardModelIntegrationTest {
    private static Path jar(){String value=System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");assumeTrue(value!=null);return Path.of(value);}
    private static BlockState state(String name,String... properties){Map<String,String> p=new TreeMap<>();for(int i=0;i<properties.length;i+=2)p.put(properties[i],properties[i+1]);return new BlockState("minecraft:"+name,p);}
    @Test void bakesAllSlabStairAndTorchGeometryBranchesWithoutNameRegistration() throws Exception {
        List<BlockState> states=new ArrayList<>();
        for(String name:List.of("stone_slab","oak_slab","brick_slab","cut_copper_slab"))for(String type:List.of("top","bottom","double"))states.add(state(name,"type",type,"waterlogged","false"));
        for(String facing:List.of("north","south","east","west"))for(String half:List.of("top","bottom"))for(String shape:List.of("straight","inner_left","inner_right","outer_left","outer_right"))states.add(state("oak_stairs","facing",facing,"half",half,"shape",shape,"waterlogged","false"));
        states.add(state("torch"));for(String facing:List.of("north","south","east","west"))states.add(state("wall_torch","facing",facing));
        try(var archive=ClientResourceArchive.open(jar(),"1.21.1")) {
            var textures=BlockTextureSet.load(archive,states,CancellationSignal.NONE);
            for(var state:states)assertNotNull(textures.surfaces(state),state+": "+textures.fallbackReason(state));
            var bottom=textures.surfaces(state("stone_slab","type","bottom","waterlogged","false"));
            assertEquals(.5,bottom.stream().flatMap(f->java.util.stream.IntStream.range(0,4).mapToObj(i->f.positions().get(i*3+1))).mapToDouble(Float::doubleValue).max().orElseThrow(),1e-6);
            var top=textures.surfaces(state("stone_slab","type","top","waterlogged","false"));
            assertEquals(.5,top.stream().flatMap(f->java.util.stream.IntStream.range(0,4).mapToObj(i->f.positions().get(i*3+1))).mapToDouble(Float::doubleValue).min().orElseThrow(),1e-6);
            assertTrue(textures.surfaces(state("torch")).stream().allMatch(f->!f.shade()));
            assertTrue(textures.surfaces(state("wall_torch","facing","east")).stream().anyMatch(f->f.normal().stream().filter(n->Math.abs(n)>1e-5).count()>1),"墙上火把需要真实元素旋转法线");
        }
    }
    @Test void combinesMultipartConnectionsAndKeepsCutoutPlanesAndThinDoors() throws Exception {
        var disconnected=state("oak_fence","north","false","south","false","east","false","west","false","waterlogged","false");
        var connected=state("oak_fence","north","true","south","true","east","true","west","true","waterlogged","false");
        var flower=state("poppy");var door=state("oak_door","facing","north","half","lower","hinge","left","open","false","powered","false");
        try(var archive=ClientResourceArchive.open(jar(),"1.21.1")) {
            var textures=BlockTextureSet.load(archive,List.of(disconnected,connected,flower,door),CancellationSignal.NONE);
            for(var state:List.of(disconnected,connected,flower,door))assertNotNull(textures.surfaces(state),state+": "+textures.fallbackReason(state));
            assertTrue(textures.surfaces(connected).size()>textures.surfaces(disconnected).size());
            assertTrue(textures.surfaces(flower).stream().allMatch(f->f.layer()==RenderLayer.CUTOUT));
            var faces=textures.surfaces(door);
            double minX=faces.stream().flatMap(f->java.util.stream.IntStream.range(0,4).mapToObj(i->f.positions().get(i*3))).mapToDouble(Float::doubleValue).min().orElseThrow();
            double maxX=faces.stream().flatMap(f->java.util.stream.IntStream.range(0,4).mapToObj(i->f.positions().get(i*3))).mapToDouble(Float::doubleValue).max().orElseThrow();
            double minZ=faces.stream().flatMap(f->java.util.stream.IntStream.range(0,4).mapToObj(i->f.positions().get(i*3+2))).mapToDouble(Float::doubleValue).min().orElseThrow();
            double maxZ=faces.stream().flatMap(f->java.util.stream.IntStream.range(0,4).mapToObj(i->f.positions().get(i*3+2))).mapToDouble(Float::doubleValue).max().orElseThrow();
            assertTrue(Math.min(maxX-minX,maxZ-minZ)<.2,"门必须为薄模型，透明窗口不应作为完整遮挡体");
        }
    }
    @Test void waterloggedModelKeepsItsSolidGeometryAndAddsIndependentlyClassifiedWater() throws Exception {
        var dry=state("stone_slab","type","bottom","waterlogged","false");
        var wet=state("stone_slab","type","bottom","waterlogged","true");
        try(var archive=ClientResourceArchive.open(jar(),"1.21.1")) {
            var textures=BlockTextureSet.load(archive,List.of(dry,wet),CancellationSignal.NONE);
            assertNotNull(textures.surfaces(wet),textures.fallbackReason(wet));
            assertEquals(textures.surfaces(dry).size()+6,textures.surfaces(wet).size());
            assertEquals(6,textures.surfaces(wet).stream().filter(f->f.layer()==RenderLayer.FLUID).count());
            assertEquals(ModelOcclusion.boxes(textures.surfaces(dry)),ModelOcclusion.boxes(textures.surfaces(wet)),"含水不能改变固体遮挡范围");
            var area=GenericModelScene.area(List.of(wet),List.of());
            var scene=new com.mcworldexplorer.voxel.mesh.VoxelSceneBuilder(textures).build(area,CancellationSignal.NONE).snapshot().orElseThrow();
            assertEquals(1,scene.blockCount());
            assertTrue(scene.chunks().getFirst().batches().stream().anyMatch(batch->batch.layer()==RenderLayer.FLUID&&batch.faceCount()>0));
        }
    }
    @Test void opaqueCoverageIsExactForFullAndPartialNeighborRectangles() throws Exception {
        BlockState stone=state("stone"),bottom=state("stone_slab","type","bottom","waterlogged","false"),top=state("stone_slab","type","top","waterlogged","false");
        try(var archive=ClientResourceArchive.open(jar(),"1.21.1")) {
            var textures=BlockTextureSet.load(archive,List.of(stone,bottom,top),CancellationSignal.NONE);
            var fullFace=textures.surfaces(stone).stream().filter(f->f.direction()==BlockModelResolver.Direction.EAST).findFirst().orElseThrow();
            assertFalse(ModelOcclusion.covers(fullFace,ModelOcclusion.boxes(textures.surfaces(bottom)),1,0,0));
            List<ModelOcclusion.Box> union=new ArrayList<>(ModelOcclusion.boxes(textures.surfaces(bottom)));union.addAll(ModelOcclusion.boxes(textures.surfaces(top)));
            assertTrue(ModelOcclusion.covers(fullFace,union,1,0,0),"上下两块覆盖矩形的并集应遮挡完整面");
            var partial=textures.surfaces(bottom).stream().filter(f->f.direction()==BlockModelResolver.Direction.EAST).findFirst().orElseThrow();
            assertTrue(ModelOcclusion.covers(partial,ModelOcclusion.boxes(textures.surfaces(stone)),1,0,0));
        }
    }
}
