package com.mcworldexplorer.experimental.v05;

import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.io.VoxelAreaLoader;
import com.mcworldexplorer.voxel.io.VoxelAreaRequest;
import com.mcworldexplorer.voxel.mesh.VoxelSceneBuilder;
import com.mcworldexplorer.voxel.mesh.VoxelSceneResult;

import java.io.IOException;

public final class V05ValidationPipeline {
    public V05ValidationContext prepare(V05Arguments arguments) throws IOException {
        VoxelAreaRequest request = new VoxelAreaRequest(
                arguments.world(), arguments.dimensionId(), arguments.target());
        var loadResult = new VoxelAreaLoader().load(request, CancellationSignal.NONE);
        VoxelSceneResult sceneResult = new VoxelSceneBuilder().build(
                loadResult, CancellationSignal.NONE);
        if (sceneResult.snapshot().isEmpty()
                || sceneResult.snapshot().orElseThrow().faceCount() == 0) {
            throw new IOException("no target chunk produced renderable geometry; statuses="
                    + sceneResult.targetStatuses());
        }
        return new V05ValidationContext(arguments, loadResult, sceneResult);
    }
}
