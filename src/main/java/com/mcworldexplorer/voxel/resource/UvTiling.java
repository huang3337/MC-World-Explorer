package com.mcworldexplorer.voxel.resource;

import java.io.IOException;
import java.util.*;

/** Clips repeated UVs into atlas-local unit tiles; never samples neighbouring atlas entries. */
final class UvTiling {
    private record Vertex(double x,double y,double z,double u,double v) {
        Vertex mix(Vertex b,double t){return new Vertex(x+(b.x-x)*t,y+(b.y-y)*t,z+(b.z-z)*t,u+(b.u-u)*t,v+(b.v-v)*t);}
    }
    static List<ModelGeometry.Quad> split(ModelGeometry.Quad quad)throws IOException {
        if(quad.uv().stream().allMatch(value->value>=0&&value<=1))return List.of(quad);
        List<Vertex> polygon=new ArrayList<>();
        for(int i=0;i<4;i++)polygon.add(new Vertex(quad.positions().get(i*3),quad.positions().get(i*3+1),quad.positions().get(i*3+2),quad.uv().get(i*2),quad.uv().get(i*2+1)));
        double minU=polygon.stream().mapToDouble(Vertex::u).min().orElseThrow(),maxU=polygon.stream().mapToDouble(Vertex::u).max().orElseThrow();
        double minV=polygon.stream().mapToDouble(Vertex::v).min().orElseThrow(),maxV=polygon.stream().mapToDouble(Vertex::v).max().orElseThrow();
        int u0=(int)Math.floor(minU),u1=Math.max(u0,(int)Math.ceil(maxU)-1),v0=(int)Math.floor(minV),v1=Math.max(v0,(int)Math.ceil(maxV)-1);
        if((long)(u1-u0+1)*(v1-v0+1)>256)throw new IOException("单面 UV 重复超过 256 个采样格预算");
        List<ModelGeometry.Quad> result=new ArrayList<>();
        for(int u=u0;u<=u1;u++)for(int v=v0;v<=v1;v++) {
            var clipped=clip(clip(clip(clip(polygon,0,u,true),0,u+1,false),1,v,true),1,v+1,false);
            if(clipped.size()<3)continue;
            if(clipped.size()==4)add(result,quad,clipped,u,v);
            else for(int i=1;i<clipped.size()-1;i++)add(result,quad,List.of(clipped.getFirst(),clipped.get(i),clipped.get(i+1),clipped.get(i+1)),u,v);
        }
        return List.copyOf(result);
    }
    private static List<Vertex> clip(List<Vertex> input,int axis,double bound,boolean greater) {
        if(input.isEmpty())return input;List<Vertex> output=new ArrayList<>();Vertex previous=input.getLast();
        for(var current:input) {
            double a=(axis==0?previous.u:previous.v)-bound,b=(axis==0?current.u:current.v)-bound;
            boolean insideA=greater?a>=0:a<=0,insideB=greater?b>=0:b<=0;
            if(insideA!=insideB)output.add(previous.mix(current,a/(a-b)));
            if(insideB)output.add(current);previous=current;
        }
        List<Vertex> unique=new ArrayList<>();
        for(var point:output)if(unique.isEmpty()||!same(unique.getLast(),point))unique.add(point);
        if(unique.size()>1&&same(unique.getFirst(),unique.getLast()))unique.removeLast();
        return unique;
    }
    private static boolean same(Vertex a,Vertex b){return Math.abs(a.x-b.x)<1e-9&&Math.abs(a.y-b.y)<1e-9&&Math.abs(a.z-b.z)<1e-9&&Math.abs(a.u-b.u)<1e-9&&Math.abs(a.v-b.v)<1e-9;}
    private static void add(List<ModelGeometry.Quad> output,ModelGeometry.Quad original,List<Vertex> polygon,int u,int v) {
        var a=polygon.get(0);var b=polygon.get(1);var c=polygon.get(2);
        double nx=(b.y-a.y)*(c.z-a.z)-(b.z-a.z)*(c.y-a.y),ny=(b.z-a.z)*(c.x-a.x)-(b.x-a.x)*(c.z-a.z),nz=(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);
        if(nx*nx+ny*ny+nz*nz<1e-16)return;
        List<Float> positions=new ArrayList<>(12),uv=new ArrayList<>(8);
        for(var point:polygon){positions.add((float)point.x);positions.add((float)point.y);positions.add((float)point.z);uv.add((float)Math.max(0,Math.min(1,point.u-u)));uv.add((float)Math.max(0,Math.min(1,point.v-v)));}
        output.add(new ModelGeometry.Quad(original.direction(),original.cull(),List.copyOf(positions),original.normal(),List.copyOf(uv)));
    }
}
