package com.mcworldexplorer.voxel.data;

import net.kyori.adventure.nbt.*;
import java.io.*;
import java.util.*;

/** Immutable bounded legacy appearance plus format-neutral saved model input. */
public record BlockEntityData(int x,int y,int z,CompoundBinaryTag visual,String fingerprint,ModelData modelData) {
    public record ModelData(CompoundBinaryTag values,String fingerprint,boolean complete) {
        public ModelData {Objects.requireNonNull(values);Objects.requireNonNull(fingerprint);}
        public CompoundBinaryTag requireComplete(){if(!complete)throw new IllegalStateException("方块实体模型NBT超过预算，不能作为完整外观数据");return values;}
    }
    public BlockEntityData(int x,int y,int z,CompoundBinaryTag visual,String fingerprint){this(x,y,z,visual,fingerprint,new ModelData(visual,fingerprint,true));}
    public BlockEntityData {Objects.requireNonNull(visual);Objects.requireNonNull(fingerprint);Objects.requireNonNull(modelData);}
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
            return Optional.of(new BlockEntityData(x,y,z,visual,hash,savedModelData(tag)));
        }catch(IOException|java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static ModelData savedModelData(CompoundBinaryTag tag)throws IOException,java.security.NoSuchAlgorithmException {
        var builder=CompoundBinaryTag.builder();int[] nodes={2048};long bytes=16;boolean complete=true;
        for(String key:new TreeSet<>(tag.keySet())) {
            if(Set.of("x","y","z").contains(key))continue;
            var value=tag.get(key);
            if(key.length()>256||!bounded(value,0,nodes)){complete=false;continue;}
            long cost=3L*key.length()+3+encodedUpperBound(value);
            if(bytes+cost>65536){complete=false;continue;}
            builder.put(key,value);bytes+=cost;
        }
        var values=builder.build();var output=new ByteArrayOutputStream();BinaryTagIO.writer().write(values,output,BinaryTagIO.Compression.NONE);
        var digest=java.security.MessageDigest.getInstance("SHA-256");digest.update(output.toByteArray());digest.update((byte)(complete?1:0));
        return new ModelData(values,HexFormat.of().formatHex(digest.digest()),complete);
    }
    /** Conservative modified-UTF bound, after node/depth validation, before allocating serialized bytes. */
    private static long encodedUpperBound(BinaryTag value) {
        if(value instanceof StringBinaryTag text)return 2L+3L*text.value().length();
        if(value instanceof ByteArrayBinaryTag array)return 4L+array.value().length;
        if(value instanceof IntArrayBinaryTag array)return 4L+4L*array.value().length;
        if(value instanceof LongArrayBinaryTag array)return 4L+8L*array.value().length;
        if(value instanceof ListBinaryTag list){long size=5;for(var child:list)size+=encodedUpperBound(child);return size;}
        if(value instanceof CompoundBinaryTag compound){long size=1;for(String key:compound.keySet())size+=3L+3L*key.length()+encodedUpperBound(compound.get(key));return size;}
        return 8;
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
