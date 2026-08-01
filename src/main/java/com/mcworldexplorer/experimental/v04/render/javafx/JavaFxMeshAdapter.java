package com.mcworldexplorer.experimental.v04.render.javafx;

import com.mcworldexplorer.experimental.v04.mesh.MeshBatch;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.shape.VertexFormat;

public final class JavaFxMeshAdapter {
    public MeshView createView(MeshBatch batch) {
        TriangleMesh mesh = new TriangleMesh(VertexFormat.POINT_NORMAL_TEXCOORD);
        mesh.getPoints().setAll(batch.positions());
        mesh.getNormals().setAll(batch.normals());
        mesh.getTexCoords().setAll(0, 0);
        mesh.getFaces().setAll(faceElements(batch));
        mesh.getFaceSmoothingGroups().setAll(new int[batch.indices().length / 3]);

        Color color = Color.rgb(
                (batch.rgb() >>> 16) & 0xFF,
                (batch.rgb() >>> 8) & 0xFF,
                batch.rgb() & 0xFF);
        PhongMaterial material = new PhongMaterial(color);
        material.setSpecularColor(Color.color(0.08, 0.08, 0.08));
        MeshView view = new MeshView(mesh);
        view.setMaterial(material);
        view.setCullFace(CullFace.BACK);
        return view;
    }

    static int[] faceElements(MeshBatch batch) {
        int[] indices = batch.indices();
        int[] faces = new int[indices.length * 3];
        for (int index = 0; index < indices.length; index++) {
            int vertexIndex = indices[index];
            int offset = index * 3;
            faces[offset] = vertexIndex;
            faces[offset + 1] = vertexIndex;
            faces[offset + 2] = 0;
        }
        return faces;
    }
}
