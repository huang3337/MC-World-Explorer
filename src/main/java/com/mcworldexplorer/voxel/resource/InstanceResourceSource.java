package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.io.CancellationSignal;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;
import static com.mcworldexplorer.voxel.resource.ResourceAccessException.Reason.*;

/** Owned, bounded read-only instance assets. Only selected resources are decompressed. */
public final class InstanceResourceSource implements BlockResourceSource,AutoCloseable {
    private static final long MAX_CONTAINER=512L*1024*1024,MAX_TOTAL=8L*1024*1024*1024;
    private static final int MAX_ENTRIES=2_000_000,MAX_NAME_CHARS=64*1024*1024,MAX_HANDLES=16;
    private final ClientResourceArchive client;
    private final CancellationSignal cancel;
    private final Optional<Path> instance;
    private final Path world,explicit;
    private final List<Path> selection;
    private final List<String> selectionWarnings;
    private final List<Stamp> selectedStamps=new ArrayList<>();
    private final List<Descriptor> descriptors=new ArrayList<>();
    private final List<String> warnings=new ArrayList<>();
    private final Map<String,List<Descriptor>> namespaces=new HashMap<>();
    private final Map<Path,ZipFile> handles=new LinkedHashMap<>(16,.75f,true);
    private final Map<String,String> provenance=new LinkedHashMap<>();
    private final ClientResourceArchive.Fingerprint fingerprint;
    private boolean closed;
    private long readBytes,totalSize;
    private int entryCount,nameChars;

    private record Stamp(Path path,long size,FileTime modified,Object key,boolean directory) {
        static Stamp capture(Path path)throws IOException {
            var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!a.isRegularFile()&&!a.isDirectory())throw new IOException("资源来源不是普通文件或目录："+path);
            return new Stamp(path,a.size(),a.lastModifiedTime(),a.fileKey(),a.isDirectory());
        }
        void validate()throws IOException {
            var now=capture(path);
            if(size!=now.size||!modified.equals(now.modified)||directory!=now.directory||(key!=null&&!key.equals(now.key)))
                throw new ResourceAccessException(SOURCE_CHANGED,"实例资源已变化："+path.getFileName());
        }
    }
    private record Descriptor(Stamp stamp,Set<String> namespaces,Map<String,Stamp> files,Set<String> entries,String hash) { }

    public static InstanceResourceSource open(Path world,Path clientJar,String version,Path explicitInstance,
            CancellationSignal cancel,Consumer<String> progress)throws IOException {
        var client=ClientResourceArchive.open(clientJar,version);
        try{return new InstanceResourceSource(client,world,explicitInstance,cancel,progress);}
        catch(IOException|RuntimeException|Error error){try{client.close();}catch(IOException close){error.addSuppressed(close);}throw error;}
    }
    private InstanceResourceSource(ClientResourceArchive client,Path world,Path explicit,CancellationSignal cancel,
            Consumer<String> progress)throws IOException {
        this.client=client;this.world=world;this.explicit=explicit;this.cancel=Objects.requireNonNull(cancel);Objects.requireNonNull(progress);
        var located=new InstanceResourceLocator().locate(world,explicit,cancel);instance=located.instance();selection=located.sources();selectionWarnings=located.warnings();warnings.addAll(located.warnings());
        MessageDigest identity=digest();update(identity,client.fingerprint().toString());update(identity,instance.map(Path::toString).orElse("detached"));for(String warning:located.warnings())update(identity,warning);
        int index=0;
        for(Path path:located.sources()) {
            check();progress.accept("正在索引实例资源 "+(++index)+"/"+located.sources().size());
            try {
                selectedStamps.add(Stamp.capture(path));
                var descriptor=index(path);descriptors.add(descriptor);update(identity,path.toString());update(identity,descriptor.hash());
                for(String namespace:descriptor.namespaces())namespaces.computeIfAbsent(namespace,ignored->new ArrayList<>()).add(descriptor);
            }catch(ResourceAccessException failure){if(failure.reason()==LIMIT_EXCEEDED)throw failure;warn("资源来源不可用："+path.getFileName()+"（"+failure.getMessage()+"）");update(identity,"unavailable:"+path);}
            catch(IOException invalid){warn("资源来源不可用："+path.getFileName()+"（"+invalid.getMessage()+"）");update(identity,"unavailable:"+path);}
        }
        fingerprint=new ClientResourceArchive.Fingerprint(client.fingerprint().path(),Math.addExact(totalSize,client.fingerprint().size()),
                client.fingerprint().modifiedMillis(),HexFormat.of().formatHex(identity.digest()));
        validateAll();
    }
    public Optional<Path> instance(){return instance;}
    public int sourceCount(){return descriptors.size();}
    public List<String> warnings(){return List.copyOf(warnings);}
    public List<String> originSamples(){return provenance.entrySet().stream().limit(128).map(entry->entry.getKey()+" → "+entry.getValue()).toList();}
    public String description(){return instance.map(path->"实例："+path+"；附加资源来源 "+sourceCount()+" 项").orElse("原版与存档资源");}
    public Optional<String> origin(ResourceLocation.Kind kind,ResourceLocation id){return Optional.ofNullable(provenance.get(kind+":"+id));}
    @Override public ClientResourceArchive.Fingerprint fingerprint(){return fingerprint;}

    private Descriptor index(Path path)throws IOException {
        var stamp=Stamp.capture(path);Set<String> names=new HashSet<>(), indexedEntries=new HashSet<>();Map<String,Stamp> files=new TreeMap<>();MessageDigest hash=digest();
        if(stamp.directory()) {
            for(String folder:List.of("assets","data")) {
                Path start=path.resolve(folder);if(!Files.isDirectory(start,LinkOption.NOFOLLOW_LINKS))continue;
                try(var walk=Files.walk(start,32)) {
                    var iterator=walk.iterator();
                    while(iterator.hasNext()) {
                        check();Path entry=iterator.next();String relative=path.relativize(entry).toString().replace('\\','/');budgetName(relative);
                        if(Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS))files.put(relative,Stamp.capture(entry));
                        else if(Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS)) {
                            Stamp file=Stamp.capture(entry);if(!entry.toRealPath().startsWith(path))throw new IOException("资源包文件越界");
                            addSize(file.size());if(file.size()>MAX_CONTAINER)throw limit("单资源文件超过 512 MiB");
                            files.put(relative,file);indexedEntries.add(relative);namespace(relative).ifPresent(names::add);
                        }
                    }
                }
            }
            for(var entry:files.entrySet()){update(hash,entry.getKey());if(!entry.getValue().directory())hashFile(entry.getValue().path(),hash);entry.getValue().validate();}
        }else {
            if(stamp.size()>MAX_CONTAINER)throw limit("资源归档超过 512 MiB");addSize(stamp.size());
            try(var zip=new ZipFile(path.toFile())) {
                Set<String> unique=new HashSet<>();var entries=zip.entries();int count=0;
                while(entries.hasMoreElements()) {
                    if((count++&127)==0)check();var entry=entries.nextElement();String name=entry.getName();budgetName(name);
                    if(count>100_000)throw limit("单归档条目超过 100000");
                    if(name.startsWith("assets/")||name.startsWith("data/")) {
                        if(!unique.add(name))throw new IOException("资源归档包含重复素材条目");
                        if(name.indexOf('\\')>=0||name.contains("/../")||name.contains("/./")||name.chars().anyMatch(c->c<32))throw new IOException("非法素材路径");
                        if(!entry.isDirectory()){indexedEntries.add(name);namespace(name).ifPresent(names::add);}
                    }
                }
            }
            hashFile(path,hash);
        }
        stamp.validate();return new Descriptor(stamp,Set.copyOf(names),Map.copyOf(files),Set.copyOf(indexedEntries),HexFormat.of().formatHex(hash.digest()));
    }
    private void addSize(long size)throws IOException {totalSize=Math.addExact(totalSize,size);if(totalSize>MAX_TOTAL)throw limit("实例资源总大小超过 8 GiB");}
    private void budgetName(String name)throws IOException {entryCount++;nameChars=Math.addExact(nameChars,name.length());if(entryCount>MAX_ENTRIES||nameChars>MAX_NAME_CHARS||name.length()>2048)throw limit("实例资源索引超过预算");}
    private static Optional<String> namespace(String entry) {
        String[] parts=entry.split("/",4);if(parts.length<4)return Optional.empty();
        try{return Optional.of(new ResourceLocation(parts[1],"probe").namespace());}catch(IllegalArgumentException invalid){return Optional.empty();}
    }
    private void hashFile(Path file,MessageDigest hash)throws IOException {
        try(var input=Files.newInputStream(file)){byte[] buffer=new byte[64*1024];int count;long total=0;while((count=input.read(buffer))!=-1){check();total+=count;if(total>MAX_CONTAINER)throw limit("资源在计算指纹期间增长并超过预算");hash.update(buffer,0,count);}}
    }
    private ZipFile handle(Descriptor descriptor)throws IOException {
        Path path=descriptor.stamp().path();var zip=handles.get(path);if(zip!=null)return zip;
        if(handles.size()>=MAX_HANDLES){var iterator=handles.entrySet().iterator();var old=iterator.next();old.getValue().close();iterator.remove();}
        zip=new ZipFile(path.toFile());handles.put(path,zip);return zip;
    }
    @Override public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id)throws IOException {
        check();validate();String entry=id.entryName(kind);
        for(var source:namespaces.getOrDefault(id.namespace(),List.of())) {
            if(!source.entries().contains(entry))continue;
            source.stamp().validate();Optional<byte[]> value;
            if(source.stamp().directory()) {
                var file=source.files().get(entry);if(file==null||file.directory())continue;
                if(!file.path().toRealPath().startsWith(source.stamp().path()))throw new ResourceAccessException(SOURCE_CHANGED,"目录资源越界");
                file.validate();try(var input=Files.newInputStream(file.path())){value=Optional.of(bytes(input,file.size(),kind.maxBytes()));}file.validate();
            }else {
                var zip=handle(source);var item=zip.getEntry(entry);if(item==null||item.isDirectory())continue;
                try(var input=zip.getInputStream(item)){value=Optional.of(bytes(input,item.getSize(),kind.maxBytes()));}
            }
            source.stamp().validate();recordOrigin(kind,id,source.stamp().path().toString());return value;
        }
        var value=client.read(kind,id);if(value.isPresent()){readBytes+=value.orElseThrow().length;if(readBytes>ClientResourceArchive.MAX_SESSION_READ_BYTES)throw limit("组合来源读取超过 64 MiB 会话预算");recordOrigin(kind,id,client.fingerprint().path().toString());}return value;
    }
    private byte[] bytes(InputStream input,long size,int maximum)throws IOException {
        if(size<0||size>maximum)throw new ResourceAccessException(LIMIT_EXCEEDED,"单个实例资源超过读取预算",ResourceAccessException.Scope.RESOURCE);
        if(readBytes+size>ClientResourceArchive.MAX_SESSION_READ_BYTES)throw limit("实例资源读取超过 64 MiB 会话预算");
        byte[] bytes=input.readNBytes(maximum+1);if(bytes.length!=size||bytes.length>maximum)throw new IOException("资源条目解压长度无效");
        readBytes+=bytes.length;return bytes;
    }
    private void recordOrigin(ResourceLocation.Kind kind,ResourceLocation id,String origin){String key=kind+":"+id;if(provenance.size()>=8192&&!provenance.containsKey(key))provenance.remove(provenance.keySet().iterator().next());provenance.put(key,origin);}
    @Override public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id)throws IOException {
        if(kind==ResourceLocation.Kind.TEXTURE)throw new IllegalArgumentException("PNG 不能按 JSON 读取");
        var data=read(kind,id);if(data.isEmpty())return Optional.empty();
        try{return Optional.of(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data.orElseThrow())).toString());}
        catch(CharacterCodingException invalid){throw new ResourceAccessException(INVALID_METADATA,"实例资源 JSON 不是有效 UTF-8",invalid);}
    }
    @Override public void validate()throws IOException {
        check();if(closed)throw new ResourceAccessException(CLOSED,"实例资源读取器已关闭");client.validate();
        for(var source:descriptors)if(handles.containsKey(source.stamp().path()))source.stamp().validate();
    }
    /** Full manifest checks at request/cache boundaries, avoiding an all-Mod stat scan per model face. */
    public void validateAll()throws IOException {
        validate();for(var stamp:selectedStamps){check();stamp.validate();}
        for(var source:descriptors){check();source.stamp().validate();for(var file:source.files().values())file.validate();}
        var now=new InstanceResourceLocator().locate(world,explicit,cancel);
        if(!selection.equals(now.sources())||!selectionWarnings.equals(now.warnings()))throw new ResourceAccessException(SOURCE_CHANGED,"实例资源列表或启用顺序发生变化，请重新生成");
    }
    private void check(){if(cancel.isCancelled()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("取消实例资源读取");}
    private void warn(String detail){if(warnings.size()<128)warnings.add(detail);}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void update(MessageDigest digest,String text){digest.update(text.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}
    private static ResourceAccessException limit(String message){return new ResourceAccessException(LIMIT_EXCEEDED,message);}
    @Override public void close()throws IOException {
        if(closed)return;closed=true;IOException error=null;
        for(var zip:handles.values())try{zip.close();}catch(IOException failure){if(error==null)error=failure;else error.addSuppressed(failure);}
        handles.clear();try{client.close();}catch(IOException failure){if(error==null)error=failure;else error.addSuppressed(failure);}
        if(error!=null)throw error;
    }
}
