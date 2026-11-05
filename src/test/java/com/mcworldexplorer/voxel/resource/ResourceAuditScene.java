package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.io.*;
import java.nio.file.Path;
import java.util.*;

/** Controlled isolated declarations, with known empty neighbors. This is not a real-world sample. */
final class ResourceAuditScene {
    static ModelBlockContext.Position position(int index){return new ModelBlockContext.Position(2+(index%8)*3,1,2+(index/8)*3);}
    static VoxelAreaLoadResult isolated(List<BlockState> states)throws Exception {
        if(states.size()>32)throw new IllegalArgumentException("审计场景最多32个声明");
        var target=ChunkRectangle.target(0,0,1,0);List<BlockState> palette=new ArrayList<>();palette.add(BlockState.AIR);for(var state:states)if(!palette.contains(state))palette.add(state);
        int bits=Math.max(4,32-Integer.numberOfLeadingZeros(palette.size()-1)),per=64/bits;
        Map<ChunkCoordinate,ChunkLoadResult> results=new HashMap<>();
        for(var coordinate:target.expanded(1).coordinates()) {
            long[] data=new long[(4096+per-1)/per];
            for(int i=0;i<states.size();i++){var pos=position(i);if(Math.floorDiv(pos.x(),16)!=coordinate.x()||Math.floorDiv(pos.z(),16)!=coordinate.z())continue;int index=256+(int)Math.floorMod(pos.z(),16)*16+(int)Math.floorMod(pos.x(),16);data[index/per]|=(long)palette.indexOf(states.get(i))<<((index%per)*bits);}
            var chunk=new VoxelChunk(coordinate.x(),coordinate.z(),List.of(new ChunkSection(0,palette,data)));results.put(coordinate,ChunkLoadResult.success(coordinate,chunk,0,0));
        }
        Path world=Path.of(".").toAbsolutePath();return new VoxelAreaLoadResult(world,WorldDimension.overworld(world),target,results);
    }
}
