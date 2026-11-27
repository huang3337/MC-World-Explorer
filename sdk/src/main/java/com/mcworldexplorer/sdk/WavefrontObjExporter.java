package com.mcworldexplorer.sdk;

import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.mesh.ChunkMeshSnapshot;
import com.mcworldexplorer.voxel.mesh.MeshBatch;
import com.mcworldexplorer.voxel.mesh.RenderLayer;
import com.mcworldexplorer.voxel.mesh.VoxelSceneSnapshot;
import com.mcworldexplorer.voxel.resource.TextureAtlas;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

/**
 * Exports a {@link VoxelSceneSnapshot} to standard Wavefront {@code .obj} + {@code .mtl}
 * (and baked {@code _atlas.png} when textured).
 *
 * <p>For textured batches using the 0.8.0 vertex biome tinting and dual-layer coplanar overlay
 * pipeline, each unique {@code (baseTile, baseRgb, overlayTile, overlayRgb, overlayTransform)}
 * combination is Porter-Duff {@code source-over} composited into a deduplicated export atlas with
 * 1-pixel extruded borders and Wavefront OBJ bottom-left V coordinates ({@code 1.0 - v}).</p>
 */
public final class WavefrontObjExporter {
    private static final Pattern SAFE_BASE_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final int EXPORT_ATLAS_WIDTH = 4096;
    private static final int EXPORT_ATLAS_MAX_HEIGHT = 8192;

    private WavefrontObjExporter() {
    }

    /**
     * Validates that {@code baseName} is a safe single-segment file stem without path traversal.
     *
     * @param baseName file stem without extension
     * @return validated base name
     * @throws IllegalArgumentException if {@code baseName} is null, blank, contains separators, or contains {@code ..}
     */
    public static String validateBaseName(String baseName) {
        if (baseName == null || baseName.isBlank()) {
            throw new IllegalArgumentException("baseName must not be blank");
        }
        if (!baseName.equals(baseName.trim())
                || ".".equals(baseName)
                || "..".equals(baseName)
                || baseName.contains("..")
                || !SAFE_BASE_NAME.matcher(baseName).matches()) {
            throw new IllegalArgumentException(
                    "Invalid baseName '" + baseName + "': only [A-Za-z0-9._-] without '..' is allowed");
        }
        return baseName;
    }

    /**
     * Exports the scene snapshot into {@code outputDirectory} as {@code {baseName}.obj},
     * {@code {baseName}.mtl}, and (when textured geometry is present) {@code {baseName}_atlas.png}.
     *
     * @param scene           non-null scene snapshot
     * @param outputDirectory target directory (created if absent)
     * @param baseName        safe file stem without extension
     * @return export summary including written paths and counts
     * @throws IOException if writing output files fails
     */
    public static ObjExportResult export(
            VoxelSceneSnapshot scene,
            Path outputDirectory,
            String baseName) throws IOException {
        return export(scene, outputDirectory, baseName, CancellationSignal.NONE);
    }

    /**
     * Exports the scene snapshot with cooperative cancellation support and atomic staging cleanup on failure.
     *
     * @param scene           non-null scene snapshot
     * @param outputDirectory target directory (created if absent)
     * @param baseName        safe file stem without extension
     * @param cancellation    cancellation signal
     * @return export summary including written paths and counts
     * @throws IOException if writing output files fails
     */
    public static ObjExportResult export(
            VoxelSceneSnapshot scene,
            Path outputDirectory,
            String baseName,
            CancellationSignal cancellation) throws IOException {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(cancellation, "cancellation");
        String safeName = validateBaseName(baseName);
        checkCancelled(cancellation);

        Path dir = outputDirectory.toAbsolutePath().normalize();
        Path objFile = dir.resolve(safeName + ".obj").normalize();
        Path mtlFile = dir.resolve(safeName + ".mtl").normalize();
        Path atlasFile = dir.resolve(safeName + "_atlas.png").normalize();
        if (!dir.equals(objFile.getParent()) || !dir.equals(mtlFile.getParent()) || !dir.equals(atlasFile.getParent())) {
            throw new IllegalArgumentException("Resolved export paths escape outputDirectory");
        }
        Files.createDirectories(dir);

        TextureAtlas sourceAtlas = scene.atlas().orElse(null);
        int[] sourcePixels = sourceAtlas == null ? null : sourceAtlas.pixels();
        int sourceW = sourceAtlas == null ? 0 : sourceAtlas.width();
        int sourceH = sourceAtlas == null ? 0 : sourceAtlas.height();

        ExportAtlasBuilder exportAtlas = new ExportAtlasBuilder();
        Map<SolidMaterialKey, String> solidMaterials = new LinkedHashMap<>();
        Map<TexturedMaterialKey, String> texturedMaterials = new LinkedHashMap<>();

        List<TriangleRecord> triangles = new ArrayList<>();
        int totalVertices = 0;

        // Pass 1: Collect baked tiles and material definitions so export atlas dimensions are finalized before UV writing.
        for (ChunkMeshSnapshot chunk : scene.chunks()) {
            checkCancelled(cancellation);
            for (MeshBatch batch : chunk.batches()) {
                checkCancelled(cancellation);
                int[] indices = batch.indices();
                int vertexCount = batch.vertexCount();
                if (vertexCount == 0 || indices.length == 0) {
                    continue;
                }
                int baseVertexOffset = totalVertices;
                totalVertices += vertexCount;

                boolean textured = batch.textured();
                float[] baseUvs = textured ? batch.textureCoordinates() : null;
                boolean hasOverlays = textured && batch.hasOverlays();
                float[] overlayUvs = hasOverlays ? batch.overlayTextureCoordinates() : null;
                int[] overlayRgb = hasOverlays ? batch.overlayRgb() : null;
                int alphaMilli = Math.round(batch.alpha() * 1000.0f);

                if (textured && sourceAtlas == null) {
                    throw new IOException("Textured scene snapshot is missing source TextureAtlas");
                }

                for (int i = 0; i < indices.length; i += 3) {
                    int i0 = indices[i];
                    int i1 = indices[i + 1];
                    int i2 = indices[i + 2];

                    if (!textured) {
                        int rgb = batch.vertexColor(i0);
                        SolidMaterialKey key = new SolidMaterialKey(batch.layer(), rgb, alphaMilli);
                        String matName = solidMaterials.computeIfAbsent(key, WavefrontObjExporter::solidMaterialName);
                        triangles.add(new TriangleRecord(
                                matName,
                                baseVertexOffset + i0 + 1,
                                baseVertexOffset + i1 + 1,
                                baseVertexOffset + i2 + 1,
                                false,
                                null,
                                0, 0, 0, 0, 0, 0));
                    } else {
                        float u0 = baseUvs[i0 * 2];
                        float v0 = baseUvs[i0 * 2 + 1];
                        float u1 = baseUvs[i1 * 2];
                        float v1 = baseUvs[i1 * 2 + 1];
                        float u2 = baseUvs[i2 * 2];
                        float v2 = baseUvs[i2 * 2 + 1];

                        Rect baseRect = pixelRect(u0, v0, u1, v1, u2, v2, sourceW, sourceH);
                        int baseRgb = batch.vertexColor(i0);

                        float minU = (float) baseRect.x0 / sourceW;
                        float maxU = (float) baseRect.x1 / sourceW;
                        float minV = (float) baseRect.y0 / sourceH;
                        float maxV = (float) baseRect.y1 / sourceH;

                        float lu0 = normalizeCoord(u0, minU, maxU);
                        float lv0 = normalizeCoord(v0, minV, maxV);
                        float lu1 = normalizeCoord(u1, minU, maxU);
                        float lv1 = normalizeCoord(v1, minV, maxV);
                        float lu2 = normalizeCoord(u2, minU, maxU);
                        float lv2 = normalizeCoord(v2, minV, maxV);

                        boolean triOverlay = hasOverlays && overlayRgb[i0] >= 0;
                        Rect overlayRect = null;
                        int ovRgb = -1;
                        OverlayMapping mapping = OverlayMapping.IDENTITY;
                        if (triOverlay) {
                            float ou0 = overlayUvs[i0 * 2];
                            float ov0 = overlayUvs[i0 * 2 + 1];
                            float ou1 = overlayUvs[i1 * 2];
                            float ov1 = overlayUvs[i1 * 2 + 1];
                            float ou2 = overlayUvs[i2 * 2];
                            float ov2 = overlayUvs[i2 * 2 + 1];
                            overlayRect = pixelRect(ou0, ov0, ou1, ov1, ou2, ov2, sourceW, sourceH);
                            ovRgb = overlayRgb[i0];

                            float ominU = (float) overlayRect.x0 / sourceW;
                            float omaxU = (float) overlayRect.x1 / sourceW;
                            float ominV = (float) overlayRect.y0 / sourceH;
                            float omaxV = (float) overlayRect.y1 / sourceH;
                            mapping = OverlayMapping.fromTriangles(
                                    lu0, lv0, lu1, lv1, lu2, lv2,
                                    normalizeCoord(ou0, ominU, omaxU),
                                    normalizeCoord(ov0, ominV, omaxV),
                                    normalizeCoord(ou1, ominU, omaxU),
                                    normalizeCoord(ov1, ominV, omaxV),
                                    normalizeCoord(ou2, ominU, omaxU),
                                    normalizeCoord(ov2, ominV, omaxV));
                        }

                        BakedTileKey tileKey = new BakedTileKey(
                                baseRect.x0, baseRect.y0, baseRect.x1, baseRect.y1, baseRgb,
                                triOverlay,
                                triOverlay ? overlayRect.x0 : 0,
                                triOverlay ? overlayRect.y0 : 0,
                                triOverlay ? overlayRect.x1 : 0,
                                triOverlay ? overlayRect.y1 : 0,
                                ovRgb,
                                mapping);
                        ExportTile tile = exportAtlas.bakeOrReuse(tileKey, sourcePixels, sourceW);

                        TexturedMaterialKey matKey = new TexturedMaterialKey(batch.layer(), alphaMilli);
                        String matName = texturedMaterials.computeIfAbsent(matKey, WavefrontObjExporter::texturedMaterialName);

                        triangles.add(new TriangleRecord(
                                matName,
                                baseVertexOffset + i0 + 1,
                                baseVertexOffset + i1 + 1,
                                baseVertexOffset + i2 + 1,
                                true,
                                tile,
                                lu0, lv0, lu1, lv1, lu2, lv2));
                    }
                }
            }
        }

        boolean wroteAtlas = !exportAtlas.isEmpty();
        Path stagedAtlas = null;
        Path stagedMtl = null;
        Path stagedObj = null;
        try {
            int exportW = 0;
            int exportH = 0;
            if (wroteAtlas) {
                checkCancelled(cancellation);
                BufferedImage atlasImage = exportAtlas.buildImage();
                exportW = atlasImage.getWidth();
                exportH = atlasImage.getHeight();
                stagedAtlas = Files.createTempFile(dir, "." + safeName + "_atlas_", ".tmp");
                if (!ImageIO.write(atlasImage, "png", stagedAtlas.toFile())) {
                    throw new IOException("No PNG ImageIO writer available for " + atlasFile);
                }
            }

            checkCancelled(cancellation);
            stagedMtl = Files.createTempFile(dir, "." + safeName + "_mtl_", ".tmp");
            writeMtl(stagedMtl, solidMaterials, texturedMaterials, wroteAtlas ? atlasFile.getFileName().toString() : null);

            checkCancelled(cancellation);
            stagedObj = Files.createTempFile(dir, "." + safeName + "_obj_", ".tmp");
            int writtenFaces = writeObj(stagedObj, mtlFile.getFileName().toString(), scene, triangles, exportW, exportH, cancellation);

            checkCancelled(cancellation);
            if (stagedAtlas != null) {
                Files.move(stagedAtlas, atlasFile, StandardCopyOption.REPLACE_EXISTING);
                stagedAtlas = null;
            }
            Files.move(stagedMtl, mtlFile, StandardCopyOption.REPLACE_EXISTING);
            stagedMtl = null;
            Files.move(stagedObj, objFile, StandardCopyOption.REPLACE_EXISTING);
            stagedObj = null;

            Optional<Path> writtenAtlas = wroteAtlas ? Optional.of(atlasFile) : Optional.empty();
            int materialCount = solidMaterials.size() + texturedMaterials.size();
            return new ObjExportResult(objFile, mtlFile, writtenAtlas, totalVertices, writtenFaces, materialCount);
        } finally {
            deleteQuietly(stagedAtlas);
            deleteQuietly(stagedMtl);
            deleteQuietly(stagedObj);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
        }
    }

    private static void checkCancelled(CancellationSignal cancellation) {
        if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("OBJ export cancelled");
        }
    }

    private static int writeObj(
            Path objFile,
            String mtlFileName,
            VoxelSceneSnapshot scene,
            List<TriangleRecord> triangles,
            int exportW,
            int exportH,
            CancellationSignal cancellation) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(objFile, StandardCharsets.UTF_8)) {
            writer.write("# Exported by MC World Explorer SDK\n");
            writer.write("mtllib " + mtlFileName + "\n");

            // Write all vertex positions and normals in batch order matching Pass 1 offsets.
            for (ChunkMeshSnapshot chunk : scene.chunks()) {
                checkCancelled(cancellation);
                double offsetX = chunk.worldOriginX() - scene.anchorWorldX();
                double offsetZ = chunk.worldOriginZ() - scene.anchorWorldZ();
                for (MeshBatch batch : chunk.batches()) {
                    float[] positions = batch.positions();
                    float[] normals = batch.normals();
                    int vertexCount = batch.vertexCount();
                    if (vertexCount == 0 || batch.indexCount() == 0) {
                        continue;
                    }
                    for (int v = 0; v < vertexCount; v++) {
                        int base = v * 3;
                        writer.write(String.format(
                                Locale.ROOT, "v %.6f %.6f %.6f\n",
                                positions[base] + offsetX,
                                (double) positions[base + 1],
                                positions[base + 2] + offsetZ));
                    }
                    for (int v = 0; v < vertexCount; v++) {
                        int base = v * 3;
                        writer.write(String.format(
                                Locale.ROOT, "vn %.6f %.6f %.6f\n",
                                normals[base],
                                normals[base + 1],
                                normals[base + 2]));
                    }
                }
            }

            // Write UV coordinates for textured triangles, flipping V to OBJ bottom-left convention.
            int nextVtIndex = 1;
            int[] triVtStart = new int[triangles.size()];
            for (int t = 0; t < triangles.size(); t++) {
                if ((t & 4095) == 0) {
                    checkCancelled(cancellation);
                }
                TriangleRecord tri = triangles.get(t);
                if (!tri.textured) {
                    continue;
                }
                triVtStart[t] = nextVtIndex;
                writeVt(writer, tri.tile, tri.lu0, tri.lv0, exportW, exportH);
                writeVt(writer, tri.tile, tri.lu1, tri.lv1, exportW, exportH);
                writeVt(writer, tri.tile, tri.lu2, tri.lv2, exportW, exportH);
                nextVtIndex += 3;
            }

            // Write faces grouped by material transitions.
            String activeMaterial = null;
            for (int t = 0; t < triangles.size(); t++) {
                if ((t & 4095) == 0) {
                    checkCancelled(cancellation);
                }
                TriangleRecord tri = triangles.get(t);
                if (!tri.materialName.equals(activeMaterial)) {
                    activeMaterial = tri.materialName;
                    writer.write("usemtl " + activeMaterial + "\n");
                }
                if (tri.textured) {
                    int vt = triVtStart[t];
                    writer.write("f "
                            + tri.v0 + "/" + vt + "/" + tri.v0 + " "
                            + tri.v1 + "/" + (vt + 1) + "/" + tri.v1 + " "
                            + tri.v2 + "/" + (vt + 2) + "/" + tri.v2 + "\n");
                } else {
                    writer.write("f "
                            + tri.v0 + "//" + tri.v0 + " "
                            + tri.v1 + "//" + tri.v1 + " "
                            + tri.v2 + "//" + tri.v2 + "\n");
                }
            }
        }
        return triangles.size();
    }

    private static void writeVt(
            BufferedWriter writer,
            ExportTile tile,
            float localU,
            float localV,
            int exportW,
            int exportH) throws IOException {
        double u = (tile.x + localU * tile.width) / (double) exportW;
        double topDownV = (tile.y + localV * tile.height) / (double) exportH;
        double objV = 1.0 - topDownV;
        writer.write(String.format(Locale.ROOT, "vt %.6f %.6f\n", u, objV));
    }

    private static void writeMtl(
            Path mtlFile,
            Map<SolidMaterialKey, String> solidMaterials,
            Map<TexturedMaterialKey, String> texturedMaterials,
            String atlasFileName) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(mtlFile, StandardCharsets.UTF_8)) {
            writer.write("# Exported by MC World Explorer SDK\n");
            for (Map.Entry<SolidMaterialKey, String> entry : solidMaterials.entrySet()) {
                SolidMaterialKey key = entry.getKey();
                double r = ((key.rgb >>> 16) & 0xFF) / 255.0;
                double g = ((key.rgb >>> 8) & 0xFF) / 255.0;
                double b = (key.rgb & 0xFF) / 255.0;
                double alpha = key.alphaMilli / 1000.0;
                writer.write("\nnewmtl " + entry.getValue() + "\n");
                writer.write("Ka 0.000000 0.000000 0.000000\n");
                writer.write(String.format(Locale.ROOT, "Kd %.6f %.6f %.6f\n", r, g, b));
                writer.write("Ks 0.000000 0.000000 0.000000\n");
                writer.write(String.format(Locale.ROOT, "d %.6f\n", alpha));
                writer.write(alpha < 0.999 ? "illum 2\n" : "illum 1\n");
            }
            if (atlasFileName != null) {
                for (Map.Entry<TexturedMaterialKey, String> entry : texturedMaterials.entrySet()) {
                    TexturedMaterialKey key = entry.getKey();
                    double alpha = key.alphaMilli / 1000.0;
                    writer.write("\nnewmtl " + entry.getValue() + "\n");
                    writer.write("Ka 0.000000 0.000000 0.000000\n");
                    writer.write("Kd 1.000000 1.000000 1.000000\n");
                    writer.write("Ks 0.000000 0.000000 0.000000\n");
                    writer.write(String.format(Locale.ROOT, "d %.6f\n", alpha));
                    writer.write(key.layer == RenderLayer.OPAQUE ? "illum 1\n" : "illum 2\n");
                    writer.write("map_Kd " + atlasFileName + "\n");
                    if (key.layer != RenderLayer.OPAQUE) {
                        writer.write("map_d " + atlasFileName + "\n");
                    }
                }
            }
        }
    }

    private static String solidMaterialName(SolidMaterialKey key) {
        String hex = String.format(Locale.ROOT, "%06x", key.rgb & 0xFFFFFF);
        String layer = key.layer.name().toLowerCase(Locale.ROOT);
        return key.alphaMilli >= 1000
                ? "solid_" + layer + "_" + hex
                : "solid_" + layer + "_" + hex + "_a" + key.alphaMilli;
    }

    private static String texturedMaterialName(TexturedMaterialKey key) {
        String layer = key.layer.name().toLowerCase(Locale.ROOT);
        return key.alphaMilli >= 1000
                ? "textured_" + layer
                : "textured_" + layer + "_a" + key.alphaMilli;
    }

    private static Rect pixelRect(
        float u0, float v0, float u1, float v1, float u2, float v2, int atlasW, int atlasH) {
        float minU = Math.min(u0, Math.min(u1, u2));
        float maxU = Math.max(u0, Math.max(u1, u2));
        float minV = Math.min(v0, Math.min(v1, v2));
        float maxV = Math.max(v0, Math.max(v1, v2));
        int x0 = Math.clamp((int) Math.floor(minU * atlasW + 1e-4f), 0, atlasW - 1);
        int y0 = Math.clamp((int) Math.floor(minV * atlasH + 1e-4f), 0, atlasH - 1);
        int x1 = Math.clamp((int) Math.ceil(maxU * atlasW - 1e-4f), x0 + 1, atlasW);
        int y1 = Math.clamp((int) Math.ceil(maxV * atlasH - 1e-4f), y0 + 1, atlasH);
        return new Rect(x0, y0, x1, y1);
    }

    private static float normalizeCoord(float value, float min, float max) {
        float span = max - min;
        if (span <= 1e-7f) {
            return 0.5f;
        }
        return Math.clamp((value - min) / span, 0.0f, 1.0f);
    }

    private static int multiplyRgb(int argb, int rgb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (((argb >>> 16) & 0xFF) * ((rgb >>> 16) & 0xFF) + 127) / 255;
        int g = (((argb >>> 8) & 0xFF) * ((rgb >>> 8) & 0xFF) + 127) / 255;
        int b = ((argb & 0xFF) * (rgb & 0xFF) + 127) / 255;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Exact CPU counterpart of {@code /v05/shaders/voxel.frag} Porter-Duff {@code source-over} compositing.
     */
    private static int compositeSourceOver(int baseArgb, int topArgb) {
        float baseA = ((baseArgb >>> 24) & 0xFF) / 255.0f;
        float topA = ((topArgb >>> 24) & 0xFF) / 255.0f;
        float outA = topA + baseA * (1.0f - topA);
        if (outA <= 0.0f) {
            return 0;
        }
        float baseR = ((baseArgb >>> 16) & 0xFF) / 255.0f;
        float baseG = ((baseArgb >>> 8) & 0xFF) / 255.0f;
        float baseB = (baseArgb & 0xFF) / 255.0f;
        float topR = ((topArgb >>> 16) & 0xFF) / 255.0f;
        float topG = ((topArgb >>> 8) & 0xFF) / 255.0f;
        float topB = (topArgb & 0xFF) / 255.0f;

        float invTop = 1.0f - topA;
        float outR = (topR * topA + baseR * baseA * invTop) / outA;
        float outG = (topG * topA + baseG * baseA * invTop) / outA;
        float outB = (topB * topA + baseB * baseA * invTop) / outA;

        int a = Math.clamp(Math.round(outA * 255.0f), 0, 255);
        int r = Math.clamp(Math.round(outR * 255.0f), 0, 255);
        int g = Math.clamp(Math.round(outG * 255.0f), 0, 255);
        int b = Math.clamp(Math.round(outB * 255.0f), 0, 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Quantized affine mapping from base local UV {@code (u, v)} in {@code [0,1]^2} to overlay local UV,
     * evaluated at canonical corners {@code (0,0)}, {@code (1,0)}, and {@code (0,1)} so both triangles of a
     * rotated/flipped overlay quad produce identical keys and share one baked tile.
     */
    private record OverlayMapping(int c00u, int c00v, int c10u, int c10v, int c01u, int c01v) {
        private static final int SCALE = 1024;
        static final OverlayMapping IDENTITY = new OverlayMapping(0, 0, SCALE, 0, 0, SCALE);

        static OverlayMapping fromTriangles(
                float bu0, float bv0, float bu1, float bv1, float bu2, float bv2,
                float ou0, float ov0, float ou1, float ov1, float ou2, float ov2) {
            float du1 = bu1 - bu0;
            float dv1 = bv1 - bv0;
            float du2 = bu2 - bu0;
            float dv2 = bv2 - bv0;
            float det = du1 * dv2 - du2 * dv1;
            if (Math.abs(det) <= 1e-6f) {
                return IDENTITY;
            }
            float p00u = eval(0.0f, 0.0f, bu0, bv0, dv2, du2, du1, dv1, det, ou0, ou1, ou2);
            float p00v = eval(0.0f, 0.0f, bu0, bv0, dv2, du2, du1, dv1, det, ov0, ov1, ov2);
            float p10u = eval(1.0f, 0.0f, bu0, bv0, dv2, du2, du1, dv1, det, ou0, ou1, ou2);
            float p10v = eval(1.0f, 0.0f, bu0, bv0, dv2, du2, du1, dv1, det, ov0, ov1, ov2);
            float p01u = eval(0.0f, 1.0f, bu0, bv0, dv2, du2, du1, dv1, det, ou0, ou1, ou2);
            float p01v = eval(0.0f, 1.0f, bu0, bv0, dv2, du2, du1, dv1, det, ov0, ov1, ov2);
            return new OverlayMapping(
                    Math.round(p00u * SCALE), Math.round(p00v * SCALE),
                    Math.round(p10u * SCALE), Math.round(p10v * SCALE),
                    Math.round(p01u * SCALE), Math.round(p01v * SCALE));
        }

        private static float eval(
                float u, float v, float bu0, float bv0,
                float dv2, float du2, float du1, float dv1, float det,
                float o0, float o1, float o2) {
            float b1 = ((u - bu0) * dv2 - (v - bv0) * du2) / det;
            float b2 = (du1 * (v - bv0) - dv1 * (u - bu0)) / det;
            float b0 = 1.0f - b1 - b2;
            return b0 * o0 + b1 * o1 + b2 * o2;
        }

        float mapU(float u, float v) {
            float raw = (c00u + u * (c10u - c00u) + v * (c01u - c00u)) / (float) SCALE;
            return Math.clamp(raw, 0.0f, 1.0f);
        }

        float mapV(float u, float v) {
            float raw = (c00v + u * (c10v - c00v) + v * (c01v - c00v)) / (float) SCALE;
            return Math.clamp(raw, 0.0f, 1.0f);
        }
    }

    private record Rect(int x0, int y0, int x1, int y1) {
    }

    private record SolidMaterialKey(RenderLayer layer, int rgb, int alphaMilli) {
    }

    private record TexturedMaterialKey(RenderLayer layer, int alphaMilli) {
    }

    private record BakedTileKey(
            int bx0, int by0, int bx1, int by1, int baseRgb,
            boolean hasOverlay,
            int ox0, int oy0, int ox1, int oy1, int overlayRgb,
            OverlayMapping mapping) {
    }

    private record ExportTile(int x, int y, int width, int height) {
    }

    private record TriangleRecord(
            String materialName,
            int v0, int v1, int v2,
            boolean textured,
            ExportTile tile,
            float lu0, float lv0, float lu1, float lv1, float lu2, float lv2) {
    }

    private static final class ExportAtlasBuilder {
        private final Map<BakedTileKey, ExportTile> tiles = new LinkedHashMap<>();
        private final List<int[]> rows = new ArrayList<>();
        private int cursorX;
        private int cursorY;
        private int rowHeight;

        boolean isEmpty() {
            return tiles.isEmpty();
        }

        ExportTile bakeOrReuse(BakedTileKey key, int[] sourcePixels, int sourceW) throws IOException {
            ExportTile existing = tiles.get(key);
            if (existing != null) {
                return existing;
            }
            int bw = key.bx1 - key.bx0;
            int bh = key.by1 - key.by0;
            int ow = key.hasOverlay ? (key.ox1 - key.ox0) : 0;
            int oh = key.hasOverlay ? (key.oy1 - key.oy0) : 0;
            int w = key.hasOverlay ? Math.max(bw, ow) : bw;
            int h = key.hasOverlay ? Math.max(bh, oh) : bh;
            if (w < 1 || h < 1 || w + 2 > EXPORT_ATLAS_WIDTH) {
                throw new IOException("Export tile dimensions out of range: " + w + "x" + h);
            }
            if (cursorX + w + 2 > EXPORT_ATLAS_WIDTH) {
                cursorX = 0;
                cursorY += rowHeight;
                rowHeight = 0;
            }
            if (cursorY + h + 2 > EXPORT_ATLAS_MAX_HEIGHT) {
                throw new IOException("Export texture atlas exceeded maximum height " + EXPORT_ATLAS_MAX_HEIGHT);
            }

            int[] baked = new int[w * h];
            for (int dy = 0; dy < h; dy++) {
                float v = (dy + 0.5f) / h;
                int sy = key.by0 + Math.min(bh - 1, (int) (v * bh));
                for (int dx = 0; dx < w; dx++) {
                    float u = (dx + 0.5f) / w;
                    int sx = key.bx0 + Math.min(bw - 1, (int) (u * bw));
                    int base = multiplyRgb(sourcePixels[sy * sourceW + sx], key.baseRgb);
                    if (key.hasOverlay) {
                        float ou = key.mapping.mapU(u, v);
                        float ov = key.mapping.mapV(u, v);
                        int ox = key.ox0 + Math.min(ow - 1, (int) (ou * ow));
                        int oy = key.oy0 + Math.min(oh - 1, (int) (ov * oh));
                        int top = multiplyRgb(sourcePixels[oy * sourceW + ox], key.overlayRgb);
                        baked[dy * w + dx] = compositeSourceOver(base, top);
                    } else {
                        baked[dy * w + dx] = base;
                    }
                }
            }

            ensureRows(cursorY + h + 2);
            for (int py = -1; py <= h; py++) {
                int clampedY = Math.clamp(py, 0, h - 1);
                int[] destRow = rows.get(cursorY + py + 1);
                System.arraycopy(baked, clampedY * w, destRow, cursorX + 1, w);
                destRow[cursorX] = baked[clampedY * w];
                destRow[cursorX + w + 1] = baked[clampedY * w + w - 1];
            }

            ExportTile tile = new ExportTile(cursorX + 1, cursorY + 1, w, h);
            tiles.put(key, tile);
            cursorX += w + 2;
            rowHeight = Math.max(rowHeight, h + 2);
            return tile;
        }

        private void ensureRows(int neededHeight) {
            while (rows.size() < neededHeight) {
                rows.add(new int[EXPORT_ATLAS_WIDTH]);
            }
        }

        BufferedImage buildImage() {
            int usedHeight = Math.max(1, cursorY + rowHeight);
            int width = Math.min(EXPORT_ATLAS_WIDTH, Math.max(1, cursorY == 0 ? cursorX : EXPORT_ATLAS_WIDTH));
            BufferedImage image = new BufferedImage(width, usedHeight, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < usedHeight; y++) {
                int[] row = y < rows.size() ? rows.get(y) : new int[width];
                image.setRGB(0, y, width, 1, row, 0, width);
            }
            return image;
        }
    }
}
