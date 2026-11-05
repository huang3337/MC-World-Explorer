package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.io.*;
import java.io.IOException;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;

/** Deduplicates colour variants, with a bounded sparse section index (no per-position model copies). */
final class BlockTintPreparation {
    private static final int MAX_SECTIONS=2048; // at most 16 MiB of position indices
    private record Section(long x,int y,long z) { }
    private final Map<Section,short[]> sections=new HashMap<>();
    private final List<BlockState> variants=new ArrayList<>();
    private final Map<BlockState,Integer> ids=new HashMap<>();
    private final Set<String> names=new HashSet<>();
    BlockState state(BlockState original,long x,int y,long z) {
        if(!names.contains(original.name()))return original;
        var values=sections.get(new Section(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16)));
        if(values==null)return original;
        int index=Short.toUnsignedInt(values[(Math.floorMod(y,16)*16+Math.floorMod(z,16))*16+(int)Math.floorMod(x,16)]);
        if(index==0)return original;
        var variant=variants.get(index-1);
        return variant.name().equals(original.name())&&variant.properties().size()==original.properties().size()+1
                &&variant.properties().entrySet().containsAll(original.properties().entrySet())?variant:original;
    }
    private boolean store(long x,int y,long z,BlockState state) {
        var section=new Section(Math.floorDiv(x,16),Math.floorDiv(y,16),Math.floorDiv(z,16));
        if(!sections.containsKey(section)&&sections.size()>=MAX_SECTIONS)return false;
        names.add(state.name());
        int id=ids.computeIfAbsent(state,key->{variants.add(key);return variants.size();});
        if(id>BlockTextureSet.MAX_STATES)throw new IllegalStateException("颜色变体超出准备上限");
        sections.computeIfAbsent(section,key->new short[4096])[(Math.floorMod(y,16)*16+Math.floorMod(z,16))*16+(int)Math.floorMod(x,16)]=(short)id;
        return true;
    }
    static BlockTintPreparation prepare(BuiltinBlockModels source,VoxelAreaLoadResult area,CancellationSignal cancel,
            Set<BlockState> states,Map<BlockState,Result> resolved,Map<BlockState,Dependencies> dependencies)throws IOException {
        var output=new BlockTintPreparation();var colors=new BlockTintResolver(source);var contextual=new HashSet<String>();
        int retained=resolved.values().stream().filter(Result::resolved).mapToInt(r->r.model().orElseThrow().faces().size()).sum();
        for(var entry:resolved.entrySet()) {
            if(!entry.getValue().resolved()||!BlockTintResolver.indexed(entry.getValue().model().orElseThrow()))continue;
            var kind=colors.kind(entry.getKey());
            if(kind.isPresent()&&BlockTintResolver.contextual(kind.orElseThrow())){contextual.add(entry.getKey().name());continue;}
            var tint=colors.colors(entry.getKey(),entry.getValue().model().orElseThrow(),null);
            entry.setValue(BlockTintResolver.apply(entry.getValue(),tint));note(source,entry.getKey(),tint.detail());
        }
        if(contextual.isEmpty())return output;
        for(var coordinate:area.results().keySet()) {
            var loaded=area.result(coordinate);if(loaded.chunk()==null)continue;
            try {
                for(var section:loaded.chunk().sections())for(int y=0;y<16;y++) {
                    if(cancel.isCancelled()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("颜色准备已取消");
                    for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                        var physical=section.blockState(x,y,z);if(!contextual.contains(physical.name()))continue;
                        long wx=(long)coordinate.x()*16+x,wz=(long)coordinate.z()*16+z;int wy=section.sectionY()*16+y;
                        if(wx<(long)area.target().minX()*16-1||wx>(long)area.target().maxX()*16+16||wz<(long)area.target().minZ()*16-1||wz>(long)area.target().maxZ()*16+16)continue;
                        var state=BlockTextureSet.visualState(physical,loaded.chunk().blockEntity(wx,wy,wz).orElse(null));
                        var context=new ModelBlockContext(area,new ModelBlockContext.Position(wx,wy,wz),cancel);var dependency=dependencies.get(state);
                        if(dependency!=null)state=BlockTextureSet.contextKey(state,context,dependency);
                        var original=resolved.get(state);if(original==null||!original.resolved())continue;
                        var tint=colors.colors(state,original.model().orElseThrow(),context);
                        if(tint.values().isEmpty()){note(source,state,tint.detail());continue;}
                        var properties=new TreeMap<>(state.properties());properties.put("__mcwe_tint",tint.signature());var key=new BlockState(state.name(),properties);
                        if(!resolved.containsKey(key)) {
                            int count=original.model().orElseThrow().faces().size();
                            if(states.size()>=BlockTextureSet.MAX_STATES||retained+count>32768){note(source,state,"颜色未解析：颜色变体达到现有状态/面预算；保留未染色几何");continue;}
                            states.add(key);resolved.put(key,BlockTintResolver.apply(original,tint));retained+=count;
                            var originalProperties=new TreeMap<>(state.properties());originalProperties.remove("__mcwe_context");
                            for(String existing:source.notes(new BlockState(state.name(),originalProperties)))note(source,key,existing);
                            note(source,key,tint.detail());
                        }
                        if(!output.store(wx,wy,wz,key))note(source,state,"颜色未解析：保存位置颜色索引超过2048区段预算；保留未染色几何");
                    }
                }
            }catch(ChunkDecodeException corrupt){/* Existing mesher reports the unavailable section. */}
        }
        return output;
    }
    static void note(BuiltinBlockModels source,BlockState state,String text){if(!text.isBlank()){var properties=new TreeMap<>(state.properties());properties.remove("__mcwe_context");source.note(new BlockState(state.name(),properties),text);}}
}
