package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Managed plugin library. Mutations run on a background owner while preview work is idle.
 * Prepare and persist precede replacement; old workers are stopped only after a successful swap. */
public final class ModelPluginStore implements AutoCloseable {
    public static String hostVersion()throws IOException {
        try(var stream=ModelPluginStore.class.getResourceAsStream("/mcwe-build.properties")){
            if(stream==null)throw new IOException("缺少宿主构建版本，无法校验外部插件");var properties=new Properties();properties.load(stream);
            String version=properties.getProperty("version");if(version==null||version.isBlank())throw new IOException("宿主构建版本无效");return version;
        }
    }
    public record Snapshot(ModelPluginCatalog catalog,ModelPluginCatalog.Selection selection,Set<ResourceLocation> external,List<String> notices,Map<ResourceLocation,Long> processIds,Map<ResourceLocation,String> executions) {
        public Snapshot {external=Set.copyOf(external);notices=List.copyOf(notices);processIds=Map.copyOf(processIds);executions=Map.copyOf(executions);}
    }
    private record Installed(String hash,ModelPluginManifest manifest) {
        Installed {if(hash==null||!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("插件库内容指纹无效");Objects.requireNonNull(manifest);}
    }
    private record State(Map<ResourceLocation,Installed> packages,Set<ResourceLocation> disabled,Set<String> cleanup) {
        State {packages=Map.copyOf(packages);disabled=Set.copyOf(disabled);cleanup=Set.copyOf(cleanup);if(packages.size()>128||disabled.size()>256||cleanup.size()>256)throw new IllegalArgumentException("插件库记录超过预算");if(!packages.keySet().containsAll(disabled))throw new IllegalArgumentException("停用记录包含未安装的插件");}
    }
    private final Path root,packages,index;
    private final String hostVersion;
    private final ModelPluginCatalog builtin;
    private State state;
    private volatile Map<ResourceLocation,ExternalModelPlugin> workers=Map.of();
    private Snapshot snapshot;
    private boolean closed;

    public ModelPluginStore(Path directory,String hostVersion)throws IOException {this(directory,hostVersion,ModelPluginCatalog.core());}
    ModelPluginStore(Path directory,String hostVersion,ModelPluginCatalog builtin)throws IOException {
        Path normalized=directory.toAbsolutePath().normalize();
        if(Files.isSymbolicLink(normalized))throw new IOException("插件库目录不能是符号链接");
        this.root=canonicalDirectory(normalized);this.packages=root.resolve("packages");this.index=root.resolve("plugins.json");this.hostVersion=Objects.requireNonNull(hostVersion);this.builtin=Objects.requireNonNull(builtin);
        if(Files.isSymbolicLink(packages)||(Files.exists(packages)&&!packages.toRealPath().startsWith(root)))throw new IOException("插件包目录不在受管理库内");
        state=Files.exists(index)?readState():new State(Map.of(),Set.of(),Set.of());
        for(var id:state.packages.keySet())if(builtin.ids().contains(id))throw new IOException("外部包ID与内置适配重复："+id);
        var catalog=catalog(state,Map.of());var enabled=new HashSet<>(builtin.ids());enabled.removeAll(state.disabled);
        snapshot=new Snapshot(catalog,catalog.select(enabled),state.packages.keySet(),List.of(),Map.of(),Map.of());
        // Startup is explicitly invoked on the background owner; no code runs merely from index inspection.
    }
    public synchronized Snapshot snapshot(){return snapshot;}
    /** Nonblocking health check for the FX owner; Process handles avoid PID-reuse ambiguity. */
    public boolean running(ResourceLocation id){var worker=workers.get(id);return worker!=null&&worker.running();}
    public synchronized Snapshot restore()throws IOException {
        requireOpen();var notices=new ArrayList<>(cleanup());var next=new HashMap<ResourceLocation,ExternalModelPlugin>();
        var enabled=new HashSet<>(builtin.ids());enabled.removeAll(state.disabled);
        for(var entry:state.packages.entrySet())if(!state.disabled.contains(entry.getKey())){
            try{next.put(entry.getKey(),start(entry.getKey(),entry.getValue()));enabled.add(entry.getKey());}
            catch(IOException|RuntimeException failure){notices.add(entry.getValue().manifest.plugin().name()+" 恢复失败："+failure.getMessage());}
        }
        try{
            var catalog=catalog(state,next);boolean changed;
            do{
                changed=false;
                for(var entry:catalog.entries())if(enabled.contains(entry.descriptor().id()))for(var dependency:entry.descriptor().dependencies().entrySet()){
                    var installed=catalog.entries().stream().filter(value->value.descriptor().id().equals(dependency.getKey())).findFirst();
                    if(!enabled.contains(dependency.getKey())||installed.isEmpty()||(!dependency.getValue().equals("*")&&!dependency.getValue().equals(installed.get().descriptor().version()))){
                        enabled.remove(entry.descriptor().id());notices.add(entry.descriptor().name()+" 未恢复，依赖不可用："+dependency.getKey());changed=true;break;
                    }
                }
            }while(changed);
            var selection=catalog.select(enabled);
            var previous=workers;workers=Map.copyOf(next);snapshot=new Snapshot(catalog,selection,state.packages.keySet(),notices,processIds(selection),executions(selection));
            for(var entry:previous.entrySet())if(next.get(entry.getKey())!=entry.getValue())entry.getValue().close();
            for(var entry:next.entrySet())if(!enabled.contains(entry.getKey()))entry.getValue().close();
            // Preserve desired preferences on disk; an unavailable package is not silently disabled permanently.
            return snapshot;
        }catch(RuntimeException failure){
            for(var worker:next.values())if(!workers.containsValue(worker))worker.close();
            notices.add("恢复集合存在冲突，已保留当前规则及保存配置："+failure.getMessage());snapshot=new Snapshot(snapshot.catalog(),snapshot.selection(),state.packages.keySet(),notices,snapshot.processIds(),snapshot.executions());return snapshot;
        }
    }
    public synchronized Snapshot apply(Set<ResourceLocation> enabled)throws IOException {
        requireOpen();var known=catalog(state,workers).ids();if(!known.containsAll(enabled))throw new IllegalArgumentException("启用集合含未登记插件");
        var disabled=new HashSet<>(state.disabled);for(var id:known)if(enabled.contains(id))disabled.remove(id);else disabled.add(id);
        return replace(new State(state.packages,disabled,state.cleanup),true);
    }
    /** Called only after the UI has shown the inspected identity and received explicit trust for this package. */
    public synchronized Snapshot install(ExternalModelPluginPackage approved,boolean update)throws IOException {
        requireOpen();approved.validate();approved.manifest().requireHost(hostVersion);var id=approved.manifest().plugin().id();
        if(builtin.ids().contains(id))throw new IOException("外部插件不能覆盖内置ID："+id);
        var previous=state.packages.get(id);if(update!=(previous!=null))throw new IOException(update?"要更新的插件尚未安装":"插件已存在，请使用更新操作");
        var copied=copy(approved);var all=new HashMap<>(state.packages);all.put(id,copied);
        var disabled=new HashSet<>(state.disabled);if(previous==null)disabled.add(id);
        var cleanup=new HashSet<>(state.cleanup);cleanup.remove(copied.hash);if(previous!=null&&!previous.hash.equals(copied.hash))cleanup.add(previous.hash);
        try{return replace(new State(all,disabled,cleanup),true);}
        catch(IOException|RuntimeException failure){
            // Only remove the newly copied content if no live/persisted entry owns it.
            if(state.packages.values().stream().noneMatch(value->value.hash.equals(copied.hash)))try{Files.deleteIfExists(owned(copied.hash));}catch(IOException cleanupFailure){failure.addSuppressed(cleanupFailure);}
            try{cleanup();}catch(IOException cleanupFailure){failure.addSuppressed(cleanupFailure);}
            throw failure;
        }
    }
    public synchronized Snapshot uninstall(ResourceLocation id)throws IOException {
        requireOpen();var previous=state.packages.get(id);if(previous==null)throw new IOException("未安装此外部插件："+id);
        var all=new HashMap<>(state.packages);all.remove(id);var disabled=new HashSet<>(state.disabled);disabled.remove(id);
        var cleanup=new HashSet<>(state.cleanup);cleanup.add(previous.hash);return replace(new State(all,disabled,cleanup),true);
    }
    public synchronized Snapshot retryCleanup()throws IOException {requireOpen();snapshot=withNotices(cleanup());return snapshot;}
    private Snapshot replace(State candidate,boolean persist)throws IOException {
        var next=new HashMap<ResourceLocation,ExternalModelPlugin>();var started=new ArrayList<ExternalModelPlugin>();
        try{
            for(var entry:candidate.packages.entrySet())if(!candidate.disabled.contains(entry.getKey())){
                var worker=start(entry.getKey(),entry.getValue());if(workers.get(entry.getKey())!=worker)started.add(worker);next.put(entry.getKey(),worker);
            }
            var catalog=catalog(candidate,next);var enabled=new HashSet<>(catalog.ids());enabled.removeAll(candidate.disabled);
            var selection=catalog.select(enabled);if(persist)writeState(candidate);
            var previous=workers;workers=Map.copyOf(next);state=candidate;snapshot=new Snapshot(catalog,selection,candidate.packages.keySet(),List.of(),processIds(selection),executions(selection));
            for(var entry:previous.entrySet())if(next.get(entry.getKey())!=entry.getValue())entry.getValue().close();
            try{snapshot=withNotices(cleanup());}catch(IOException cleanupFailure){snapshot=withNotices(List.of("插件变更已应用，清理待重试："+cleanupFailure.getMessage()));}return snapshot;
        }catch(IOException|RuntimeException failure){for(var worker:started)if(!workers.containsValue(worker))worker.close();throw failure;}
    }
    private ExternalModelPlugin start(ResourceLocation id,Installed installed)throws IOException {
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("插件库操作已取消");
        var running=workers.get(id);if(running!=null&&running.running()&&running.contentFingerprint().equals(installed.hash))return running;
        var receipt=ExternalModelPluginPackage.inspect(owned(installed.hash),hostVersion);
        if(!receipt.sha256().equals(installed.hash)||!receipt.manifest().equals(installed.manifest))throw new IOException("插件库文件身份与索引不符："+id);
        return new ExternalModelPlugin(receipt,hostVersion,Duration.ofSeconds(30));
    }
    private ModelPluginCatalog catalog(State data,Map<ResourceLocation,ExternalModelPlugin> running){
        var plugins=new ArrayList<ModelPlugin>();
        for(var entry:builtin.entries())plugins.add(new ModelPlugin(){public Descriptor descriptor(){return entry.descriptor();}public String contentFingerprint(){return entry.contentFingerprint();}public Collection<? extends ModelRule> rules(){return entry.rules();}});
        for(var entry:data.packages.entrySet()){
            var worker=running.get(entry.getKey());if(worker!=null){plugins.add(worker);continue;}
            var installed=entry.getValue();plugins.add(new ModelPlugin(){public Descriptor descriptor(){return installed.manifest.plugin();}public String contentFingerprint(){return installed.hash;}public Collection<? extends ModelRule> rules(){return List.of();}});
        }
        return new ModelPluginCatalog(builtin.coreRules(),plugins);
    }
    private Installed copy(ExternalModelPluginPackage approved)throws IOException {
        ensureDirectories();Path target=owned(approved.sha256());
        if(Files.exists(target)){
            var existing=ExternalModelPluginPackage.inspect(target,hostVersion);if(!existing.sha256().equals(approved.sha256()))throw new IOException("插件库目标文件被替换");
        }else{
            Path temporary=Files.createTempFile(packages,".install-",".tmp");
            try{
                Files.copy(approved.file(),temporary,StandardCopyOption.REPLACE_EXISTING);
                var copied=ExternalModelPluginPackage.inspect(temporary,hostVersion);if(!copied.sha256().equals(approved.sha256()))throw new IOException("复制期间插件包已改变");
                move(temporary,target);
            }finally{Files.deleteIfExists(temporary);}
        }
        return new Installed(approved.sha256(),approved.manifest());
    }
    private Path owned(String hash)throws IOException {
        if(hash==null||!hash.matches("[a-f0-9]{64}"))throw new IOException("插件库文件标识无效");
        Path path=packages.resolve(hash+".jar").normalize();
        if(!path.getParent().equals(packages)||Files.isSymbolicLink(path)||Files.isSymbolicLink(packages)||(Files.exists(packages)&&!packages.toRealPath().startsWith(root)))throw new IOException("插件文件不在受管理目录内");return path;
    }
    private List<String> cleanup()throws IOException {
        validateRoot();
        var pending=new HashSet<>(state.cleanup);var notices=new ArrayList<String>();
        // Recover only our exact managed names after an interrupted transaction.
        for(Path directory:List.of(root,packages))if(Files.isDirectory(directory)){
            if(Files.isSymbolicLink(directory)||!directory.toRealPath().startsWith(root))throw new IOException("插件清理目录已改变");
            try(var paths=Files.newDirectoryStream(directory)){
                int count=0;for(Path path:paths){
                    if(++count>4096)throw new IOException("插件清理目录条目超过预算");
                    String name=path.getFileName().toString();
                    if(directory.equals(packages)&&name.matches("[a-f0-9]{64}\\.jar")){
                        String hash=name.substring(0,64);
                        if(state.packages.values().stream().noneMatch(value->value.hash.equals(hash)))pending.add(hash);
                    }else if(name.matches(directory.equals(packages)?"\\.install-[0-9]+\\.tmp":"\\.plugins-[0-9]+\\.tmp")){
                        try{if(Files.isSymbolicLink(path))throw new IOException("临时文件不是受管理普通文件");Files.deleteIfExists(path);}
                        catch(IOException failure){notices.add("插件临时文件待清理："+name+"；"+failure.getMessage());}
                    }
                }
            }
        }
        for(String hash:Set.copyOf(pending)){
            if(state.packages.values().stream().anyMatch(value->value.hash.equals(hash))){pending.remove(hash);continue;}
            try{Files.deleteIfExists(owned(hash));pending.remove(hash);}catch(IOException failure){notices.add("旧插件包待清理："+hash+"；"+failure.getMessage());}
        }
        if(!pending.equals(state.cleanup)){
            var cleaned=new State(state.packages,state.disabled,pending);
            try{writeState(cleaned);state=cleaned;}catch(IOException failure){notices.add("清理记录保存失败，将在下次重试："+failure.getMessage());}
        }
        if(state.packages.isEmpty()&&state.disabled.isEmpty()&&state.cleanup.isEmpty())pruneEmptyLibrary();
        return List.copyOf(notices);
    }
    private Snapshot withNotices(List<String> notices){
        return new Snapshot(snapshot.catalog(),snapshot.selection(),state.packages.keySet(),notices,snapshot.processIds(),snapshot.executions());
    }
    private Map<ResourceLocation,Long> processIds(ModelPluginCatalog.Selection selection){
        var ids=new HashMap<ResourceLocation,Long>();for(var entry:workers.entrySet())if(selection.enabled().contains(entry.getKey()))ids.put(entry.getKey(),entry.getValue().processId());return ids;
    }
    private Map<ResourceLocation,String> executions(ModelPluginCatalog.Selection selection){
        var ids=new HashMap<ResourceLocation,String>();for(var entry:workers.entrySet())if(selection.enabled().contains(entry.getKey()))ids.put(entry.getKey(),entry.getValue().executionIdentity());return ids;
    }
    private State readState()throws IOException {
        validateRoot();
        if(Files.isSymbolicLink(index)||Files.size(index)>4L*1024*1024)throw new IOException("插件库索引无效或超过4MiB");
        byte[] bytes;try(var stream=Files.newInputStream(index)){bytes=stream.readNBytes(4*1024*1024+1);}
        if(bytes.length>4*1024*1024)throw new IOException("插件库索引超过4MiB");
        try(var reader=new JsonReader(new StringReader(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()))){
            reader.setStrictness(Strictness.STRICT);var seen=new HashSet<String>();var installed=new HashMap<ResourceLocation,Installed>();var disabled=new HashSet<ResourceLocation>();var cleanup=new HashSet<String>();
            reader.beginObject();while(reader.hasNext()){
                String key=reader.nextName();if(!seen.add(key))throw new IOException("插件索引重复字段");
                switch(key){
                    case "format" -> {if(reader.peek()!=JsonToken.NUMBER||!reader.nextString().equals("1"))throw new IOException("插件索引版本无效");}
                    case "disabled" -> {reader.beginArray();while(reader.hasNext()){if(disabled.size()>=256||!disabled.add(ResourceLocation.parse(string(reader))))throw new IOException("插件停用记录重复或超限");}reader.endArray();}
                    case "cleanup" -> {reader.beginArray();while(reader.hasNext()){String hash=string(reader);if(!hash.matches("[a-f0-9]{64}")||cleanup.size()>=256||!cleanup.add(hash))throw new IOException("插件清理记录无效");}reader.endArray();}
                    case "packages" -> {reader.beginArray();while(reader.hasNext()){
                        if(installed.size()>=128)throw new IOException("外部插件数量超过128项");reader.beginObject();var fields=new HashMap<String,String>();
                        while(reader.hasNext()){String field=reader.nextName();if(!Set.of("sha256","manifest").contains(field)||fields.putIfAbsent(field,string(reader))!=null)throw new IOException("插件包索引字段无效");}reader.endObject();
                        if(fields.size()!=2)throw new IOException("插件包索引字段缺失");var value=new Installed(fields.get("sha256"),ModelPluginManifest.parse(fields.get("manifest")));
                        if(installed.putIfAbsent(value.manifest.plugin().id(),value)!=null)throw new IOException("插件索引ID重复");
                    }reader.endArray();}
                    default -> throw new IOException("插件索引未知字段："+key);
                }
            }reader.endObject();if(!seen.equals(Set.of("format","disabled","cleanup","packages"))||reader.peek()!=JsonToken.END_DOCUMENT)throw new IOException("插件索引字段缺失或含尾随内容");return new State(installed,disabled,cleanup);
        }catch(IllegalArgumentException|IllegalStateException invalid){throw new IOException("插件索引无效，原文件已保留："+invalid.getMessage(),invalid);}
    }
    private static String string(JsonReader reader)throws IOException {if(reader.peek()!=JsonToken.STRING)throw new IOException("插件索引字段须为字符串");return reader.nextString();}
    private void writeState(State value)throws IOException {
        validateRoot();
        if(value.packages.isEmpty()&&value.disabled.isEmpty()&&value.cleanup.isEmpty()){
            if(Files.isSymbolicLink(index))throw new IOException("插件索引不能是符号链接");
            Files.deleteIfExists(index);pruneEmptyLibrary();return;
        }
        ensureDirectories();
        if(Files.isSymbolicLink(index))throw new IOException("插件索引不能是符号链接");
        var data=new JsonObject();data.addProperty("format",1);var disabled=new JsonArray();value.disabled.stream().map(Object::toString).sorted().forEach(disabled::add);data.add("disabled",disabled);
        var cleanup=new JsonArray();value.cleanup.stream().sorted().forEach(cleanup::add);data.add("cleanup",cleanup);var installed=new JsonArray();
        value.packages.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(Object::toString))).forEach(entry->{var item=new JsonObject();item.addProperty("sha256",entry.getValue().hash);item.addProperty("manifest",entry.getValue().manifest.json());installed.add(item);});data.add("packages",installed);
        byte[] bytes=new GsonBuilder().setPrettyPrinting().create().toJson(data).getBytes(StandardCharsets.UTF_8);if(bytes.length>4*1024*1024)throw new IOException("插件索引超过4MiB");
        Path temporary=Files.createTempFile(root,".plugins-",".tmp");try{Files.write(temporary,bytes);move(temporary,index);}finally{Files.deleteIfExists(temporary);}
    }
    private void validateRoot()throws IOException {if(Files.isSymbolicLink(root)||(Files.exists(root)&&!root.toRealPath().equals(root)))throw new IOException("插件库根目录已改变");}
    private static Path canonicalDirectory(Path directory)throws IOException {
        // Resolve Windows short parent names without creating an unused library.
        var missing=new ArrayDeque<Path>();Path existing=directory;
        while(!Files.exists(existing)){missing.addFirst(existing.getFileName());existing=existing.getParent();if(existing==null)throw new IOException("插件库没有有效父目录");}
        Path canonical=existing.toRealPath();for(Path part:missing)canonical=canonical.resolve(part);return canonical;
    }
    private void ensureDirectories()throws IOException {
        validateRoot();Files.createDirectories(root);validateRoot();
        Files.createDirectories(packages);
        if(Files.isSymbolicLink(packages)||!packages.toRealPath().getParent().equals(root))throw new IOException("插件包目录已改变");
    }
    private void pruneEmptyLibrary()throws IOException {
        for(Path directory:List.of(packages,root)){
            if(Files.isSymbolicLink(directory))throw new IOException("插件库目录已改变");
            try{Files.deleteIfExists(directory);}catch(DirectoryNotEmptyException retained){/* Unrelated files belong to the user. */}
        }
    }
    private static void move(Path source,Path target)throws IOException {
        try{Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException ignored){Files.move(source,target,StandardCopyOption.REPLACE_EXISTING);}
    }
    private void requireOpen(){if(closed)throw new IllegalStateException("插件库已关闭");}
    @Override public synchronized void close(){if(closed)return;closed=true;workers.values().forEach(ExternalModelPlugin::close);workers=Map.of();}
}
