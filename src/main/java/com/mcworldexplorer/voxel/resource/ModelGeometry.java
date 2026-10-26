package com.mcworldexplorer.voxel.resource;

import java.util.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;

/** Pure model-space baking. Element rotation precedes blockstate rotation. */
final class ModelGeometry {
    record Quad(Direction direction, Direction cull, List<Float> positions, List<Float> normal, List<Float> uv) { }
    static Quad bake(Face face, Part part) {
        float[] p = vertices(face.direction());
        double[] a = {face.from().x(),face.from().y(),face.from().z()};
        double[] b = {face.to().x(),face.to().y(),face.to().z()};
        List<Float> positions = new ArrayList<>(12);
        for (int i=0;i<4;i++) {
            double[] v = face.vertices().isEmpty()?new double[]{a[0]+p[i*3]*(b[0]-a[0]),a[1]+p[i*3+1]*(b[1]-a[1]),a[2]+p[i*3+2]*(b[2]-a[2])}:
                new double[]{face.vertices().get(i).x(),face.vertices().get(i).y(),face.vertices().get(i).z()};
            var r = face.elementRotation();
            if (r != null) {
                double[] origin = {r.origin().x(),r.origin().y(),r.origin().z()};
                for(int k=0;k<3;k++)v[k]-=origin[k];
                if(r.rescale()) {
                    double scale=1/Math.abs(Math.cos(Math.toRadians(r.angle())));
                    for(int k=0;k<3;k++)if(k!="xyz".indexOf(r.axis()))v[k]*=scale;
                }
                rotate(v,r.axis(),r.angle());
                for(int k=0;k<3;k++)v[k]+=origin[k];
            }
            for(int k=0;k<3;k++)v[k]=v[k]/16-.5;
            blockRotate(v,part.xRotation(),part.yRotation());
            double[] offset={part.offset().x(),part.offset().y(),part.offset().z()};
            for(int k=0;k<3;k++) positions.add((float)(v[k]+.5+offset[k]));
        }
        double[] edge1 = new double[3], edge2 = new double[3];
        for(int k=0;k<3;k++){edge1[k]=positions.get(3+k)-positions.get(k);edge2[k]=positions.get(6+k)-positions.get(k);}
        double[] n={edge1[1]*edge2[2]-edge1[2]*edge2[1],edge1[2]*edge2[0]-edge1[0]*edge2[2],edge1[0]*edge2[1]-edge1[1]*edge2[0]};
        double length=Math.sqrt(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]);
        if(length>1e-9)for(int k=0;k<3;k++)n[k]/=length;
        Direction direction=nearest(n);
        Direction cull=null;
        if(face.cullFace()!=null){double[] c=normal(face.cullFace());blockRotate(c,part.xRotation(),part.yRotation());cull=nearest(c);}
        boolean horizontal=face.direction()==Direction.UP||face.direction()==Direction.DOWN;
        float[][] corners=horizontal?new float[][]{{0,0},{0,1},{1,1},{1,0}}:new float[][]{{0,1},{1,1},{1,0},{0,0}};
        List<Float> uv=new ArrayList<>(8);
        for(int i=0;i<4;i++) {
            if(!face.vertexUv().isEmpty()) {
                uv.add((float)face.vertexUv().get(i).u());uv.add((float)face.vertexUv().get(i).v());continue;
            }
            float u=corners[i][0],v=corners[i][1];
            if(part.uvLock()) {
                // Express transformed positions in the destination face's unrotated texture basis.
                double[] vertex={p[i*3]-.5,p[i*3+1]-.5,p[i*3+2]-.5};
                blockRotate(vertex,part.xRotation(),part.yRotation());
                double[] basis=uvAt(direction,vertex[0]+.5,vertex[1]+.5,vertex[2]+.5);
                u=(float)basis[0];v=(float)basis[1];
            }
            for(int turn=0;turn<face.rotation()/90;turn++){float old=u;u=v;v=1-old;}
            uv.add((float)(face.uv().u0()+u*(face.uv().u1()-face.uv().u0()))/16);
            uv.add((float)(face.uv().v0()+v*(face.uv().v1()-face.uv().v0()))/16);
        }
        return new Quad(direction,cull,List.copyOf(positions),List.of((float)n[0],(float)n[1],(float)n[2]),List.copyOf(uv));
    }
    static double[] uvAt(Direction d,double x,double y,double z){return switch(d){
        case DOWN->new double[]{x,1-z};case UP->new double[]{x,z};case NORTH->new double[]{1-x,1-y};
        case SOUTH->new double[]{x,1-y};case WEST->new double[]{z,1-y};case EAST->new double[]{1-z,1-y};};}
    static void blockRotate(double[] v,int x,int y){rotate(v,"x",-x);rotate(v,"y",-y);}
    static void rotate(double[] v,String axis,double angle){double s=Math.sin(Math.toRadians(angle)),c=Math.cos(Math.toRadians(angle));int k="xyz".indexOf(axis),a=(k+1)%3,b=(k+2)%3;double old=v[a];v[a]=old*c-v[b]*s;v[b]=old*s+v[b]*c;}
    static double[] normal(Direction d){return switch(d){case DOWN->new double[]{0,-1,0};case UP->new double[]{0,1,0};case NORTH->new double[]{0,0,-1};case SOUTH->new double[]{0,0,1};case WEST->new double[]{-1,0,0};case EAST->new double[]{1,0,0};};}
    private static Direction nearest(double[] n){if(Math.abs(n[1])>=Math.abs(n[0])&&Math.abs(n[1])>=Math.abs(n[2]))return n[1]<0?Direction.DOWN:Direction.UP;if(Math.abs(n[0])>=Math.abs(n[2]))return n[0]<0?Direction.WEST:Direction.EAST;return n[2]<0?Direction.NORTH:Direction.SOUTH;}
    private static float[] vertices(Direction d){return switch(d){
        case WEST->new float[]{0,0,0,0,0,1,0,1,1,0,1,0};case EAST->new float[]{1,0,1,1,0,0,1,1,0,1,1,1};
        case DOWN->new float[]{0,0,1,0,0,0,1,0,0,1,0,1};case UP->new float[]{0,1,0,0,1,1,1,1,1,1,1,0};
        case NORTH->new float[]{1,0,0,0,0,0,0,1,0,1,1,0};case SOUTH->new float[]{0,0,1,1,0,1,1,1,1,0,1,1};};}
}
