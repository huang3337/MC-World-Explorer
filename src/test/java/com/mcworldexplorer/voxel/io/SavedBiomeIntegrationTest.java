package com.mcworldexplorer.voxel.io;

import com.google.gson.*;
import com.mcworldexplorer.region.RegionFileReader;
import com.mcworldexplorer.voxel.data.BiomeSection;
import net.kyori.adventure.nbt.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SavedBiomeIntegrationTest {
    @Test void selectedModernRegionsKeepEverySampledBiomeCellAndRemainUnchanged()throws Exception {
        String selection=System.getenv("MCWORLD_BIOME_SELECTION"),destination=System.getenv("MCWE_UI_SNAPSHOT_DIR");assumeTrue(selection!=null&&destination!=null);
        var rows=JsonParser.parseString(Files.readString(Path.of(selection))).getAsJsonArray();assertTrue(rows.size()>0&&rows.size()<=32);
        var reports=new ArrayList<Map<String,Object>>();int totalChunks=0,totalSections=0,totalCells=0;
        for(var value:rows){
            var row=value.getAsJsonObject();Path path=Path.of(row.get("region").getAsString());String before=sha(path);
            var names=path.getFileName().toString().split("\\.");int rx=Integer.parseInt(names[1]),rz=Integer.parseInt(names[2]),chunks=0,sections=0;var identifiers=new TreeSet<String>();
            try(var reader=new RegionFileReader(path)){
                for(int z=0;z<32&&chunks<16;z++)for(int x=0;x<32&&chunks<16;x++){
                    var data=reader.readChunk(x,z);if(data.isEmpty())continue;
                    CompoundBinaryTag root;try(var stream=data.orElseThrow().openNbtStream()){root=BinaryTagIO.reader(64L*1024*1024).read(stream,BinaryTagIO.Compression.NONE);}
                    assertNotNull(root.get("sections"),"selected modern sample lacks root sections; audit its chunk version");
                    var chunk=ChunkNbtDecoder.read(data.orElseThrow(),rx*32+x,rz*32+z).chunk();
                    for(var tag:root.getList("sections",BinaryTagTypes.COMPOUND)){
                        var raw=(CompoundBinaryTag)tag;if(raw.get("biomes")==null)continue;
                        var decoded=chunk.biomeSection(raw.getInt("Y")).orElseThrow();assertEquals(BiomeSection.Status.AVAILABLE,decoded.status(),path+": "+decoded.detail());
                        var palette=raw.getCompound("biomes").getList("palette",BinaryTagTypes.STRING);var allowed=new HashSet<String>();for(var id:palette)allowed.add(((StringBinaryTag)id).value());
                        for(int by=0;by<4;by++)for(int bz=0;bz<4;bz++)for(int bx=0;bx<4;bx++){
                            var id=chunk.biomeAtQuart(bx,raw.getInt("Y")*4+by,bz).orElseThrow();assertTrue(allowed.contains(id));identifiers.add(id);totalCells++;
                        }sections++;
                    }chunks++;
                }
            }
            assertTrue(chunks>0&&sections>0,path.toString());assertEquals(before,sha(path));totalChunks+=chunks;totalSections+=sections;
            reports.add(Map.of("world",row.get("world").getAsString(),"region",path.toString(),"clientVersion",row.get("version").getAsString(),"sha256",before,"chunks",chunks,"biomeSections",sections,"identifiers",identifiers));
        }
        Path output=Path.of(destination);Files.createDirectories(output);
        Files.writeString(output.resolve("saved-biome-palette.json"),new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("scope","First 16 present chunks of one selected overworld region per available 1.20.1/1.21.1 world; raw saved cells only, not seed-jittered biome lookup or color acceptance","worlds",reports,"chunks",totalChunks,"biomeSections",totalSections,"cells",totalCells,"failures",List.of())));
    }
    private static String sha(Path path)throws Exception {
        var digest=java.security.MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path)){byte[] buffer=new byte[65536];for(int n;(n=input.read(buffer))!=-1;)digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());
    }
}
