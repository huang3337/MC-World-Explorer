package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Audit one representative actual state per declared blockstate, not a support whitelist. */
class ResourceCoverageIntegrationTest {
    @Test void auditsClientDeclaredBlocksAndReportsUnsupportedExpressionFamilies() throws Exception {
        String configured=System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");assumeTrue(configured!=null);
        List<BlockState> states=new ArrayList<>();
        try(var zip=new ZipFile(configured)) {
            for(var entry:zip.stream().filter(e->e.getName().startsWith("assets/minecraft/blockstates/")&&e.getName().endsWith(".json")).sorted(Comparator.comparing(java.util.zip.ZipEntry::getName)).toList()) {
                String name=entry.getName().substring("assets/minecraft/blockstates/".length(),entry.getName().length()-5);
                JsonObject json;
                try(var input=zip.getInputStream(entry)){json=JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();}
                Map<String,String> properties=new TreeMap<>();
                if(json.has("variants")&&!json.getAsJsonObject("variants").isEmpty()) {
                    String selector=json.getAsJsonObject("variants").keySet().iterator().next();
                    if(!selector.isEmpty())for(String term:selector.split(",")){String[] pair=term.split("=",2);properties.put(pair[0],pair[1].split("\\|")[0]);}
                } else if(json.has("multipart")) collect(json.get("multipart"),properties);
                states.add(new BlockState("minecraft:"+name,properties));
            }
        }
        assertTrue(states.size()>500);
        Map<String,Integer> reasons=new TreeMap<>();Map<String,String> failures=new TreeMap<>();int resolved=0;
        try(var archive=ClientResourceArchive.open(Path.of(configured),"1.21.1")) {
            var resolver=new BlockModelResolver(archive);
            for(var state:states){var result=resolver.resolve(state);if(result.resolved())resolved++;else{reasons.merge(result.reason()+": "+result.detail(),1,Integer::sum);failures.put(state.name(),result.detail());}}
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("scope","One representative state per blockstate; not exhaustive state or visual acceptance");report.put("declaredBlocks",states.size());report.put("resolvedModels",resolved);report.put("reasonCounts",reasons);report.put("failures",failures);
        try(var archive=ClientResourceArchive.open(Path.of(configured),"1.21.1")) {
            var textures=BlockTextureSet.load(archive,states,CancellationSignal.NONE);
            int prepared=0;Map<String,String> preparationFailures=new TreeMap<>();
            for(var state:states)if(textures.surfaces(state)!=null)prepared++;else preparationFailures.put(state.name(),textures.fallbackReason(state));
            report.put("preparedModels",prepared);report.put("preparationFailures",preparationFailures);
        }
        System.out.println(new Gson().toJson(report));
        String output=System.getenv("MCWE_UI_SNAPSHOT_DIR");
        if(output!=null){Path directory=Path.of(output);Files.createDirectories(directory);Files.writeString(directory.resolve("resource-coverage.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));}
        assertTrue(resolved>states.size()/2,"Standard model parsing should cover the majority of declared blocks");
    }
    @Test void auditsEveryDeclaredVariantAndMultipartPropertyCombinationInBatches() throws Exception {
        String configured=System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");assumeTrue(configured!=null);
        Set<BlockState> states=new LinkedHashSet<>();int variantBranches=0,multipartBlocks=0;
        try(var zip=new ZipFile(configured)) {
            for(var entry:zip.stream().filter(e->e.getName().startsWith("assets/minecraft/blockstates/")&&e.getName().endsWith(".json")).sorted(Comparator.comparing(java.util.zip.ZipEntry::getName)).toList()) {
                String name="minecraft:"+entry.getName().substring("assets/minecraft/blockstates/".length(),entry.getName().length()-5);
                JsonObject root;try(var input=zip.getInputStream(entry)){root=JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();}
                if(root.has("variants"))for(String selector:root.getAsJsonObject("variants").keySet()) {
                    variantBranches++;Map<String,Set<String>> domains=new TreeMap<>();
                    if(!selector.isEmpty())for(String term:selector.split(",")){String[] pair=term.split("=",2);domains.put(pair[0],new LinkedHashSet<>(List.of(pair[1].split("\\|"))));}
                    combinations(name,new ArrayList<>(domains.entrySet()),0,new TreeMap<>(),states);
                } else {
                    multipartBlocks++;Map<String,Set<String>> domains=new TreeMap<>();
                    for(var part:root.getAsJsonArray("multipart"))if(part.getAsJsonObject().has("when"))domains(part.getAsJsonObject().get("when"),domains);
                    for(var values:domains.values()) {
                        if(values.contains("true")||values.contains("false")){values.add("true");values.add("false");}
                        if(values.contains("low")||values.contains("tall")||values.contains("side")||values.contains("up"))values.add("none");
                    }
                    long count=1;for(var values:domains.values())count*=values.size();assertTrue(count<=2048,"multipart test domain exceeds fixture bound: "+name);
                    combinations(name,new ArrayList<>(domains.entrySet()),0,new TreeMap<>(),states);
                }
            }
        }
        List<BlockState> ordered=new ArrayList<>(states);Map<String,String> failures=new TreeMap<>();
        for(int start=0;start<ordered.size();start+=64)try(var archive=ClientResourceArchive.open(Path.of(configured),"1.21.1")) {
            var batch=ordered.subList(start,Math.min(ordered.size(),start+64));var textures=BlockTextureSet.load(archive,batch,CancellationSignal.NONE);
            for(var state:batch)if(textures.surfaces(state)==null)failures.put(state.toString(),textures.fallbackReason(state));
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("scope","Every declared variants key and Cartesian multipart condition domains, including absent connections; not all runtime NBT or visual acceptance");report.put("variantBranches",variantBranches);report.put("multipartBlocks",multipartBlocks);report.put("states",states.size());report.put("prepared",states.size()-failures.size());report.put("failures",failures);
        String output=System.getenv("MCWE_UI_SNAPSHOT_DIR");if(output!=null)Files.writeString(Path.of(output).resolve("state-coverage.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println(new Gson().toJson(report));assertTrue(failures.isEmpty(),failures.toString());
    }
    private static void domains(JsonElement value,Map<String,Set<String>> domains) {
        if(value.isJsonArray())for(var child:value.getAsJsonArray())domains(child,domains);
        else if(value.isJsonObject())for(var entry:value.getAsJsonObject().entrySet()) {
            if(entry.getKey().equals("OR")||entry.getKey().equals("AND"))domains(entry.getValue(),domains);
            else domains.computeIfAbsent(entry.getKey(),ignored->new LinkedHashSet<>()).addAll(List.of(entry.getValue().getAsString().split("\\|")));
        }
    }
    private static void combinations(String name,List<Map.Entry<String,Set<String>>> domains,int index,Map<String,String> properties,Set<BlockState> states) {
        if(index==domains.size()){states.add(new BlockState(name,properties));return;}
        var domain=domains.get(index);for(String value:domain.getValue()){properties.put(domain.getKey(),value);combinations(name,domains,index+1,properties,states);}properties.remove(domain.getKey());
    }
    private static void collect(JsonElement value,Map<String,String> properties) {
        if(value.isJsonArray()){for(var child:value.getAsJsonArray())collect(child,properties);}
        else if(value.isJsonObject())for(var entry:value.getAsJsonObject().entrySet()) {
            if(entry.getKey().equals("when")||entry.getKey().equals("OR")||entry.getKey().equals("AND"))collect(entry.getValue(),properties);
            else if(entry.getValue().isJsonPrimitive()&&!Set.of("model","x","y","uvlock","weight").contains(entry.getKey()))
                properties.putIfAbsent(entry.getKey(),entry.getValue().getAsString().split("\\|")[0]);
        }
    }
}
