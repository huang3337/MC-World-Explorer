package com.mcworldexplorer.voxel.data;

import net.kyori.adventure.nbt.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BiomeSectionTest {
    private static ListBinaryTag palette(int count){
        var values=new ArrayList<BinaryTag>();for(int i=0;i<count;i++)values.add(StringBinaryTag.stringBinaryTag("biome_author:biome_"+i));
        return ListBinaryTag.listBinaryTag(BinaryTagTypes.STRING,values);
    }
    @Test void readsAllPaddedWidthsAndUsesYzxCellOrder(){
        for(int count:List.of(2,3,5,9,17,33,64)){
            int bits=32-Integer.numberOfLeadingZeros(count-1),perLong=64/bits;long[] data=new long[(64+perLong-1)/perLong];
            for(int cell=0;cell<64;cell++)data[cell/perLong]|=(long)(cell%count)<<(cell%perLong*bits);
            var value=BiomeSection.read(CompoundBinaryTag.builder().put("palette",palette(count)).putLongArray("data",data).build());
            assertEquals(BiomeSection.Status.AVAILABLE,value.status(),value.detail());
            for(int y=0;y<4;y++)for(int z=0;z<4;z++)for(int x=0;x<4;x++)assertEquals("biome_author:biome_"+((y*16+z*4+x)%count),value.at(x,y,z).orElseThrow());
        }
    }
    @Test void singleEntryHasNoPackedStorageAndDoesNotInventCoordinates(){
        var value=BiomeSection.read(CompoundBinaryTag.builder().put("palette",palette(1)).build());
        assertEquals("biome_author:biome_0",value.at(3,3,3).orElseThrow());assertThrows(IndexOutOfBoundsException.class,()->value.at(-1,0,0));
        assertThrows(IndexOutOfBoundsException.class,()->value.at(0,4,0));
    }
    @Test void malformedPaletteAndStorageRemainInvalidRatherThanPlains(){
        var invalid=List.<BinaryTag>of(StringBinaryTag.stringBinaryTag("wrong"),CompoundBinaryTag.empty(),
            CompoundBinaryTag.builder().put("palette",palette(65)).build(),
            CompoundBinaryTag.builder().put("palette",palette(2)).putString("data","wrong").build(),
            CompoundBinaryTag.builder().put("palette",palette(3)).putLongArray("data",new long[]{3,0}).build(),
            CompoundBinaryTag.builder().put("palette",palette(5)).putLongArray("data",new long[3]).build(),
            CompoundBinaryTag.builder().put("palette",ListBinaryTag.listBinaryTag(BinaryTagTypes.STRING,List.of(StringBinaryTag.stringBinaryTag("Bad:ID")))).build());
        for(var tag:invalid){var value=BiomeSection.read(tag);assertEquals(BiomeSection.Status.INVALID,value.status());assertFalse(value.detail().isBlank());assertTrue(value.at(0,0,0).isEmpty());}
    }
}
