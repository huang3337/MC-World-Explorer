package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.io.CancellationSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class InstanceResourceSourceTest {
    @TempDir Path root;
    private static final String ENTRY="assets/example/models/block/shape.json";
    private static final ResourceLocation ID=ResourceLocation.parse("example:block/shape");
    static void zip(Path path,Map<String,String> entries)throws Exception {
        Files.createDirectories(path.getParent());try(var zip=new ZipOutputStream(Files.newOutputStream(path))){for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}
    }
    Path client()throws Exception {
        Path path=root.resolve("client.jar");zip(path,Map.of("version.json","{\"id\":\"1.21.1\",\"world_version\":3955,\"pack_version\":{\"resource\":34}}",
            "assets/minecraft/blockstates/stone.json","{}","assets/minecraft/models/block/stone.json","{}","assets/minecraft/textures/block/stone.png","synthetic",ENTRY,"client"));return path;
    }
    @Test void usesNearestInstanceEnabledPackOrderAndWorldPackWithoutSiblingLeakage() throws Exception {
        Path instance=root.resolve("packA"),world=Files.createDirectories(instance.resolve("saves/world"));
        zip(instance.resolve("mods/a.jar"),Map.of(ENTRY,"mod"));zip(root.resolve("packB/mods/b.jar"),Map.of(ENTRY,"wrong-instance"));
        zip(instance.resolve("resourcepacks/low.zip"),Map.of(ENTRY,"low"));zip(instance.resolve("resourcepacks/high.zip"),Map.of(ENTRY,"high"));
        zip(instance.resolve("resourcepacks/disabled.zip"),Map.of(ENTRY,"disabled"));
        Files.writeString(instance.resolve("options.txt"),"resourcePacks:[\"vanilla\",\"mod_resources\",\"file/low.zip\",\"file/high.zip\"]\n");
        var located=new InstanceResourceLocator().locate(world,null,CancellationSignal.NONE);
        assertEquals(instance.toRealPath(),located.instance().orElseThrow());assertEquals(3,located.sources().size());
        try(var source=InstanceResourceSource.open(world,client(),"1.21.1",null,CancellationSignal.NONE,message->{})) {
            assertEquals("high",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());assertTrue(source.origin(ResourceLocation.Kind.MODEL,ID).orElseThrow().endsWith("high.zip"));
        }
        zip(world.resolve("resources.zip"),Map.of(ENTRY,"world"));
        try(var source=InstanceResourceSource.open(world,client(),"1.21.1",null,CancellationSignal.NONE,message->{})) {
            assertEquals("world",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());
        }
    }
    @Test void directoryPackChangesAndNewNestedFilesInvalidateAndClosingReleasesJars() throws Exception {
        Path instance=root.resolve("pack"),world=Files.createDirectories(instance.resolve("saves/world"));
        Path folder=Files.createDirectories(instance.resolve("resourcepacks/local/assets/example/models/block"));Files.writeString(folder.resolve("shape.json"),"local");
        Files.writeString(instance.resolve("options.txt"),"resourcePacks:[\"file/local\"]");zip(instance.resolve("mods/a.jar"),Map.of("assets/another/models/a.json","{}"));
        var source=InstanceResourceSource.open(world,client(),"1.21.1",null,CancellationSignal.NONE,message->{});
        try {
            assertEquals("local",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());
            assertTrue(source.readJson(ResourceLocation.Kind.MODEL,ResourceLocation.parse("another:a")).isPresent());
            Files.writeString(folder.resolve("new.json"),"{}");
            assertEquals(ResourceAccessException.Reason.SOURCE_CHANGED,assertThrows(ResourceAccessException.class,source::validateAll).reason());
        }finally{source.close();}
        assertEquals(ResourceAccessException.Reason.CLOSED,assertThrows(ResourceAccessException.class,source::validate).reason());
        Files.move(instance.resolve("mods/a.jar"),instance.resolve("mods/a.closed"));
    }
    @Test void rejectsEscapingEnabledPacksAndDoesNotGuessInstanceFromClientJar() throws Exception {
        Path instance=root.resolve("pack"),world=Files.createDirectories(instance.resolve("saves/world"));
        Files.writeString(instance.resolve("options.txt"),"resourcePacks:[\"file/../../outside.zip\",\"unknown/runtime\"]");
        var found=new InstanceResourceLocator().locate(world,null,CancellationSignal.NONE);assertTrue(found.sources().isEmpty());assertEquals(2,found.warnings().size());
        var detached=Files.createDirectories(root.resolve("detached/world"));
        try(var source=InstanceResourceSource.open(detached,client(),"1.21.1",null,CancellationSignal.NONE,message->{})) {
            assertTrue(source.instance().isEmpty());assertEquals("client",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());
        }
    }
    @Test void fingerprintChangesWithSourceContentAndEnabledOrderAndCancelDoesNotLeak() throws Exception {
        Path instance=root.resolve("pack"),world=Files.createDirectories(instance.resolve("saves/world"));
        zip(instance.resolve("mods/a.jar"),Map.of(ENTRY,"one"));var client=client();String first;
        try(var source=InstanceResourceSource.open(world,client,"1.21.1",null,CancellationSignal.NONE,message->{})){first=source.fingerprint().sha256();}
        zip(instance.resolve("mods/a.jar"),Map.of(ENTRY,"two"));
        try(var source=InstanceResourceSource.open(world,client,"1.21.1",null,CancellationSignal.NONE,message->{})){assertNotEquals(first,source.fingerprint().sha256());}
        assertThrows(java.util.concurrent.CancellationException.class,()->InstanceResourceSource.open(world,client,"1.21.1",null,()->true,message->{}));
        Files.move(client,root.resolve("client.closed"));
    }
    @Test void enabledOrderAndModAdditionsInvalidateAnOpenManifest() throws Exception {
        Path instance=root.resolve("pack"),world=Files.createDirectories(instance.resolve("saves/world"));
        zip(instance.resolve("resourcepacks/a.zip"),Map.of(ENTRY,"a"));zip(instance.resolve("resourcepacks/b.zip"),Map.of(ENTRY,"b"));
        Files.writeString(instance.resolve("options.txt"),"resourcePacks:[\"file/a.zip\",\"file/b.zip\"]");
        try(var source=InstanceResourceSource.open(world,client(),"1.21.1",null,CancellationSignal.NONE,message->{})) {
            assertEquals("b",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());
            Files.writeString(instance.resolve("options.txt"),"resourcePacks:[\"file/b.zip\",\"file/a.zip\"]");
            assertEquals(ResourceAccessException.Reason.SOURCE_CHANGED,assertThrows(ResourceAccessException.class,source::validateAll).reason());
        }
        try(var source=InstanceResourceSource.open(world,root.resolve("client.jar"),"1.21.1",null,CancellationSignal.NONE,message->{})) {
            zip(instance.resolve("mods/new.jar"),Map.of(ENTRY,"new"));
            assertEquals(ResourceAccessException.Reason.SOURCE_CHANGED,assertThrows(ResourceAccessException.class,source::validateAll).reason());
        }
    }
    @Test void oversizedIndividualResourceDoesNotPoisonOtherStates() throws Exception {
        Path instance=root.resolve("pack"),world=Files.createDirectories(instance.resolve("saves/world"));
        zip(instance.resolve("mods/a.jar"),Map.of(ENTRY," ".repeat(ResourceLocation.Kind.MODEL.maxBytes()+1),
            "assets/example/blockstates/shape.json","{\"variants\":{\"\":{\"model\":\"example:block/shape\"}}}",
            "assets/example/blockstates/empty.json","{\"variants\":{\"\":{\"model\":\"example:block/empty\"}}}",
            "assets/example/models/block/empty.json","{\"elements\":[]}"));
        try(var source=InstanceResourceSource.open(world,client(),"1.21.1",null,CancellationSignal.NONE,message->{})) {
            var error=assertThrows(ResourceAccessException.class,()->source.read(ResourceLocation.Kind.MODEL,ID));
            assertEquals(ResourceAccessException.Scope.RESOURCE,error.scope());
            var bad=new com.mcworldexplorer.voxel.data.BlockState("example:shape",Map.of());
            var good=new com.mcworldexplorer.voxel.data.BlockState("example:empty",Map.of());
            var prepared=BlockTextureSet.load(source,List.of(bad,good),CancellationSignal.NONE);
            assertNull(prepared.surfaces(bad));assertTrue(prepared.fallbackReason(bad).startsWith("LIMIT_EXCEEDED"));
            assertNotNull(prepared.surfaces(good));source.validateAll();
        }
    }
}
