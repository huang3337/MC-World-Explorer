package com.mcworldexplorer.viewer.lwjgl;

import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.mesh.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ViewerLoadedAreaTest {
    @Test void negativeWorldAnchorsAndMissingChunksAreNeverReportedAsLoadedAir() {
        var bounds=new MeshBounds(0,-64,0,16,320,16);
        var mesh=new ChunkMeshSnapshot(new ChunkCoordinate(-60,63),-960,1008,List.of(),bounds,0,0,0,0,List.of());
        var area=new ViewerLoadedArea(new VoxelSceneSnapshot(-960,1008,List.of(mesh),bounds,0,0,0,0,List.of()));
        assertTrue(area.notice(pose(8,8)).contains("仅查看")); assertTrue(area.notice(pose(1,8)).contains("接近"));
        assertTrue(area.notice(pose(-.1,8)).contains("未加载")); assertTrue(area.notice(pose(16,8)).contains("未加载"));
        assertTrue(area.notice(new ViewerOrbitCameraState.CameraPose(8, 500, 8, 8, 500, 7)).contains("模型高度范围"));
        assertTrue(area.notice(new ViewerOrbitCameraState.CameraPose(8, -100, 8, 8, -100, 7)).contains("模型高度范围"));
    }
    private static ViewerOrbitCameraState.CameraPose pose(double x,double z) { return new ViewerOrbitCameraState.CameraPose(x,70,z,x,70,z-1); }
}
