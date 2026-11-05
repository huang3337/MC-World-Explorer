package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Locates explicitly enabled embedded packs by their registered ID and archive path. */
final class BuiltinResourcePacks {
    record Pack(Path archive,String prefix,Set<String> owners) { }
    private record Stamp(Path path,long size,FileTime modified,Object key) { }
    private record Catalog(List<Stamp> stamps,List<Pack> packs) { }
    private static final Map<Path,Catalog> CACHE=new LinkedHashMap<>(8,.75f,true);
    private static final Pattern MOD_ID=Pattern.compile("^modId\\s*=\\s*[\"']([^\"']+)[\"'].*$");

    static Optional<InstanceResourceLocator.Layer> locate(String id,Path instance,List<Path> jars,CancellationSignal cancel,List<String> warnings)throws IOException {
        try(var lookup=new Lookup(instance,jars,cancel)){return lookup.locate(id,warnings);}
    }
    /** One manifest selection; check its archive receipts at both boundaries, not per pack ID. */
    static final class Lookup implements AutoCloseable {
        private final Path instance;
        private final List<Path> jars;
        private final CancellationSignal cancel;
        private Catalog catalog;
        private boolean closed;
        Lookup(Path instance,List<Path> jars,CancellationSignal cancel){this.instance=Objects.requireNonNull(instance);this.jars=List.copyOf(jars);this.cancel=Objects.requireNonNull(cancel);}
        Optional<InstanceResourceLocator.Layer> locate(String id,List<String> warnings)throws IOException {
        if(closed)throw new IllegalStateException("内置包查询已结束");check(cancel);
        String owner=null,path=id;
        if(path.startsWith("mod/"))path=path.substring(4);
        if(path.startsWith("builtin/"))path=path.substring(8);
        else if(path.contains(":")){int colon=path.indexOf(':');owner=path.substring(0,colon);path=path.substring(colon+1);}
        else if(path.contains("/")){int slash=path.indexOf('/');owner=path.substring(0,slash);path=path.substring(slash+1);}
        if(!safe(path)){warnings.add("已拒绝非法内置资源包路径："+id);return Optional.empty();}
        Set<String> prefixes=Set.of(path+"/","resourcepacks/"+path+"/","packs/"+path+"/","builtin/"+path+"/");List<Pack> matches=new ArrayList<>();
        if(catalog==null)catalog=catalog(instance,jars,cancel);
        for(var pack:catalog.packs())if(prefixes.contains(pack.prefix()))matches.add(pack);
        final String expected=owner;
        if(owner!=null&&!owner.equals("minecraft")){var owned=matches.stream().filter(pack->pack.owners().contains(expected)).toList();if(!owned.isEmpty())matches=new ArrayList<>(owned);else matches.clear();}
        if(matches.size()>1){warnings.add("内置资源包来源不唯一，未混用："+id);return Optional.empty();}
        if(matches.isEmpty())return Optional.empty();var pack=matches.getFirst();
        return Optional.of(new InstanceResourceLocator.Layer(pack.archive(),pack.prefix(),id));
        }
        @Override public void close()throws IOException {
            if(closed)return;closed=true;check(cancel);
            if(catalog==null)return;
            for(var stamp:catalog.stamps()){
                check(cancel);
                try {
                    var a=Files.readAttributes(stamp.path(),BasicFileAttributes.class);
                    if(stamp.size()!=a.size()||!stamp.modified().equals(a.lastModifiedTime())||stamp.key()!=null&&!stamp.key().equals(a.fileKey()))
                        throw new ResourceAccessException(ResourceAccessException.Reason.SOURCE_CHANGED,"内置包来源在选择期间发生变化："+stamp.path().getFileName());
                }catch(ResourceAccessException changed){throw changed;}
                catch(IOException missing){throw new ResourceAccessException(ResourceAccessException.Reason.SOURCE_CHANGED,"内置包来源在选择期间不可读："+stamp.path().getFileName(),missing);}
            }
        }
    }
    private static Catalog catalog(Path instance,List<Path> jars,CancellationSignal cancel)throws IOException {
        List<Stamp> stamps=new ArrayList<>();for(var path:jars){check(cancel);Path normalized=path.toAbsolutePath().normalize();var a=Files.readAttributes(normalized,BasicFileAttributes.class);stamps.add(new Stamp(normalized,a.size(),a.lastModifiedTime(),a.fileKey()));}
        // Resource and saved data-pack selection visit the same jars in opposite orders.
        // This catalog finds unique owner/prefix matches; it does not decide layer priority.
        stamps.sort(Comparator.comparing(stamp->stamp.path().toString()));
        synchronized(CACHE){var old=CACHE.get(instance);if(old!=null&&old.stamps().equals(stamps))return old;}
        List<Pack> packs=new ArrayList<>();int names=0;
        for(var path:jars) {
            check(cancel);
            if(Files.size(path)>InstanceResourceSource.MAX_CONTAINER)continue;
            try(var zip=new ZipFile(path.toFile())) {
                Set<String> prefixes=new LinkedHashSet<>();int count=0;
                for(var entries=zip.entries();entries.hasMoreElements();) {
                    if((count++&127)==0)check(cancel);if(count>100000)throw new IOException("内置包容器条目超限");
                    var entry=entries.nextElement();String name=entry.getName();
                    String prefix=null;int assets=name.indexOf("/assets/");
                    if(name.endsWith("/pack.mcmeta"))prefix=name.substring(0,name.length()-"pack.mcmeta".length());
                    else if(assets>=0)prefix=name.substring(0,assets+1);
                    if(prefix!=null&&safe(prefix.substring(0,prefix.length()-1))&&prefixes.add(prefix)) {
                        names+=prefix.length();if(names>1024*1024||packs.size()+prefixes.size()>4096)throw new IOException("内置包目录索引超限");
                    }
                }
                if(!prefixes.isEmpty()){var owners=owners(zip);for(String prefix:prefixes)packs.add(new Pack(path.toRealPath(),prefix,owners));}
            }catch(ZipException invalid){/* The owning resource source retains the invalid-archive diagnostic. */}
        }
        var result=new Catalog(List.copyOf(stamps),List.copyOf(packs));
        synchronized(CACHE){if(CACHE.size()>=8)CACHE.remove(CACHE.keySet().iterator().next());CACHE.put(instance,result);}
        return result;
    }
    static Set<String> owners(ZipFile zip)throws IOException {return owners(text(zip,"fabric.mod.json"),text(zip,"META-INF/neoforge.mods.toml"),text(zip,"META-INF/mods.toml"));}
    static Set<String> owners(String fabric,String neo,String forge)throws IOException {
        Set<String> ids=new HashSet<>();if(fabric!=null)try(var reader=new com.google.gson.stream.JsonReader(new StringReader(fabric))) {
            // Descriptions in some locally installed metadata contain raw control characters; only bounded IDs are used.
            reader.setStrictness(Strictness.LENIENT);reader.setNestingLimit(32);var json=JsonParser.parseReader(reader).getAsJsonObject();
            if(json.has("id"))addId(ids,json.get("id").getAsString());
            if(json.has("provides"))for(var value:json.getAsJsonArray("provides"))addId(ids,value.getAsString());
        }catch(RuntimeException invalid){/* An ownerless pack can only be selected by an unambiguous builtin path. */}
        for(String toml:java.util.Arrays.asList(neo,forge)) {
            if(toml==null)continue;boolean mod=false;
            for(String raw:toml.lines().toList()){String line=raw.strip();if(line.startsWith("["))mod=line.equals("[[mods]]");
                if(mod){var matcher=MOD_ID.matcher(line);if(matcher.matches())addId(ids,matcher.group(1));}}
        }
        return Set.copyOf(ids);
    }
    static String text(ZipFile zip,String name)throws IOException {var entry=zip.getEntry(name);if(entry==null||entry.isDirectory()||entry.getSize()<0||entry.getSize()>1024*1024)return null;try(var input=zip.getInputStream(entry)){byte[] bytes=input.readNBytes(1024*1024+1);if(bytes.length>1024*1024)throw new IOException("Mod元信息超限");return new String(bytes,StandardCharsets.UTF_8);}}
    private static void addId(Set<String> ids,String value){if(value.length()<=64&&value.matches("[a-z0-9_.-]+"))ids.add(value);}
    private static boolean safe(String value){if(value.isEmpty()||value.length()>1024||value.startsWith("/")||value.contains("\\")||value.contains(":")||value.chars().anyMatch(c->c<32))return false;for(String part:value.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))return false;return true;}
    private static void check(CancellationSignal cancel){if(cancel.isCancelled()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("取消内置资源包索引");}
}
