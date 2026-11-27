package com.mcworldexplorer.viewer;

import com.google.gson.GsonBuilder;
import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.mesh.MeshWarning;
import com.mcworldexplorer.voxel.resource.BlockResourceDiagnostic;
import com.mcworldexplorer.voxel.resource.ModelPluginManifest;
import com.mcworldexplorer.voxel.resource.ModelPluginStore;
import java.io.IOException;
import java.util.*;

/** A bounded report of one generated selection, not an inventory of whole-Mod support. */
public final class ResourceDiagnosticReport {
    public static final int MAX_ENTRIES=4096,MAX_CONTEXT_DETAILS=128,MAX_CHUNKS_PER_ENTRY=128;
    public enum Severity {
        FAILURE("解析失败"),DEGRADED("显示降级"),INFO("解析说明");
        private final String label;
        Severity(String label){this.label=label;}
        public String label(){return label;}
    }
    public record Asset(String kind,String id,String origin) { }
    public record Entry(String namespace,String ownerLabel,boolean ownerConfirmed,String blockId,Map<String,String> state,
                        Severity severity,String reasonCode,String detail,long occurrences,List<String> loaders,
                        List<Asset> assets,List<ChunkCoordinate> chunks,boolean truncated) {
        public Entry {
            state=Collections.unmodifiableMap(new TreeMap<>(state));loaders=List.copyOf(loaders);assets=List.copyOf(assets);chunks=List.copyOf(chunks);
        }
    }
    public record Group(String namespace,String ownerLabel,boolean ownerConfirmed,long failedOccurrences,
                        long degradedOccurrences,long infoOccurrences,int issueCount) { }
    private record Key(BlockState state,Severity severity,String reason,String detail,List<String> loaders,List<Asset> assets) { }
    private static final class Count {
        long occurrences;boolean truncated;
        final Set<ChunkCoordinate> chunks=new TreeSet<>();
        void add(MeshWarning warning){
            occurrences=Math.addExact(occurrences,warning.occurrences());
            if(chunks.size()<MAX_CHUNKS_PER_ENTRY||chunks.contains(warning.coordinate()))chunks.add(warning.coordinate());else truncated=true;
            truncated|=warning.diagnostic().orElseThrow().truncated();
        }
    }
    private final int schemaVersion=1;
    private final String hostVersion,pluginApiRevision=ModelPluginManifest.SDK_REVISION,scope,summary;
    private final boolean generated,truncated,resourceModelsPrepared;
    private final List<Group> groups;
    private final List<Entry> entries;
    private final List<String> contextDetails;
    private final long omittedWarningCount,omittedOccurrences,omittedContextDetails;

    private ResourceDiagnosticReport(boolean generated,String scope,String summary,List<Group> groups,List<Entry> entries,
                                     List<String> contextDetails,boolean resourceModelsPrepared,boolean truncated,long omittedWarningCount,long omittedOccurrences,long omittedContextDetails){
        this.generated=generated;this.scope=scope;this.summary=summary;this.groups=List.copyOf(groups);this.entries=List.copyOf(entries);
        this.contextDetails=List.copyOf(contextDetails);this.resourceModelsPrepared=resourceModelsPrepared;this.truncated=truncated;
        this.omittedWarningCount=omittedWarningCount;this.omittedOccurrences=omittedOccurrences;this.omittedContextDetails=omittedContextDetails;
        String version;try{version=ModelPluginStore.hostVersion();}catch(IOException unavailable){version="";}hostVersion=version;
    }
    public static ResourceDiagnosticReport empty(){return empty("尚未生成三维预览；生成后可查看当前选区的解析诊断。");}
    public static ResourceDiagnosticReport empty(String explanation){
        return new ResourceDiagnosticReport(false,"",Objects.requireNonNull(explanation),List.of(),List.of(),List.of(),false,false,0,0,0);
    }
    public static ResourceDiagnosticReport from(ViewerTaskResult result){
        Objects.requireNonNull(result);var request=result.request();var scene=result.snapshot();
        String scope=request.worldName()+" · "+request.dimensionName()+" ["+request.dimensionId()+"] · 区块 X="+request.target().minX()+".."+request.target().maxX()+", Z="+request.target().minZ()+".."+request.target().maxZ()+" · "+request.world();
        var counts=new LinkedHashMap<Key,Count>();long omittedWarnings=0,omittedOccurrences=0;
        // Scene warnings merge counts for the HUD; chunk warnings retain exact sample coordinates.
        var evidenceWarnings=scene.chunks().isEmpty()?scene.warnings():scene.chunks().stream().flatMap(chunk->chunk.warnings().stream()).toList();
        // Keep failure evidence before informational approximation notes when the report budget is reached.
        for(var severity:Severity.values())for(var warning:evidenceWarnings){
            var optional=warning.diagnostic();if(optional.isEmpty())continue;var evidence=optional.orElseThrow();
            if(!evidence.severity().name().equals(severity.name()))continue;
            var assets=evidence.assets().stream().map(a->new Asset(a.kind(),a.id(),a.origin())).toList();
            var key=new Key(evidence.state(),severity,evidence.reasonCode(),evidence.detail(),evidence.loaders(),assets);
            var count=counts.get(key);
            if(count==null){
                if(counts.size()>=MAX_ENTRIES){omittedWarnings++;omittedOccurrences+=warning.occurrences();continue;}
                count=new Count();counts.put(key,count);
            }
            count.add(warning);
        }
        var entries=new ArrayList<Entry>();
        for(var item:counts.entrySet()){
            var key=item.getKey();var count=item.getValue();String id=key.state().name();int colon=id.indexOf(':');
            String namespace=colon>0?id.substring(0,colon):"未知";
            boolean confirmed=namespace.equals("minecraft")||scene.confirmedModIds().contains(namespace);
            String owner=namespace.equals("minecraft")?"Minecraft（原版）":confirmed?namespace+"（Mod ID）":"未确认（命名空间 "+namespace+"）";
            entries.add(new Entry(namespace,owner,confirmed,id,key.state().properties(),key.severity(),key.reason(),key.detail(),count.occurrences,key.loaders(),key.assets(),List.copyOf(count.chunks),count.truncated));
        }
        entries.sort(Comparator.comparing(Entry::severity).thenComparing(Comparator.comparingLong(Entry::occurrences).reversed()).thenComparing(Entry::blockId).thenComparing(e->e.state().toString()).thenComparing(Entry::reasonCode));
        var perNamespace=new TreeMap<String,List<Entry>>();for(var entry:entries)perNamespace.computeIfAbsent(entry.namespace(),ignored->new ArrayList<>()).add(entry);
        var groups=new ArrayList<Group>();
        for(var list:perNamespace.values()){
            var first=list.getFirst();groups.add(new Group(first.namespace(),first.ownerLabel(),first.ownerConfirmed(),occurrences(list,Severity.FAILURE),occurrences(list,Severity.DEGRADED),occurrences(list,Severity.INFO),list.size()));
        }
        groups.sort(Comparator.comparingLong(Group::failedOccurrences).reversed().thenComparing(Comparator.comparingLong(Group::degradedOccurrences).reversed()).thenComparing(Group::namespace));
        var context=new ArrayList<String>();long omittedContext=0;
        for(var warning:scene.warnings())if(warning.diagnostic().isEmpty()&&warning.code()!=MeshWarning.Code.FALLBACK_BLOCK){
            if(context.size()<MAX_CONTEXT_DETAILS)context.add(warning.code()+" · "+warning.detail());else omittedContext++;
        }
        if(result.failedChunks()>0)context.add("当前选区有 "+result.failedChunks()+" 个区块读取失败；未读取内容不计为解析通过。");
        if(scene.atlas().isEmpty())context.add("本次没有准备模型纹理资源，简化颜色不是 Mod 解析通过的证据。");
        context.add("仅统计当前加载选区；次数为问题出现次数，同一方块可有多项诊断。Mod 归属与资源来源分别记录，不代表整个 Mod 的支持范围。");
        boolean truncated=omittedWarnings>0||omittedContext>0||entries.stream().anyMatch(Entry::truncated);
        if(truncated)context.add("报告存在截断：省略 "+omittedWarnings+" 条方块警告记录（"+omittedOccurrences+" 次）、"+omittedContext+" 条环境说明；资源引用、坐标或详情也可能达到展示预算。各分组仅为已保留证据。");
        long failures=occurrences(entries,Severity.FAILURE),degraded=occurrences(entries,Severity.DEGRADED),info=occurrences(entries,Severity.INFO);
        String summary=entries.isEmpty()?(scene.atlas().isEmpty()?"未使用模型纹理资源，不能判断 Mod 模型是否解析成功。":"当前选区未记录解析问题；这不代表整个 Mod 均已支持。")
            :"当前选区 · "+groups.size()+" 个 Mod/命名空间分组 · 解析失败 "+failures+" 次 · 显示降级 "+degraded+" 次 · 解析说明 "+info+" 次";
        if(truncated)summary+=" · 记录已截断，统计不是全量";
        return new ResourceDiagnosticReport(true,scope,summary,groups,entries,context,scene.atlas().isPresent(),truncated,omittedWarnings,omittedOccurrences,omittedContext);
    }
    private static long occurrences(List<Entry> entries,Severity severity){return entries.stream().filter(e->e.severity()==severity).mapToLong(Entry::occurrences).sum();}
    public boolean generated(){return generated;}
    public String summary(){return summary;}
    public String scope(){return scope;}
    public List<Group> groups(){return groups;}
    public List<Entry> entries(){return entries;}
    public List<String> contextDetails(){return contextDetails;}
    public boolean truncated(){return truncated;}
    public long omittedWarningCount(){return omittedWarningCount;}
    public long omittedOccurrences(){return omittedOccurrences;}
    public long omittedContextDetails(){return omittedContextDetails;}
    public String toJson(){return new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(this);}
    public String toText(){
        var text=new StringBuilder("MC World Explorer 资源诊断\n").append(summary).append('\n');
        if(!scope.isBlank())text.append("范围：").append(scope).append('\n');
        for(var group:groups)text.append("\n").append(group.ownerLabel()).append("：解析失败 ").append(group.failedOccurrences()).append(" 次，显示降级 ").append(group.degradedOccurrences()).append(" 次，解析说明 ").append(group.infoOccurrences()).append(" 次\n");
        for(var entry:entries){
            text.append("\n[").append(entry.severity().label()).append("] ").append(entry.blockId()).append(' ').append(entry.state()).append(" · ").append(entry.occurrences()).append(" 次\n")
                .append("所属：").append(entry.ownerLabel()).append("\n原因：").append(entry.reasonCode()).append("\n").append(entry.detail()).append('\n');
            if(!entry.loaders().isEmpty())text.append("Loader：").append(String.join(", ",entry.loaders())).append('\n');
            for(var asset:entry.assets())text.append("资源 ").append(asset.kind()).append(' ').append(asset.id()).append(" ← ").append(asset.origin()).append('\n');
            text.append("区块：").append(entry.chunks()).append(entry.truncated()?"（该条目有截断）":"").append('\n');
        }
        text.append("\n范围与来源说明\n");contextDetails.forEach(line->text.append(line).append('\n'));return text.toString();
    }
}
