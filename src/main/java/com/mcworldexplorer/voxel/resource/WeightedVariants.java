package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** World blockstate variant selection. The weights are compiled once, independently of geometry and tint. */
final class WeightedVariants {
    enum Draw { LEGACY, BOUNDED }
    static Draw protocol(int resourceFormat) {
        if (resourceFormat >= 4 && resourceFormat <= 36) return Draw.LEGACY;
        if (resourceFormat >= 37 && resourceFormat <= 97) return Draw.BOUNDED;
        return null;
    }

    private static final long MULTIPLIER=0x5deece66dL, MASK=(1L<<48)-1;
    private static long next(long bits) { return (bits*MULTIPLIER+11)&MASK; }
    private static long nextLong(long bits) {
        long first=next(bits);
        return ((long)(int)(first>>>16)<<32)+(int)(next(first)>>>16);
    }
    private static int ticket(Draw draw,long bits,int bound) {
        if (draw==Draw.LEGACY) return Math.abs((int)nextLong(bits))%bound;
        long first=next(bits);
        int value=(int)(first>>>17);
        if ((bound & (bound-1))==0) return (int)((bound*(long)value)>>31);
        int result=value%bound;
        while(value-result+(bound-1)<0) {
            first=next(first);
            value=(int)(first>>>17);
            result=value%bound;
        }
        return result;
    }
    // Mth.getSeed multiplies X as an int before widening. The world seed is not an input.
    static long positionSeed(int x,int y,int z) {
        long hash=(long)(x*3129871) ^ (long)z*116129781L ^ y;
        return (hash*hash*42317861L+hash*11L)>>16;
    }

    private final Draw draw;
    private final boolean multipart;
    private final List<int[]> groups; // cumulative weights of the non-singleton applications, in declaration order
    private final int dx,dy,dz;

    WeightedVariants(Draw draw,boolean multipart,List<int[]> groups,BlockState state) {
        this.draw=draw; this.multipart=multipart; this.groups=List.copyOf(groups);
        int x=0,y=0,z=0;
        if(state.name().startsWith("minecraft:")) {
            // Vanilla doors and two-block plants anchor at the lower half, beds at the head.
            if ("upper".equals(state.properties().get("half"))) y=-1;
            if (state.name().endsWith("_bed") && "foot".equals(state.properties().get("part"))) {
                switch(state.properties().getOrDefault("facing","")) {
                    case "north" -> z=-1; case "south" -> z=1;
                    case "west" -> x=-1; case "east" -> x=1;
                }
            }
        }
        dx=x;dy=y;dz=z;
    }
    long bits(long x,int y,long z) {
        long bits=(positionSeed((int)x+dx,y+dy,(int)z+dz)^MULTIPLIER)&MASK;
        return multipart ? (nextLong(bits)^MULTIPLIER)&MASK : bits;
    }
    int weightEntries() {
        int count=0;
        for(var ends:groups)count+=ends.length;
        return count;
    }
    int index(int group,long bits) {
        var ends=groups.get(group);
        int value=ticket(draw,bits,ends[ends.length-1]);
        int i=Arrays.binarySearch(ends,value+1);
        return i>=0?i:-i-1;
    }

    /** Intern only combinations actually encountered in the target and its one-block mesh border. */
    static final class States {
        private final BlockState base;
        private final WeightedVariants plan;
        private final BlockState[] single;
        private final Map<String,BlockState> multiple;
        private final BlockState overflow;
        States(BlockState base,WeightedVariants plan) {
            this.base=base;this.plan=plan;
            single=plan.groups.size()==1?new BlockState[plan.groups.getFirst().length]:null;
            multiple=single==null?new HashMap<>():null;
            overflow=derived("limit");
            if(single!=null)single[0]=base;
            else multiple.put(new String(new char[plan.groups.size()]),base);
        }
        WeightedVariants plan(){return plan;}
        BlockState state(long x,int y,long z,boolean canCreate) {
            long bits=plan.bits(x,y,z);
            if(single!=null) {
                int i=plan.index(0,bits);
                var known=single[i];
                if(known!=null)return known;
                return canCreate ? single[i]=derived(Integer.toString(i)) : overflow;
            }
            char[] indices=new char[plan.groups.size()];
            for(int i=0;i<indices.length;i++)indices[i]=(char)plan.index(i,bits);
            String key=new String(indices);
            var known=multiple.get(key);
            if(known!=null)return known;
            if(!canCreate)return overflow;
            StringBuilder label=new StringBuilder();
            for(char index:indices){if(!label.isEmpty())label.append('.');label.append((int)index);}
            var selected=derived(label.toString());
            multiple.put(key,selected);
            return selected;
        }
        private BlockState derived(String label) {
            var properties=new TreeMap<>(base.properties());properties.put("__mcwe_variant",label);
            return base.withProperties(properties);
        }
    }
}
