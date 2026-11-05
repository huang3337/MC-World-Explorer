package com.mcworldexplorer.viewer.lwjgl;

import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.mesh.VoxelSceneSnapshot;
import java.util.Set;
import java.util.stream.Collectors;

/** Only the successfully loaded chunk set, not a claim that absent geometry is air. */
final class ViewerLoadedArea {
    private final long anchorX, anchorZ;
    private final Set<ChunkCoordinate> chunks;
    private final double minY, maxY;
    ViewerLoadedArea(VoxelSceneSnapshot scene) {
        anchorX = scene.anchorWorldX(); anchorZ = scene.anchorWorldZ();
        minY = scene.bounds().minY(); maxY = scene.bounds().maxY();
        chunks = scene.chunks().stream().map(c -> c.coordinate()).collect(Collectors.toUnmodifiableSet());
    }
    String notice(ViewerOrbitCameraState.CameraPose pose) {
        double x = pose.eyeX() + anchorX, z = pose.eyeZ() + anchorZ;
        if (!contains(x, z)) return "已超出已加载区块 · 区域外未加载 · R 复位";
        if (pose.eyeY() < minY || pose.eyeY() > maxY) return "已离开当前模型高度范围 · R 复位 · 不会自动加载";
        for (int dx : new int[]{-4, 0, 4}) for (int dz : new int[]{-4, 0, 4})
            if (!contains(x + dx, z + dz)) return "接近已加载区块边界 · 不会自动加载新区块";
        return "仅查看当前已加载区块 · 无碰撞 · 不会自动加载";
    }
    private boolean contains(double x, double z) {
        long cx = (long) Math.floor(x / 16), cz = (long) Math.floor(z / 16);
        return cx >= Integer.MIN_VALUE && cx <= Integer.MAX_VALUE && cz >= Integer.MIN_VALUE && cz <= Integer.MAX_VALUE
                && chunks.contains(new ChunkCoordinate((int) cx, (int) cz));
    }
}
