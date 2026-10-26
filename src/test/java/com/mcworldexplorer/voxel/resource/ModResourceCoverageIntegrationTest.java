package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Resource declaration coverage, separate from actual-world occurrence and visual acceptance. */
class ModResourceCoverageIntegrationTest {
    @TempDir Path world;
    @Test void auditsAllOuterModDeclaredBlocksInBoundedBatchesAndSeparatesEmptyModels()throws Exception {
        String configured=System.getenv("MCWORLD_MOD_INSTANCE_DIR"),client=System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");assumeTrue(configured!=null&&client!=null);
        Path instance=Path.of(configured);Set<ResourceLocation> ids=new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
        try(var jars=Files.list(instance.resolve("mods"))) {
            for(var jar:jars.filter(path->path.getFileName().toString().endsWith(".jar")).toList())try(var zip=new ZipFile(jar.toFile())) {
                for(var entry:zip.stream().map(java.util.zip.ZipEntry::getName).filter(name->name.startsWith("assets/")&&name.contains("/blockstates/")&&name.endsWith(".json")).toList()) {
                    String[] parts=entry.split("/",4);if(parts.length==4&&parts[2].equals("blockstates"))ids.add(new ResourceLocation(parts[1],parts[3].substring(0,parts[3].length()-5)));
                }
            }
        }
        assertTrue(ids.size()>1000);List<ResourceLocation> ordered=new ArrayList<>(ids);
        Map<String,String> failures=new TreeMap<>();Map<String,Integer> reasons=new TreeMap<>();Map<String,Integer> namespacePrepared=new TreeMap<>();
        int prepared=0,empty=0,sessions=1;long start=System.nanoTime();
        InstanceResourceSource source=open(instance,Path.of(client));String identity=source.fingerprint().sha256();
        try {
            for(int offset=0;offset<ordered.size();offset+=32) {
                if(offset%512==0)System.out.printf("Mod coverage progress: %d/%d; %.1f s%n",offset,ordered.size(),(System.nanoTime()-start)/1e9);
                List<ResourceLocation> batch=ordered.subList(offset,Math.min(offset+32,ordered.size()));
                for(int attempt=0;attempt<2;attempt++)try {
                    List<BlockState> states=new ArrayList<>();
                    for(var id:batch) {
                        var text=source.readJson(ResourceLocation.Kind.BLOCKSTATE,id);
                        if(text.isEmpty()){failures.put(id.toString(),"MISSING_RESOURCE: blockstate");reasons.merge("MISSING_RESOURCE",1,Integer::sum);continue;}
                        try{states.add(representative(id,JsonParser.parseString(text.orElseThrow()).getAsJsonObject()));}
                        catch(RuntimeException invalid){failures.put(id.toString(),"INVALID_JSON: "+invalid.getMessage());reasons.merge("INVALID_JSON",1,Integer::sum);}
                    }
                    var textures=BlockTextureSet.load(source,states,CancellationSignal.NONE);
                    for(var state:states) {
                        var faces=textures.surfaces(state);
                        if(faces==null){String detail=textures.fallbackReason(state);failures.put(state.name(),detail);String reason=detail.contains(":")?detail.substring(0,detail.indexOf(':')):detail;reasons.merge(reason,1,Integer::sum);}
                        else if(faces.isEmpty())empty++;
                        else{prepared++;namespacePrepared.merge(ResourceLocation.parse(state.name()).namespace(),1,Integer::sum);}
                    }
                    break;
                }catch(ResourceAccessException exhausted) {
                    if(exhausted.reason()!=ResourceAccessException.Reason.LIMIT_EXCEEDED)throw exhausted;
                    if(attempt==1){for(var id:batch){failures.put(id.toString(),"SESSION_BUDGET: "+exhausted.getMessage());reasons.merge("SESSION_BUDGET",1,Integer::sum);}break;}
                    source.close();source=open(instance,Path.of(client));sessions++;assertEquals(identity,source.fingerprint().sha256(),"资源在覆盖审计期间发生变化");
                }
            }
            source.validateAll();
        }finally{source.close();}
        Map<String,Object> report=new LinkedHashMap<>();report.put("scope","One representative state for each outer Mod blockstate declaration; not all properties/NBT, actual-world frequency or visual acceptance");report.put("declaredBlocks",ids.size());report.put("preparedVisible",prepared);report.put("declaredWithoutStaticFaces",empty);report.put("failures",failures);report.put("reasonCounts",reasons);report.put("namespacePrepared",namespacePrepared);report.put("sessions",sessions);report.put("milliseconds",(System.nanoTime()-start)/1e6);
        String output=System.getenv("MCWE_UI_SNAPSHOT_DIR");if(output!=null){Files.createDirectories(Path.of(output));Files.writeString(Path.of(output).resolve("mod-resource-coverage.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));}
        System.out.printf("Mod declaration coverage: %d visible, %d no static faces, %d failed / %d; %d resource sessions; %.1f s%n",prepared,empty,failures.size(),ids.size(),sessions,(System.nanoTime()-start)/1e9);
        assertTrue(prepared>ids.size()/2,"标准与声明式模型应覆盖大多数本地声明；详细失败必须另行审查");
    }
    private InstanceResourceSource open(Path instance,Path client)throws Exception {return InstanceResourceSource.open(world,client,"1.21.1",instance,CancellationSignal.NONE,message->{});}
    static BlockState representative(ResourceLocation id,JsonObject json) {
        Map<String,String> properties=new TreeMap<>();
        if(json.has("variants")) {
            var variants=json.getAsJsonObject("variants");String selector=variants.keySet().iterator().next();
            if(!selector.isEmpty())for(String term:selector.split(",")){String[] pair=term.split("=",2);properties.put(pair[0],pair[1].split("\\|")[0]);}
        }else if(json.has("multipart"))collect(json.get("multipart"),properties);
        return new BlockState(id.toString(),properties);
    }
    private static void collect(JsonElement value,Map<String,String> properties) {
        if(value.isJsonArray())for(var child:value.getAsJsonArray())collect(child,properties);
        else if(value.isJsonObject())for(var entry:value.getAsJsonObject().entrySet()) {
            if(Set.of("when","AND","OR").contains(entry.getKey()))collect(entry.getValue(),properties);
            else if(entry.getValue().isJsonPrimitive()&&!Set.of("model","x","y","uvlock","weight").contains(entry.getKey()))properties.putIfAbsent(entry.getKey(),entry.getValue().getAsString().split("\\|")[0]);
        }
    }
}
