package com.mcworldexplorer.voxel.resource;

import java.nio.*;
import java.security.*;

/** Inspected 1.20.1/1.21.1 BiomeManager seeded nearest-quart selection; no game classes loaded. */
final class SavedBiomeSampler {
    record Quart(int x,int y,int z) { }
    static long obfuscateSeed(long seed){
        try{
            byte[] input=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(seed).array();
            return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(input)).order(ByteOrder.LITTLE_ENDIAN).getLong();
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    static Quart sample(long seed,int x,int y,int z){
        int shiftedX=Math.subtractExact(x,2),shiftedY=Math.subtractExact(y,2),shiftedZ=Math.subtractExact(z,2);
        int qx=shiftedX>>2,qy=shiftedY>>2,qz=shiftedZ>>2;
        double fx=(shiftedX&3)/4.0,fy=(shiftedY&3)/4.0,fz=(shiftedZ&3)/4.0;
        double best=Double.POSITIVE_INFINITY;int selected=0;
        for(int i=0;i<8;i++){
            boolean lowerX=(i&4)==0,lowerY=(i&2)==0,lowerZ=(i&1)==0;
            double distance=distance(seed,lowerX?qx:qx+1,lowerY?qy:qy+1,lowerZ?qz:qz+1,lowerX?fx:fx-1,lowerY?fy:fy-1,lowerZ?fz:fz-1);
            if(distance<best){selected=i;best=distance;}
        }
        return new Quart((selected&4)==0?qx:qx+1,(selected&2)==0?qy:qy+1,(selected&1)==0?qz:qz+1);
    }
    private static double distance(long seed,int x,int y,int z,double fx,double fy,double fz){
        long h=next(seed,x);h=next(h,y);h=next(h,z);h=next(h,x);h=next(h,y);h=next(h,z);
        double dx=fiddle(h);h=next(h,seed);double dy=fiddle(h);h=next(h,seed);double dz=fiddle(h);
        double a=fz+dz,b=fy+dy,c=fx+dx;return a*a+b*b+c*c;
    }
    private static long next(long value,long salt){return value*(value*6364136223846793005L+1442695040888963407L)+salt;}
    private static double fiddle(long value){return (Math.floorMod(value>>24,1024L)/1024.0-.5)*.9;}
}
