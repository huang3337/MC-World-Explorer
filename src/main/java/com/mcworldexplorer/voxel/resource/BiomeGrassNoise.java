package com.mcworldexplorer.voxel.resource;

import java.util.Random;

/** Inspected BIOME_INFO_NOISE: seed 2345, one zero octave, no coordinate offsets.
 * Pure arithmetic over host-owned data, not a general noise engine or game-code executor. */
final class BiomeGrassNoise {
    private static final int[][] GRADIENTS={{1,1},{-1,1},{1,-1},{-1,-1},{1,0},{-1,0},{1,0},{-1,0},{0,1},{0,-1},{0,1},{0,-1}};
    private static final double SKEW=(Math.sqrt(3)-1)/2,UNSKEW=(3-Math.sqrt(3))/6;
    private static final int[] PERMUTATION=permutation();
    private static int[] permutation(){
        // Reviewed LegacyRandomSource / BitRandomSource use java.util.Random's 48-bit stream.
        var random=new Random(2345);for(int i=0;i<3;i++)random.nextDouble();
        int[] values=new int[256];for(int i=0;i<256;i++)values[i]=i;
        for(int i=0;i<256;i++){int j=i+random.nextInt(256-i),old=values[i];values[i]=values[j];values[j]=old;}
        return values;
    }
    static int color(int x,int z){return sample(x*.0225,z*.0225)<-.1?5011004:6975545;}
    static double sample(double x,double z){
        double skew=(x+z)*SKEW;int cellX=(int)Math.floor(x+skew),cellZ=(int)Math.floor(z+skew);
        double unskew=(cellX+cellZ)*UNSKEW,dx=x-(cellX-unskew),dz=z-(cellZ-unskew);
        int stepX=dx>dz?1:0,stepZ=1-stepX,gx=cellX&255,gz=cellZ&255;
        return 70*(corner(p(gx+p(gz))%12,dx,dz)+corner(p(gx+stepX+p(gz+stepZ))%12,dx-stepX+UNSKEW,dz-stepZ+UNSKEW)
                +corner(p(gx+1+p(gz+1))%12,dx-1+2*UNSKEW,dz-1+2*UNSKEW));
    }
    private static int p(int index){return PERMUTATION[index&255];}
    private static double corner(int gradient,double x,double z){
        double weight=.5-x*x-z*z;if(weight<0)return 0;weight*=weight;
        return weight*weight*(GRADIENTS[gradient][0]*x+GRADIENTS[gradient][1]*z);
    }
}
