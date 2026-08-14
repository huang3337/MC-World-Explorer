package com.mcworldexplorer.experimental.v05;

import com.mcworldexplorer.voxel.io.VoxelAreaLoadResult;
import com.mcworldexplorer.voxel.mesh.VoxelSceneResult;

public record V05ValidationContext(
        V05Arguments arguments,
        VoxelAreaLoadResult loadResult,
        VoxelSceneResult sceneResult) {
}
