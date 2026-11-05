package com.mcworldexplorer.voxel.data;

import com.mcworldexplorer.voxel.io.ChunkDecodeException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkSectionCountsTest {
    @Test void exactCountsExcludeUnusedEntriesAndMergeDuplicateStates()throws Exception {
        var stone=new BlockState("test:stone",Map.of());var unused=new BlockState("test:unused",Map.of());long[] storage=new long[256];storage[0]=0x2211;
        var section=new ChunkSection(0,List.of(BlockState.AIR,stone,stone,unused),storage);var counts=section.usedStateCounts();
        assertEquals(Map.of(BlockState.AIR,4092,stone,4),counts);assertFalse(counts.containsKey(unused));assertThrows(UnsupportedOperationException.class,()->counts.clear());
        assertEquals(Map.of(stone,4096),new ChunkSection(0,List.of(stone),new long[0]).usedStateCounts());
    }
    @Test void usedCountsRejectOutOfRangePaletteIndices()throws Exception {
        long[] data=new long[256];data[0]=15;var section=new ChunkSection(0,List.of(BlockState.AIR,new BlockState("test:block",Map.of())),data);
        assertThrows(ChunkDecodeException.class,section::usedStateCounts);
    }
}
