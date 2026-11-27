package com.mcworldexplorer.sdk;

import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.mesh.ChunkMeshSnapshot;
import com.mcworldexplorer.voxel.mesh.MeshBatch;
import com.mcworldexplorer.voxel.mesh.MeshBounds;
import com.mcworldexplorer.voxel.mesh.RenderLayer;
import com.mcworldexplorer.voxel.mesh.VoxelSceneSnapshot;
import com.mcworldexplorer.voxel.resource.TextureAtlas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WavefrontObjExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void bakesVertexBiomeTintAndDualLayerOverlayWithFlippedObjV() throws Exception {
        BufferedImage dirtImage = solidImage(16, 16, 0xFF805030);
        BufferedImage grassOverlay = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            int argb = y < 8 ? 0xFFFFFFFF : 0x00000000;
            for (int x = 0; x < 16; x++) {
                grassOverlay.setRGB(x, y, argb);
            }
        }
        BufferedImage waterImage = solidImage(16, 16, 0x80FFFFFF);

        TextureAtlas.Builder atlasBuilder = new TextureAtlas.Builder();
        TextureAtlas.Tile dirtTile = atlasBuilder.add("dirt", dirtImage);
        TextureAtlas.Tile overlayTile = atlasBuilder.add("grass_side_overlay", grassOverlay);
        TextureAtlas.Tile waterTile = atlasBuilder.add("water", waterImage);
        TextureAtlas atlas = atlasBuilder.build();

        float aw = atlas.width();
        float ah = atlas.height();

        // Quad 1: grass block side (untinted dirt base + green biome-tinted overlay on top half).
        float[] grassPositions = {
                0, 0, 0,
                1, 0, 0,
                1, 1, 0,
                0, 1, 0
        };
        float[] grassNormals = {
                0, 0, -1,
                0, 0, -1,
                0, 0, -1,
                0, 0, -1
        };
        int[] quadIndices = {0, 1, 2, 0, 2, 3};
        float[] dirtUv = tileUv(dirtTile, aw, ah);
        float[] overlayUv = tileUv(overlayTile, aw, ah);
        int[] whiteVertexRgb = {0xFFFFFF, 0xFFFFFF, 0xFFFFFF, 0xFFFFFF};
        int[] greenOverlayRgb = {0x40C020, 0x40C020, 0x40C020, 0x40C020};
        MeshBatch grassBatch = new MeshBatch(
                RenderLayer.OPAQUE, 0xFFFFFF, 1.0f, 1,
                grassPositions, grassNormals, quadIndices,
                dirtUv, true, whiteVertexRgb, overlayUv, greenOverlayRgb);

        // Quad 2: biome-tinted water top face.
        float[] waterPositions = {
                1, 1, 0,
                2, 1, 0,
                2, 1, 1,
                1, 1, 1
        };
        float[] waterNormals = {
                0, 1, 0,
                0, 1, 0,
                0, 1, 0,
                0, 1, 0
        };
        float[] waterUv = tileUv(waterTile, aw, ah);
        int[] blueWaterRgb = {0x3060FF, 0x3060FF, 0x3060FF, 0x3060FF};
        MeshBatch waterBatch = new MeshBatch(
                RenderLayer.FLUID, 0xFFFFFF, 0.8f, 1,
                waterPositions, waterNormals, quadIndices,
                waterUv, true, blueWaterRgb, new float[0], new int[0]);

        ChunkMeshSnapshot chunk = new ChunkMeshSnapshot(
                new ChunkCoordinate(0, 0),
                0L, 0L,
                List.of(grassBatch, waterBatch),
                new MeshBounds(0, 0, 0, 2, 1, 1),
                2, 2, 8, 12,
                List.of());
        VoxelSceneSnapshot scene = new VoxelSceneSnapshot(
                0L, 0L,
                List.of(chunk),
                new MeshBounds(0, 0, 0, 2, 1, 1),
                2, 2, 8, 12,
                List.of(),
                Optional.of(atlas));

        ObjExportResult result = WavefrontObjExporter.export(scene, tempDir, "biome_scene");
        assertTrue(Files.isRegularFile(result.objFile()));
        assertTrue(Files.isRegularFile(result.mtlFile()));
        assertTrue(result.textureFile().isPresent());
        assertTrue(Files.isRegularFile(result.textureFile().orElseThrow()));
        assertEquals(8, result.vertexCount());
        assertEquals(4, result.faceCount());
        assertEquals(2, result.materialCount());

        String mtl = Files.readString(result.mtlFile(), StandardCharsets.UTF_8);
        assertTrue(mtl.contains("newmtl textured_opaque"));
        assertTrue(mtl.contains("newmtl textured_fluid_a800"));
        assertTrue(mtl.contains("map_Kd biome_scene_atlas.png"));
        assertTrue(mtl.contains("map_d biome_scene_atlas.png"));

        String obj = Files.readString(result.objFile(), StandardCharsets.UTF_8);
        assertTrue(obj.contains("mtllib biome_scene.mtl"));
        assertTrue(obj.contains("usemtl textured_opaque"));
        assertTrue(obj.contains("usemtl textured_fluid_a800"));
        assertTrue(obj.contains("f 1/1/1 2/2/2 3/3/3"));

        // Verify flipped OBJ V coordinates: top-of-tile (localV=0) must have a higher OBJ V than bottom-of-tile (localV=1).
        List<double[]> vts = obj.lines()
                .filter(line -> line.startsWith("vt "))
                .map(line -> line.substring(3).trim().split("\\s+"))
                .map(parts -> new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])})
                .toList();
        assertEquals(12, vts.size());
        // First triangle vertices are (0,0)->localV=0, (1,0)->localV=0, (1,1)->localV=1.
        assertTrue(vts.get(0)[1] > vts.get(2)[1], "Wavefront OBJ V must be flipped (bottom-left origin)");

        // Verify baked _atlas.png pixels: tile 1 at (1,1) is 16x16 grass side; tile 2 at (19,1) is 16x16 blue water.
        BufferedImage bakedAtlas = ImageIO.read(result.textureFile().orElseThrow().toFile());
        assertNotNull(bakedAtlas);
        int grassTopPixel = bakedAtlas.getRGB(1 + 8, 1 + 2);
        int grassBottomPixel = bakedAtlas.getRGB(1 + 8, 1 + 12);
        assertEquals(0xFF40C020, grassTopPixel, "Top half of grass side must be biome-tinted green overlay");
        assertEquals(0xFF805030, grassBottomPixel, "Bottom half of grass side must preserve untinted brown dirt");

        int waterPixel = bakedAtlas.getRGB(19 + 8, 1 + 8);
        assertEquals(0x80, (waterPixel >>> 24) & 0xFF, "Water alpha must be preserved");
        assertEquals(0x30, (waterPixel >>> 16) & 0xFF, "Water red channel must match biome tint");
        assertEquals(0x60, (waterPixel >>> 8) & 0xFF, "Water green channel must match biome tint");
        assertEquals(0xFF, waterPixel & 0xFF, "Water blue channel must match biome tint");
    }

    @Test
    void preservesHigherResolutionRotatedOverlayAndCleansUpStagingOnCancellation() throws Exception {
        // 8x8 base dirt + 16x16 overlay rotated 90 degrees in UV space.
        BufferedImage base = solidImage(8, 8, 0xFF805030);
        BufferedImage overlay = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                // Left half (ou < 0.5) is opaque white; right half is transparent.
                overlay.setRGB(x, y, x < 8 ? 0xFFFFFFFF : 0x00000000);
            }
        }
        TextureAtlas.Builder builder = new TextureAtlas.Builder();
        TextureAtlas.Tile baseTile = builder.add("base8", base);
        TextureAtlas.Tile ovTile = builder.add("ov16", overlay);
        TextureAtlas atlas = builder.build();
        float aw = atlas.width(), ah = atlas.height();

        float bu0 = baseTile.x() / aw, bv0 = baseTile.y() / ah;
        float bu1 = (baseTile.x() + baseTile.width()) / aw, bv1 = (baseTile.y() + baseTile.height()) / ah;
        float ou0 = ovTile.x() / aw, ov0 = ovTile.y() / ah;
        float ou1 = (ovTile.x() + ovTile.width()) / aw, ov1 = (ovTile.y() + ovTile.height()) / ah;

        // Base UVs: (0,0), (1,0), (1,1), (0,1); Overlay UVs rotated so base top (bv < 0.5) maps to overlay left (ou < 0.5).
        float[] baseUvs = {bu0, bv0, bu1, bv0, bu1, bv1, bu0, bv1};
        float[] rotOverlayUvs = {ou0, ov1, ou0, ov0, ou1, ov0, ou1, ov1};
        MeshBatch batch = new MeshBatch(
                RenderLayer.OPAQUE, 0xFFFFFF, 1.0f, 1,
                new float[]{0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0},
                new float[]{0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1},
                new int[]{0, 1, 2, 0, 2, 3},
                baseUvs, true,
                new int[]{0xFFFFFF, 0xFFFFFF, 0xFFFFFF, 0xFFFFFF},
                rotOverlayUvs,
                new int[]{0x50D030, 0x50D030, 0x50D030, 0x50D030});
        ChunkMeshSnapshot chunk = new ChunkMeshSnapshot(
                new ChunkCoordinate(0, 0), 0, 0, List.of(batch),
                new MeshBounds(0, 0, 0, 1, 1, 1), 1, 1, 4, 6, List.of());
        VoxelSceneSnapshot scene = new VoxelSceneSnapshot(
                0, 0, List.of(chunk), new MeshBounds(0, 0, 0, 1, 1, 1),
                1, 1, 4, 6, List.of(), Optional.of(atlas));

        Path cancelDir = tempDir.resolve("cancelled_out");
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> WavefrontObjExporter.export(scene, cancelDir, "aborted", () -> true));
        assertFalse(Files.exists(cancelDir.resolve("aborted.obj")));

        Path okDir = tempDir.resolve("rotated_out");
        ObjExportResult result = WavefrontObjExporter.export(scene, okDir, "rotated_quad");
        BufferedImage baked = ImageIO.read(result.textureFile().orElseThrow().toFile());
        // Both triangles share one 16x16 baked tile (+2 border = 18x18 image).
        assertEquals(18, baked.getWidth());
        assertEquals(18, baked.getHeight());
        assertEquals(0xFF50D030, baked.getRGB(1 + 8, 1 + 2), "Rotated overlay top half must be tinted green");
        assertEquals(0xFF805030, baked.getRGB(1 + 8, 1 + 13), "Rotated overlay bottom half must keep brown dirt");
    }

    @Test
    void rejectsPathTraversalAndIllegalBaseNames() {
        for (String illegal : List.of("", "   ", ".", "..", "../escape", "a/b", "a\\b", "foo..bar", "bad:name", " lead")) {
            assertThrows(IllegalArgumentException.class, () -> WavefrontObjExporter.validateBaseName(illegal),
                    () -> "Expected IllegalArgumentException for baseName: " + illegal);
        }
        assertEquals("valid_scene-01.v2", WavefrontObjExporter.validateBaseName("valid_scene-01.v2"));
    }

    private static BufferedImage solidImage(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    private static float[] tileUv(TextureAtlas.Tile tile, float atlasW, float atlasH) {
        float u0 = tile.x() / atlasW;
        float v0 = tile.y() / atlasH;
        float u1 = (tile.x() + tile.width()) / atlasW;
        float v1 = (tile.y() + tile.height()) / atlasH;
        return new float[]{u0, v0, u1, v0, u1, v1, u0, v1};
    }
}
