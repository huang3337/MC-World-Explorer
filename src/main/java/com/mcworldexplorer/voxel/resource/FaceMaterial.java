package com.mcworldexplorer.voxel.resource;

/** Immutable extra face data, independent of biome tint indices and resource identifiers. */
public record FaceMaterial(int argb,int blockLight,int skyLight,boolean ambientOcclusion,boolean forceTranslucent,BlockModelResolver.Direction shadeDirectionOverride) {
    public static final FaceMaterial DEFAULT=new FaceMaterial(0xFFFFFFFF,0,0,true);
    public FaceMaterial(int argb,int blockLight,int skyLight,boolean ambientOcclusion){this(argb,blockLight,skyLight,ambientOcclusion,false,null);}
    public FaceMaterial {
        if(blockLight<0||blockLight>15||skyLight<0||skyLight>15)throw new IllegalArgumentException("face light must be within 0..15");
    }
    public float minimumLight(){return Math.max(blockLight,skyLight)/15f;}
}
