package com.mcworldexplorer.voxel.mesh;
import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.resource.BlockModelResolver.Direction;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class FluidGeometryTest {
    private static BlockState water(int level){return new BlockState("minecraft:water",Map.of("level",""+level));}
    @Test void removesSharedFluidFacesButKeepsExposedHigherSidesAndSeparateLiquids() {
        assertTrue(FluidGeometry.hidden(water(0),water(0),Direction.EAST,false));
        assertTrue(FluidGeometry.hidden(water(7),water(0),Direction.WEST,false));
        assertFalse(FluidGeometry.hidden(water(0),water(7),Direction.EAST,false));
        assertTrue(FluidGeometry.hidden(water(0),water(7),Direction.UP,true));
        assertFalse(FluidGeometry.hidden(water(0),new BlockState("minecraft:lava",Map.of("level","0")),Direction.EAST,false));
        assertEquals(1,FluidGeometry.height(water(8)));
        assertEquals(1,FluidGeometry.height(water(15)));
        assertEquals(8.0/9,FluidGeometry.height(water(0)),1e-6);
    }
}
