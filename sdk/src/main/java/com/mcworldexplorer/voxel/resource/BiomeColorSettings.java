package com.mcworldexplorer.voxel.resource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Only the selected instance's saved color setting. No game options or files are changed. */
record BiomeColorSettings(Optional<Integer> radius,String problem) {
    static BiomeColorSettings read(Optional<Path> instance){
        if(instance.isEmpty())return new BiomeColorSettings(Optional.empty(),"");
        var path=instance.orElseThrow().resolve("options.txt");
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return new BiomeColorSettings(Optional.of(2),"");
        try{
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>1024*1024)throw new IOException("options.txt不是1MiB以内的普通文件");
            byte[] bytes;try(var input=Files.newInputStream(path)){bytes=input.readNBytes(1024*1024+1);}
            if(bytes.length>1024*1024)throw new IOException("options.txt超过1MiB");
            var text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var values=text.lines().filter(line->line.startsWith("biomeBlendRadius:")).map(line->line.substring(17)).toList();
            if(values.size()>1)throw new IOException("群系混合半径重复，不能确定有效设置");
            int radius=values.isEmpty()?2:Integer.parseInt(values.getFirst());
            if(radius<0||radius>7)throw new IOException("群系混合半径不在0..7范围");
            return new BiomeColorSettings(Optional.of(radius),"");
        }catch(IOException|NumberFormatException invalid){return new BiomeColorSettings(Optional.empty(),"群系混合设置不可用："+invalid.getMessage());}
    }
}
