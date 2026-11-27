package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.mesh.RenderLayer;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelOcclusionTest {
    @Test void singleBoxMatchesRectangleUnionForFullPartialAndOffsetCoverage() {
        var east = new BlockTextureSet.Surface(BlockModelResolver.Direction.EAST,
                List.of(1f,0f,0f, 1f,1f,0f, 1f,1f,1f, 1f,0f,1f), List.of(),
                List.of(1f,0f,0f), BlockModelResolver.Direction.EAST,true,RenderLayer.OPAQUE,0);
        var cube = new ModelOcclusion.Box(0,0,0,1,1,1);
        assertFalse(ModelOcclusion.covers(east, List.of(cube),0,0,0));
        assertTrue(ModelOcclusion.covers(east, List.of(cube),1,0,0));
        for (var box : List.of(cube, new ModelOcclusion.Box(0,0,0,1,.5,1),
                new ModelOcclusion.Box(0,.000005,0,1,1,1))) {
            for (int dx = -1; dx <= 1; dx++) {
                assertEquals(ModelOcclusion.covers(east,List.of(box,box),dx,0,0),
                        ModelOcclusion.covers(east,List.of(box),dx,0,0));
            }
        }
    }
}
