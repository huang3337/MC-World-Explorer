package com.mcworldexplorer.voxel.mesh;

import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.resource.BlockTextureSet.Surface;
import com.mcworldexplorer.voxel.resource.BlockModelResolver.Direction;
import java.util.ArrayList;

/** Neighborhood rules for static liquid levels, independent of texture resource selection. */
final class FluidGeometry {
    static boolean liquid(BlockState state) {return family(state)!=0;}
    static boolean same(BlockState a,BlockState b) {int family=family(a);return family!=0&&family==family(b);}
    private static int family(BlockState state) {
        if("true".equals(state.properties().get("waterlogged")))return 1;
        return switch(state.name()){case "minecraft:water","minecraft:bubble_column"->1;case "minecraft:lava"->2;default->0;};
    }
    static double height(BlockState state) {
        if(state.name().equals("minecraft:bubble_column"))return 1;
        int level;try{level=Integer.parseInt(state.properties().getOrDefault("level","0"));}catch(NumberFormatException invalid){level=0;}
        return level>=8?1:Math.max(1.0/16,(8-Math.min(7,Math.max(0,level)))/9.0);
    }
    static Surface continuousColumn(Surface surface) {
        var positions=new ArrayList<>(surface.positions());
        for(int i=1;i<positions.size();i+=3)if(positions.get(i)>0)positions.set(i,1f);
        return new Surface(surface.direction(),positions,surface.uv(),surface.normal(),surface.cullFace(),surface.shade(),surface.layer(),surface.element());
    }
    static boolean hidden(BlockState current,BlockState neighbor,Direction direction,boolean fullHeight) {
        if(!same(current,neighbor))return false;
        return direction==Direction.UP||direction==Direction.DOWN||height(neighbor)+1e-6>=(fullHeight?1:height(current));
    }
}
