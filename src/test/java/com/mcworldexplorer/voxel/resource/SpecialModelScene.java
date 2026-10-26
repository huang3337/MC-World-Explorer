package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.io.VoxelAreaLoadResult;
import net.kyori.adventure.nbt.*;
import java.util.*;

/** Small real-resource fixture for renderer families and per-position entity appearance. */
public final class SpecialModelScene {
    public static BlockState state(String name,String... properties) {
        Map<String,String> p=new TreeMap<>();for(int i=0;i<properties.length;i+=2)p.put(properties[i],properties[i+1]);
        return new BlockState("minecraft:"+name,p);
    }
    public static BlockEntityData entity(int slot,CompoundBinaryTag appearance) {
        var data=CompoundBinaryTag.builder().put(appearance).putInt("x",1+slot%4*3).putInt("y",1).putInt("z",1+slot/4*3).build();
        return BlockEntityData.read(data,0,0).orElseThrow();
    }
    public static CompoundBinaryTag text(String line) {
        return CompoundBinaryTag.builder().putString("id","minecraft:sign").put("front_text",CompoundBinaryTag.builder()
            .putString("color","black").put("messages",ListBinaryTag.listBinaryTag(BinaryTagTypes.STRING,List.of(StringBinaryTag.stringBinaryTag("{\"text\":\""+line+"\"}")))).build()).build();
    }
    public static VoxelAreaLoadResult area() throws Exception {
        var states=List.of(state("chest","facing","south","type","single","waterlogged","false"),
            state("trapped_chest","facing","south","type","left","waterlogged","false"),
            state("ender_chest","facing","south","waterlogged","false"),state("red_shulker_box","facing","up"),
            state("red_bed","facing","north","part","head","occupied","false"),state("red_bed","facing","north","part","foot","occupied","false"),
            state("oak_sign","rotation","0","waterlogged","false"),state("oak_sign","rotation","0","waterlogged","false"),
            state("white_banner","rotation","0"),state("oak_hanging_sign","rotation","0","attached","false","waterlogged","false"),
            state("skeleton_skull","rotation","0","powered","false"),state("player_head","rotation","0","powered","false"),
            state("dragon_head","rotation","0","powered","false"),state("piglin_head","rotation","0","powered","false"),
            state("decorated_pot","facing","north","cracked","false","waterlogged","false"),state("conduit","waterlogged","false"));
        var banner=CompoundBinaryTag.builder().putString("id","minecraft:banner").put("patterns",ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND,List.of(
            CompoundBinaryTag.builder().putString("pattern","minecraft:cross").putString("color","red").build()))).build();
        var pot=CompoundBinaryTag.builder().putString("id","minecraft:decorated_pot").put("sherds",ListBinaryTag.listBinaryTag(BinaryTagTypes.STRING,List.of(
            StringBinaryTag.stringBinaryTag("minecraft:angler_pottery_sherd"),StringBinaryTag.stringBinaryTag("minecraft:brick"),StringBinaryTag.stringBinaryTag("minecraft:brick"),StringBinaryTag.stringBinaryTag("minecraft:archer_pottery_sherd")))).build();
        return GenericModelScene.area(states,List.of(entity(6,text("Hello")),entity(7,text("World")),entity(8,banner),entity(9,text("Hanging")),entity(14,pot)));
    }
}
