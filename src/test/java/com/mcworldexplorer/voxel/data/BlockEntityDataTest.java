package com.mcworldexplorer.voxel.data;

import net.kyori.adventure.nbt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockEntityDataTest {
    private static CompoundBinaryTag.Builder at(int x,int z) {
        return CompoundBinaryTag.builder().putInt("x",x).putInt("y",64).putInt("z",z).putString("id","minecraft:sign");
    }
    @Test void keepsAppearanceDiscardsInventoryAndHashesIndependentlyOfPosition() {
        var first=BlockEntityData.read(at(-1,-16).putString("Text1","Hello").put("Items",ListBinaryTag.empty()).build(),-1,-1).orElseThrow();
        var other=BlockEntityData.read(at(-2,-15).putString("Text1","Hello").build(),-1,-1).orElseThrow();
        assertEquals(first.fingerprint(),other.fingerprint());
        assertEquals("Hello",first.visual().getString("Text1"));
        assertFalse(first.visual().keySet().contains("Items"));
        assertTrue(BlockEntityData.read(at(16,0).build(),0,0).isEmpty());
        assertTrue(BlockEntityData.read(CompoundBinaryTag.builder().putString("x","0").putInt("y",0).putInt("z",0).build(),0,0).isEmpty());
    }
    @Test void oversizedAppearanceIsBoundedAndExplicitlyMarked() {
        var entity=BlockEntityData.read(at(0,0).putString("Text1","x".repeat(8193)).putString("Text2","kept").build(),0,0).orElseThrow();
        assertFalse(entity.visual().keySet().contains("Text1"));
        assertEquals("kept",entity.visual().getString("Text2"));
        assertFalse(entity.visual().getString("__visual_warning").isBlank());
    }
    @Test void chunkLookupUsesWorldCoordinatesAndDoesNotAliasAnotherPosition() throws Exception {
        var entity=BlockEntityData.read(at(-1,-1).putString("Text1","A").build(),-1,-1).orElseThrow();
        var chunk=new VoxelChunk(-1,-1,java.util.List.of(),java.util.List.of(entity));
        assertEquals(entity,chunk.blockEntity(-1,64,-1).orElseThrow());
        assertTrue(chunk.blockEntity(15,64,15).isEmpty());
    }
}
