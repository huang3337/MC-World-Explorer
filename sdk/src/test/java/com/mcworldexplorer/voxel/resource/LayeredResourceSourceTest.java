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
    @Test void packOverridesTextureContentsWithoutChangingBaseLayout()throws Exception {
        var id=ResourceLocation.parse("entity/banner/banner_base");
        var pack=source("pack",Map.of(id.entryName(ResourceLocation.Kind.TEXTURE),new byte[]{1}));
        var client=source("client",Map.of());
        var resources=new LayeredResourceSource(List.of(pack,client));
        assertTrue(resources.read(ResourceLocation.Kind.TEXTURE,id).isPresent());
        assertFalse(resources.hasBaseTexture(id));
    }
    @Test void delegatesBaseClientCapabilitiesAndRejectsConflictingClientVersions() throws Exception {
        var base = new BlockResourceSource() {
            public ClientResourceArchive.Fingerprint fingerprint(){return new ClientResourceArchive.Fingerprint(Path.of("client").toAbsolutePath(),1,1,"client");}
            public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id){return Optional.empty();}
            public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id){return Optional.empty();}
            public Optional<String> baseClientVersion(){return Optional.of("1.16.5");}
            public int baseResourceFormat(){return 6;}
            public Optional<Boolean> baseDataRegistry(String directory){return Optional.of(false);}
            public Optional<Long> worldSeed(){return Optional.of(77L);}
            public Optional<Integer> biomeBlendRadius(){return Optional.of(2);}
        };
        var overlay = source("pack", Map.of());
        var layered = new LayeredResourceSource(List.of(overlay, base));
        assertEquals(Optional.of("1.16.5"), layered.baseClientVersion());
        assertEquals(6, layered.baseResourceFormat());
        assertEquals(Optional.of(false), layered.baseDataRegistry("worldgen/biome"));
        assertEquals(Optional.of(77L), layered.worldSeed());
        assertEquals(Optional.of(2), layered.biomeBlendRadius());

        var conflicting = new BlockResourceSource() {
            public ClientResourceArchive.Fingerprint fingerprint(){return new ClientResourceArchive.Fingerprint(Path.of("other").toAbsolutePath(),1,1,"other");}
            public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id){return Optional.empty();}
            public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id){return Optional.empty();}
            public Optional<String> baseClientVersion(){return Optional.of("1.18.2");}
        };
        assertEquals(Optional.empty(), new LayeredResourceSource(List.of(conflicting, base)).baseClientVersion());
    }
}
