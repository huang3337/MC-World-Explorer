package com.mcworldexplorer.voxel.resource;

import net.kyori.adventure.nbt.*;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Saved enabled data-pack selection; commands/functions and runtime pack generators are never executed. */
final class WorldDataPacks {
    record Seed(Optional<Long> value,String problem) { }
    record DimensionType(String reference,BlockResourceSource.DimensionHeight inline,String problem) { }
    record Dimensions(Map<ResourceLocation,DimensionType> types,String problem){Dimensions{types=Map.copyOf(types);}}
    record Selection(List<String> enabled,List<String> disabled,List<InstanceResourceLocator.Layer> layers,Seed seed,Dimensions dimensions){Selection{enabled=List.copyOf(enabled);disabled=List.copyOf(disabled);layers=List.copyOf(layers);}}
    static Selection locate(Path world,Optional<Path> instance,List<Path> jars,CancellationSignal cancel)throws IOException {
        Path file=world.resolve("level.dat");if(!Files.isRegularFile(file))return new Selection(List.of(),List.of(),List.of(),new Seed(Optional.empty(),""),new Dimensions(Map.of(),""));
        CompoundBinaryTag root;try(var input=Files.newInputStream(file)){root=BinaryTagIO.reader(16*1024*1024).read(input,BinaryTagIO.Compression.GZIP);}
        var packs=root.getCompound("Data").getCompound("DataPacks");List<String> enabled=strings(packs,"Enabled"),disabled=strings(packs,"Disabled");
        List<InstanceResourceLocator.Layer> layers=new ArrayList<>();Path folder=world.resolve("datapacks");
        try(var builtinLookup=instance.isPresent()?new BuiltinResourcePacks.Lookup(instance.orElseThrow(),jars,cancel):null){
        for(int i=enabled.size()-1;i>=0;i--){
            if(cancel.isCancelled()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("取消数据包读取");String id=enabled.get(i);
            if(id.startsWith("file/")){
                String name=id.substring(5);if(name.isBlank()||name.contains("\\")||name.contains(":")||name.startsWith("/")||Arrays.stream(name.split("/",-1)).anyMatch(v->v.isEmpty()||v.equals(".")||v.equals("..")))throw new IOException("数据包路径无效："+id);
                Path path=folder.resolve(name).normalize();if(!path.startsWith(folder.normalize())||!Files.exists(path)||!path.toRealPath().startsWith(folder.toRealPath())||!folder.toRealPath().startsWith(world.toRealPath()))throw new IOException("启用的数据包缺失或越界："+id);
                validateMetadata(path);layers.add(new InstanceResourceLocator.Layer(path.toRealPath(),"",id));
            }else if(instance.isPresent()&&(id.startsWith("builtin/")||id.startsWith("mod/"))){
                var warnings=new ArrayList<String>();var embedded=builtinLookup.locate(id,warnings);embedded.ifPresent(layers::add);
            }
        }
        }
        return new Selection(enabled,disabled,layers,seed(root.getCompound("Data")),dimensions(root.getCompound("Data")));
    }
    private static Dimensions dimensions(CompoundBinaryTag data){
        var settings=data.get("WorldGenSettings");
        if(settings==null)return new Dimensions(Map.of(),"");
        if(!(settings instanceof CompoundBinaryTag compound))return new Dimensions(Map.of(),"WorldGenSettings不是复合标签");
        var raw=compound.get("dimensions");if(raw==null)return new Dimensions(Map.of(),"");
        if(!(raw instanceof CompoundBinaryTag dimensions)||dimensions.keySet().size()>1024)return new Dimensions(Map.of(),"保存维度列表不是复合标签或超过1024项");
        var result=new TreeMap<ResourceLocation,DimensionType>(Comparator.comparing(ResourceLocation::toString));
        for(String key:dimensions.keySet()){
            ResourceLocation id;try{id=ResourceLocation.parse(key);}catch(IllegalArgumentException invalid){return new Dimensions(Map.of(),"保存维度ID无效");}
            var entry=dimensions.get(key);DimensionType type;
            if(!(entry instanceof CompoundBinaryTag value))type=new DimensionType(null,null,"保存维度不是复合标签");
            else if(value.get("type") instanceof StringBinaryTag name){
                try{type=new DimensionType(ResourceLocation.parse(name.value()).toString(),null,"");}catch(IllegalArgumentException invalid){type=new DimensionType(null,null,"保存维度类型ID无效");}
            }else if(value.get("type") instanceof CompoundBinaryTag inline){
                try{if(!(inline.get("min_y") instanceof IntBinaryTag min)||!(inline.get("height") instanceof IntBinaryTag height))throw new IllegalArgumentException("内联维度高度须为int标签");type=new DimensionType(null,new BlockResourceSource.DimensionHeight(min.value(),height.value()),"");}
                catch(IllegalArgumentException invalid){type=new DimensionType(null,null,invalid.getMessage());}
            }else type=new DimensionType(null,null,"保存维度缺少可识别的type");
            if(result.putIfAbsent(id,type)!=null)return new Dimensions(Map.of(),"保存维度ID规范化后重复");
        }
        return new Dimensions(result,"");
    }
    private static Seed seed(CompoundBinaryTag data){
        var legacy=data.get("RandomSeed");var settings=data.get("WorldGenSettings");
        if(settings!=null&&!(settings instanceof CompoundBinaryTag))return new Seed(Optional.empty(),"WorldGenSettings不是复合标签");
        var modern=settings instanceof CompoundBinaryTag compound?compound.get("seed"):null;
        if(legacy!=null&&!(legacy instanceof LongBinaryTag)||modern!=null&&!(modern instanceof LongBinaryTag))return new Seed(Optional.empty(),"保存世界种子不是long标签");
        if(legacy instanceof LongBinaryTag old&&modern instanceof LongBinaryTag current&&old.value()!=current.value())return new Seed(Optional.empty(),"保存世界种子字段冲突");
        return new Seed(modern instanceof LongBinaryTag value?Optional.of(value.value()):legacy instanceof LongBinaryTag value?Optional.of(value.value()):Optional.empty(),"");
    }
    private static void validateMetadata(Path pack)throws IOException {
        String text;
        if(Files.isDirectory(pack,LinkOption.NOFOLLOW_LINKS)){
            Path meta=pack.resolve("pack.mcmeta");if(!Files.isRegularFile(meta,LinkOption.NOFOLLOW_LINKS)||Files.size(meta)>1024*1024)throw new IOException("数据包缺少有界普通pack.mcmeta");text=Files.readString(meta);
        }else try(var zip=new java.util.zip.ZipFile(pack.toFile())){
            var entry=zip.getEntry("pack.mcmeta");if(entry==null||entry.getSize()<0||entry.getSize()>1024*1024)throw new IOException("数据包缺少有界pack.mcmeta");try(var input=zip.getInputStream(entry)){byte[] bytes=input.readNBytes(1024*1024+1);if(bytes.length>1024*1024)throw new IOException("数据包元信息超限");text=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);}
        }
        try{var json=com.google.gson.JsonParser.parseString(text).getAsJsonObject();if(!json.has("pack"))throw new IOException("数据包缺少pack声明");if(json.has("overlays")||json.has("filter"))throw new IOException("数据包使用尚未支持的overlays/filter语义，不能静默忽略");}
        catch(com.google.gson.JsonParseException|IllegalArgumentException|IllegalStateException invalid){throw new IOException("数据包元信息无效",invalid);}
    }
    private static List<String> strings(CompoundBinaryTag packs,String key)throws IOException {
        var raw=packs.get(key);if(raw==null)return List.of();if(!(raw instanceof ListBinaryTag list)||list.size()>2048)throw new IOException("数据包列表类型无效或超过2048项");
        List<String> result=new ArrayList<>();for(var value:list){if(!(value instanceof StringBinaryTag text)||text.value().length()>512||text.value().chars().anyMatch(c->c<32))throw new IOException("数据包ID无效");result.add(text.value());}return List.copyOf(result);
    }
}
