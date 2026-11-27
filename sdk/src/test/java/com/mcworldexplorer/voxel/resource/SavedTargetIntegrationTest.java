package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.io.*;
import com.mcworldexplorer.voxel.mesh.*;
import com.mcworldexplorer.viewer.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Fixed real saved positions, closed-resource lookup and retained raw block identity. */
class SavedTargetIntegrationTest {
    @TempDir Path temporary;
    @Test void actualSavedGrassKeepsRawIdentityColorStatusAndCacheAfterResourcesClose() throws Exception {
        var f=StandardBlockTintTest.fixture();Path world=Path.of(f.get("smallWorld").getAsString());
        var area=new VoxelAreaLoader().load(new VoxelAreaRequest(world,"minecraft:overworld",ChunkRectangle.target(0,0,0,0)),CancellationSignal.NONE);
        VoxelSceneSnapshot scene;
        try(var source=InstanceResourceSource.open(world,Path.of(f.get("vanillaClient").getAsString()),"1.21.1",null,CancellationSignal.NONE,m->{})) {
            scene=prepared(area,source,ModelRuleRegistry.defaults());source.validateAll();
        }
        var index=new VoxelTargetIndex(scene);var query=index.query(9.5,63.01,.5,0,-1,0);var hit=query.hit().orElseThrow();
        assertEquals("minecraft:grass_block",hit.entry().original().name());assertEquals(62,hit.y());assertEquals(9,hit.x());assertEquals(0,hit.z());
        assertFalse(hit.entry().original().properties().keySet().stream().anyMatch(k->k.startsWith("__mcwe")));
        assertFalse(hit.entry().reason().contains("颜色未解析"));assertTrue(hit.entry().reason().isEmpty() || hit.entry().reason().equals("随机候选模型固定选择第一项"),hit.entry().reason());assertFalse(hit.entry().reason().contains("指纹"));assertEquals("方块归属：Minecraft（原版）",index.lines(query).get(1));
        var key=new ViewerAreaKey(world,"minecraft:overworld",area.target(),VoxelPreviewCoordinator.RENDER_VERSION);var signature=new RegionSourceSignature(List.of());
        var cache=new ViewerSceneCache(ViewerPerformanceProfile.BASIC);assertTrue(cache.put(key,scene,signature));
        assertEquals(hit,new VoxelTargetIndex(cache.get(key,signature).orElseThrow()).query(9.5,63.01,.5,0,-1,0).hit().orElseThrow());cache.clear();
        save("saved-grass",Map.of("position",List.of(hit.x(),hit.y(),hit.z()),"rawState",hit.entry().original(),"hud",index.lines(query),"indexBytes",scene.chunks().stream().mapToLong(c->c.targets().estimatedBytes()).sum(),"lookupAfterResourcesClosed",true));
    }

    private static VoxelSceneSnapshot prepared(VoxelAreaLoadResult area,InstanceResourceSource source,ModelRuleRegistry rules)throws Exception {
        var textures=BlockTextureSet.load(source,area,CancellationSignal.NONE,rules);var s=new VoxelSceneBuilder(textures).build(area,CancellationSignal.NONE).snapshot().orElseThrow();
        return new VoxelSceneSnapshot(s.anchorWorldX(),s.anchorWorldZ(),s.chunks(),s.bounds(),s.blockCount(),s.faceCount(),s.vertexCount(),s.indexCount(),s.warnings(),s.atlas(),source.confirmedModIds());
    }
    private static void save(String name,Object value)throws Exception {String output=System.getProperty("mcwe.r3.output");if(output!=null){Path dir=Files.createDirectories(Path.of(output));Files.writeString(dir.resolve(name+".json"),new GsonBuilder().setPrettyPrinting().create().toJson(value));}}
}
