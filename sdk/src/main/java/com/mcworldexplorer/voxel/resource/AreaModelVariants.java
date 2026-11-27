package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.io.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Shares geometry by chosen candidate indices; no table of world coordinates is retained. */
final class AreaModelVariants {
    private final Map<BlockState,WeightedVariants.States> variants=new HashMap<>();
    private final Set<String> names=new HashSet<>();

    // Bound retained weight tables even when thousands of physical palette states share large JSON arrays.
    private int weightEntries;
    boolean register(BlockState state,WeightedVariants plan) {
        int entries=plan.weightEntries();
        if(weightEntries+entries>65536)return false;
        weightEntries+=entries;
        variants.put(state,new WeightedVariants.States(state,plan));
        names.add(state.name());
        return true;
    }
    BlockState state(BlockState state,long x,int y,long z) {
        if(!names.contains(state.name()))return state;
        var choices=variants.get(state);
        return choices==null?state:choices.state(x,y,z,false);
    }

    void prepare(VoxelAreaLoadResult area,CancellationSignal cancel,BlockModelResolver resolver,
            Set<BlockState> states,Map<BlockState,BlockModelResolver.Result> resolved,int[] faceBudget) throws IOException {
        if(names.isEmpty())return;
        var neighborhood=area.target().expanded(1);
        for(var entry:area.results().entrySet()) {
            var coordinate=entry.getKey();
            var chunk=entry.getValue().chunk();
            if(chunk==null || !neighborhood.contains(coordinate))continue;
            var bounds=BlockTextureSet.LocalBounds.forNeighbor(area.target(),coordinate);
            try {
                for(var section:chunk.sections()) {
                    if(!section.containsAny(names))continue;
                    for(int ly=0;ly<16;ly++) {
                        if(cancel.isCancelled()||Thread.currentThread().isInterrupted())throw new CancellationException();
                        int y=section.sectionY()*16+ly;
                        for(int lz=bounds.minZ();lz<=bounds.maxZ();lz++)for(int lx=bounds.minX();lx<=bounds.maxX();lx++) {
                            var physical=section.blockState(lx,ly,lz);
                            if(!names.contains(physical.name()))continue;
                            long x=(long)coordinate.x()*16+lx,z=(long)coordinate.z()*16+lz;
                            var base=BlockTextureSet.visualState(physical,chunk.blockEntity(x,y,z).orElse(null));
                            var choices=variants.get(base);
                            if(choices==null)continue;
                            var selected=choices.state(x,y,z,states.size()<BlockTextureSet.MAX_STATES);
                            if(states.contains(selected))continue;
                            states.add(selected);
                            if(states.size()>BlockTextureSet.MAX_STATES)return;
                            resolved.put(selected,BlockTextureSet.retain(resolver.resolveVariant(base,selected,new ModelBlockContext.Position(x,y,z),choices.plan()),faceBudget));
                        }
                    }
                }
            } catch(ChunkDecodeException corrupt) { /* Mesher diagnoses the affected chunk. */ }
        }
    }
}
