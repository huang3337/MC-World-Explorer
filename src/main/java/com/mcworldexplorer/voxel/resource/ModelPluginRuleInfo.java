package com.mcworldexplorer.voxel.resource;

import java.util.*;

/** Frozen rule metadata shared only between matching host and worker builds. */
record ModelPluginRuleInfo(ResourceLocation id,String version,int apiVersion,Set<ResourceLocation> loaders,Set<String> fields,
                           Set<String> stateSelectors,Set<ModelBlockContext.Offset> neighbors,
                           boolean positionDependent,boolean biomeColorDependent,boolean savedEntityDependent,
                           ModelRule.RootTransformMode rootTransformMode,boolean inheritRenderType)implements ModelRule {
    ModelPluginRuleInfo {loaders=Set.copyOf(loaders);fields=Set.copyOf(fields);stateSelectors=Set.copyOf(stateSelectors);neighbors=Set.copyOf(neighbors);}
    static ModelPluginRuleInfo of(ModelRule rule){return new ModelPluginRuleInfo(rule.id(),rule.version(),rule.apiVersion(),rule.loaders(),rule.fields(),rule.stateSelectors(),rule.neighbors(),rule.positionDependent(),rule.biomeColorDependent(),rule.savedEntityDependent(),rule.rootTransformMode(),rule.inheritRenderType());}
    public List<BlockModelResolver.Part> resolve(Context context)throws Failure{throw new Failure(BlockModelResolver.Reason.UNSUPPORTED_MODEL,"仅有插件元信息，未建立执行连接");}
}
