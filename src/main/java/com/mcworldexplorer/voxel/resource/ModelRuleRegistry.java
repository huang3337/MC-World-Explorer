package com.mcworldexplorer.voxel.resource;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Immutable explicit registration. Neither service discovery nor arbitrary Mod class execution occurs here. */
public final class ModelRuleRegistry {
    private final Map<ResourceLocation,ModelRule> loaders;
    private final List<ModelRule> rules;
    private final String fingerprint;
    private final Set<ModelBlockContext.Offset> neighbors;
    private final Set<String> stateSelectors;
    private final boolean positionDependent;
    public ModelRuleRegistry(Collection<? extends ModelRule> input) {this(input, "");}
    ModelRuleRegistry(Collection<? extends ModelRule> input,String pluginIdentity) {
        Objects.requireNonNull(input);if(input.size()>256)throw new IllegalArgumentException("规则数量超过256");
        Map<ResourceLocation,ModelRule> registered=new TreeMap<>(Comparator.comparing(ResourceLocation::toString));
        Set<ResourceLocation> ids=new HashSet<>();List<ModelRule> values=new ArrayList<>();
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(var rule:input) {
                Objects.requireNonNull(rule);if(rule.apiVersion()!=ModelRule.API_VERSION)throw new IllegalArgumentException("规则API版本不兼容："+rule.id());
                if(!ids.add(Objects.requireNonNull(rule.id())))throw new IllegalArgumentException("重复规则ID："+rule.id());
                if(rule.version()==null||rule.version().isBlank()||rule.version().length()>128)throw new IllegalArgumentException("规则版本无效");
                var aliases=Set.copyOf(rule.loaders());var fields=Set.copyOf(rule.fields());
                var selectors=Set.copyOf(rule.stateSelectors());var dependencies=Set.copyOf(rule.neighbors());
                if(selectors.size()>8||dependencies.size()>256)throw new IllegalArgumentException("规则上下文依赖超限");
                for(String selector:selectors)if(!selector.matches("[a-z0-9_.-]+:[a-z0-9_.-]+"))throw new IllegalArgumentException("blockstate选择字段无效");
                if(aliases.isEmpty()||aliases.size()>64||fields.size()>128)throw new IllegalArgumentException("规则loader/字段数量无效");
                for(String field:fields)if(field.isBlank()||field.length()>128)throw new IllegalArgumentException("规则字段名无效");
                // Freeze metadata so an extension cannot silently change matching after registration.
                ModelRule frozen=new Registered(rule,rule.id(),rule.version(),aliases,fields,selectors,dependencies,rule.positionDependent(),rule.biomeColorDependent(),rule.savedEntityDependent(),Objects.requireNonNull(rule.rootTransformMode()),rule.inheritRenderType());values.add(frozen);
                for(var alias:aliases)if(registered.putIfAbsent(alias,frozen)!=null)throw new IllegalArgumentException("模型loader注册冲突："+alias);
            }
            values.sort(Comparator.comparing(rule->rule.id().toString()));
            update(digest,"model-rule-api:"+ModelRule.API_VERSION+":core-074-v1");
            update(digest,"host-definition:"+ModelDefinitionProtocol.REVISION);
            update(digest,"host-block-state:"+ClientBlockSymbols.REVISION);
            if(!pluginIdentity.isEmpty())update(digest,"external-plugins:"+pluginIdentity);
            for(var rule:values) {
                update(digest,rule.id().toString());update(digest,rule.version());
                update(digest,"root-transform:"+rule.rootTransformMode());
                update(digest,"inherit-render-type:"+rule.inheritRenderType());
                section(digest,"loaders",rule.loaders().stream().map(ResourceLocation::toString).toList());
                section(digest,"fields",rule.fields());
            }
            Set<ModelBlockContext.Offset> dependencies=new HashSet<>();Set<String> selectors=new HashSet<>();
            for(var rule:values){dependencies.addAll(rule.neighbors());selectors.addAll(rule.stateSelectors());update(digest,"position:"+rule.positionDependent());update(digest,"saved-entity:"+rule.savedEntityDependent());update(digest,"biome-color:"+rule.biomeColorDependent());section(digest,"state-selectors",rule.stateSelectors());section(digest,"neighbors",rule.neighbors().stream().map(Object::toString).toList());}
            if(dependencies.size()>256)throw new IllegalArgumentException("组合规则的邻域依赖超过256项");
            neighbors=Set.copyOf(dependencies);stateSelectors=Set.copyOf(selectors);positionDependent=values.stream().anyMatch(rule->rule.positionDependent()||rule.biomeColorDependent());
            fingerprint=HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        loaders=Map.copyOf(registered);rules=List.copyOf(values);
    }
    public static ModelRuleRegistry defaults(){return BuiltinModelRules.REGISTRY;}
    public Optional<ModelRule> find(ResourceLocation loader){return Optional.ofNullable(loaders.get(loader));}
    public List<ModelRule> rules(){return rules;}
    public String fingerprint(){return fingerprint;}
    public Set<ModelBlockContext.Offset> neighbors(){return neighbors;}
    public Set<String> stateSelectors(){return stateSelectors;}
    public boolean positionDependent(){return positionDependent;}
    public ModelRuleRegistry with(ModelRule... additions){var combined=new ArrayList<>(rules);combined.addAll(List.of(additions));return new ModelRuleRegistry(combined);}
    public boolean equivalent(ResourceLocation first,ResourceLocation second){return first.equals(second)||(loaders.containsKey(first)&&loaders.get(first)==loaders.get(second));}
    private record Registered(ModelRule implementation,ResourceLocation id,String version,Set<ResourceLocation> loaders,Set<String> fields,Set<String> stateSelectors,Set<ModelBlockContext.Offset> neighbors,boolean positionDependent,boolean biomeColorDependent,boolean savedEntityDependent,RootTransformMode rootTransformMode,boolean inheritRenderType)implements ModelRule {
        public List<BlockModelResolver.Part> resolve(Context context)throws java.io.IOException,Failure{return implementation.resolve(context);}
    }
    private static void section(MessageDigest digest,String name,Collection<String> values) {
        update(digest,name);update(digest,Integer.toString(values.size()));values.stream().sorted().forEach(value->update(digest,value));
    }
    private static void update(MessageDigest digest,String text){byte[] bytes=text.getBytes(StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}
}
