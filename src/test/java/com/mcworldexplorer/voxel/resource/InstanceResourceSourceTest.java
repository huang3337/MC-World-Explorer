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
    @Test void unusedModConfigurationDoesNotChangeCoreResourceIdentity() throws Exception {
        Path instance=root.resolve("core-only"),world=Files.createDirectories(instance.resolve("saves/world"));
        Path config=Files.createDirectories(instance.resolve("config")),base=client();
        Files.writeString(config.resolve("adapter.toml"),"value=1");
        String identity;
        try(var source=InstanceResourceSource.open(world,base,"1.21.1",null,CancellationSignal.NONE,m->{})) {
            identity=source.fingerprint().sha256();
            Files.writeString(config.resolve("adapter.toml"),"value=2");
            Files.writeString(config.resolve("new-adapter.json"),"{}");
            source.validateAll();
        }
        try(var source=InstanceResourceSource.open(world,base,"1.21.1",null,CancellationSignal.NONE,m->{})) {
            assertEquals(identity,source.fingerprint().sha256());
        }
    }
    @Test void directoryAllocationChangesDoNotHideActualIdentityOrFileSizeChanges(){
        var time=java.nio.file.attribute.FileTime.fromMillis(1000);var later=java.nio.file.attribute.FileTime.fromMillis(2000);
        var directory=new InstanceResourceSource.Stamp(root,0,time,"directory-key",true);
        assertTrue(directory.sameIdentity(new InstanceResourceSource.Stamp(root,131072,time,"directory-key",true)),"observed Windows allocation change after listing");
        assertFalse(directory.sameIdentity(new InstanceResourceSource.Stamp(root,0,later,"directory-key",true)));
        assertFalse(directory.sameIdentity(new InstanceResourceSource.Stamp(root,0,time,"replacement-key",true)));
        assertFalse(directory.sameIdentity(new InstanceResourceSource.Stamp(root,0,time,"directory-key",false)));
        assertFalse(directory.sameIdentity(new InstanceResourceSource.Stamp(root.resolve("other"),0,time,"directory-key",true)));
        var file=new InstanceResourceSource.Stamp(root.resolve("file"),0,time,null,false);
        assertFalse(file.sameIdentity(new InstanceResourceSource.Stamp(file.path(),131072,time,null,false)));
    }
    @Test void archiveReopenRehashesContentDespiteUnchangedSizeAndTimestamp() throws Exception {
        Path instance=root.resolve("archive-identity"),world=Files.createDirectories(instance.resolve("saves/world"));
        Path archive=instance.resolve("mods/example.jar"),base=client();
        storedZip(archive,ENTRY,"value=1");long size=Files.size(archive);var modified=Files.getLastModifiedTime(archive);String before;
        try(var source=InstanceResourceSource.open(world,base,"1.21.1",null,CancellationSignal.NONE,m->{})){
            before=source.fingerprint().sha256();assertEquals("value=1",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());
        }
        storedZip(archive,ENTRY,"value=2");Files.setLastModifiedTime(archive,modified);assertEquals(size,Files.size(archive));
        try(var source=InstanceResourceSource.open(world,base,"1.21.1",null,CancellationSignal.NONE,m->{})){
            assertNotEquals(before,source.fingerprint().sha256());assertEquals("value=2",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());source.validateAll();
        }
    }
    @Test void sharedArchiveIndexKeepsCurrentPathAndIndependentReaderLifetime() throws Exception {
        Path instance=root.resolve("archive-origin"),world=Files.createDirectories(instance.resolve("saves/world"));
        Path original=instance.resolve("mods/original.jar"),moved=instance.resolve("mods/moved.jar"),base=client();
        storedZip(original,ENTRY,"same-content");String before;
        try(var source=InstanceResourceSource.open(world,base,"1.21.1",null,CancellationSignal.NONE,m->{})){
            before=source.fingerprint().sha256();assertEquals("same-content",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());
        }
        Files.move(original,moved);
        try(var second=InstanceResourceSource.open(world,base,"1.21.1",null,CancellationSignal.NONE,m->{})){
            try(var first=InstanceResourceSource.open(world,base,"1.21.1",null,CancellationSignal.NONE,m->{})){
                assertEquals("same-content",first.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());
                assertNotEquals(before,first.fingerprint().sha256());assertEquals(first.fingerprint(),second.fingerprint());
            }
            assertEquals("same-content",second.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());
            assertTrue(second.origin(ResourceLocation.Kind.MODEL,ID).orElseThrow().endsWith("moved.jar"));second.validateAll();
        }
        Files.move(moved,instance.resolve("released.jar"));
    }
    @Test void reusedArchiveIndexChargesFullBudgetsAndHonoursCancellation() throws Exception {
        Path instance=root.resolve("archive-budget"),world=Files.createDirectories(instance.resolve("saves/world"));
        Path archive=instance.resolve("mods/budget.jar"),base=client();zip(archive,Map.of(ENTRY,"model","fixture/Owned.class","class-data","ignored.txt","text"));
        var entries=InstanceResourceSource.class.getDeclaredField("entryCount");entries.setAccessible(true);
        var chars=InstanceResourceSource.class.getDeclaredField("nameChars");chars.setAccessible(true);int expectedEntries,expectedChars;
        try(var source=InstanceResourceSource.open(world,base,"1.21.1",null,CancellationSignal.NONE,m->{})){
            expectedEntries=entries.getInt(source);expectedChars=chars.getInt(source);
        }
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        try(var source=InstanceResourceSource.open(world,base,"1.21.1",null,cancelled::get,m->{})){
            assertEquals(expectedEntries,entries.getInt(source));assertEquals(expectedChars,chars.getInt(source));
            var index=InstanceResourceSource.class.getDeclaredMethod("index",Path.class,String.class,List.class);index.setAccessible(true);
            entries.setInt(source,2_000_000);
            var entryFailure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->index.invoke(source,archive,"",List.of()));
            assertEquals(ResourceAccessException.Reason.LIMIT_EXCEEDED,assertInstanceOf(ResourceAccessException.class,entryFailure.getCause()).reason());
            entries.setInt(source,expectedEntries);chars.setInt(source,64*1024*1024);
            var charFailure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->index.invoke(source,archive,"",List.of()));
            assertEquals(ResourceAccessException.Reason.LIMIT_EXCEEDED,assertInstanceOf(ResourceAccessException.class,charFailure.getCause()).reason());
            chars.setInt(source,expectedChars);cancelled.set(true);
            assertThrows(java.util.concurrent.CancellationException.class,source::validateAll);
        }
    }
    private static void storedZip(Path path,String name,String text)throws Exception {
        Files.createDirectories(path.getParent());byte[] data=text.getBytes(StandardCharsets.UTF_8);var crc=new CRC32();crc.update(data);
        try(var zip=new ZipOutputStream(Files.newOutputStream(path))){var entry=new ZipEntry(name);entry.setMethod(ZipEntry.STORED);entry.setSize(data.length);entry.setCrc(crc.getValue());entry.setTime(0);zip.putNextEntry(entry);zip.write(data);zip.closeEntry();}
    }



    @Test void targetOwnerMetadataFailureDoesNotHideGeometryButClosedSourceStillFails() throws Exception {
        Path instance=root.resolve("bad-target-owner"),world=Files.createDirectories(instance.resolve("saves/world"));
        zip(instance.resolve("mods/bad.jar"),Map.of("META-INF/mods.toml"," ".repeat(1024*1024+1),ENTRY,"model"));
        var source=InstanceResourceSource.open(world,client(),"1.21.1",null,CancellationSignal.NONE,m->{});
        try {
            assertTrue(source.confirmedModIds().isEmpty());
            assertEquals("model",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());
            assertTrue(source.warnings().stream().anyMatch(w->w.contains("所属 Mod 元数据不可用")));
        }finally{source.close();}
        assertThrows(ResourceAccessException.class,source::confirmedModIds);
    }
    @Test void targetOwnersUseUniqueModMetadataAndIgnorePackAndPluginNames() throws Exception {
        Path instance=root.resolve("target-owners"),world=Files.createDirectories(instance.resolve("saves/world"));
        zip(instance.resolve("mods/a.jar"),Map.of("META-INF/mods.toml","[[mods]]\nmodId=\"example\"\ndisplayName=\"Example\"",ENTRY,"mod"));
        zip(instance.resolve("resourcepacks/cover.zip"),Map.of("fabric.mod.json","{\"id\":\"fakepack\"}",ENTRY,"cover"));
        Files.writeString(instance.resolve("options.txt"),"resourcePacks:[\"file/cover.zip\"]");
        zip(instance.resolve("mods/fabric.jar"),Map.of("fabric.mod.json","{\"id\":\"primary\",\"provides\":[\"alias\"]}","assets/alias/models/probe.json","{}"));
        Path base=client();
        try(var source=InstanceResourceSource.open(world,base,"1.21.1",null,CancellationSignal.NONE,m->{})) {
            assertEquals("cover",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());
            assertEquals(Set.of("example","primary"),source.confirmedModIds());
            assertThrows(UnsupportedOperationException.class,()->source.confirmedModIds().add("fake"));
        }
        zip(instance.resolve("mods/b.jar"),Map.of("fabric.mod.json","{\"id\":\"example\"}","assets/example/models/another.json","{}"));
        try(var source=InstanceResourceSource.open(world,base,"1.21.1",null,CancellationSignal.NONE,m->{})) {
            assertEquals(Set.of("primary"),source.confirmedModIds(),"重复、多平台身份和 provides 别名不能猜归属");
        }
    }

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
    @Test void legacyAndExplicitResourcesPacksPreserveOrderAndStayInsideTheirRoots() throws Exception {
        Path instance=root.resolve("legacy"),world=Files.createDirectories(instance.resolve("saves/world"));
        zip(instance.resolve("resourcepacks/旧材质.zip"),Map.of(ENTRY,"legacy"));
        zip(instance.resolve("resources/selected.zip"),Map.of(ENTRY,"selected"));
        zip(instance.resolve("resources/disabled.zip"),Map.of(ENTRY,"disabled"));
        Files.writeString(instance.resolve("options.txt"),"resourcePacks:[\"fabric\",\"旧材质.zip\",\"resources/selected.zip\",\"resources/../../outside.zip\",\"../outside.zip\"]");
        var located=new InstanceResourceLocator().locate(world,null,CancellationSignal.NONE);
        assertEquals(2,located.sources().size());assertEquals(2,located.warnings().size());
        try(var source=InstanceResourceSource.open(world,client(),"1.21.1",null,CancellationSignal.NONE,message->{})) {
            assertEquals("selected",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());
            Files.writeString(instance.resolve("options.txt"),"resourcePacks:[\"resources/selected.zip\",\"旧材质.zip\"]");
            assertEquals(ResourceAccessException.Reason.SOURCE_CHANGED,assertThrows(ResourceAccessException.class,source::validateAll).reason());
        }
        try(var source=InstanceResourceSource.open(world,root.resolve("client.jar"),"1.21.1",null,CancellationSignal.NONE,message->{})) {
            assertEquals("legacy",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());
        }
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
