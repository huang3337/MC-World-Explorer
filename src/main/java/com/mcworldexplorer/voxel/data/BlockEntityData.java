package com.mcworldexplorer.voxel.data;

import net.kyori.adventure.nbt.*;
import java.io.*;
import java.util.*;

/** Immutable, bounded visual metadata only. Inventories and unrelated gameplay data are discarded. */
public record BlockEntityData(int x,int y,int z,CompoundBinaryTag visual,String fingerprint) {
    private static final Set<String> VISUAL=Set.of("id","front_text","back_text","Text1","Text2","Text3","Text4","Color","GlowingText",
            "Patterns","patterns","Base","base_color","profile","SkullOwner","Owner","Rot","rotation","sherds","Sherds","attachment","blockState","progress","facing","extending","source");
    public static Optional<BlockEntityData> read(CompoundBinaryTag tag,int chunkX,int chunkZ) {
        for(String key:List.of("x","y","z"))if(tag.get(key)==null||tag.get(key).type()!=BinaryTagTypes.INT)return Optional.empty();
        int x=tag.getInt("x"),y=tag.getInt("y"),z=tag.getInt("z");
        if(Math.floorDiv(x,16)!=chunkX||Math.floorDiv(z,16)!=chunkZ)return Optional.empty();
        var builder=CompoundBinaryTag.builder();int[] budget={2048};
        for(String key:new TreeSet<>(tag.keySet()))if(VISUAL.contains(key)) {
            var value=tag.get(key);
            if(bounded(value,0,budget))builder.put(key,value);
            else builder.putString("__visual_warning","部分方块实体外观数据超出预算，使用基本外观");
        }
        var visual=builder.build();
        try {
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            BinaryTagIO.writer().write(visual,output,BinaryTagIO.Compression.NONE);
            if(output.size()>65536){visual=CompoundBinaryTag.builder().putString("id",tag.getString("id")).putString("__visual_warning","外观 NBT 超过 64 KiB").build();output.reset();BinaryTagIO.writer().write(visual,output,BinaryTagIO.Compression.NONE);}
            String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(output.toByteArray()));
            return Optional.of(new BlockEntityData(x,y,z,visual,hash));
        }catch(IOException|java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static boolean bounded(BinaryTag value,int depth,int[] budget) {
        if(value==null||depth>16||--budget[0]<0)return false;
        if(value instanceof StringBinaryTag text)return text.value().length()<=8192;
        if(value instanceof ListBinaryTag list){if(list.size()>256)return false;for(var child:list)if(!bounded(child,depth+1,budget))return false;}
        if(value instanceof CompoundBinaryTag compound){if(compound.keySet().size()>256)return false;for(String key:compound.keySet())if(key.length()>256||!bounded(compound.get(key),depth+1,budget))return false;}
        if(value instanceof ByteArrayBinaryTag array)return array.value().length<=8192;
        if(value instanceof IntArrayBinaryTag array)return array.value().length<=2048;
        if(value instanceof LongArrayBinaryTag array)return array.value().length<=1024;
        return true;
    }
}
