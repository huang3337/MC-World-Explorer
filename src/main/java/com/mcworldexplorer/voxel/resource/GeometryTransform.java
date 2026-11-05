package com.mcworldexplorer.voxel.resource;

import java.util.*;
import org.joml.Matrix4d;
import org.joml.Matrix3d;
import org.joml.Vector3d;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;

/** Shared vertex, normal and material transforms for ordinary models. */
final class GeometryTransform {
    private GeometryTransform() {}
    static Part transform(Part part,Matrix4d pose){
        return transform(part,pose,false);
    }
    static Part transform(Part part,Matrix4d pose,boolean preserveSourceCull){
        List<Face> faces=new ArrayList<>();Matrix3d normalMatrix=pose.normal(new Matrix3d());
        for(var face:part.faces()){
            var quad=ModelGeometry.bake(face,part);List<Vec3> points=new ArrayList<>(4);List<TexCoord> uv=new ArrayList<>(4);
            for(int i=0;i<4;i++){var p=pose.transformPosition(new Vector3d(quad.positions().get(i*3),quad.positions().get(i*3+1),quad.positions().get(i*3+2)));points.add(new Vec3(p.x*16,p.y*16,p.z*16));uv.add(new TexCoord(quad.uv().get(i*2),quad.uv().get(i*2+1)));}
            var transformedNormal=normalMatrix.transform(new Vector3d(quad.normal().get(0),quad.normal().get(1),quad.normal().get(2)));
            if(pose.determinant3x3()<0){Collections.reverse(points);Collections.reverse(uv);}
            var material=face.material();if(material.shadeDirectionOverride()!=null){double[] n=ModelGeometry.normal(material.shadeDirectionOverride());ModelGeometry.blockRotate(n,part.xRotation(),part.yRotation());var vector=normalMatrix.transform(new Vector3d(n[0],n[1],n[2]));material=new FaceMaterial(material.argb(),material.blockLight(),material.skyLight(),material.ambientOcclusion(),material.forceTranslucent(),nearest(vector));}
            faces.add(new Face(nearest(transformedNormal),face.texture(),face.uv(),0,face.tintIndex(),preserveSourceCull?quad.cull():null,face.layer(),face.from(),face.to(),null,face.shade(),points,uv,face.opacity(),material));
        }return new Part(part.id(),0,0,false,faces,part.renderType());
    }
    private static Direction nearest(Vector3d n){if(Math.abs(n.y)>=Math.abs(n.x)&&Math.abs(n.y)>=Math.abs(n.z))return n.y<0?Direction.DOWN:Direction.UP;if(Math.abs(n.x)>=Math.abs(n.z))return n.x<0?Direction.WEST:Direction.EAST;return n.z<0?Direction.NORTH:Direction.SOUTH;}
}
