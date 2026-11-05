package com.mcworldexplorer.viewer.lwjgl;

import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.mesh.MeshBounds;
import com.mcworldexplorer.voxel.mesh.VoxelTargetIndex;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;

import java.nio.FloatBuffer;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ViewerTargetOutlineTest {
    private static VoxelTargetIndex.Hit hit(long x, int y, long z, MeshBounds box) {
        return new VoxelTargetIndex.Hit(x, y, z,
                new VoxelTargetIndex.Entry(new BlockState("sample:original", Map.of()), box, "格定位", ""), 3);
    }

    @Test void preservesSlabHeightAndNegativeWorldAnchorWithoutUsingFallbackIdentity() {
        var box = ViewerTargetOutline.bounds(hit(-30_000_000, -16, -4872,
                new MeshBounds(0, 0, 0, 1, .5, 1)), -29_999_984, -4880);
        assertEquals(-16.002, box.minX(), 1e-8);
        assertEquals(-14.998, box.maxX(), 1e-8);
        assertEquals(-16.002, box.minY(), 1e-8);
        assertEquals(-15.498, box.maxY(), 1e-8);
        assertEquals(7.998, box.minZ(), 1e-8);
        assertEquals(9.002, box.maxZ(), 1e-8);
    }

    @Test void thinPlaneRemainsThinAndFallbackUsesWholeSelectionCell() {
        var plane = ViewerTargetOutline.bounds(hit(0, 0, 0,
                new MeshBounds(.5, 0, 0, .5, 1, 1)), 0, 0);
        assertEquals(.004, plane.maxX() - plane.minX(), 1e-8);
        var cell = ViewerTargetOutline.bounds(hit(15, 64, 15,
                new MeshBounds(0, 0, 0, 1, 1, 1)), 0, 0);
        assertEquals(1.004, cell.maxX() - cell.minX(), 1e-8);
        assertEquals(1.004, cell.maxY() - cell.minY(), 1e-8);
        assertEquals(1.004, cell.maxZ() - cell.minZ(), 1e-8);
    }

    @Test void noTargetOrIntentionalEmptyGeometryHasNoOutline() {
        assertNull(ViewerTargetOutline.bounds(null, 0, 0));
        assertNull(ViewerTargetOutline.bounds(hit(0, 0, 0, null), 0, 0));
    }

    @Test void projectedRibbonsStayFivePixelsWideAtDifferentDistancesAndViewportSizes() {
        for (int size : new int[]{256, 384, 960}) for (float distance : new float[]{2, 10, 100}) {
            var matrix = new Matrix4f().perspective((float) Math.toRadians(60), 1, .1f, 200)
                    .translate(-.5f, -.5f, -distance);
            var data = FloatBuffer.allocate(72 * 6);
            assertTrue(ViewerTargetOutline.vertices(matrix, data, size, size) > 0);
            // First ribbon's opposite sides at its horizontal start, measured in framebuffer pixels.
            assertEquals(5, Math.abs(data.get(1) - data.get(7)) * size / 2, 1e-4);
        }
    }

    @Test void clipsEdgesCrossingNearPlaneAndRejectsBehindCameraOrFarPlane() {
        var projection = new Matrix4f().perspective((float) Math.toRadians(60), 1, .1f, 100);
        var data = FloatBuffer.allocate(72 * 6);
        int count = ViewerTargetOutline.vertices(new Matrix4f(projection).translate(-.5f, -.5f, -.5f), data, 384, 384);
        assertTrue(count > 0 && count <= 72);
        for (int i = 0; i < count * 6; i++) assertTrue(Float.isFinite(data.get(i)));
        for (int i = 2; i < count * 6; i += 6) assertTrue(Math.abs(data.get(i)) <= 1.00001f, "深度应保留在裁剪体内");
        data.clear();
        assertEquals(0, ViewerTargetOutline.vertices(new Matrix4f(projection).translate(0, 0, 2), data, 384, 384));
        assertEquals(0, ViewerTargetOutline.vertices(new Matrix4f(projection).translate(0, 0, -200), data, 384, 384));
        assertEquals(0, ViewerTargetOutline.vertices(projection, data, 0, 384));
        assertEquals(0, data.position());
    }
}
