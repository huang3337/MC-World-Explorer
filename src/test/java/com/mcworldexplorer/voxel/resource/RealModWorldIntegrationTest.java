package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.mcworldexplorer.nbt.LevelDatReader;
import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.io.*;
import com.mcworldexplorer.voxel.mesh.VoxelSceneBuilder;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Explicit local-world read-only acceptance; declaration coverage is tested separately. */
class RealModWorldIntegrationTest {
    @Test void comparesActualWorldOccurrencesBeforeAndAfterInstanceResources() throws Exception {
        String configured=System.getenv("MCWORLD_MOD_TEST_WORLD"),client=System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");
        assumeTrue(configured!=null&&client!=null);
        Path world=Path.of(configured);var info=LevelDatReader.readLevelDat(world);assertTrue(info.isParsed());
        int cx=Math.floorDiv((int)Math.floor(info.isPlayerPositionAvailable()?info.getPlayerX():info.getSpawnX()),16);
        int cz=Math.floorDiv((int)Math.floor(info.isPlayerPositionAvailable()?info.getPlayerZ():info.getSpawnZ()),16);
        String dimension=info.isPlayerPositionAvailable()&&info.getPlayerDimension()!=null?info.getPlayerDimension():"minecraft:overworld";
        var target=ChunkRectangle.target(cx-1,cz-1,cx+1,cz+1);long start=System.nanoTime();
        var area=new VoxelAreaLoader().load(new VoxelAreaRequest(world,dimension,target),CancellationSignal.NONE);
        Map<BlockState,Integer> occurrences=new LinkedHashMap<>();
        for(var coordinate:target.coordinates()) {
            var chunk=area.result(coordinate).chunk();if(chunk==null)continue;
            for(var section:chunk.sections())for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                int wy=section.sectionY()*16+y;
                var state=BlockTextureSet.visualState(section.blockState(x,y,z),chunk.blockEntity((long)coordinate.x()*16+x,wy,(long)coordinate.z()*16+z).orElse(null));
                if(!state.name().equals("minecraft:air")&&!state.name().equals("minecraft:cave_air")&&!state.name().equals("minecraft:void_air"))occurrences.merge(state,1,Integer::sum);
            }
        }
        assertFalse(occurrences.isEmpty());Map<String,Object> report=new LinkedHashMap<>();
        report.put("scope","Actual target non-air occurrences, 3x3 chunks at saved player/spawn; static resource and scene acceptance, not all world blocks");
        report.put("world",world.toString());report.put("dimension",dimension);report.put("target",target.toString());
        report.put("chunksLoaded",area.results().values().stream().filter(r->r.chunk()!=null).count());report.put("targetStates",occurrences.size());
        try(var vanilla=ClientResourceArchive.open(Path.of(client),info.getVersionName());
            var instance=InstanceResourceSource.open(world,Path.of(client),info.getVersionName(),null,CancellationSignal.NONE,message->{})) {
            var before=BlockTextureSet.load(vanilla,area,CancellationSignal.NONE);
            long resources=System.nanoTime();var after=BlockTextureSet.load(instance,area,CancellationSignal.NONE);
            report.put("resourcePrepareMs",(System.nanoTime()-resources)/1e6);report.put("instance",instance.description());report.put("warnings",instance.warnings());
            report.put("clientOnly",summarize(before,occurrences));report.put("withInstance",summarize(after,occurrences));
            assertTrue(occurrences.keySet().stream().anyMatch(state->!state.name().startsWith("minecraft:")&&after.surfaces(state)!=null&&!after.surfaces(state).isEmpty()),"真实选区必须含有成功准备的 Mod 方块");
            long mesh=System.nanoTime();var scene=new VoxelSceneBuilder(after).build(area,CancellationSignal.NONE).snapshot().orElseThrow();
            report.put("meshMs",(System.nanoTime()-mesh)/1e6);report.put("sceneFaces",scene.faceCount());report.put("sceneBlocks",scene.blockCount());
            instance.validateAll();assertTrue(scene.faceCount()>0);
        }
        report.put("totalMs",(System.nanoTime()-start)/1e6);
        String output=System.getenv("MCWE_UI_SNAPSHOT_DIR");if(output!=null){Files.createDirectories(Path.of(output));Files.writeString(Path.of(output).resolve("real-world-coverage.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));}
        System.out.println(new Gson().toJson(report));
    }
    private static Map<String,Object> summarize(BlockTextureSet textures,Map<BlockState,Integer> occurrences) {
        long visible=0,empty=0,failed=0;Map<String,Long> failures=new TreeMap<>(),namespaces=new TreeMap<>();
        for(var item:occurrences.entrySet()) {
            var surfaces=textures.surfaces(item.getKey());int count=item.getValue();
            if(surfaces==null){failed+=count;failures.merge(item.getKey().name()+" "+textures.fallbackReason(item.getKey()),(long)count,Long::sum);}
            else if(surfaces.isEmpty())empty+=count;
            else{visible+=count;namespaces.merge(ResourceLocation.parse(item.getKey().name()).namespace(),(long)count,Long::sum);}
        }
        return Map.of("visible",visible,"noStaticFaces",empty,"fallback",failed,"failures",failures,"visibleNamespaces",namespaces);
    }
}
