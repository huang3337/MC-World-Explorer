package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.mesh.*;
import net.kyori.adventure.nbt.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.SpecialModelScene.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SpecialModelIntegrationTest {
    private static ClientResourceArchive archive() throws Exception {
        String path=System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");assumeTrue(path!=null);
        return ClientResourceArchive.open(Path.of(path),"1.21.1");
    }
    @Test void distinguishesSignContentsAndResolvesBannerPotAndHeadResources() throws Exception {
        try(var archive=archive()) {
            var area=area();var textures=BlockTextureSet.load(archive,area,CancellationSignal.NONE);
            var chunk=area.result(new ChunkCoordinate(0,0)).chunk();
            for(int slot=0;slot<16;slot++) {
                int x=1+slot%4*3,z=1+slot/4*3;
                var state=BlockTextureSet.visualState(chunk.blockState(x,1,z),chunk.blockEntity(x,1,z).orElse(null));
                assertNotNull(textures.surfaces(state),state+": "+textures.fallbackReason(state));
            }
            var a=BlockTextureSet.visualState(chunk.blockState(7,1,4),chunk.blockEntity(7,1,4).orElseThrow());
            var b=BlockTextureSet.visualState(chunk.blockState(10,1,4),chunk.blockEntity(10,1,4).orElseThrow());
            assertNotEquals(a,b);assertNotEquals(textures.surfaces(a),textures.surfaces(b),"不同文字不能共享同一图集区域");
            assertTrue(textures.surfaces(a).size()>12,"文字需作为牌面附加面");
            var scene=new VoxelSceneBuilder(textures).build(area,CancellationSignal.NONE).snapshot().orElseThrow();
            assertTrue(scene.warnings().stream().anyMatch(w->w.code()==MeshWarning.Code.MODEL_APPROXIMATION));
            assertTrue(scene.warnings().stream().noneMatch(w->w.code()==MeshWarning.Code.TEXTURE_FALLBACK),scene.warnings().toString());
        }
    }
    @Test void preparesOrientationColorAndStructuralBranchesInBoundedBatches() throws Exception {
        List<BlockState> states=new ArrayList<>();
        for(String facing:List.of("north","east","south","west")) {
            for(String type:List.of("single","left","right"))states.add(state("chest","facing",facing,"type",type,"waterlogged","false"));
            for(String part:List.of("head","foot"))states.add(state("blue_bed","facing",facing,"part",part,"occupied","false"));
            states.add(state("oak_wall_sign","facing",facing,"waterlogged","false"));
            states.add(state("oak_wall_hanging_sign","facing",facing,"waterlogged","false"));
            states.add(state("red_wall_banner","facing",facing));
            states.add(state("skeleton_wall_skull","facing",facing,"powered","false"));
        }
        for(String facing:List.of("up","down","north","east","south","west"))states.add(state("purple_shulker_box","facing",facing));
        for(int rotation=0;rotation<16;rotation++) {
            states.add(state("oak_sign","rotation",""+rotation,"waterlogged","false"));
            states.add(state("white_banner","rotation",""+rotation));
            states.add(state("player_head","rotation",""+rotation,"powered","false"));
            for(String attached:List.of("true","false"))states.add(state("oak_hanging_sign","rotation",""+rotation,"attached",attached,"waterlogged","false"));
        }
        try(var archive=archive()) {
            for(int start=0;start<states.size();start+=24) {
                var batch=states.subList(start,Math.min(states.size(),start+24));
                var textures=BlockTextureSet.load(archive,batch,CancellationSignal.NONE);
                for(var state:batch)assertNotNull(textures.surfaces(state),state+": "+textures.fallbackReason(state));
            }
        }
    }
    @Test void wallBannerExtendsBelowItsAnchorAndDoubleChestLocksMeetAtTheirSeam() throws Exception {
        var banner=state("red_wall_banner","facing","north");
        try(var archive=archive()) {
            var result=new BlockModelResolver(archive).resolve(banner);assertTrue(result.resolved());
            var faces=result.model().orElseThrow().faces();assertTrue(faces.stream().anyMatch(f->f.from().y()<0));
            assertTrue(faces.stream().allMatch(f->f.to().y()<16));
            for(String side:List.of("left","right")) {
                var chest=new BlockModelResolver(archive).resolve(state("chest","facing","north","type",side,"waterlogged","false")).model().orElseThrow();
                var lock=chest.faces().stream().filter(f->f.layer()==2).findFirst().orElseThrow();
                assertEquals(side.equals("left")?0:15,lock.from().x());assertEquals(1,lock.to().x()-lock.from().x());
            }
        }
    }
    @Test void movingPistonPreservesAllMultipartPartsAndSnapshotDisplacement() throws Exception {
        var carried=CompoundBinaryTag.builder().putString("Name","minecraft:oak_fence").put("Properties",CompoundBinaryTag.builder()
            .putString("north","true").putString("south","true").putString("east","true").putString("west","true").putString("waterlogged","false").build()).build();
        var entity=entity(0,CompoundBinaryTag.builder().putString("id","minecraft:piston").put("blockState",carried).putFloat("progress",.25f).putBoolean("extending",true).putInt("facing",5).build());
        var state=BlockTextureSet.visualState(state("moving_piston","facing","east","type","normal"),entity);
        try(var archive=archive()) {
            var source=new BuiltinBlockModels(archive,Map.of(entity.fingerprint(),entity));
            var result=new BlockModelResolver(source).resolve(state);assertTrue(result.resolved(),result.detail());
            assertTrue(result.model().orElseThrow().parts().size()>1);
            assertTrue(result.model().orElseThrow().parts().stream().allMatch(p->Math.abs(p.offset().x()+.75)<1e-6));
            var textures=BlockTextureSet.load(source,List.of(state),CancellationSignal.NONE);
            assertNotNull(textures.surfaces(state),textures.fallbackReason(state));
            assertTrue(textures.surfaces(state).stream().flatMap(f->java.util.stream.IntStream.range(0,4).mapToObj(i->f.positions().get(i*3))).anyMatch(x->x<0));
        }
    }
}
