package com.mcworldexplorer.voxel.resource;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Immutable package catalog and activation snapshots. Lifecycle owners replace snapshots at an idle boundary.
 * Removing a package removes its rules/providers from the new snapshot; external code unloading is not implied. */
public final class ModelPluginCatalog {
    public record Entry(ModelPlugin.Descriptor descriptor,List<ModelRule> rules,String contentFingerprint) {
        public Entry {Objects.requireNonNull(descriptor);rules=List.copyOf(rules);}
    }
    public record Selection(Set<ResourceLocation> enabled,ModelRuleRegistry rules) {
        public Selection {enabled=Set.copyOf(enabled);Objects.requireNonNull(rules);}
    }
    private final List<ModelRule> core;
    private final Map<ResourceLocation,Entry> entries;
    public ModelPluginCatalog(Collection<? extends ModelRule> core,Collection<? extends ModelPlugin> plugins){
        this.core=new ModelRuleRegistry(core).rules();if(plugins.size()>128)throw new IllegalArgumentException("插件目录超过128项");
        Map<ResourceLocation,Entry> entries=new TreeMap<>(Comparator.comparing(ResourceLocation::toString));
        for(var plugin:plugins){
            var descriptor=Objects.requireNonNull(plugin.descriptor());
            if(descriptor.apiVersion()!=ModelPlugin.API_VERSION)throw new IllegalArgumentException("插件API不兼容："+descriptor.id());
            String content=Objects.requireNonNull(plugin.contentFingerprint());if(!content.isEmpty()&&!content.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("插件内容指纹无效");
            var entry=new Entry(descriptor,new ModelRuleRegistry(plugin.rules()).rules(),content);
            if(entries.putIfAbsent(descriptor.id(),entry)!=null)throw new IllegalArgumentException("插件ID重复："+descriptor.id());
        }
        this.entries=Collections.unmodifiableMap(entries);
    }
    public static ModelPluginCatalog core(){return new ModelPluginCatalog(ModelRuleRegistry.defaults().rules(),List.of());}
    public List<Entry> entries(){return List.copyOf(entries.values());}
    public Set<ResourceLocation> ids(){return entries.keySet();}
    public List<ModelRule> coreRules(){return core;}
    /** Explicit registration by a trusted lifecycle owner, not filesystem/plugin auto-discovery. */
    public ModelPluginCatalog with(ModelPlugin plugin){var all=asPlugins();all.add(plugin);return new ModelPluginCatalog(core,all);}
    public ModelPluginCatalog without(ResourceLocation id){
        if(!entries.containsKey(id))throw new IllegalArgumentException("插件未登记："+id);
        var all=asPlugins();all.removeIf(plugin->plugin.descriptor().id().equals(id));return new ModelPluginCatalog(core,all);
    }
    public Selection select(Set<ResourceLocation> enabled){
        enabled=Set.copyOf(enabled);if(!ids().containsAll(enabled))throw new IllegalArgumentException("启用列表包含未登记插件");
        Set<ResourceLocation> visiting=new HashSet<>(),complete=new HashSet<>();
        for(var id:enabled)validateDependencies(id,enabled,visiting,complete);
        var rules=new ArrayList<ModelRule>(core);StringBuilder identity=new StringBuilder("model-plugin-api:1");
        for(var entry:entries.values())if(enabled.contains(entry.descriptor().id())){
            var d=entry.descriptor();identity.append('\0').append(d.id()).append('\0').append(d.version());
            if(!entry.contentFingerprint().isEmpty())identity.append('\0').append("content:").append(entry.contentFingerprint());
            d.dependencies().entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString))).forEach(e->identity.append('\0').append(e.getKey()).append('=').append(e.getValue()));
            d.capabilities().stream().sorted().forEach(c->identity.append('\0').append(c));rules.addAll(entry.rules());
        }
        if(enabled.isEmpty())return new Selection(enabled,new ModelRuleRegistry(core));
        try{return new Selection(enabled,new ModelRuleRegistry(rules,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.toString().getBytes(StandardCharsets.UTF_8)))));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private void validateDependencies(ResourceLocation id,Set<ResourceLocation> enabled,Set<ResourceLocation> visiting,Set<ResourceLocation> complete){
        if(complete.contains(id))return;if(!visiting.add(id))throw new IllegalArgumentException("插件依赖循环："+id);
        for(var requirement:entries.get(id).descriptor().dependencies().entrySet()){
            var dependency=entries.get(requirement.getKey());
            if(dependency==null||!enabled.contains(requirement.getKey()))throw new IllegalArgumentException(entries.get(id).descriptor().name()+" 需要启用依赖："+requirement.getKey());
            if(!requirement.getValue().equals("*")&&!requirement.getValue().equals(dependency.descriptor().version()))throw new IllegalArgumentException("插件依赖版本不匹配："+requirement.getKey());
            validateDependencies(requirement.getKey(),enabled,visiting,complete);
        }
        visiting.remove(id);complete.add(id);
    }
    private ArrayList<ModelPlugin> asPlugins(){var result=new ArrayList<ModelPlugin>();for(var entry:entries.values())result.add(new ModelPlugin(){public Descriptor descriptor(){return entry.descriptor();}public String contentFingerprint(){return entry.contentFingerprint();}public Collection<? extends ModelRule> rules(){return entry.rules();}});return result;}
}
