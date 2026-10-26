package com.mcworldexplorer.voxel.resource;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LayeredResourceSourceTest {
    private static BlockResourceSource source(String name,Map<String,byte[]> entries) {
        return new BlockResourceSource(){
            public ClientResourceArchive.Fingerprint fingerprint(){return new ClientResourceArchive.Fingerprint(Path.of(name).toAbsolutePath(),1,1,name);}
            public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id){return Optional.ofNullable(entries.get(id.entryName(kind)));}
            public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id){return read(kind,id).map(bytes->new String(bytes,StandardCharsets.UTF_8));}
        };
    }
    @Test void firstSourceWinsAndMissingResourcesResolveAcrossNamespaces() throws Exception {
        var id=ResourceLocation.parse("example:block/shape");String path=id.entryName(ResourceLocation.Kind.MODEL);
        var lower=source("client",Map.of(path,"lower".getBytes(StandardCharsets.UTF_8)));
        var upper=source("pack",Map.of(path,"upper".getBytes(StandardCharsets.UTF_8),"assets/another/models/block/other.json","{}".getBytes(StandardCharsets.UTF_8)));
        var first=new LayeredResourceSource(List.of(upper,lower));var reversed=new LayeredResourceSource(List.of(lower,upper));
        assertEquals("upper",first.readJson(ResourceLocation.Kind.MODEL,id).orElseThrow());
        assertEquals("lower",reversed.readJson(ResourceLocation.Kind.MODEL,id).orElseThrow());
        assertTrue(reversed.readJson(ResourceLocation.Kind.MODEL,ResourceLocation.parse("another:block/other")).isPresent());
        assertNotEquals(first.fingerprint(),reversed.fingerprint());
        assertThrows(IllegalArgumentException.class,()->new LayeredResourceSource(List.of()));
        assertThrows(IllegalArgumentException.class,()->first.readJson(ResourceLocation.Kind.TEXTURE,id));
    }
    @Test void rejectsBadUtf8AndEnforcesAggregateReadBudget() throws Exception {
        var id=ResourceLocation.parse("example:block/shape");
        var bad=new LayeredResourceSource(List.of(source("bad",Map.of(id.entryName(ResourceLocation.Kind.MODEL),new byte[]{(byte)0xFF}))));
        assertThrows(ResourceAccessException.class,()->bad.readJson(ResourceLocation.Kind.MODEL,id));
        byte[] oneMiB=new byte[1024*1024];var bounded=new LayeredResourceSource(List.of(source("large",Map.of(id.entryName(ResourceLocation.Kind.MODEL),oneMiB))));
        for(int i=0;i<64;i++)assertTrue(bounded.read(ResourceLocation.Kind.MODEL,id).isPresent());
        var failure=assertThrows(ResourceAccessException.class,()->bounded.read(ResourceLocation.Kind.MODEL,id));
        assertEquals(ResourceAccessException.Reason.LIMIT_EXCEEDED,failure.reason());
    }
}
