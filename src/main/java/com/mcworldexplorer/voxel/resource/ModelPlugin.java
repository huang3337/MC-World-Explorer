package com.mcworldexplorer.voxel.resource;

import java.util.*;

/** Explicit, trusted adapter package. This contract does not discover or execute Minecraft Mod archives. */
public interface ModelPlugin {
    int API_VERSION=1;
    enum Capability { SAVED_DATA, NEIGHBORS, BIOME_COLOR }
    record Descriptor(ResourceLocation id,String name,String version,int apiVersion,String description,
                      Set<String> targetMods,Map<ResourceLocation,String> dependencies,Set<Capability> capabilities) {
        public Descriptor {
            Objects.requireNonNull(id);text(name,80,"插件名称");text(version,128,"插件版本");text(description,600,"插件说明");
            targetMods=Set.copyOf(targetMods);dependencies=Map.copyOf(dependencies);capabilities=Set.copyOf(capabilities);
            if(targetMods.size()>32||dependencies.size()>32)throw new IllegalArgumentException("插件目标或依赖超过32项");
            for(String mod:targetMods)if(!mod.matches("[a-z0-9_.-]{1,64}"))throw new IllegalArgumentException("插件目标Mod标识无效");
            for(var entry:dependencies.entrySet()){if(entry.getKey().equals(id))throw new IllegalArgumentException("插件不能依赖自身");text(entry.getValue(),128,"依赖版本");}
        }
        private static void text(String value,int limit,String name){if(value==null||value.isBlank()||value.length()>limit||value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException(name+"无效或超限");}
    }
    Descriptor descriptor();
    /** Supplied by the lifecycle owner for external packages, never inferred from a displayed version. */
    default String contentFingerprint(){return "";}
    Collection<? extends ModelRule> rules();
}
