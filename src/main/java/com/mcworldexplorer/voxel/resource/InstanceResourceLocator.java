package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Discovers only the current world's instance. Never searches sibling modpacks. */
public final class InstanceResourceLocator {
    public static final int MAX_MODS=1024;
    public record Result(Optional<Path> instance,List<Path> sources,List<String> warnings) {
        public Result {sources=List.copyOf(sources);warnings=List.copyOf(warnings);}
    }
    public Result locate(Path world,Path explicit,CancellationSignal cancel)throws IOException {
        check(cancel);Path realWorld=world.toRealPath();Path instance=null;
        if(explicit!=null) {
            instance=explicit.toRealPath();if(!Files.isDirectory(instance))throw new IOException("资源实例必须为游戏目录");
        }else {
            Path parent=realWorld.getParent();
            for(int i=0;parent!=null&&i<8;i++,parent=parent.getParent()) {
                if(parent.getFileName()!=null&&parent.getFileName().toString().equalsIgnoreCase("saves")){instance=parent.getParent();break;}
            }
        }
        List<Path> sources=new ArrayList<>();List<String> warnings=new ArrayList<>();
        Path worldPack=realWorld.resolve("resources.zip");if(Files.isRegularFile(worldPack))sources.add(worldPack);
        if(instance==null)return new Result(Optional.empty(),sources,List.of("未定位存档所属游戏实例，可手动选择实例目录；当前仅使用存档资源与原版"));
        Path packs=instance.resolve("resourcepacks");Path options=instance.resolve("options.txt");
        if(Files.isRegularFile(options)) {
            if(Files.size(options)>1024*1024)warnings.add("options.txt 超过 1 MiB，未读取启用资源包列表");
            else {
                try {
                List<Path> enabledSources=new ArrayList<>();
                byte[] optionBytes;
                try(var input=Files.newInputStream(options)){optionBytes=input.readNBytes(1024*1024+1);}
                if(optionBytes.length>1024*1024)throw new IOException("options.txt 在读取期间增长并超过预算");
                String optionText=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(optionBytes)).toString();
                List<String> lines=optionText.lines().toList();
                String value=lines.stream().filter(line->line.startsWith("resourcePacks:")).map(line->line.substring("resourcePacks:".length())).findFirst().orElse("[]");
                try(var reader=new JsonReader(new StringReader(value))) {
                    reader.setStrictness(Strictness.STRICT);reader.setNestingLimit(16);
                    var enabled=JsonParser.parseReader(reader).getAsJsonArray();
                    if(enabled.size()>64)throw new JsonParseException("启用包超过 64 项");
                    if(reader.peek()!=com.google.gson.stream.JsonToken.END_DOCUMENT)throw new JsonParseException("资源包列表尾随内容");
                    for(int i=enabled.size()-1;i>=0;i--) {
                        check(cancel);var valueId=enabled.get(i);if(!valueId.isJsonPrimitive()||!valueId.getAsJsonPrimitive().isString()){warnings.add("启用资源包列表含无效 ID，已跳过");continue;}String id=valueId.getAsString();if(id.length()>512){warnings.add("启用资源包 ID 超过 512 字符，已跳过");continue;}
                        if(id.equals("vanilla")||id.equals("mod_resources"))continue;
                        if(!id.startsWith("file/")){warnings.add("未解析的实例资源包 ID："+id);continue;}
                        String relative=id.substring(5);Path path=packs.resolve(relative).normalize();
                        if(relative.isBlank()||Path.of(relative).isAbsolute()||!path.startsWith(packs)||relative.contains("\\")) {
                            warnings.add("已拒绝越界资源包路径："+id);continue;
                        }
                        if(!Files.exists(path)){warnings.add("已启用的资源包不存在："+id);continue;}
                        if(!path.toRealPath().startsWith(packs.toRealPath())){warnings.add("资源包指向实例目录之外："+id);continue;}
                        enabledSources.add(path.toRealPath());
                    }
                    sources.addAll(enabledSources);
                }catch(RuntimeException invalid){if(invalid instanceof java.util.concurrent.CancellationException)throw invalid;warnings.add("启用资源包列表无效，未自动启用目录中的其他资源包");}
                }catch(IOException invalid){warnings.add("启用资源包配置不可读，未自动启用其他资源包");}
            }
        }
        Path mods=instance.resolve("mods");
        if(Files.isDirectory(mods))try(var files=Files.list(mods)) {
            var jars=files.filter(path->Files.isRegularFile(path)&&path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .sorted(Comparator.comparing(path->path.getFileName().toString())).limit(MAX_MODS+1L).toList();
            if(jars.size()>MAX_MODS)warnings.add("Mod 归档超过 1024 项，仅索引前 1024 项");
            // Mod precedence is deterministic; the game may order conflicts differently.
            if(jars.size()>1)warnings.add("Mod 重名资源按文件名稳定排序，可能与游戏加载器的覆盖顺序不同");
            for(int i=Math.min(jars.size(),MAX_MODS)-1;i>=0;i--){check(cancel);sources.add(jars.get(i).toRealPath());}
        }
        return new Result(Optional.of(instance.toRealPath()),new ArrayList<>(new LinkedHashSet<>(sources)),warnings);
    }
    private static void check(CancellationSignal cancel){if(cancel.isCancelled()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("取消实例资源查找");}
}
