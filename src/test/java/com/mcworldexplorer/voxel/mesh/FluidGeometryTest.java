package com.mcworldexplorer.voxel.mesh;

import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.data.SavedFluid;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import com.mcworldexplorer.voxel.resource.BlockTextureSet.Surface;
import com.mcworldexplorer.voxel.resource.BlockModelResolver.Direction;
import static org.junit.jupiter.api.Assertions.*;

class FluidGeometryTest {
    private static BlockState water(int level){return new BlockState("minecraft:water",Map.of("level",""+level));}
    private static final BlockState STONE=new BlockState("minecraft:stone",Map.of());
    @Test void openBankSlopesAndSharedCornersMeetAcrossLevels()throws Exception {
        FluidGeometry.Samples strip=(x,y,z)->y!=0?BlockState.AIR:x==0?water(0):x==1?water(7):BlockState.AIR;
        var source=new FluidGeometry(water(0),strip,STONE::equals,STONE::equals);
        var low=new FluidGeometry(water(7),(x,y,z)->strip.at(x+1,y,z),STONE::equals,STONE::equals);
        assertEquals(source.height(1,0),low.height(0,0),1e-6);
        assertEquals(source.height(1,1),low.height(0,1),1e-6);
        assertTrue(source.flowing());assertTrue(low.height(0,0)>low.height(1,0));
        var top=new Surface(Direction.UP,List.of(0f,1f,0f,0f,1f,1f,1f,1f,1f,1f,1f,0f),
                List.of(0f,0f,0f,.25f,.25f,.25f,.25f,0f),List.of(0f,1f,0f),Direction.UP,false,RenderLayer.FLUID,0,true);
        var sprite=new Surface(Direction.NORTH,top.positions(),List.of(.5f,.5f,1f,.5f,1f,1f,.5f,1f),top.normal(),
                Direction.NORTH,false,RenderLayer.FLUID,0,true);
        var baked=source.surface(top,List.of(top,sprite));
        assertEquals(.875f,baked.uv().get(0),1e-6);assertEquals(.625f,baked.uv().get(1),1e-6);
        assertEquals(source.height(0,0),baked.positions().get(1),1e-6);
    }
    @Test void supportedColumnsAndAquaticBlocksUseSourceWater()throws Exception {
        assertEquals(SavedFluid.WATER,SavedFluid.of(new BlockState("minecraft:kelp",Map.of())));
        assertEquals(8f/9,SavedFluid.ownHeight(water(15)),1e-6);
        var column=new FluidGeometry(water(8),(x,y,z)->x==0&&z==0?water(0):STONE,STONE::equals,STONE::equals);
        assertEquals(1,column.height(0,1));assertFalse(column.flowing());
        var shallow=new FluidGeometry(water(0),(x,y,z)->y==0?STONE:BlockState.AIR,STONE::equals,STONE::equals);
        assertEquals(8f/9,shallow.height(0,0),1e-6);
    }
}
