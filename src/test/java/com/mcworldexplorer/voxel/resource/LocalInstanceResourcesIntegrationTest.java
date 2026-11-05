package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Source selection/index acceptance, deliberately separate from world geometry and visual acceptance. */
class LocalInstanceResourcesIntegrationTest {
    @Test void indexesEveryBaselineModInstanceWithItsOwnClientAndEffectiveLayers()throws Exception {
        String configured=System.getenv("MCWORLD_BASELINE_JSON");
        assumeTrue(configured!=null&&"1".equals(System.getenv("MCWORLD_INSTANCE_RESOURCE_AUDIT")));
        var baseline=JsonParser.parseString(Files.readString(Path.of(configured))).getAsJsonObject();
        List<Map<String,Object>> results=new ArrayList<>();List<String> failures=new ArrayList<>();
        long start=System.nanoTime();int expectedInstances=0;
        for(var value:baseline.getAsJsonArray("instances")) {
            var item=value.getAsJsonObject();if(item.getAsJsonArray("mods").isEmpty())continue;
            expectedInstances++;
            Path instance=Path.of(item.get("path").getAsString());var candidates=item.getAsJsonArray("clientCandidates");
            if(candidates.size()!=1){failures.add(instance+": client candidates="+candidates.size());continue;}
            var client=candidates.get(0).getAsJsonObject();Path jar=Path.of(client.getAsJsonObject("file").get("path").getAsString());String version=client.getAsJsonObject("version").get("id").getAsString();
            try(var source=InstanceResourceSource.open(instance,jar,version,instance,CancellationSignal.NONE,s->{})) {
                var located=new InstanceResourceLocator().locate(instance,instance,CancellationSignal.NONE);
                var ids=source.instanceBlockstates();int read=0;
                for(var id:ids.stream().limit(16).toList()){assertTrue(source.readJson(ResourceLocation.Kind.BLOCKSTATE,id).isPresent());read++;}
                source.validateAll();results.add(Map.of("instance",instance.toString(),"clientVersion",version,"sources",source.sourceCount(),"nestedLayers",located.layers().stream().filter(l->!l.nested().isEmpty()).count(),"effectiveDeclaredBlocks",ids.size(),"sampleReads",read,"warnings",source.warnings(),"sha256",source.fingerprint().sha256()));
                System.out.printf("Instance source audit: %s, %d declarations, %d layers%n",instance.getFileName(),ids.size(),source.sourceCount());
            }catch(Exception error){failures.add(instance+": "+error);}
        }
        String output=System.getenv("MCWE_UI_SNAPSHOT_DIR");if(output!=null){Path directory=Path.of(output);Files.createDirectories(directory);Files.writeString(directory.resolve("instance-resource-index.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("scope","All baseline Mod instance resource selection/index and first 16 declaration reads; not complete model/world/visual acceptance","instances",results,"failures",failures,"milliseconds",(System.nanoTime()-start)/1e6)));}
        assertTrue(expectedInstances>0);assertEquals(expectedInstances,results.size()+failures.size(),"必须覆盖基线每个Mod实例");assertTrue(failures.isEmpty(),failures.toString());
    }
}
