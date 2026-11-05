package com.mcworldexplorer.voxel.mesh;

import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.io.*;
import com.mcworldexplorer.voxel.resource.*;
import com.mcworldexplorer.viewer.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VoxelTargetIndexTest {
    private static BlockState state(String id) { return new BlockState(id,Map.of()); }
    private static VoxelSceneSnapshot scene(List<ChunkMeshSnapshot> chunks, Set<String> mods) {
        return new VoxelSceneSnapshot(0,0,chunks,new MeshBounds(-16,-16,-16,32,16,16),0,0,0,0,List.of(),Optional.empty(),mods);
    }
    private static ChunkMeshSnapshot chunk(int x,int z,VoxelTargetIndex.ChunkData data) {
        return new ChunkMeshSnapshot(new ChunkCoordinate(x,z),x*16L,z*16L,List.of(),new MeshBounds(0,0,0,16,16,16),0,0,0,0,List.of(),data);
    }
    private static BlockTextureSet textures() throws Exception {
        var png=new java.io.ByteArrayOutputStream();var image=new java.awt.image.BufferedImage(1,1,2);image.setRGB(0,0,0x80FFFFFF);javax.imageio.ImageIO.write(image,"png",png);
        Map<String,String> models=Map.of("slab",model("0,0,0","16,8,16"),"pane",model("7,0,0","9,16,16"),"glass",model("0,0,0","16,16,16"),"empty","{\"elements\":[]}","missing_geometry","{}");
        var source=new BlockResourceSource() {
            public Optional<byte[]> read(ResourceLocation.Kind k,ResourceLocation id) { return k==ResourceLocation.Kind.TEXTURE?Optional.of(png.toByteArray()):Optional.empty(); }
            public Optional<String> readJson(ResourceLocation.Kind k,ResourceLocation id) {
                if(k==ResourceLocation.Kind.BLOCKSTATE&&models.containsKey(id.path())) return Optional.of("{\"variants\":{\"\":{\"model\":\"sample:block/"+id.path()+"\"}}}");
                return Optional.ofNullable(models.get(id.path().replace("block/","")));
            }
            public ClientResourceArchive.Fingerprint fingerprint(){return new ClientResourceArchive.Fingerprint(Path.of("fixture"),1,1,"selection");}
            public Optional<String> origin(ResourceLocation.Kind k,ResourceLocation id){return Optional.of("Resource Pack, not block owner");}
        };
        return BlockTextureSet.load(source,models.keySet().stream().map(s->state("sample:"+s)).toList(),CancellationSignal.NONE);
    }
    private static String model(String from,String to) {
        var faces=new ArrayList<String>();for(String d:List.of("up","down","north","south","west","east"))faces.add("\""+d+"\":{\"texture\":\"sample:paint\"}");
        return "{\"elements\":[{\"from\":["+from+"],\"to\":["+to+"],\"faces\":{"+String.join(",",faces)+"}}]}";
    }
    @Test void selectsSlabAndThinPaneEnvelopeAndTransparentGlassWithoutAlphaPicking() throws Exception {
        var t=textures();var b=new VoxelTargetIndex.Builder(new VoxelTargetIndex.Budget());
        for(int i=0;i<3;i++){var s=state("sample:"+List.of("slab","pane","glass").get(i));b.add(1+i*3,1,1,s,s,t,t.surfaces(s));}
        var index=new VoxelTargetIndex(scene(List.of(chunk(0,0,b.build())),Set.of("sample")));
        assertEquals("sample:slab",index.query(1.5,1.25,0,0,0,1).hit().orElseThrow().entry().original().name());
        assertTrue(index.query(1.5,1.75,0,0,0,1).hit().isEmpty());
        assertEquals("sample:pane",index.query(4.5,1.5,0,0,0,1).hit().orElseThrow().entry().original().name());
        assertTrue(index.query(4.1,1.5,0,0,0,1).hit().isEmpty());
        var glass=index.query(7.5,1.5,0,0,0,1);assertEquals("sample:glass",glass.hit().orElseThrow().entry().original().name());
        assertEquals("方块归属：sample（Mod ID）", index.lines(glass).get(1));
        assertFalse(String.join(" ",index.lines(glass)).contains("Resource Pack"));
    }
    @Test void distinguishesIntentionalEmptyMissingGeometryAndFallback() throws Exception {
        var t=textures();var b=new VoxelTargetIndex.Builder(new VoxelTargetIndex.Budget());
        int x=1;for(String name:List.of("empty","missing_geometry","unknown")){var s=state("sample:"+name);b.add(x,1,1,s,s,t,t.surfaces(s));x+=3;}
        var index=new VoxelTargetIndex(scene(List.of(chunk(0,0,b.build())),Set.of()));
        assertTrue(index.query(1.5,1.5,0,0,0,1).hit().isEmpty());
        var missing=index.query(4.5,1.5,0,0,0,1).hit().orElseThrow();assertTrue(missing.entry().status().contains("无几何"));assertFalse(missing.entry().reason().isBlank());
        var fallback=index.query(7.5,1.5,0,0,0,1);assertTrue(fallback.hit().orElseThrow().entry().status().contains("降级"));
        assertTrue(index.lines(fallback).get(1).contains("未确认"));
    }
    @Test void crossesNegativeChunkAndYCoordinatesAndHandlesBoundaryInsideAndRange() {
        var left=new VoxelTargetIndex.Builder(new VoxelTargetIndex.Budget());var right=new VoxelTargetIndex.Builder(new VoxelTargetIndex.Budget());
        var raw=state("minecraft:stone");left.add(15,-2,1,raw,raw,null,null);right.add(0,-2,1,raw,raw,null,null);
        var index=new VoxelTargetIndex(scene(List.of(chunk(-1,0,left.build()),chunk(0,0,right.build())),Set.of()));
        var hit=index.query(-2,-1.5,1.5,1,0,0).hit().orElseThrow();assertEquals(-1,hit.x());assertEquals(-2,hit.y());assertEquals(1,hit.distance(),1e-9);
        assertTrue(index.query(-2,-1.5,1.5,1,0,0).sameTarget(index.query(-3,-1.5,1.5,1,0,0)),"同一目标距离改变不重排文字");
        assertFalse(index.query(-2,-1.5,1.5,1,0,0).sameTarget(index.query(0,-1.5,1.5,1,0,0)));
        assertEquals(0,index.query(0,-1.5,1.5,1,0,0).hit().orElseThrow().distance());
        assertEquals(-1,index.query(0,-1.5,1.5,-1,0,0).hit().orElseThrow().x());
        assertTrue(index.query(-200,-1.5,1.5,1,0,0).hit().isEmpty());
        assertTrue(index.query(Double.NaN,0,0,1,0,0).hit().isEmpty());
        assertTrue(index.query(0,0,0,0,0,0).hit().isEmpty());
    }
    @Test void retainsOriginalIdentityAndStopsAtMissingChunksInsteadOfSeeingThrough() {
        var b=new VoxelTargetIndex.Builder(new VoxelTargetIndex.Budget());var raw=new BlockState("unknown:machine",Map.of("facing","south"));
        b.add(1,1,1,raw,new BlockState(raw.name(),Map.of("__mcwe_context","secret","facing","south")),null,null);
        var loadedEmpty=new VoxelTargetIndex.Builder(new VoxelTargetIndex.Budget()).build();
        var index=new VoxelTargetIndex(scene(List.of(chunk(0,0,loadedEmpty),chunk(2,0,b.build())),Set.of()));
        assertEquals("射线进入未加载区块",index.query(1.5,1.5,1.5,1,0,0).notice());
        var hit=index.query(32,1.5,1.5,1,0,0).hit().orElseThrow();assertEquals(raw,hit.entry().original());
        assertFalse(hit.entry().original().properties().containsKey("__mcwe_context"));
    }
    @Test void budgetsStayExplicitAndPaletteIsSharedWithoutNbtOrPerBlockStrings() {
        var b=new VoxelTargetIndex.Builder(new VoxelTargetIndex.Budget());var raw=state("minecraft:stone");
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)b.add(x,y,z,raw,raw,null,null);
        var data=b.build();assertEquals(1,data.entryCount());assertTrue(data.estimatedBytes()<10000);
        assertThrows(IllegalStateException.class,()->b.add(0,0,0,raw,raw,null,null));
        var small=new VoxelTargetIndex.Builder(new VoxelTargetIndex.Budget(0));small.add(0,0,0,raw,raw,null,null);
        var index=new VoxelTargetIndex(scene(List.of(chunk(0,0,small.build())),Set.of()));
        assertTrue(index.query(.5,.5,.5,1,0,0).notice().contains("预算"));
    }
    @Test void sharedPreparedStateKeepsDistinctSavedPropertiesAndStillSharesRepeatedIdentity() {
        var first=new BlockState("sample:machine",Map.of("__mcwe_entity","saved-first"));
        var second=new BlockState("sample:machine",Map.of("__mcwe_entity","saved-second"));
        var visual=net.kyori.adventure.nbt.CompoundBinaryTag.builder().putString("Color","red").build();
        var entity=new BlockEntityData(0,0,0,visual,"shared-visual");
        var prepared=BlockTextureSet.visualState(first,entity);
        assertEquals(prepared,BlockTextureSet.visualState(second,entity));
        var builder=new VoxelTargetIndex.Builder(new VoxelTargetIndex.Budget());
        builder.add(1,1,1,first,prepared,null,null);
        builder.add(4,1,1,second,prepared,null,null);
        builder.add(7,1,1,first,prepared,null,null);
        var data=builder.build();assertEquals(2,data.entryCount());
        var index=new VoxelTargetIndex(scene(List.of(chunk(0,0,data)),Set.of()));
        assertEquals(first,index.query(1.5,1.5,0,0,0,1).hit().orElseThrow().entry().original());
        assertEquals(second,index.query(4.5,1.5,0,0,0,1).hit().orElseThrow().entry().original());
        assertEquals(first,index.query(7.5,1.5,0,0,0,1).hit().orElseThrow().entry().original());
    }
    @Test void actualMeshingRetainsTargetsThroughCacheAndInvalidation() throws Exception {
        var raw=new BlockState("unknown:machine",Map.of("facing","north"));var area=GenericModelScene.area(List.of(raw),List.of());
        var scene=new VoxelSceneBuilder().build(area,CancellationSignal.NONE).snapshot().orElseThrow();
        var key=new ViewerAreaKey(area.world(),area.dimension().id(),area.target(),VoxelPreviewCoordinator.RENDER_VERSION);var signature=new RegionSourceSignature(List.of());
        var cache=new ViewerSceneCache(ViewerPerformanceProfile.BASIC);assertTrue(cache.put(key,scene,signature));
        var saved=cache.get(key,signature).orElseThrow();assertEquals(raw,new VoxelTargetIndex(saved).query(1.5,1.5,0,0,0,1).hit().orElseThrow().entry().original());
        long meshBytes=scene.chunks().stream().flatMap(c->c.batches().stream()).mapToLong(b->b.vertexCount()*6L*4+b.indices().length*4L).sum();
        assertTrue(ViewerSceneCache.estimatedBytes(scene)>meshBytes+scene.chunks().getFirst().targets().estimatedBytes(),
                "未知方块的结构化诊断也必须计入缓存预算，不能只计几何与目标索引");
        assertEquals(ViewerSceneCache.estimatedBytes(scene),cache.currentBytes());
        cache.clear();assertEquals(0,cache.currentBytes());assertTrue(cache.get(key,signature).isEmpty());
        var replacement=new VoxelTargetIndex(scene(List.of(),Set.of()));assertTrue(replacement.query(1.5,1.5,0,0,0,1).hit().isEmpty());
    }

    @Test void hudSeparatesAppearanceApproximationFromMissingColorAndRemovesPickingInternals() {
        var index = new VoxelTargetIndex(scene(List.of(), Set.of()));
        var raw = state("minecraft:sand");
        var box = new MeshBounds(0,0,0,1,1,1);
        var entry = new VoxelTargetIndex.Entry(raw, box, "模型已生成（含诊断）／包围盒定位", "随机候选模型固定选择第一项");
        var query = new VoxelTargetIndex.Query(Optional.of(new VoxelTargetIndex.Hit(32,62,7,entry,2)), "");
        assertEquals(List.of("minecraft:sand", "方块归属：Minecraft（原版）", "坐标：32, 62, 7",
                "显示状态：已解析（外观近似）", "说明：随机外观使用固定样式"), index.lines(query));
        assertEquals("随机候选模型固定选择第一项", entry.reason(), "完整诊断保持原内容");
        var notes = List.of("", "颜色未解析：未绑定处理器", "尚未实现的动态部件", "随机候选模型固定选择第一项；颜色未解析：未绑定处理器");
        var statuses = List.of("已解析", "部分解析", "模型已显示（见说明）", "部分解析");
        for (int i=0;i<notes.size();i++) {
            var detail = new VoxelTargetIndex.Entry(raw,box,i==0?"已解析／模型包围盒定位":entry.status(),notes.get(i));
            var lines = index.lines(new VoxelTargetIndex.Query(Optional.of(new VoxelTargetIndex.Hit(0,0,0,detail,1)), ""));
            assertEquals("显示状态："+statuses.get(i), lines.get(3));
            assertFalse(String.join(" ",lines).contains("定位"));
            assertEquals(i==0?4:5, lines.size());
            if(i>0)assertTrue(lines.get(4).contains(i==3?"颜色未解析":notes.get(i)));
        }
    }

    @Test void hudLabelsOwnershipWithoutClaimingModelTextureOrPluginProvenance() {
        var index = new VoxelTargetIndex(scene(List.of(), Set.of("twilightforest")));
        for (String id : List.of("twilightforest:giant_leaves", "unknown:machine")) {
            var entry = new VoxelTargetIndex.Entry(state(id),new MeshBounds(0,0,0,1,1,1),"解析降级／方块格定位","尚未支持该模型");
            var lines = index.lines(new VoxelTargetIndex.Query(Optional.of(new VoxelTargetIndex.Hit(1,2,3,entry,2)),""));
            assertEquals(id,lines.getFirst());
            assertEquals(id.startsWith("twilightforest")?"方块归属：twilightforest（Mod ID）":"方块归属：未确认（命名空间 unknown）",lines.get(1));
            assertEquals("显示状态：简化显示",lines.get(3));
            assertEquals("说明：尚未支持该模型",lines.get(4));
            assertFalse(String.join(" ",lines).contains("资产来源"));
        }
    }
}
