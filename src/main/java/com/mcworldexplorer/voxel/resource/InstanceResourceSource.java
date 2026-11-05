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
    static final long MAX_CONTAINER=1024L*1024*1024;
    private static final long MAX_TOTAL=8L*1024*1024*1024;
    private static final int MAX_ENTRIES=2_000_000,MAX_NAME_CHARS=64*1024*1024,MAX_HANDLES=16;
    // 只缓存内容已校验的相对目录数据，不保留路径、来源对象或ZIP句柄。
    private static final int MAX_CACHED_LAYERS=512,MAX_CACHED_ENTRIES=524288,MAX_CACHED_CHARS=32*1024*1024;
    private record IndexKey(String contentHash,String prefix,List<String> nested) {
        IndexKey {nested=List.copyOf(nested);}
    }
    private record CachedIndex(Set<String> namespaces,Set<String> entries,String hash,
                               int budgetEntries,int budgetChars,int retainedEntries,long retainedChars) { }
    private static final Map<IndexKey,CachedIndex> INDEX_CACHE=new LinkedHashMap<>(16,.75f,true);
    private static int cachedEntries;
    private static long cachedChars;
    private final ClientResourceArchive client;
    private final NestedZipAccess nestedAccess;
    private final Map<Path,String> physicalHashes=new HashMap<>();
    private final CancellationSignal cancel;
    private final Optional<Path> instance;
    private final Path world,explicit;
    private final List<InstanceResourceLocator.Layer> selection;
    private final List<String> selectionWarnings;
    private final List<Stamp> selectedStamps=new ArrayList<>();
    private final Map<Path,Stamp> selectedByPath=new HashMap<>();
    private final List<Descriptor> descriptors=new ArrayList<>();
    private final List<Descriptor> registrationSources=new ArrayList<>();
    private final List<Path> launchInputs;
    private final Optional<String> loaderGameVersion;
    private final String selectedLoaderProblem;
    private final Map<Path,Stamp> launchStamps=new LinkedHashMap<>();
    private final Set<Path> missingLaunchInputs=new LinkedHashSet<>();
    private final List<Descriptor> dataSources=new ArrayList<>();
    private WorldDataPacks.Selection dataSelection;
    private String dataProblem;
    private final BiomeColorSettings biomeSettings;
    private final Map<Path,Stamp> biomeInputStamps=new HashMap<>();
    private final Set<Path> missingBiomeInputs=new HashSet<>();
    private final Map<String,Set<ResourceLocation>> jsonLists=new LinkedHashMap<>(16,.75f,true);
    private Set<String> modIds;
    private Map<String,Integer> modOwnerCounts = Map.of();
    private String loader;
    private Map<String,Set<String>> modPlatforms=Map.of();
    private final List<String> warnings=new ArrayList<>();
    private final Map<String,List<Descriptor>> namespaces=new HashMap<>();
    private final Map<Path,ZipFile> handles=new LinkedHashMap<>(16,.75f,true);
    private final Map<String,String> provenance=new LinkedHashMap<>();
    private final ClientResourceArchive.Fingerprint fingerprint;
    private boolean closed;
    private long readBytes,totalSize;
    private int entryCount,nameChars;

    record Stamp(Path path,long size,FileTime modified,Object key,boolean directory) {
        static Stamp capture(Path path)throws IOException {
            var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!a.isRegularFile()&&!a.isDirectory())throw new IOException("资源来源不是普通文件或目录："+path);
            return new Stamp(path,a.size(),a.lastModifiedTime(),a.fileKey(),a.isDirectory());
        }
        void validate()throws IOException {
            var now=capture(path);
            if(!sameIdentity(now))
                throw new ResourceAccessException(SOURCE_CHANGED,"实例资源已变化："+path.getFileName());
        }
        boolean sameIdentity(Stamp now) {
            // Windows may report directory allocation as 0 before enumeration and a block size afterwards.
            // Directory membership is checked separately; ordinary file sizes remain part of identity.
            return path.equals(now.path)&&directory==now.directory&&(directory||size==now.size)
                    &&modified.equals(now.modified)&&(key==null||key.equals(now.key));
        }
    }
    private record Descriptor(Stamp stamp,Set<String> namespaces,Map<String,Stamp> files,Set<String> entries,String hash,String prefix,List<String> nested) { }

    public static InstanceResourceSource open(Path world,Path clientJar,String version,Path explicitInstance,
            CancellationSignal cancel,Consumer<String> progress)throws IOException {
        var client=ClientResourceArchive.open(clientJar,version);
        var nested=new NestedZipAccess(cancel);
        try{return new InstanceResourceSource(client,nested,world,explicitInstance,cancel,progress);}
        catch(IOException|RuntimeException|Error error){try{nested.close();}catch(IOException close){error.addSuppressed(close);}try{client.close();}catch(IOException close){error.addSuppressed(close);}throw error;}
    }
    private InstanceResourceSource(ClientResourceArchive client,NestedZipAccess nestedAccess,Path world,Path explicit,CancellationSignal cancel,
            Consumer<String> progress)throws IOException {
        this.client=client;this.nestedAccess=nestedAccess;this.world=world;this.explicit=explicit;this.cancel=Objects.requireNonNull(cancel);Objects.requireNonNull(progress);
        var located=new InstanceResourceLocator().locate(world,explicit,cancel);instance=located.instance();selection=located.layers();selectionWarnings=located.warnings();warnings.addAll(located.warnings());
        launchInputs=located.launchInputs();loaderGameVersion=located.loaderGameVersion();selectedLoaderProblem=located.loaderProblem();
        MessageDigest identity=digest();update(identity,"instance-resource-source:core-resources-v5");update(identity,client.fingerprint().toString());update(identity,instance.map(Path::toString).orElse("detached"));for(String warning:located.warnings())update(identity,warning);

        update(identity,"loader-game:"+loaderGameVersion);update(identity,"launch-problem:"+selectedLoaderProblem);
        for(var input:launchInputs){
            update(identity,input.toString());
            if(Files.exists(input,LinkOption.NOFOLLOW_LINKS)){
                try{var stamp=Stamp.capture(input);launchStamps.put(input,stamp);
                    if(stamp.directory()||stamp.size()>(input.toString().endsWith(".jar")?MAX_CONTAINER:2*1024*1024))throw new IOException("启动协议输入不是预算内的文件");
                    hashFile(input,identity);stamp.validate();
                }catch(IOException invalid){throw new IOException("启动配置输入不可读："+invalid.getMessage(),invalid);}
            }
            else{missingLaunchInputs.add(input);update(identity,"absent");}
        }
        var biomeInputs=new ArrayList<Path>();biomeInputs.add(world.resolve("level.dat"));instance.ifPresent(path->biomeInputs.add(path.resolve("options.txt")));
        for(var path:biomeInputs){if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))biomeInputStamps.put(path,Stamp.capture(path));else if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))missingBiomeInputs.add(path);}
        biomeSettings=BiomeColorSettings.read(instance);update(identity,"biome-settings:"+biomeSettings);if(!biomeSettings.problem().isEmpty())warn(biomeSettings.problem());
        int index=0;
        for(var layer:located.layers()) {
            Path path=layer.path();
            check();progress.accept("正在索引实例资源 "+(++index)+"/"+located.layers().size());
            update(identity,path.toString());update(identity,layer.prefix());update(identity,layer.packId());update(identity,Integer.toString(layer.nested().size()));for(String item:layer.nested())update(identity,item);
            try {
                var selected=Stamp.capture(path);selectedStamps.add(selected);selectedByPath.putIfAbsent(path,selected);
                var descriptor=index(path,layer.prefix(),layer.nested());descriptors.add(descriptor);update(identity,descriptor.hash());
                if(layer.prefix().isEmpty()&&(layer.packId().equals("mod")||layer.packId().startsWith("nested:")))registrationSources.add(descriptor);
                for(String namespace:descriptor.namespaces())namespaces.computeIfAbsent(namespace,ignored->new ArrayList<>()).add(descriptor);
            }catch(ResourceAccessException failure){if(failure.reason()==LIMIT_EXCEEDED)throw failure;warn("资源来源不可用："+path.getFileName()+"（"+failure.getMessage()+"）");update(identity,"unavailable:"+path);}
            catch(IOException invalid){warn("资源来源不可用："+path.getFileName()+"（"+invalid.getMessage()+"）");update(identity,"unavailable:"+path);}
        }
        var modJars=located.layers().stream().filter(layer->layer.packId().equals("mod")).map(InstanceResourceLocator.Layer::path).distinct().toList();
        try{
            dataSelection=WorldDataPacks.locate(world,instance,modJars,cancel);
            update(identity,dataSelection.enabled().toString());update(identity,dataSelection.disabled().toString());
            update(identity,"world-seed:"+dataSelection.seed());if(!dataSelection.seed().problem().isEmpty())warn(dataSelection.seed().problem());
            update(identity,"dimension-types:"+dataSelection.dimensions().problem());
            dataSelection.dimensions().types().entrySet().stream().sorted(Comparator.comparing(e->e.getKey().toString())).forEach(e->{update(identity,e.getKey().toString());update(identity,e.getValue().toString());});
            for(var layer:dataSelection.layers()){
                var stamp=Stamp.capture(layer.path());selectedStamps.add(stamp);selectedByPath.putIfAbsent(layer.path(),stamp);
                var descriptor=index(layer.path(),layer.prefix(),layer.nested());dataSources.add(descriptor);update(identity,layer.toString());update(identity,descriptor.hash());
            }
        }catch(IOException invalid){dataProblem=invalid.getMessage();warn("数据注册来源不可用："+dataProblem);update(identity,"data-unavailable:"+dataProblem);}
        for(var source:registrationSources){
            if(dataSelection!=null&&!dataSelection.disabled().isEmpty()){
                var disabled=dataSelection.disabled().stream().filter(id->id.startsWith("mod:")).map(id->id.substring(4)).collect(java.util.stream.Collectors.toSet());
                if(!disabled.isEmpty()){
                    var owners=owners(source);long omitted=owners.stream().filter(disabled::contains).count();
                    if(omitted>0){if(omitted!=owners.size()){dataProblem="同一归档含启用和禁用的Mod数据包，不能拆分注册来源";warn(dataProblem);}continue;}
                }
            }dataSources.add(source);
        }
        fingerprint=new ClientResourceArchive.Fingerprint(client.fingerprint().path(),Math.addExact(totalSize,client.fingerprint().size()),
                client.fingerprint().modifiedMillis(),HexFormat.of().formatHex(identity.digest()));
        validateAll();
    }
    public Optional<Path> instance(){return instance;}
    public int sourceCount(){return descriptors.size();}
    public List<String> warnings(){return List.copyOf(warnings);}
    /** Effective instance declarations, including selected packs and nested archives; excludes client fallback. */
    public Set<ResourceLocation> instanceBlockstates() {
        var ids=new TreeSet<ResourceLocation>(Comparator.comparing(ResourceLocation::toString));
        for(var descriptor:descriptors)for(String entry:descriptor.entries()) {
            String[] parts=entry.split("/",4);
            if(parts.length!=4||!parts[0].equals("assets")||!parts[2].equals("blockstates")||!parts[3].endsWith(".json"))continue;
            try{ids.add(new ResourceLocation(parts[1],parts[3].substring(0,parts[3].length()-5)));}catch(IllegalArgumentException invalid){/* Not an addressable block ID; ordinary reads still validate IDs. */}
        }
        return Collections.unmodifiableSet(ids);
    }
    public List<String> originSamples(){return provenance.entrySet().stream().limit(128).map(entry->entry.getKey()+" → "+entry.getValue()).toList();}
    public String description(){return instance.map(path->"实例："+path+"；附加资源来源 "+sourceCount()+" 项").orElse("原版与存档资源");}
    public Optional<String> origin(ResourceLocation.Kind kind,ResourceLocation id){return Optional.ofNullable(provenance.get(kind+":"+id));}
    @Override public ClientResourceArchive.Fingerprint fingerprint(){return fingerprint;}

    private Descriptor index(Path path,String prefix,List<String> nested)throws IOException {
        check();var stamp=Stamp.capture(path);String contentHash=null;IndexKey cacheKey=null;
        if(!stamp.directory()) {
            if(stamp.size()>MAX_CONTAINER)throw limit("资源归档超过 1 GiB");
            // 每个新来源仍完整计算SHA；属性相同也不能绕过内容身份校验。
            contentHash=physicalHash(stamp);cacheKey=new IndexKey(contentHash,prefix,nested);
            CachedIndex cached;synchronized(INDEX_CACHE){cached=INDEX_CACHE.get(cacheKey);}
            if(cached!=null){
                applyIndexBudget(cached.budgetEntries(),cached.budgetChars());stamp.validate();check();
                return new Descriptor(stamp,cached.namespaces(),Map.of(),cached.entries(),cached.hash(),prefix,List.copyOf(nested));
            }
        }
        int entriesBefore=entryCount,charsBefore=nameChars;
        Set<String> names=new HashSet<>(), indexedEntries=new HashSet<>();Map<String,Stamp> files=new TreeMap<>();MessageDigest hash=digest();
        if(!nested.isEmpty()) {
            if(stamp.directory()||stamp.size()>MAX_CONTAINER)throw limit("嵌套来源不是受控大小的归档");
            Path root=nestedAccess.root(path,nested);int count=0;
            try(var walk=Files.walk(root,48)) {
                var iterator=walk.iterator();while(iterator.hasNext()) {
                    check();Path item=iterator.next();if(++count>100000)throw limit("单嵌套归档条目超过100000");
                    String raw=root.relativize(item).toString();if(!raw.startsWith(prefix))continue;String name=raw.substring(prefix.length());

                    if(!name.startsWith("assets/")&&!name.startsWith("data/"))continue;budgetName(name);
                    if(name.indexOf('\\')>=0||name.contains("/../")||name.contains("/./")||name.chars().anyMatch(c->c<32))throw new IOException("嵌套素材路径无效");
                    if(Files.isRegularFile(item)){indexedEntries.add(name);namespace(name).ifPresent(names::add);}
                }
            }
            update(hash,contentHash);for(String entry:nested)update(hash,entry);
        }else if(stamp.directory()) {
            for(String folder:List.of("assets","data")) {
                Path start=path.resolve(prefix+folder);if(!Files.isDirectory(start,LinkOption.NOFOLLOW_LINKS))continue;
                try(var walk=Files.walk(start,32)) {
                    var iterator=walk.iterator();
                    while(iterator.hasNext()) {
                        check();Path entry=iterator.next();String relative=path.relativize(entry).toString().replace('\\','/');if(!relative.startsWith(prefix))throw new IOException("资源层前缀越界");relative=relative.substring(prefix.length());budgetName(relative);
                        if(Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS))files.put(relative,Stamp.capture(entry));
                        else if(Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS)) {
                            Stamp file=Stamp.capture(entry);if(!entry.toRealPath().startsWith(path))throw new IOException("资源包文件越界");
                            addSize(file.size());if(file.size()>MAX_CONTAINER)throw limit("单资源文件超过 1 GiB");
                            files.put(relative,file);indexedEntries.add(relative);namespace(relative).ifPresent(names::add);
                        }
                    }
                }
            }
            Path metadata=path.resolve(prefix+"pack.mcmeta");if(Files.exists(metadata,LinkOption.NOFOLLOW_LINKS)){if(!Files.isRegularFile(metadata,LinkOption.NOFOLLOW_LINKS))throw new IOException("资源包元信息不是普通文件");var meta=Stamp.capture(metadata);if(meta.size()>1024*1024)throw limit("资源包元信息超过1MiB");files.put("pack.mcmeta",meta);}
            for(var entry:files.entrySet()){update(hash,entry.getKey());if(!entry.getValue().directory())hashFile(entry.getValue().path(),hash);entry.getValue().validate();}
        }else {
            if(stamp.size()>MAX_CONTAINER)throw limit("资源归档超过 1 GiB");
            try(var zip=new ZipFile(path.toFile())) {
                Set<String> unique=new HashSet<>();var entries=zip.entries();int count=0;
                while(entries.hasMoreElements()) {
                    if((count++&127)==0)check();
                    if(count>100_000)throw limit("单归档条目超过 100000");
                    var entry=entries.nextElement();String raw=entry.getName();if(!raw.startsWith(prefix))continue;String name=raw.substring(prefix.length());budgetName(name);
                    if(name.startsWith("assets/")||name.startsWith("data/")) {
                        if(!entry.isDirectory()&&!unique.add(name))throw new IOException("资源归档包含重复素材条目");
                        if(name.indexOf('\\')>=0||name.contains("/../")||name.contains("/./")||name.chars().anyMatch(c->c<32))throw new IOException("非法素材路径");
                        if(!entry.isDirectory()){indexedEntries.add(name);namespace(name).ifPresent(names::add);}
                    }
                }
            }
            update(hash,contentHash);
        }
        stamp.validate();check();var result=new Descriptor(stamp,Set.copyOf(names),Map.copyOf(files),Set.copyOf(indexedEntries),HexFormat.of().formatHex(hash.digest()),prefix,List.copyOf(nested));
        if(cacheKey!=null)cacheIndex(cacheKey,result,entryCount-entriesBefore,nameChars-charsBefore);
        return result;
    }
    private void applyIndexBudget(int entries,int chars)throws IOException {
        entryCount=Math.addExact(entryCount,entries);nameChars=Math.addExact(nameChars,chars);
        if(entryCount>MAX_ENTRIES||nameChars>MAX_NAME_CHARS)throw limit("实例资源索引超过预算");
    }
    private static void cacheIndex(IndexKey key,Descriptor value,int entries,int chars) {
        int retained=1+key.nested().size();long characters=key.contentHash().length()+key.prefix().length()+value.hash().length();
        for(String item:key.nested())characters+=item.length();
        for(var items:List.of(value.namespaces(),value.entries())){retained+=items.size();for(String item:items)characters+=item.length();}
        if(retained>MAX_CACHED_ENTRIES||characters>MAX_CACHED_CHARS)return;
        var cached=new CachedIndex(value.namespaces(),value.entries(),value.hash(),entries,chars,retained,characters);
        synchronized(INDEX_CACHE) {
            var old=INDEX_CACHE.remove(key);if(old!=null){cachedEntries-=old.retainedEntries();cachedChars-=old.retainedChars();}
            while(!INDEX_CACHE.isEmpty()&&(INDEX_CACHE.size()>=MAX_CACHED_LAYERS||cachedEntries+retained>MAX_CACHED_ENTRIES||cachedChars+characters>MAX_CACHED_CHARS)) {
                var iterator=INDEX_CACHE.entrySet().iterator();var removed=iterator.next().getValue();iterator.remove();cachedEntries-=removed.retainedEntries();cachedChars-=removed.retainedChars();
            }
            INDEX_CACHE.put(key,cached);cachedEntries+=retained;cachedChars+=characters;
        }
    }


    private String physicalHash(Stamp stamp)throws IOException {
        var known=physicalHashes.get(stamp.path());if(known!=null){stamp.validate();return known;}
        addSize(stamp.size());var digest=digest();hashFile(stamp.path(),digest);stamp.validate();String value=HexFormat.of().formatHex(digest.digest());physicalHashes.put(stamp.path(),value);return value;
    }
    private void addSize(long size)throws IOException {totalSize=Math.addExact(totalSize,size);if(totalSize>MAX_TOTAL)throw limit("实例资源总大小超过 8 GiB");}
    private void budgetName(String name)throws IOException {entryCount++;nameChars=Math.addExact(nameChars,name.length());if(entryCount>MAX_ENTRIES||nameChars>MAX_NAME_CHARS||name.length()>2048)throw limit("实例资源索引超过预算");}
    private static Optional<String> namespace(String entry) {
        String[] parts=entry.split("/",4);if(parts.length<4)return Optional.empty();
        try{return Optional.of(new ResourceLocation(parts[1],"probe").namespace());}catch(IllegalArgumentException invalid){return Optional.empty();}
    }
    private void hashFile(Path file,MessageDigest hash)throws IOException {
        try(var input=Files.newInputStream(file)){byte[] buffer=new byte[256*1024];int count;long total=0;while((count=input.read(buffer))!=-1){check();total+=count;if(total>MAX_CONTAINER)throw limit("资源在计算指纹期间增长并超过预算");hash.update(buffer,0,count);}}
    }
    private ZipFile handle(Descriptor descriptor)throws IOException {
        Path path=descriptor.stamp().path();var zip=handles.get(path);if(zip!=null)return zip;
        if(handles.size()>=MAX_HANDLES){var iterator=handles.entrySet().iterator();var old=iterator.next();old.getValue().close();iterator.remove();}
        zip=new ZipFile(path.toFile());handles.put(path,zip);return zip;
    }


    @Override public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id)throws IOException {
        check();validate();String entry=id.entryName(kind);
        if(kind==ResourceLocation.Kind.RAW_DATA&&dataProblem!=null)throw new ResourceAccessException(INVALID_METADATA,dataProblem,ResourceAccessException.Scope.RESOURCE);
        for(var source:kind==ResourceLocation.Kind.RAW_DATA?dataSources:namespaces.getOrDefault(id.namespace(),List.of())) {
            if(!source.entries().contains(entry))continue;
            source.stamp().validate();Optional<byte[]> value;
            if(!source.nested().isEmpty()) {
                Path item=nestedAccess.root(source.stamp().path(),source.nested()).resolve(source.prefix()+entry);
                try(var input=Files.newInputStream(item)){value=Optional.of(bytes(input,Files.size(item),kind.maxBytes()));}
            }else if(source.stamp().directory()) {
                var file=source.files().get(entry);if(file==null||file.directory())continue;
                if(!file.path().toRealPath().startsWith(source.stamp().path()))throw new ResourceAccessException(SOURCE_CHANGED,"目录资源越界");
                file.validate();try(var input=Files.newInputStream(file.path())){value=Optional.of(bytes(input,file.size(),kind.maxBytes()));}file.validate();
            }else {
                var zip=handle(source);var item=zip.getEntry(source.prefix()+entry);if(item==null||item.isDirectory())continue;
                try(var input=zip.getInputStream(item)){value=Optional.of(bytes(input,item.getSize(),kind.maxBytes()));}
            }
            source.stamp().validate();recordOrigin(kind,id,source.stamp().path()+source.nested().stream().map(item->"!/"+item).collect(java.util.stream.Collectors.joining())+(source.prefix().isEmpty()?"":"!/"+source.prefix()));return value;
        }
        var value=client.read(kind,id);if(value.isPresent()){readBytes+=value.orElseThrow().length;if(readBytes>ClientResourceArchive.MAX_SESSION_READ_BYTES)throw limit("组合来源读取超过 64 MiB 会话预算");recordOrigin(kind,id,client.fingerprint().path().toString());}return value;
    }
    @Override public Set<ResourceLocation> jsonFiles(ResourceLocation.Kind kind,String folder)throws IOException {
        validate();if(kind!=ResourceLocation.Kind.RAW_ASSET&&kind!=ResourceLocation.Kind.RAW_DATA)throw new IllegalArgumentException("JSON枚举仅支持资产或数据");
        new ResourceLocation("mcwe",folder+"/check.json");if(folder.length()>128)throw new IllegalArgumentException("JSON目录过长");
        if(kind==ResourceLocation.Kind.RAW_DATA&&dataProblem!=null)throw new ResourceAccessException(INVALID_METADATA,dataProblem,ResourceAccessException.Scope.RESOURCE);
        String key=kind+":"+folder;var old=jsonLists.get(key);if(old!=null)return old;
        Set<ResourceLocation> found=new TreeSet<>(Comparator.comparing(ResourceLocation::toString));String domain=kind==ResourceLocation.Kind.RAW_DATA?"data":"assets";
        for(var source:kind==ResourceLocation.Kind.RAW_DATA?dataSources:descriptors)for(String name:source.entries()){
            String[] pieces=name.split("/",3);if(pieces.length!=3||!pieces[0].equals(domain)||!pieces[2].startsWith(folder+"/")||!pieces[2].endsWith(".json"))continue;
            try{found.add(new ResourceLocation(pieces[1],pieces[2]));}catch(IllegalArgumentException invalid){throw new IOException("JSON注册路径无效",invalid);}
            if(found.size()>1024)throw new ResourceAccessException(LIMIT_EXCEEDED,"JSON注册目录超过1024项",ResourceAccessException.Scope.RESOURCE);
        }
        var result=Collections.unmodifiableSet(found);if(jsonLists.size()>=16)jsonLists.remove(jsonLists.keySet().iterator().next());jsonLists.put(key,result);return result;
    }


    @Override public Optional<Boolean> hasMod(String id)throws IOException {
        new ResourceLocation(id,"check");loadModMetadata();if(id.equals("minecraft"))return Optional.of(true);if(Set.of("forge","neoforge","fabricloader").contains(id))return Optional.empty();return Optional.of(modIds.contains(id));
    }
    @Override public Optional<Boolean> baseDataRegistry(String directory)throws IOException{validate();return client.baseDataRegistry(directory);}
    @Override public Optional<String> baseClientVersion(){return client.baseClientVersion();}
    @Override public Optional<Long> worldSeed()throws IOException{
        validate();if(dataSelection==null)throw new ResourceAccessException(INVALID_METADATA,"保存世界元数据不可用："+dataProblem);
        if(!biomeInputStamps.containsKey(world.resolve("level.dat"))&&!missingBiomeInputs.contains(world.resolve("level.dat")))throw new ResourceAccessException(INVALID_METADATA,"保存世界种子需要普通level.dat文件");
        if(!dataSelection.seed().problem().isEmpty())throw new ResourceAccessException(INVALID_METADATA,dataSelection.seed().problem());return dataSelection.seed().value();
    }
    @Override public Optional<Integer> biomeBlendRadius()throws IOException{
        validate();if(!biomeSettings.problem().isEmpty())throw new ResourceAccessException(INVALID_METADATA,biomeSettings.problem());return biomeSettings.radius();
    }
    @Override public Optional<DimensionHeight> dimensionHeight(ResourceLocation dimension)throws IOException{
        Objects.requireNonNull(dimension);validate();
        if(dataSelection==null)throw new ResourceAccessException(INVALID_METADATA,"保存维度元数据不可用："+dataProblem);
        if(!dataSelection.dimensions().problem().isEmpty())throw new ResourceAccessException(INVALID_METADATA,dataSelection.dimensions().problem());
        var type=dataSelection.dimensions().types().get(dimension);if(type==null)return Optional.empty();
        if(!type.problem().isEmpty())throw new ResourceAccessException(INVALID_METADATA,type.problem());
        if(type.inline()!=null)return Optional.of(type.inline());
        var id=ResourceLocation.parse(type.reference());var data=new ResourceLocation(id.namespace(),"dimension_type/"+id.path()+".json");
        var raw=readJson(ResourceLocation.Kind.RAW_DATA,data);if(raw.isEmpty())return Optional.empty();
        try{
            var json=com.google.gson.JsonParser.parseString(raw.orElseThrow()).getAsJsonObject();
            var min=json.get("min_y");var height=json.get("height");
            if(min==null||height==null||!min.isJsonPrimitive()||!height.isJsonPrimitive()||!min.getAsJsonPrimitive().isNumber()||!height.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("维度min_y/height须为整数");
            return Optional.of(new DimensionHeight(min.getAsBigDecimal().intValueExact(),height.getAsBigDecimal().intValueExact()));
        }catch(com.google.gson.JsonParseException|IllegalArgumentException|IllegalStateException|ArithmeticException invalid){throw new ResourceAccessException(INVALID_METADATA,"有效维度类型高度无效："+id,invalid);}
        finally{validate();}
    }
    @Override public synchronized Optional<RegistrationClass> clientClass(String name)throws IOException{
        check();validate();long before=client.bytesRead();Optional<RegistrationClass> result;
        try{result=client.clientClass(name);}finally{readBytes+=client.bytesRead()-before;}
        if(readBytes>ClientResourceArchive.MAX_SESSION_READ_BYTES)throw limit("组合来源读取超过 64 MiB 会话预算");
        return result;
    }
    @Override public Optional<String> platform()throws IOException {loadModMetadata();return Optional.ofNullable(loader);}
    @Override public Optional<String> platform(String modId)throws IOException {
        new ResourceLocation(modId,"check");loadModMetadata();var candidates=modPlatforms.getOrDefault(modId,Set.of());
        if(candidates.size()>1)throw new ResourceAccessException(INVALID_METADATA,"Mod拥有者存在多平台元数据，无法确定生效格式："+modId+" "+candidates.stream().sorted().toList(),ResourceAccessException.Scope.RESOURCE);
        return candidates.stream().findFirst();
    }
    private void loadModMetadata()throws IOException {
        validate();if(modIds!=null)return;Set<String> ids=new HashSet<>();Map<String,Integer> ownerCounts=new HashMap<>();Map<String,Set<String>> platforms=new HashMap<>();String selectedLoader=null;
        for(var source:registrationSources){check();source.stamp().validate();String fabric,neo,forge;
            if(source.nested().isEmpty()){var zip=handle(source);fabric=metadataZipText(zip,"fabric.mod.json");neo=metadataZipText(zip,"META-INF/neoforge.mods.toml");forge=metadataZipText(zip,"META-INF/mods.toml");}
            else{Path root=nestedAccess.root(source.stamp().path(),source.nested());fabric=metadataText(root.resolve("fabric.mod.json"));neo=metadataText(root.resolve("META-INF/neoforge.mods.toml"));forge=metadataText(root.resolve("META-INF/mods.toml"));}
            Set<String> sourceIds=new HashSet<>(BuiltinResourcePacks.owners(null,neo,forge));
            if(fabric!=null)try(var reader=new com.google.gson.stream.JsonReader(new java.io.StringReader(fabric))) {
                reader.setStrictness(com.google.gson.Strictness.LENIENT);reader.setNestingLimit(32);
                var metadata=com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                if(metadata.has("id")){String primary=metadata.get("id").getAsString();if(primary.length()<=64&&primary.matches("[a-z0-9_.-]+"))sourceIds.add(primary);}
            }catch(RuntimeException invalid){/* Unreadable primary metadata cannot confirm ownership. */}
            for(String platform:List.of("fabric","neoforge","forge"))for(String id:BuiltinResourcePacks.owners(platform.equals("fabric")?fabric:null,platform.equals("neoforge")?neo:null,platform.equals("forge")?forge:null)){
                ids.add(id);platforms.computeIfAbsent(id,ignored->new HashSet<>()).add(platform);
            }
            for(String id:sourceIds)ownerCounts.merge(id,1,Integer::sum);
            if(ids.size()>8192)throw limit("Mod身份元数据超过8192项");if(neo!=null)selectedLoader="neoforge";else if(forge!=null&&!"neoforge".equals(selectedLoader))selectedLoader="forge";else if(fabric!=null&&selectedLoader==null)selectedLoader="fabric";source.stamp().validate();
        }platforms.replaceAll((id,values)->Set.copyOf(values));modPlatforms=Map.copyOf(platforms);loader=selectedLoader;modIds=Set.copyOf(ids);modOwnerCounts=Map.copyOf(ownerCounts);
    }
    /** Host-only HUD identity: exact unique installed metadata IDs, never resource-pack or plugin origins. */
    public Set<String> confirmedModIds() throws IOException {
        try { loadModMetadata(); }
        catch(IOException invalid) {
            validate(); // Changes, closure and cancellation still invalidate the scene.
            if(invalid instanceof ResourceAccessException access && access.scope()==ResourceAccessException.Scope.SOURCE
                    && access.reason()!=ResourceAccessException.Reason.INVALID_METADATA)throw invalid;
            String warning="方块所属 Mod 元数据不可用，HUD 保留命名空间并标未确认";
            if(!warnings.contains(warning))warnings.add(warning);
            return Set.of();
        }
        return modIds.stream().filter(id -> modOwnerCounts.getOrDefault(id,0)==1 && modPlatforms.getOrDefault(id,Set.of()).size()==1)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    private Set<String> owners(Descriptor source)throws IOException {
        source.stamp().validate();String fabric,neo,forge;
        if(source.nested().isEmpty()){var zip=handle(source);fabric=metadataZipText(zip,"fabric.mod.json");neo=metadataZipText(zip,"META-INF/neoforge.mods.toml");forge=metadataZipText(zip,"META-INF/mods.toml");}
        else{Path root=nestedAccess.root(source.stamp().path(),source.nested());fabric=metadataText(root.resolve("fabric.mod.json"));neo=metadataText(root.resolve("META-INF/neoforge.mods.toml"));forge=metadataText(root.resolve("META-INF/mods.toml"));}
        source.stamp().validate();return BuiltinResourcePacks.owners(fabric,neo,forge);
    }
    private String metadataZipText(ZipFile zip,String name)throws IOException {var entry=zip.getEntry(name);if(entry==null||entry.isDirectory())return null;try(var input=zip.getInputStream(entry)){return new String(bytes(input,entry.getSize(),1024*1024),StandardCharsets.UTF_8);}}
    private String metadataText(Path file)throws IOException {if(!Files.isRegularFile(file))return null;if(Files.size(file)>1024*1024)throw new IOException("Mod元数据超过预算");try(var input=Files.newInputStream(file)){return new String(bytes(input,Files.size(file),1024*1024),StandardCharsets.UTF_8);}}
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
        for(var stamp:launchStamps.values())stamp.validate();for(var path:missingLaunchInputs)if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new ResourceAccessException(SOURCE_CHANGED,"启动协议输入已出现："+path.getFileName());
        for(var stamp:biomeInputStamps.values())stamp.validate();for(var path:missingBiomeInputs)if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new ResourceAccessException(SOURCE_CHANGED,"保存群系输入已出现："+path.getFileName());
        for(var path:handles.keySet())selectedByPath.get(path).validate();
        for(var path:nestedAccess.paths())if(!handles.containsKey(path))selectedByPath.get(path).validate();
    }
    /** Full manifest checks at request/cache boundaries, avoiding an all-Mod stat scan per model face. */
    public void validateAll()throws IOException {
        validate();for(var stamp:selectedStamps){check();stamp.validate();}
        for(var source:descriptors){check();source.stamp().validate();for(var file:source.files().values())file.validate();}
        for(var source:dataSources){check();source.stamp().validate();for(var file:source.files().values())file.validate();}
        var now=new InstanceResourceLocator().locate(world,explicit,cancel);
        if(!selection.equals(now.layers())||!selectionWarnings.equals(now.warnings()))throw new ResourceAccessException(SOURCE_CHANGED,"实例资源列表或启用顺序发生变化，请重新生成");
        if(!launchInputs.equals(now.launchInputs())||!loaderGameVersion.equals(now.loaderGameVersion())||!selectedLoaderProblem.equals(now.loaderProblem()))throw new ResourceAccessException(SOURCE_CHANGED,"加载器协议来源或启动配置发生变化");
        if(!biomeSettings.equals(BiomeColorSettings.read(instance)))throw new ResourceAccessException(SOURCE_CHANGED,"群系混合设置发生变化");
        try{
            var current=WorldDataPacks.locate(world,instance,now.layers().stream().filter(layer->layer.packId().equals("mod")).map(InstanceResourceLocator.Layer::path).distinct().toList(),cancel);
            if(dataSelection==null||!dataSelection.equals(current))throw new ResourceAccessException(SOURCE_CHANGED,"存档启用数据包发生变化");
        }catch(ResourceAccessException changed){throw changed;}
        catch(IOException invalid){if(dataProblem==null||!dataProblem.equals(invalid.getMessage()))throw new ResourceAccessException(SOURCE_CHANGED,"存档数据包来源发生变化",invalid);}
    }
    private void check(){if(cancel.isCancelled()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("取消实例资源读取");}
    private void warn(String detail){if(warnings.size()<128)warnings.add(detail);}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void update(MessageDigest digest,String text){digest.update(text.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}
    private static ResourceAccessException limit(String message){return new ResourceAccessException(LIMIT_EXCEEDED,message);}
    @Override public void close()throws IOException {
        if(closed)return;closed=true;IOException error=null;
        for(var zip:handles.values())try{zip.close();}catch(IOException failure){if(error==null)error=failure;else error.addSuppressed(failure);}
        handles.clear();jsonLists.clear();modIds=null;modOwnerCounts=Map.of();modPlatforms=Map.of();loader=null;try{nestedAccess.close();}catch(IOException failure){if(error==null)error=failure;else error.addSuppressed(failure);}try{client.close();}catch(IOException failure){if(error==null)error=failure;else error.addSuppressed(failure);}
        if(error!=null)throw error;
    }
}
