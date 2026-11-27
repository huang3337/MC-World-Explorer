package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.preview.WorldDimension;
import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.io.*;
import java.nio.file.Path;
import java.util.*;

/** Only state IDs and placement; all geometry/PNG comes from the supplied local resources. */
public final class GenericModelScene {
    private static BlockState state(String name,String... properties){Map<String,String> p=new TreeMap<>();for(int i=0;i<properties.length;i+=2)p.put(properties[i],properties[i+1]);return new BlockState("minecraft:"+name,p);}
    public static VoxelAreaLoadResult area() throws Exception {
        List<BlockState> palette=new ArrayList<>();palette.add(BlockState.AIR);
        for(String type:List.of("bottom","top","double"))palette.add(state("stone_slab","type",type,"waterlogged","false"));
        palette.add(state("cut_copper_slab","type","bottom","waterlogged","false"));
        for(String[] values:List.of(new String[]{"north","bottom","straight"},new String[]{"east","top","straight"},new String[]{"south","bottom","inner_left"},new String[]{"west","bottom","outer_right"}))palette.add(state("oak_stairs","facing",values[0],"half",values[1],"shape",values[2],"waterlogged","false"));
        palette.add(state("torch"));palette.add(state("wall_torch","facing","east"));palette.add(state("wall_torch","facing","north"));
        palette.add(state("oak_fence","north","true","south","true","east","true","west","true","waterlogged","false"));
        palette.add(state("iron_bars","north","true","south","false","east","true","west","false","waterlogged","false"));
        palette.add(state("oak_door","facing","south","half","lower","hinge","left","open","true","powered","false"));
        palette.add(state("poppy"));palette.add(state("stone_slab","type","bottom","waterlogged","true"));
        return area(palette.subList(1,palette.size()),List.of());
    }
    public static VoxelAreaLoadResult area(List<BlockState> blocks,List<BlockEntityData> entities) throws Exception {
        if(blocks.size()>16)throw new IllegalArgumentException("fixture has 16 slots");
        List<BlockState> palette=new ArrayList<>();palette.add(BlockState.AIR);palette.addAll(blocks);
        int bits=Math.max(4,32-Integer.numberOfLeadingZeros(palette.size()-1)),perLong=64/bits;
        long[] data=new long[(4096+perLong-1)/perLong];
        for(int i=1;i<palette.size();i++){int x=1+((i-1)%4)*3,z=1+((i-1)/4)*3,index=256+z*16+x;data[index/perLong]|=(long)i<<((index%perLong)*bits);}
        var coordinate=new ChunkCoordinate(0,0);var target=ChunkRectangle.target(0,0,0,0);var chunk=new VoxelChunk(0,0,List.of(new ChunkSection(0,palette,data)),entities);
        Map<ChunkCoordinate,ChunkLoadResult> results=new LinkedHashMap<>();for(var c:target.expanded(1).coordinates())results.put(c,ChunkLoadResult.withoutChunk(c,ChunkLoadStatus.MISSING,"missing",0,0));results.put(coordinate,ChunkLoadResult.success(coordinate,chunk,0,0));
        Path world=Path.of(".").toAbsolutePath().normalize();return new VoxelAreaLoadResult(world,WorldDimension.overworld(world),target,results);
    }
}
