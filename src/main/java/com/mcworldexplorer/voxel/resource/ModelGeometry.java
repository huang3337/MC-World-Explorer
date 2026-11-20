package com.mcworldexplorer.voxel.resource;

import java.util.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;

/** Pure model-space baking. Element rotation precedes blockstate rotation. */
final class ModelGeometry {
    record Quad(Direction direction, Direction cull, List<Float> positions, List<Float> normal, List<Float> uv) { }
    /** Cuboid rotations apply X, then Y, then Z around the element origin, before blockstate rotation. */
    static org.joml.Matrix4f cuboidTransform(Vec3 origin,double x,double y,double z,boolean rescale){
        var matrix=new org.joml.Matrix4f().rotationZYX(radians(z),radians(y),radians(x));
        if(rescale)matrix.scale(axisScale(matrix.m00(),matrix.m01(),matrix.m02()),
                axisScale(matrix.m10(),matrix.m11(),matrix.m12()),axisScale(matrix.m20(),matrix.m21(),matrix.m22()));
        return new org.joml.Matrix4f().translation((float)origin.x(),(float)origin.y(),(float)origin.z())
                .mul(matrix).translate((float)-origin.x(),(float)-origin.y(),(float)-origin.z());
    }
    private static float radians(double degrees){return (float)Math.toRadians(degrees%360);}
    private static float axisScale(float x,float y,float z){return 1/Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z)));}

    static List<Vec3> corners(Direction side,Vec3 from,Vec3 to,org.joml.Matrix4fc transform){
        float[] unit=vertices(side);
        List<Vec3> corners=new ArrayList<>(4);
        for(int i=0;i<unit.length;i+=3){
            var point=new org.joml.Vector3f((float)(unit[i]==0?from.x():to.x()),(float)(unit[i+1]==0?from.y():to.y()),
                    (float)(unit[i+2]==0?from.z():to.z()));
            transform.transformPosition(point);
            corners.add(new Vec3(point.x,point.y,point.z));
        }
        return corners;
    }

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
            blockRotate(v,part.xRotation(),part.yRotation(),part.zRotation());
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
        if(face.cullFace()!=null){double[] c=normal(face.cullFace());blockRotate(c,part.xRotation(),part.yRotation(),part.zRotation());cull=nearest(c);}
        boolean horizontal=face.direction()==Direction.UP||face.direction()==Direction.DOWN;
        float[][] corners=horizontal?new float[][]{{0,0},{0,1},{1,1},{1,0}}:new float[][]{{0,1},{1,1},{1,0},{0,0}};
        List<Float> uv=new ArrayList<>(8);
        Direction lockedSide = face.direction();
        if (part.uvLock()) {
            double[] axis = normal(lockedSide);
            blockRotate(axis,part.xRotation(),part.yRotation(),part.zRotation());
            lockedSide = nearest(axis);
        }
        for(int i=0;i<4;i++) {
            if(!face.vertexUv().isEmpty()) {
                uv.add((float)face.vertexUv().get(i).u());uv.add((float)face.vertexUv().get(i).v());continue;
            }
            float u=corners[i][0],v=corners[i][1];
            if(part.uvLock()) {
                // Express transformed positions in the destination face's unrotated texture basis.
                double[] vertex={p[i*3]-.5,p[i*3+1]-.5,p[i*3+2]-.5};
                blockRotate(vertex,part.xRotation(),part.yRotation(),part.zRotation());
                double[] basis=uvAt(lockedSide,vertex[0]+.5,vertex[1]+.5,vertex[2]+.5);
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
    static void blockRotate(double[] v,int x,int y,int z){
        quarterTurn(v,1,2,x);
        quarterTurn(v,2,0,y);
        quarterTurn(v,0,1,z);
    }
    // The blockstate codec permits only quarter turns. Exact coordinates keep adjacent
    // cube faces coincident for occlusion as well as UV locking.
    private static void quarterTurn(double[] v,int a,int b,int degrees){
        double old=v[a];
        switch(Math.floorMod(degrees,360)){
            case 0 -> { }
            case 90 -> {v[a]=v[b];v[b]=-old;}
            case 180 -> {v[a]=-old;v[b]=-v[b];}
            case 270 -> {v[a]=-v[b];v[b]=old;}
            default -> throw new IllegalArgumentException("Block rotation must be a quarter turn");
        }
    }
    static void rotate(double[] v,String axis,double angle){double s=Math.sin(Math.toRadians(angle)),c=Math.cos(Math.toRadians(angle));int k="xyz".indexOf(axis),a=(k+1)%3,b=(k+2)%3;double old=v[a];v[a]=old*c-v[b]*s;v[b]=old*s+v[b]*c;}
    static double[] normal(Direction d){return switch(d){case DOWN->new double[]{0,-1,0};case UP->new double[]{0,1,0};case NORTH->new double[]{0,0,-1};case SOUTH->new double[]{0,0,1};case WEST->new double[]{-1,0,0};case EAST->new double[]{1,0,0};};}
    private static Direction nearest(double[] n){if(Math.abs(n[1])>=Math.abs(n[0])&&Math.abs(n[1])>=Math.abs(n[2]))return n[1]<0?Direction.DOWN:Direction.UP;if(Math.abs(n[0])>=Math.abs(n[2]))return n[0]<0?Direction.WEST:Direction.EAST;return n[2]<0?Direction.NORTH:Direction.SOUTH;}
    private static float[] vertices(Direction d){return switch(d){
        case WEST->new float[]{0,0,0,0,0,1,0,1,1,0,1,0};case EAST->new float[]{1,0,1,1,0,0,1,1,0,1,1,1};
        case DOWN->new float[]{0,0,1,0,0,0,1,0,0,1,0,1};case UP->new float[]{0,1,0,0,1,1,1,1,1,1,1,0};
        case NORTH->new float[]{1,0,0,0,0,0,0,1,0,1,1,0};case SOUTH->new float[]{0,0,1,1,0,1,1,1,1,0,1,1};};}
}
