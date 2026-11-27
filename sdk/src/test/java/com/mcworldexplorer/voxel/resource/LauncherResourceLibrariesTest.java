package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.io.CancellationSignal;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LauncherResourceLibrariesTest {
    @TempDir Path root;
    private static final String ENTRY="assets/new_platform/models/block/material.json";
    private static final ResourceLocation ID=ResourceLocation.parse("new_platform:block/material");
    private Path instance()throws Exception {return Files.createDirectories(root.resolve("game/versions/current"));}
    private void profile(Path instance,String json)throws Exception {Files.writeString(instance.resolve(instance.getFileName()+".json"),json);}
    private Path library(String relative,String value)throws Exception {Path path=root.resolve("game/libraries").resolve(relative);InstanceResourceSourceTest.zip(path,Map.of(ENTRY,value));return path;}
    private Path client()throws Exception {Path jar=root.resolve("client.jar");InstanceResourceSourceTest.zip(jar,Map.of("version.json","{\"id\":\"1.21.1\",\"world_version\":3955,\"pack_version\":{\"resource\":34}}","assets/minecraft/blockstates/stone.json","{}","assets/minecraft/models/block/stone.json","{}","assets/minecraft/textures/block/stone.png","synthetic"));return jar;}
    @Test void declaredLibraryResourcesUseChildVersionAndKeepModsAboveLibraries()throws Exception {
        Path instance=instance(),parent=Files.createDirectories(instance.getParent().resolve("base"));
        profile(parent,"{\"libraries\":[{\"name\":\"new.author:material:1\"}]}");profile(instance,"{\"inheritsFrom\":\"base\",\"libraries\":[{\"name\":\"new.author:material:2\"}]}");
        library("new/author/material/1/material-1.jar","old");Path selected=library("new/author/material/2/material-2.jar","current");
        Path client=client();try(var source=InstanceResourceSource.open(instance,client,"1.21.1",instance,CancellationSignal.NONE,s->{})){assertEquals("current",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());assertEquals(selected.toRealPath().toString(),source.origin(ResourceLocation.Kind.MODEL,ID).orElseThrow());source.validateAll();}
        InstanceResourceSourceTest.zip(instance.resolve("mods/override.jar"),Map.of(ENTRY,"mod"));
        try(var source=InstanceResourceSource.open(instance,client,"1.21.1",instance,CancellationSignal.NONE,s->{})){assertEquals("mod",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());}
    }
    @Test void loaderVersionFlagFindsExactUniversalArchiveAndChangesInvalidateSelection()throws Exception {
        Path instance=instance();profile(instance,"{\"arguments\":{\"game\":[\"--fml.neoForgeVersion\",\"21.1.233\"]}}");
        library("net/neoforged/neoforge/21.1.233/neoforge-21.1.233-universal.jar","exact");library("net/neoforged/neoforge/21.1.176/neoforge-21.1.176-universal.jar","unselected");
        try(var source=InstanceResourceSource.open(instance,client(),"1.21.1",instance,CancellationSignal.NONE,s->{})) {
            assertEquals("exact",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());profile(instance,"{\"arguments\":{\"game\":[\"--fml.neoForgeVersion\",\"21.1.176\"]}}");
            assertEquals(ResourceAccessException.Reason.SOURCE_CHANGED,assertThrows(ResourceAccessException.class,source::validateAll).reason());
        }
    }
    @Test void invalidPathsAndInheritanceCyclesStayVisibleWithoutScanningOtherLibraries()throws Exception {
        Path instance=instance();library("unlisted/hidden/1/hidden-1.jar","hidden");
        profile(instance,"{\"libraries\":[{\"name\":\"safe:declared:1\",\"downloads\":{\"artifact\":{\"path\":\"../escape.jar\"}}}]}");
        var located=new InstanceResourceLocator().locate(instance,instance,CancellationSignal.NONE);assertTrue(located.layers().isEmpty());assertTrue(located.warnings().stream().anyMatch(s->s.contains("启动依赖")&&s.contains("越界")));
        profile(instance,"{\"inheritsFrom\":\"current\"}");located=new InstanceResourceLocator().locate(instance,instance,CancellationSignal.NONE);assertTrue(located.layers().isEmpty());assertTrue(located.warnings().stream().anyMatch(s->s.contains("继承循环")));
    }
    @Test void ordinaryForgeFlagsResolveUniversalResourcesWithoutHardcodedGameVersion()throws Exception {
        Path instance=instance();profile(instance,"{\"arguments\":{\"game\":[\"--fml.forgeVersion\",\"47.4.10\",\"--fml.mcVersion\",\"1.20.1\"]}}");
        Path jar=library("net/minecraftforge/forge/1.20.1-47.4.10/forge-1.20.1-47.4.10-universal.jar","forge");
        var warnings=new ArrayList<String>();var layers=LauncherResourceLibraries.locate(instance,CancellationSignal.NONE,warnings);assertEquals(List.of(jar.toRealPath()),layers.stream().map(InstanceResourceLocator.Layer::path).toList());assertTrue(warnings.isEmpty());
    }
    @Test void windowsVersionDirectorySpellingDoesNotChangeTheSelectedLibraryRoot()throws Exception {
        Path instance=Files.createDirectories(root.resolve("game/Versions/current"));profile(instance,"{\"libraries\":[{\"name\":\"new.author:material:1\"}]}");Path jar=library("new/author/material/1/material-1.jar","selected");
        var warnings=new ArrayList<String>();assertEquals(List.of(jar.toRealPath()),LauncherResourceLibraries.locate(instance,CancellationSignal.NONE,warnings).stream().map(InstanceResourceLocator.Layer::path).toList());
    }
    @Test void actualWhiteTextureComesFromDeclaredLoaderBytes()throws Exception {
        String configured=System.getenv("MCWORLD_MOD_INSTANCE_DIR"),client=System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");assumeTrue(configured!=null&&client!=null);
        Path instance=Path.of(configured);var id=ResourceLocation.parse("neoforge:white");
        try(var source=InstanceResourceSource.open(instance,Path.of(client),"1.21.1",instance,CancellationSignal.NONE,s->{})) {
            byte[] bytes=source.read(ResourceLocation.Kind.TEXTURE,id).orElseThrow();var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));assertNotNull(image);assertEquals(0xFFFFFFFF,image.getRGB(0,0));
            String origin=source.origin(ResourceLocation.Kind.TEXTURE,id).orElseThrow();assertTrue(origin.contains("libraries"));assertTrue(origin.contains("universal.jar"));
            try(var zip=new java.util.zip.ZipFile(Path.of(origin).toFile());var input=zip.getInputStream(zip.getEntry(id.entryName(ResourceLocation.Kind.TEXTURE)))){assertArrayEquals(input.readAllBytes(),bytes);}
            source.validateAll();
        }
    }
}
