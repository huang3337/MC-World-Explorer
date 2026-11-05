package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Explicit Fabric jars / JarJar declarations only. Conflicting resource versions never get unioned. */
final class NestedResourcePacks {
    private record Stamp(Path path,long size,FileTime time,Object key) { }
    private record Archive(Path path,List<String> chain){Archive{chain=List.copyOf(chain);}}
    private record Node(Path outer,List<String> chain,String identity,String version) {Node{chain=List.copyOf(chain);}String label(){return outer+"!/"+String.join("!/",chain);}}
    private record Found(Node node,Set<String> owners,boolean resources,String resourceHash) { }
    private record Catalog(List<Stamp> stamps,List<InstanceResourceLocator.Layer> layers,List<String> warnings) { }
    private static final Map<Path,Catalog> CACHE=new LinkedHashMap<>(8,.75f,true);
    static List<InstanceResourceLocator.Layer> locate(Path instance,List<Path> jars,CancellationSignal cancel,List<String> warnings)throws IOException {
        List<Stamp> stamps=new ArrayList<>();for(var path:jars){check(cancel);var a=Files.readAttributes(path,BasicFileAttributes.class);stamps.add(new Stamp(path,a.size(),a.lastModifiedTime(),a.fileKey()));}
        synchronized(CACHE){var cached=CACHE.get(instance);if(cached!=null&&cached.stamps().equals(stamps)){warnings.addAll(cached.warnings());return cached.layers();}}
        List<String> notes=new ArrayList<>();List<Node> queue=new ArrayList<>();Set<String> outerIds=new HashSet<>();
        for(var path:jars) {
            check(cancel);if(Files.size(path)>InstanceResourceSource.MAX_CONTAINER)continue;
            try(var zip=new ZipFile(path.toFile())) {
                if(zip.size()>100000)throw new IOException("Mod归档条目超过100000");outerIds.addAll(BuiltinResourcePacks.owners(zip));
                add(queue,path,List.of(),BuiltinResourcePacks.text(zip,"fabric.mod.json"),BuiltinResourcePacks.text(zip,"META-INF/jarjar/metadata.json"));
            }catch(IOException|RuntimeException invalid){if(invalid instanceof java.util.concurrent.CancellationException)throw invalid;note(notes,"嵌套声明不可读："+path.getFileName()+"（"+invalid.getMessage()+"）");}
        }
        List<Found> found=new ArrayList<>();Set<Archive> seen=new HashSet<>();Set<Archive> fabricArchives=new HashSet<>();
        try(var access=new NestedZipAccess(cancel)) {
            for(int i=0;i<queue.size();i++) {
                check(cancel);if(queue.size()>2048)throw new IOException("实例嵌套依赖超过2048项");var node=queue.get(i);var archive=new Archive(node.outer(),node.chain());if(!seen.add(archive))continue;if(node.identity().equals("fabric"))fabricArchives.add(archive);
                try {
                    Path root=access.root(node.outer(),node.chain());String fabric=text(root,"fabric.mod.json"),neo=text(root,"META-INF/neoforge.mods.toml"),forge=text(root,"META-INF/mods.toml");
                    var owners=BuiltinResourcePacks.owners(fabric,neo,forge);
                    if(node.identity().equals("fabric")&&!Collections.disjoint(owners,outerIds))continue;
                    if(node.chain().size()<4)add(queue,node.outer(),node.chain(),fabric,text(root,"META-INF/jarjar/metadata.json"));
                    else if(fabric!=null&&fabric.contains("\"jars\"")||Files.exists(root.resolve("META-INF/jarjar/metadata.json")))note(notes,"嵌套依赖达到4层边界："+node.label());
                    String identity=node.identity();if(identity.equals("fabric")){var json=parse(fabric);if(json==null||!json.has("id"))throw new IOException("Fabric嵌套归档未声明Mod身份");identity="fabric:"+json.get("id").getAsString();}
                    if(!Collections.disjoint(owners,outerIds))continue; // Explicit outer mods are mandatory; never merge an inactive nested copy into them.
                    String hash=resources(root,cancel);boolean ownResources=!hash.equals("empty");
                    if(node.identity().equals("fabric"))hash=fabricClosure(root,fabric,hash,cancel);
                    found.add(new Found(new Node(node.outer(),node.chain(),identity,node.version()),owners,ownResources,hash));
                }catch(IOException|RuntimeException invalid){if(invalid instanceof java.util.concurrent.CancellationException)throw invalid;note(notes,"嵌套资源不可用："+node.label()+"（"+invalid.getMessage()+"）");}
            }
        }
        Map<String,List<Found>> groups=new TreeMap<>();for(var item:found)groups.computeIfAbsent(item.node().identity(),ignored->new ArrayList<>()).add(item);
        List<Found> selected=new ArrayList<>();
        for(var entry:groups.entrySet()) {
            var candidates=entry.getValue();var hashes=candidates.stream().map(Found::resourceHash).distinct().toList();
            if(hashes.size()>1){note(notes,"嵌套依赖资源版本不同，未混用，需解析版本协商："+entry.getKey()+" "+candidates.stream().map(item->item.node().version()).distinct().toList());continue;}
            selected.add(candidates.getFirst());
        }
        // Different artifact coordinates can still declare the same Mod ID. Only equivalent assets are interchangeable.
        Set<Found> conflicts=new HashSet<>();for(int i=0;i<selected.size();i++)for(int j=i+1;j<selected.size();j++) {
            var a=selected.get(i);var b=selected.get(j);if(!Collections.disjoint(a.owners(),b.owners())&&!a.resourceHash().equals(b.resourceHash())){conflicts.add(a);conflicts.add(b);note(notes,"嵌套Mod身份对应不同资源，未混用："+a.owners().stream().sorted().toList()+" / "+b.owners().stream().sorted().toList());}
        }
        selected.removeAll(conflicts);
        for(int depth=0;depth<4;depth++) {
            var active=new HashSet<Archive>();for(var item:selected)active.add(new Archive(item.node().outer(),item.node().chain()));
            selected.removeIf(item->{var node=item.node();for(int n=1;n<node.chain().size();n++){var parent=new Archive(node.outer(),node.chain().subList(0,n));if(fabricArchives.contains(parent)&&!active.contains(parent))return true;}return false;});
        }
        List<InstanceResourceLocator.Layer> layers=selected.stream().filter(Found::resources).sorted(Comparator.comparing(item->item.node().identity())).map(item->new InstanceResourceLocator.Layer(item.node().outer(),"","nested:"+item.node().identity(),item.node().chain())).toList();
        for(var stamp:stamps){var now=Files.readAttributes(stamp.path(),BasicFileAttributes.class);if(stamp.size()!=now.size()||!stamp.time().equals(now.lastModifiedTime())||stamp.key()!=null&&!stamp.key().equals(now.fileKey()))throw new ResourceAccessException(ResourceAccessException.Reason.SOURCE_CHANGED,"Mod在嵌套索引期间变化");}
        var catalog=new Catalog(List.copyOf(stamps),layers,List.copyOf(notes));synchronized(CACHE){if(CACHE.size()>=8)CACHE.remove(CACHE.keySet().iterator().next());CACHE.put(instance,catalog);}warnings.addAll(notes);return layers;
    }
    private static void add(List<Node> result,Path outer,List<String> parent,String fabric,String jarjar)throws IOException {
        var metadata=parse(fabric);
        if(metadata!=null&&metadata.has("jars")){var entries=metadata.getAsJsonArray("jars");if(entries.size()>256)throw new IOException("单Mod嵌套声明超过256项");for(var item:entries){String file=item.getAsJsonObject().get("file").getAsString();result.add(node(outer,parent,file,"fabric",""));}}
        metadata=parse(jarjar);
        if(metadata!=null&&metadata.has("jars")){var entries=metadata.getAsJsonArray("jars");if(entries.size()>256)throw new IOException("单Mod嵌套声明超过256项");for(var item:entries){var json=item.getAsJsonObject();var id=json.getAsJsonObject("identifier");var version=json.getAsJsonObject("version");String identity="jarjar:"+id.get("group").getAsString()+":"+id.get("artifact").getAsString();if(identity.length()>512)throw new IOException("嵌套依赖身份过长");result.add(node(outer,parent,json.get("path").getAsString(),identity,version.get("artifactVersion").getAsString()));}}
    }
    private static Node node(Path outer,List<String> parent,String file,String identity,String version)throws IOException {NestedZipAccess.safe(file);if(!file.toLowerCase(Locale.ROOT).endsWith(".jar")||version.length()>128)throw new IOException("嵌套依赖声明无效");var chain=new ArrayList<>(parent);chain.add(file);return new Node(outer.toRealPath(),chain,identity,version);}
    private static JsonObject parse(String value)throws IOException {if(value==null)return null;try(var reader=new com.google.gson.stream.JsonReader(new StringReader(value))){reader.setStrictness(Strictness.LENIENT);reader.setNestingLimit(32);return JsonParser.parseReader(reader).getAsJsonObject();}catch(RuntimeException invalid){throw new IOException("Mod元信息JSON无效",invalid);}}
    private static String text(Path root,String name)throws IOException {Path path=root.resolve(name);if(!Files.isRegularFile(path))return null;if(Files.size(path)>1024*1024)throw new IOException("嵌套元信息超过1MiB");try(var input=Files.newInputStream(path)){byte[] bytes=input.readNBytes(1024*1024+1);if(bytes.length>1024*1024)throw new IOException("嵌套元信息解压超限");return new String(bytes,StandardCharsets.UTF_8);}}
    private static String resources(Path root,CancellationSignal cancel)throws IOException {
        try {
            var digest=MessageDigest.getInstance("SHA-256");List<Path> entries;try(var walk=Files.walk(root,48)){entries=walk.limit(100001).toList();}if(entries.size()>100000)throw new IOException("嵌套文件索引超限");var files=entries.stream().filter(Files::isRegularFile).toList();
            List<Path> resources=files.stream().filter(path->{String name=root.relativize(path).toString();return name.startsWith("assets/")||name.startsWith("data/")||name.contains("/assets/")||name.endsWith("pack.mcmeta");}).sorted(Comparator.comparing(Path::toString)).toList();if(resources.isEmpty())return "empty";
            long bytes=0;byte[] buffer=new byte[65536];for(var file:resources){check(cancel);byte[] name=root.relativize(file).toString().getBytes(StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(name.length).array());digest.update(name);long size=Files.size(file);if(size<0||size>8*1024*1024||bytes+size>64L*1024*1024)throw new IOException("嵌套资源身份计算超过预算");bytes+=size;digest.update(java.nio.ByteBuffer.allocate(8).putLong(size).array());try(var input=Files.newInputStream(file)){int n;long actual=0;while((n=input.read(buffer))!=-1){check(cancel);actual+=n;if(actual>size)throw new IOException("嵌套资源长度不符");digest.update(buffer,0,n);}if(actual!=size)throw new IOException("嵌套资源长度不符");}}
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static String fabricClosure(Path root,String fabric,String own,CancellationSignal cancel)throws IOException {
        var metadata=parse(fabric);if(metadata==null||!metadata.has("jars"))return own;
        try {
            var digest=MessageDigest.getInstance("SHA-256");digest.update(own.getBytes(StandardCharsets.UTF_8));long total=0;var paths=new TreeSet<String>();
            for(var item:metadata.getAsJsonArray("jars"))paths.add(item.getAsJsonObject().get("file").getAsString());
            for(String name:paths){NestedZipAccess.safe(name);Path path=root.resolve(name);long size=Files.size(path);total+=size;if(size>NestedZipAccess.MAX_NESTED_BYTES||total>96L*1024*1024)throw new IOException("Fabric依赖闭包身份超过预算");digest.update(name.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);try(var input=Files.newInputStream(path)){byte[] buffer=new byte[65536];long actual=0;int n;while((n=input.read(buffer))!=-1){check(cancel);actual+=n;if(actual>size)throw new IOException("Fabric子归档长度无效");digest.update(buffer,0,n);}if(actual!=size)throw new IOException("Fabric子归档长度无效");}}
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void note(List<String> warnings,String message){if(warnings.size()<128)warnings.add(message);}
    private static void check(CancellationSignal cancel){if(cancel.isCancelled()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("取消嵌套资源索引");}
}
