package com.mcworldexplorer.sdk;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Summary of files and geometry written by {@link WavefrontObjExporter}.
 *
 * @param objFile       exported {@code .obj} geometry file
 * @param mtlFile       exported {@code .mtl} material library file
 * @param textureFile   optional baked {@code _atlas.png} texture atlas (present when textured faces exist)
 * @param vertexCount   number of written {@code v} vertex positions
 * @param faceCount     number of written triangular {@code f} faces
 * @param materialCount number of material definitions written to the {@code .mtl} file
 */
public record ObjExportResult(
        Path objFile,
        Path mtlFile,
        Optional<Path> textureFile,
        int vertexCount,
        int faceCount,
        int materialCount) {
    public ObjExportResult {
        Objects.requireNonNull(objFile, "objFile");
        Objects.requireNonNull(mtlFile, "mtlFile");
        Objects.requireNonNull(textureFile, "textureFile");
        if (vertexCount < 0 || faceCount < 0 || materialCount < 0) {
            throw new IllegalArgumentException("export counts must not be negative");
        }
    }
}
