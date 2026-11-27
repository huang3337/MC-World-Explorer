package com.mcworldexplorer.voxel.mesh;

import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.data.SavedFluid;
import com.mcworldexplorer.voxel.io.ChunkDecodeException;
import com.mcworldexplorer.voxel.resource.BlockModelResolver.Direction;
import com.mcworldexplorer.voxel.resource.BlockTextureSet.Surface;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Reconstructs the static liquid surface from the saved neighborhood. */
final class FluidGeometry {
    interface Samples { BlockState at(int dx,int dy,int dz) throws ChunkDecodeException; }
    private static final int[] DX={-1,1,0,0}, DZ={0,0,-1,1};
    private final float[] corners = new float[4]; // x + 2*z
    private float sin,cos;
    private boolean flowing;

    FluidGeometry(BlockState center,Samples samples,Predicate<BlockState> solid,Predicate<BlockState> blocksFlow) throws ChunkDecodeException {
        var fluid=SavedFluid.of(center);
        boolean covered=SavedFluid.of(samples.at(0,1,0))==fluid;
        float own=SavedFluid.ownHeight(center);
        float[] heights=new float[4];
        double flowX=0,flowZ=0;
        for(int i=0;i<4;i++) {
            int x=DX[i],z=DZ[i];
            var side=samples.at(x,0,z);
            var type=SavedFluid.of(side);
            heights[i]=sampleHeight(side,fluid,samples,solid,x,z);
            float delta=0;
            if(type==fluid)delta=own-SavedFluid.ownHeight(side);
            else if(type==SavedFluid.NONE&&!blocksFlow.test(side)){
                var below=samples.at(x,-1,z);
                if(SavedFluid.of(below)==fluid)delta=own-(SavedFluid.ownHeight(below)-8f/9);
            }
            flowX+=x*delta;
            flowZ+=z*delta;
        }
        for(int z=0;z<=1;z++)for(int x=0;x<=1;x++) {
            float a=heights[x],b=heights[z+2];
            float value=1;
            if(!covered && a<1 && b<1) {
                float diag=-1;
                if(a>0||b>0) {
                    int dx=x*2-1,dz=z*2-1;
                    diag=sampleHeight(samples.at(dx,0,dz),fluid,samples,solid,dx,dz);
                }
                if(diag<1) {
                    float weight=weight(own)+weight(a)+weight(b)+weight(diag);
                    value=(own*weight(own)+a*weight(a)+b*weight(b)+diag*weight(diag))/weight;
                }
            }
            corners[x+2*z]=value;
        }
        flowing=flowX!=0 || flowZ!=0;
        if(flowing) {
            double angle=Math.atan2(flowZ,flowX)-Math.PI/2;
            sin=(float)(.25*Math.sin(angle));
            cos=(float)(.25*Math.cos(angle));
        }
    }
    private static float sampleHeight(BlockState state,SavedFluid fluid,Samples samples,Predicate<BlockState> solid,int x,int z)throws ChunkDecodeException {
        if(SavedFluid.of(state)!=fluid)return solid.test(state)?-1:0;
        return SavedFluid.of(samples.at(x,1,z))==fluid?1:SavedFluid.ownHeight(state);
    }
    private static int weight(float height){return height<0?0:height>=.8f?10:1;}
    float height(int x,int z){return corners[x+2*z];}
    boolean flowing(){return flowing;}

    Surface surface(Surface face,List<Surface> model) {
        boolean top=face.direction()==Direction.UP;
        if(face.direction()==Direction.DOWN)return face;
        var sprite=face;
        if(top&&flowing)for(var candidate:model)if(candidate.fluid()&&candidate.direction()==Direction.NORTH){sprite=candidate;break;}
        float uMin=Float.MAX_VALUE,vMin=Float.MAX_VALUE,uMax=-Float.MAX_VALUE,vMax=-Float.MAX_VALUE;
        for(int i=0;i<8;i+=2){
            float u=sprite.uv().get(i),v=sprite.uv().get(i+1);
            uMin=Math.min(uMin,u);uMax=Math.max(uMax,u);vMin=Math.min(vMin,v);vMax=Math.max(vMax,v);
        }
        var positions=new ArrayList<>(face.positions());
        var uv=new ArrayList<>(face.uv());
        for(int i=0;i<4;i++){
            int x=Math.round(positions.get(3*i)),z=Math.round(positions.get(3*i+2));
            float h=positions.get(3*i+1)>0?height(x,z):0;
            positions.set(3*i+1,h);
            float u,v;
            if(top){
                if(!flowing)continue;
                int sx=2*x-1,sz=2*z-1;
                u=.5f+sx*cos+sz*sin; v=.5f+sz*cos-sx*sin;
            } else {
                u=.5f*switch(face.direction()){case NORTH -> x;case EAST -> z;case SOUTH -> 1-x;default -> 1-z;};
                v=.5f*(1-h);
            }
            uv.set(2*i,uMin+u*(uMax-uMin));uv.set(2*i+1,vMin+v*(vMax-vMin));
        }
        return face.withFluidGeometry(positions, uv);
    }
}
