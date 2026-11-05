package com.mcworldexplorer.voxel.data;

import net.kyori.adventure.nbt.*;
import java.util.*;

/** Saved 4x4x4 biome cells only. These are not the game's seed-jittered block-position biomes. */
public final class BiomeSection {
    public enum Status { AVAILABLE, INVALID }
    private final List<String> cells;
    private final String detail;
    private BiomeSection(List<String> cells,String detail){this.cells=List.copyOf(cells);this.detail=Objects.requireNonNull(detail);}
    public Status status(){return cells.isEmpty()?Status.INVALID:Status.AVAILABLE;}
    public String detail(){return detail;}
    public Optional<String> at(int x,int y,int z){
        if(x<0||x>3||y<0||y>3||z<0||z>3)throw new IndexOutOfBoundsException("biome cell coordinates must be 0..3");
        return cells.isEmpty()?Optional.empty():Optional.of(cells.get((y*4+z)*4+x));
    }
    /** Malformed biome input does not discard otherwise readable block geometry. */
    public static BiomeSection read(BinaryTag tag){
        if(!(tag instanceof CompoundBinaryTag compound))return invalid("biomes must be a compound");
        if(!(compound.get("palette") instanceof ListBinaryTag palette)||palette.elementType()!=BinaryTagTypes.STRING||palette.size()<1||palette.size()>64)
            return invalid("biome palette must contain 1..64 resource identifiers");
        var names=new ArrayList<String>();
        for(var value:palette){
            String name=((StringBinaryTag)value).value();
            if(name.length()>512||!name.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))return invalid("invalid saved biome identifier");
            names.add(name);
        }
        BinaryTag stored=compound.get("data");
        if(stored!=null&&!(stored instanceof LongArrayBinaryTag))return invalid("biome data must be a long array");
        long[] data=stored==null?new long[0]:((LongArrayBinaryTag)stored).value();
        if(names.size()==1){
            if(data.length!=0)return invalid("single-biome palette must not contain packed values");
            return new BiomeSection(Collections.nCopies(64,names.getFirst()),"");
        }
        int bits=Integer.SIZE-Integer.numberOfLeadingZeros(names.size()-1),perLong=64/bits;
        if(data.length!=(64+perLong-1)/perLong)return invalid("invalid padded biome data length");
        var values=new ArrayList<String>(64);long mask=(1L<<bits)-1;
        for(int cell=0;cell<64;cell++){
            int index=(int)(data[cell/perLong]>>>(cell%perLong*bits)&mask);
            if(index>=names.size())return invalid("biome palette index outside saved palette at cell "+cell);
            values.add(names.get(index));
        }
        return new BiomeSection(values,"");
    }
    private static BiomeSection invalid(String detail){return new BiomeSection(List.of(),detail);}
}
