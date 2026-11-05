package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.io.CancellationSignal;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BuiltinPackResourcesTest {
    @TempDir Path root;
    private static final String ENTRY="assets/example/models/block/shape.json";
    private static final ResourceLocation ID=ResourceLocation.parse("example:block/shape");
    private Path client()throws Exception {Path jar=root.resolve("client.jar");InstanceResourceSourceTest.zip(jar,Map.of("version.json","{\"id\":\"1.21.1\",\"world_version\":3955,\"pack_version\":{\"resource\":34}}","assets/minecraft/blockstates/stone.json","{}","assets/minecraft/models/block/stone.json","{}","assets/minecraft/textures/block/stone.png","synthetic"));return jar;}
    @Test void explicitEmbeddedPackKeepsOrderProvenanceAndSelectionIdentity()throws Exception {
        Path instance=root.resolve("instance"),world=Files.createDirectories(instance.resolve("saves/world"));
        InstanceResourceSourceTest.zip(instance.resolve("mods/library.jar"),Map.of("META-INF/neoforge.mods.toml","[[mods]]\nmodId=\"new_owner\"\n",ENTRY,"root","resourcepacks/a/pack.mcmeta","{}","resourcepacks/a/"+ENTRY,"selected-a","resourcepacks/b/pack.mcmeta","{}","resourcepacks/b/"+ENTRY,"selected-b"));
        InstanceResourceSourceTest.zip(instance.resolve("resourcepacks/high.zip"),Map.of(ENTRY,"high"));
        Path options=instance.resolve("options.txt");Files.writeString(options,"resourcePacks:[\"mod/new_owner:resourcepacks/a\",\"file/high.zip\"]");Path client=client();
        try(var source=InstanceResourceSource.open(world,client,"1.21.1",null,CancellationSignal.NONE,s->{})){assertEquals("high",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());}
        Files.writeString(options,"resourcePacks:[\"new_owner:a\"]");String identity;
        try(var source=InstanceResourceSource.open(world,client,"1.21.1",null,CancellationSignal.NONE,s->{})) {
            assertEquals("selected-a",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());assertTrue(source.origin(ResourceLocation.Kind.MODEL,ID).orElseThrow().contains("!/resourcepacks/a/"));identity=source.fingerprint().sha256();
            Files.writeString(options,"resourcePacks:[\"new_owner:b\"]");assertEquals(ResourceAccessException.Reason.SOURCE_CHANGED,assertThrows(ResourceAccessException.class,source::validateAll).reason());
        }
        try(var source=InstanceResourceSource.open(world,client,"1.21.1",null,CancellationSignal.NONE,s->{})){assertEquals("selected-b",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());assertNotEquals(identity,source.fingerprint().sha256());}
    }
    @Test void ownerMetadataDisambiguatesPacksAndUnqualifiedAmbiguityStaysVisible()throws Exception {
        Path instance=root.resolve("instance"),world=Files.createDirectories(instance.resolve("saves/world"));
        for(String owner:List.of("author_a","author_b"))InstanceResourceSourceTest.zip(instance.resolve("mods/"+owner+".jar"),Map.of("fabric.mod.json","{\"id\":\""+owner+"\",\"version\":\"1\"}",ENTRY,"root-"+owner,"resourcepacks/default/pack.mcmeta","{}","resourcepacks/default/"+ENTRY,owner));
        Path options=instance.resolve("options.txt");Files.writeString(options,"resourcePacks:[\"author_a:default\"]");Path client=client();
        try(var source=InstanceResourceSource.open(world,client,"1.21.1",null,CancellationSignal.NONE,s->{})){assertEquals("author_a",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());}
        Files.writeString(options,"resourcePacks:[\"builtin/default\"]");
        try(var source=InstanceResourceSource.open(world,client,"1.21.1",null,CancellationSignal.NONE,s->{})){assertEquals("root-author_b",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());assertTrue(source.warnings().stream().anyMatch(s->s.contains("不唯一")));}
    }
    @Test void disabledAndEscapingEmbeddedPacksAreNotLoaded()throws Exception {
        Path instance=root.resolve("instance"),world=Files.createDirectories(instance.resolve("saves/world"));
        InstanceResourceSourceTest.zip(instance.resolve("mods/a.jar"),Map.of(ENTRY,"root","resourcepacks/hidden/pack.mcmeta","{}","resourcepacks/hidden/"+ENTRY,"disabled"));
        Files.writeString(instance.resolve("options.txt"),"resourcePacks:[\"builtin/../../hidden\"]");
        try(var source=InstanceResourceSource.open(world,client(),"1.21.1",null,CancellationSignal.NONE,s->{})){assertEquals("root",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());assertTrue(source.warnings().stream().anyMatch(s->s.contains("拒绝")));}
        assertThrows(IllegalArgumentException.class,()->new InstanceResourceLocator.Layer(instance,"../","bad"));
    }
    @Test void modifiedArchiveInvalidatesTheCachedCatalog()throws Exception {
        Path instance=root.resolve("instance"),world=Files.createDirectories(instance.resolve("saves/world"));Path jar=instance.resolve("mods/a.jar");
        InstanceResourceSourceTest.zip(jar,Map.of(ENTRY,"root"));Files.writeString(instance.resolve("options.txt"),"resourcePacks:[\"builtin/new_pack\"]");
        assertTrue(new InstanceResourceLocator().locate(world,null,CancellationSignal.NONE).layers().stream().allMatch(l->l.prefix().isEmpty()));
        InstanceResourceSourceTest.zip(jar,Map.of(ENTRY,"root","resourcepacks/new_pack/pack.mcmeta","{}","resourcepacks/new_pack/"+ENTRY,"new"));
        try(var source=InstanceResourceSource.open(world,client(),"1.21.1",null,CancellationSignal.NONE,s->{})){assertEquals("new",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());}
    }
    @Test void localEnabledPackMatchesItsActualArchiveBytes()throws Exception {
        String configured=System.getenv("MCWORLD_MOD_INSTANCE_DIR"),client=System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");assumeTrue(configured!=null&&client!=null);
        Path instance=Path.of(configured);var located=new InstanceResourceLocator().locate(root,instance,CancellationSignal.NONE);
        var layer=located.layers().stream().filter(l->l.prefix().equals("resourcepacks/b_16x/")).findFirst().orElseThrow();
        try(var zip=new ZipFile(layer.path().toFile());var source=InstanceResourceSource.open(root,Path.of(client),"1.21.1",instance,CancellationSignal.NONE,s->{})) {
            var entry=zip.stream().filter(e->!e.isDirectory()&&e.getName().startsWith(layer.prefix()+"assets/")).findFirst().orElseThrow();
            String logical=entry.getName().substring(layer.prefix().length()+"assets/".length());int slash=logical.indexOf('/');var id=new ResourceLocation(logical.substring(0,slash),logical.substring(slash+1));
            try(var input=zip.getInputStream(entry)){assertArrayEquals(input.readAllBytes(),source.read(ResourceLocation.Kind.RAW_ASSET,id).orElseThrow());}
            assertTrue(source.origin(ResourceLocation.Kind.RAW_ASSET,id).orElseThrow().contains("!/resourcepacks/b_16x/"));source.validateAll();
        }
    }
    @Test void explicitBuiltinSelectionSupportsGeneratedMetadataAndCommonContainerRoots()throws Exception {
        Path instance=root.resolve("instance"),world=Files.createDirectories(instance.resolve("saves/world"));
        InstanceResourceSourceTest.zip(instance.resolve("mods/a.jar"),Map.of("fabric.mod.json","{\"id\":\"independent\",\"version\":\"1\"}",ENTRY,"base","resourcepacks/seasonal/"+ENTRY,"no-metadata","builtin/overhauls/"+ENTRY,"builtin","packs/client/appearance/"+ENTRY,"packs"));
        Path client=client();for(var entry:Map.of("independent:seasonal","no-metadata","independent:overhauls","builtin","builtin/client/appearance","packs").entrySet()) {
            Files.writeString(instance.resolve("options.txt"),"resourcePacks:[\""+entry.getKey()+"\"]");
            try(var source=InstanceResourceSource.open(world,client,"1.21.1",null,CancellationSignal.NONE,s->{})){assertEquals(entry.getValue(),source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());}
        }
        Files.writeString(instance.resolve("options.txt"),"resourcePacks:[]");try(var source=InstanceResourceSource.open(world,client,"1.21.1",null,CancellationSignal.NONE,s->{})){assertEquals("base",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());}
    }
    @Test void manyExplicitSelectionsKeepTheLastPackAndOversizedListsRemainRejected()throws Exception {
        Path instance=root.resolve("instance"),world=Files.createDirectories(instance.resolve("saves/world"));
        List<String> ids=new ArrayList<>();for(int i=0;i<66;i++){String name="pack"+i+".zip";InstanceResourceSourceTest.zip(instance.resolve("resourcepacks/"+name),Map.of(ENTRY,"value"+i));ids.add("file/"+name);}
        Path options=instance.resolve("options.txt");Files.writeString(options,"resourcePacks:"+new com.google.gson.Gson().toJson(ids));Path client=client();
        try(var source=InstanceResourceSource.open(world,client,"1.21.1",null,CancellationSignal.NONE,s->{})){assertEquals("value65",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());assertTrue(source.warnings().stream().noneMatch(s->s.contains("列表无效")));}
        Files.writeString(options,"resourcePacks:"+new com.google.gson.Gson().toJson(Collections.nCopies(InstanceResourceLocator.MAX_ENABLED_PACKS+1,"file/pack0.zip")));
        var located=new InstanceResourceLocator().locate(world,null,CancellationSignal.NONE);assertTrue(located.layers().isEmpty());assertTrue(located.warnings().stream().anyMatch(s->s.contains("列表无效")));
    }
}
