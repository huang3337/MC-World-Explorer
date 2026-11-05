package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Assets from the selected launch profile's declared local dependencies, never class loading or downloads. */
final class LauncherResourceLibraries {
    private static final int MAX_PROFILES=16,MAX_LIBRARIES=2048;
    record Selection(List<InstanceResourceLocator.Layer> resources,List<Path> inputs,Optional<String> gameVersion,String problem) {
        Selection {resources=List.copyOf(resources);inputs=List.copyOf(inputs);Objects.requireNonNull(gameVersion);Objects.requireNonNull(problem);}
    }
    static List<InstanceResourceLocator.Layer> locate(Path instance,CancellationSignal cancel,List<String> warnings)throws IOException {
        return select(instance,cancel,warnings).resources();
    }
    static Selection select(Path instance,CancellationSignal cancel,List<String> warnings)throws IOException {
        Path parent=instance.getParent();boolean versionFolder=parent!=null&&parent.getFileName()!=null&&parent.getFileName().toString().equalsIgnoreCase("versions");
        Path game=versionFolder?parent.getParent():instance;
        if(game==null)return new Selection(List.of(),List.of(),Optional.empty(),"");Path root=game.resolve("libraries");
        Path profile=instance.resolve(instance.getFileName()+".json");if(!Files.isRegularFile(profile))return new Selection(List.of(),List.of(profile),Optional.empty(),"");
        var inputs=new ArrayList<Path>();var problems=new ArrayList<String>();
        List<JsonObject> profiles=new ArrayList<>();Set<Path> seen=new HashSet<>();
        for(int depth=0;profile!=null;depth++) {
            check(cancel);if(depth>=MAX_PROFILES)throw new IOException("启动配置继承超过16层");
            Path real=profile.toRealPath();if(!real.startsWith(versionFolder?parent.toRealPath():instance.toRealPath()))throw new IOException("启动配置继承越界");
            if(!seen.add(real))throw new IOException("启动配置继承循环");
            JsonObject json=parse(real);inputs.add(real);profiles.add(json);profile=null;
            if(json.has("inheritsFrom")) {
                String id=json.get("inheritsFrom").getAsString();if(id.isBlank()||id.length()>256||id.contains("/")||id.contains("\\")||id.contains(":")||id.equals(".")||id.equals(".."))throw new IOException("继承配置ID无效");
                Path next=(versionFolder?parent:instance.resolve("versions")).resolve(id).resolve(id+".json");
                if(Files.isRegularFile(next))profile=next;else {inputs.add(next);String message="缺少继承启动配置："+id;note(warnings,message);problems.add(message);}
            }
        }
        if(Files.isDirectory(root))root=root.toRealPath();
        LinkedHashMap<String,String> coordinates=new LinkedHashMap<>();Map<String,String> flags=new HashMap<>();
        for(var json:profiles) {
            Set<String> localFlags=new HashSet<>();
            if(json.has("libraries")) {
                var libraries=json.getAsJsonArray("libraries");if(libraries.size()>MAX_LIBRARIES)throw new IOException("启动依赖声明超过2048项");
                for(var value:libraries) {
                    check(cancel);var library=value.getAsJsonObject();String name=library.get("name").getAsString();String[] parts=name.split(":",-1);
                    if(parts.length<3||parts.length>4)throw new IOException("Maven依赖坐标无效");
                    for(String part:parts)if(part.length()>256||!part.matches("[A-Za-z0-9_.+-]+")||part.equals(".")||part.equals(".."))throw new IOException("Maven依赖坐标越界");
                    String key=parts[0]+":"+parts[1]+(parts.length==4?":"+parts[3]:"");if(coordinates.containsKey(key))continue;
                    if(library.has("rules")&&!unconditional(library.getAsJsonArray("rules")))continue;
                    String path=parts[0].replace('.','/')+"/"+parts[1]+"/"+parts[2]+"/"+parts[1]+"-"+parts[2]+(parts.length==4?"-"+parts[3]:"")+".jar";
                    if(library.has("downloads")) {
                        var downloads=library.getAsJsonObject("downloads");if(downloads.has("artifact")){var artifact=downloads.getAsJsonObject("artifact");if(artifact.has("path"))path=artifact.get("path").getAsString();}
                    }
                    NestedZipAccess.safe(path);coordinates.put(key,path);if(coordinates.size()>MAX_LIBRARIES)throw new IOException("合并启动依赖超过2048项");
                }
            }
            if(json.has("arguments")) {
                var args=json.getAsJsonObject("arguments");if(args.has("game")){var values=args.getAsJsonArray("game");if(values.size()>4096)throw new IOException("启动参数声明过多");for(int i=0;i+1<values.size();i++) {
                    var key=values.get(i);var value=values.get(i+1);if(key.isJsonPrimitive()&&key.getAsJsonPrimitive().isString()&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()) {
                        String name=key.getAsString();if(Set.of("--fml.neoForgeVersion","--fml.forgeVersion","--fml.mcVersion").contains(name)){if(!localFlags.add(name))problems.add("同一启动配置重复声明加载器参数："+name);flags.putIfAbsent(name,value.getAsString());}
                    }
                }}
            }
        }
        String neo=flags.get("--fml.neoForgeVersion"),forge=flags.get("--fml.forgeVersion"),mc=flags.get("--fml.mcVersion");
        if(neo!=null){version(neo);coordinates.putIfAbsent("net.neoforged:neoforge:universal","net/neoforged/neoforge/"+neo+"/neoforge-"+neo+"-universal.jar");}
        if(forge!=null&&mc!=null){version(forge);version(mc);String version=mc+"-"+forge;coordinates.putIfAbsent("net.minecraftforge:forge:universal","net/minecraftforge/forge/"+version+"/forge-"+version+"-universal.jar");}
        List<InstanceResourceLocator.Layer> layers=new ArrayList<>();
        for(var candidate:coordinates.entrySet()) {
            check(cancel);String relative=candidate.getValue();NestedZipAccess.safe(relative);if(!relative.endsWith(".jar"))continue;Path file=root.resolve(relative);
            if(!Files.isRegularFile(file)) {if(candidate.getKey().endsWith(":universal"))note(warnings,"启动配置声明的加载器资源不存在："+relative);continue;}
            Path real=file.toRealPath();if(!real.startsWith(root))throw new IOException("启动依赖资源指向libraries之外");
            if(Files.size(real)>InstanceResourceSource.MAX_CONTAINER){note(warnings,"启动依赖资源超过容器预算："+relative);continue;}
            try(var zip=new ZipFile(real.toFile())) {
                if(zip.size()>100000)throw new IOException("启动依赖归档条目超限");
                boolean assets=zip.stream().anyMatch(e->!e.isDirectory()&&(e.getName().startsWith("assets/")||e.getName().startsWith("data/")));
                if(assets)layers.add(new InstanceResourceLocator.Layer(real,"","library:"+candidate.getKey()));
            }catch(IOException invalid){note(warnings,"启动依赖资源不可读："+relative+"（"+invalid.getMessage()+"）");}
        }
        return new Selection(new ArrayList<>(new LinkedHashSet<>(layers)),new ArrayList<>(new LinkedHashSet<>(inputs)),Optional.ofNullable(mc),String.join("；",problems));
    }

    private static boolean unconditional(JsonArray rules) {
        // Conditional native/feature entries are not assumed active. They normally contain no block resources.
        boolean allowed=false;for(var rule:rules){var object=rule.getAsJsonObject();if(object.size()!=1||!object.has("action"))return false;allowed=object.get("action").getAsString().equals("allow");}return allowed;
    }
    private static void version(String value)throws IOException {if(value.length()>128||!value.matches("[A-Za-z0-9_.+-]+")||value.equals(".")||value.equals(".."))throw new IOException("加载器版本声明无效");}
    private static JsonObject parse(Path path)throws IOException {
        if(Files.size(path)>2*1024*1024)throw new IOException("启动配置超过2MiB");
        try(var input=Files.newInputStream(path)){byte[] bytes=input.readNBytes(2*1024*1024+1);if(bytes.length>2*1024*1024)throw new IOException("启动配置增长超限");
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            try(var reader=new com.google.gson.stream.JsonReader(new StringReader(text))){reader.setStrictness(Strictness.STRICT);reader.setNestingLimit(32);var json=JsonParser.parseReader(reader).getAsJsonObject();if(reader.peek()!=com.google.gson.stream.JsonToken.END_DOCUMENT)throw new IOException("启动配置有尾随内容");return json;}
        }catch(RuntimeException invalid){throw new IOException("启动配置元信息无效",invalid);}
    }
    private static void note(List<String> warnings,String message){if(warnings.size()<128)warnings.add(message);}
    private static void check(CancellationSignal cancel){if(cancel.isCancelled()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("取消启动依赖资源定位");}
}
