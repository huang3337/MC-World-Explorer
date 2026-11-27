package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Capture the real resources selected by diagnostic family, without a per-Mod texture/model table. */
class RootTransformResourceIntegrationTest {
    @Test void capturesResolvedRootTransformsFromTwoIndependentResourceProtocols()throws Exception {
        String baseline=System.getenv("MCWORLD_BASELINE_JSON"),reference=System.getenv("MCWORLD_ROOT_TRANSFORM_REFERENCE"),destination=System.getenv("MCWE_UI_SNAPSHOT_DIR");
        assumeTrue(baseline!=null&&reference!=null&&destination!=null);
        var baselineRows=JsonParser.parseString(Files.readString(Path.of(baseline))).getAsJsonObject().getAsJsonArray("instances");Map<String,JsonObject> profiles=new HashMap<>();for(var value:baselineRows){var row=value.getAsJsonObject();profiles.put(row.get("path").getAsString(),row);}
        var index=JsonParser.parseString(Files.readString(Path.of(reference).resolve("index.json"))).getAsJsonObject();Path output=Path.of(destination).resolve("root-transform-resources");Files.createDirectories(output);
        Path world=output.resolve("empty-world");Files.createDirectories(world);
        List<Map<String,String>> reports=new ArrayList<>();Set<String> namespaces=new TreeSet<>();int total=0,branches=0;
        for(var value:index.getAsJsonArray("instances")){
            var row=value.getAsJsonObject();var report=JsonParser.parseString(Files.readString(Path.of(row.get("output").getAsString()).resolve("mod-resource-coverage.json"))).getAsJsonObject();
            if(report.getAsJsonObject("failures").isEmpty())continue;
            var profile=profiles.get(row.get("instance").getAsString());var client=profile.getAsJsonArray("clientCandidates").get(0).getAsJsonObject();Path jar=Path.of(client.getAsJsonObject("file").get("path").getAsString()),instance=Path.of(row.get("instance").getAsString());String version=client.getAsJsonObject("version").get("id").getAsString();
            try(var source=InstanceResourceSource.open(world,jar,version,instance,CancellationSignal.NONE,m->{})){
                String fingerprint=source.fingerprint().sha256();var resolver=new BlockModelResolver(source);List<List<BlockModelResolver.Part>> captured=new ArrayList<>();List<String> targets=new ArrayList<>();int checkedBranches=0;
                for(var failure:report.getAsJsonObject("failures").entrySet()){
                    assertTrue(failure.getValue().getAsString().contains("不支持的模型字段：transform"));var id=ResourceLocation.parse(failure.getKey());
                    var definition=JsonParser.parseString(source.readJson(ResourceLocation.Kind.BLOCKSTATE,id).orElseThrow()).getAsJsonObject();
                    assertTrue(definition.has("variants"),"冻结样本新增multipart时必须扩充条件覆盖");
                    Set<String> firstGroups=new HashSet<>(),transformedGroups=new HashSet<>();
                    for(var variant:definition.getAsJsonObject("variants").entrySet()){
                        assertTrue(variant.getValue().isJsonObject(),"加权分支需要另行完整展开");
                        Map<String,String> properties=new TreeMap<>();
                        if(!variant.getKey().isEmpty())for(String property:variant.getKey().split(",")){String[] pair=property.split("=",-1);assertEquals(2,pair.length);assertFalse(pair[1].contains("|"));properties.put(pair[0],pair[1]);}
                        var state=new BlockState(id.toString(),properties);var result=resolver.resolve(state);assertTrue(result.resolved(),state+": "+result.detail());assertFalse(result.model().orElseThrow().faces().isEmpty());
                        boolean transformed=result.model().orElseThrow().faces().stream().anyMatch(face->!face.vertices().isEmpty());
                        // Show both halves where declared, each at identity and a nonidentity rotation.
                        String group=properties.getOrDefault("half","whole");boolean first=firstGroups.add(group),newTransform=transformed&&transformedGroups.add(group);
                        if(first||newTransform){captured.add(result.model().orElseThrow().parts());targets.add(state.toString());}checkedBranches++;
                    }
                    assertEquals(firstGroups,transformedGroups,id+" must exercise a nonidentity root transform in every half");namespaces.add(id.namespace());total++;
                }
                branches+=checkedBranches;
                source.validateAll();String filename=String.format(Locale.ROOT,"%02d.json",reports.size());
                var assemblies=new ArrayList<Map<String,Object>>();for(int start=0;start<captured.size();start+=16)assemblies.add(Map.of("parts",captured.subList(start,Math.min(start+16,captured.size()))));
                var record=new LinkedHashMap<String,Object>();record.put("scope","Every exact variant branch of the selected declarations; selected identity/nonidentity isolated display witnesses. Not a saved NBT/position or random-branch sample.");record.put("world",world.toString());record.put("instance",instance.toString());record.put("client",jar.toString());record.put("version",version);record.put("sourceFingerprint",fingerprint);record.put("rulesFingerprint",ModelRuleRegistry.defaults().fingerprint());record.put("checkedBranches",checkedBranches);record.put("targets",targets);record.put("assemblies",assemblies);
                Files.writeString(output.resolve(filename),new GsonBuilder().setPrettyPrinting().create().toJson(record));reports.add(Map.of("report",filename));
            }
        }
        assertEquals(15,total,"当前冻结缺项选集必须全部检查");assertEquals(2,namespaces.size(),"必须覆盖两个独立资源作者");
        Files.writeString(output.resolve("index.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("worlds",reports,"models",total,"checkedBranches",branches,"namespaces",namespaces,"failures",List.of())));
    }
}
