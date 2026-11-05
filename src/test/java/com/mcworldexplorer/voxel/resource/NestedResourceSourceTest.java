package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.io.CancellationSignal;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NestedResourceSourceTest {
    @TempDir Path root;
    private static final String ENTRY="assets/independent/models/block/shape.json";
    private static final ResourceLocation ID=ResourceLocation.parse("independent:block/shape");
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static byte[] archive(Map<String,byte[]> entries)throws IOException {
        var output=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(output)){for(var item:new TreeMap<>(entries).entrySet()){zip.putNextEntry(new ZipEntry(item.getKey()));zip.write(item.getValue());zip.closeEntry();}}return output.toByteArray();
    }
    private Path write(Path path,Map<String,byte[]> entries)throws IOException {Files.createDirectories(path.getParent());Files.write(path,archive(entries));return path;}
    private static String fabric(String id,String child){return "{\"id\":\""+id+"\",\"version\":\"1\""+(child==null?"":",\"jars\":[{\"file\":\""+child+"\"}]")+"}";}
    private static byte[] inner(String id,String content)throws IOException {return archive(Map.of("fabric.mod.json",bytes(fabric(id,null)),ENTRY,bytes(content),"assets/independent/blockstates/shape.json",bytes("{\"variants\":{\"\":{\"model\":\"independent:block/shape\"}}}")));}
    private static String jarjar(String version){return "{\"jars\":[{\"identifier\":{\"group\":\"example\",\"artifact\":\"nested\"},\"version\":{\"range\":\"[1,3)\",\"artifactVersion\":\""+version+"\"},\"path\":\"META-INF/jarjar/inner.jar\"}]}";}
    private Path world()throws IOException{return Files.createDirectories(root.resolve("instance/saves/world"));}
    private Path client()throws Exception {Path jar=root.resolve("client.jar");InstanceResourceSourceTest.zip(jar,Map.of("version.json","{\"id\":\"1.21.1\",\"world_version\":3955,\"pack_version\":{\"resource\":34}}","assets/minecraft/blockstates/stone.json","{}","assets/minecraft/models/block/stone.json","{}","assets/minecraft/textures/block/stone.png","synthetic"));return jar;}
    private InstanceResourceSource open(Path world,Path client)throws Exception{return InstanceResourceSource.open(world,client,"1.21.1",null,CancellationSignal.NONE,s->{});}
    public static final class FingerprintMain {
        public static void main(String[] args)throws Exception {
            try(var source=InstanceResourceSource.open(Path.of(args[0]),Path.of(args[1]),"1.21.1",null,CancellationSignal.NONE,m->{})){System.out.println(source.fingerprint().sha256());}
        }
    }
    @Test void conflictingMultiOwnerDiagnosticsKeepTheSameFingerprintAcrossJvmProcesses()throws Exception {
        Path world=world(),client=client();byte[] a=archive(Map.of("fabric.mod.json",bytes("{\"id\":\"z_owner\",\"version\":\"1\",\"provides\":[\"a_owner\"]}"),ENTRY,bytes("one")));
        byte[] b=inner("a_owner","two");write(root.resolve("instance/mods/outer.jar"),Map.of("fabric.mod.json",bytes("{\"id\":\"outer\",\"version\":\"1\",\"jars\":[{\"file\":\"a.jar\"},{\"file\":\"b.jar\"}]}"),"a.jar",a,"b.jar",b));
        String expected;try(var source=open(world,client)){expected=source.fingerprint().sha256();assertTrue(source.warnings().stream().anyMatch(w->w.contains("[a_owner] / [a_owner, z_owner]")),source.warnings().toString());}
        String classpath=System.getProperty("java.class.path");assertNotNull(classpath);assertFalse(classpath.isBlank());
        Path javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        for(int i=0;i<6;i++){
            Path output=root.resolve("fingerprint-"+i+".log");var process=new ProcessBuilder(javaExecutable.toString(),"-cp",classpath,FingerprintMain.class.getName(),world.toString(),client.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try{assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS),"只读指纹帮助进程超时");assertEquals(0,process.exitValue(),Files.readString(output));String actual=Files.readAllLines(output).stream().filter(line->line.matches("[0-9a-f]{64}")).findFirst().orElseThrow();assertEquals(expected,actual);}finally{if(process.isAlive())process.destroyForcibly();}
        }
    }
    @Test void jarJarReadsWithoutExtractionAndTwoReadersOwnIndependentHandles()throws Exception {
        Path world=world(),client=client();Path outer=write(root.resolve("instance/mods/a.jar"),Map.of("META-INF/mods.toml",bytes("[[mods]]\nmodId=\"outer\""),"META-INF/jarjar/metadata.json",bytes(jarjar("1")),"META-INF/jarjar/inner.jar",inner("nested","{\"elements\":[]}")));
        byte[] before=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(outer));
        try(var second=open(world,client)) {
            try(var first=open(world,client)){assertEquals("{\"elements\":[]}",first.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());assertTrue(first.origin(ResourceLocation.Kind.MODEL,ID).orElseThrow().contains("!/META-INF/jarjar/inner.jar"));assertEquals(first.fingerprint(),second.fingerprint());}
            assertEquals("{\"elements\":[]}",second.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());assertTrue(second.instanceBlockstates().contains(ResourceLocation.parse("independent:shape")));second.validateAll();
        }
        assertArrayEquals(before,MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(outer)));Files.move(outer,outer.resolveSibling("moved.jar"));
        assertFalse(Files.exists(root.resolve("instance/META-INF")));
    }
    @Test void fabricRecursiveDeclarationLoadsButUnlistedArchiveDoesNot()throws Exception {
        Path world=world(),client=client();byte[] middle=archive(Map.of("fabric.mod.json",bytes(fabric("middle","lib/leaf.jar")),"lib/leaf.jar",inner("leaf","deep")));
        write(root.resolve("instance/mods/a.jar"),Map.of("fabric.mod.json",bytes(fabric("outer","lib/middle.jar")),"lib/middle.jar",middle,"hidden.jar",inner("unlisted","wrong")));
        try(var source=open(world,client)){assertEquals("deep",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());assertTrue(source.origin(ResourceLocation.Kind.MODEL,ID).orElseThrow().contains("!/lib/middle.jar!/lib/leaf.jar"));source.validateAll();}
    }
    @Test void differentResourcesRemainAnExplicitConflictAndEquivalentCopiesDeduplicate()throws Exception {
        Path world=world();Path a=root.resolve("instance/mods/a.jar"),b=root.resolve("instance/mods/b.jar");
        write(a,Map.of("META-INF/jarjar/metadata.json",bytes(jarjar("1")),"META-INF/jarjar/inner.jar",inner("nested","one")));
        write(b,Map.of("META-INF/jarjar/metadata.json",bytes(jarjar("2")),"META-INF/jarjar/inner.jar",inner("nested","two")));
        var conflict=new InstanceResourceLocator().locate(world,null,CancellationSignal.NONE);assertTrue(conflict.layers().stream().noneMatch(l->!l.nested().isEmpty()));assertTrue(conflict.warnings().stream().anyMatch(s->s.contains("版本不同")));
        write(b,Map.of("META-INF/jarjar/metadata.json",bytes(jarjar("2")),"META-INF/jarjar/inner.jar",inner("nested","one")));
        var equal=new InstanceResourceLocator().locate(world,null,CancellationSignal.NONE);assertEquals(1,equal.layers().stream().filter(l->!l.nested().isEmpty()).count());
    }
    @Test void outerModIdentityWinsAndUnsafeDeclarationIsReported()throws Exception {
        Path world=world();write(root.resolve("instance/mods/a.jar"),Map.of("fabric.mod.json",bytes(fabric("outer","lib/inner.jar")),"lib/inner.jar",inner("outer","inactive")));
        write(root.resolve("instance/mods/b.jar"),Map.of("fabric.mod.json",bytes(fabric("bad","../escape.jar"))));
        var located=new InstanceResourceLocator().locate(world,null,CancellationSignal.NONE);assertTrue(located.layers().stream().noneMatch(l->!l.nested().isEmpty()));assertTrue(located.warnings().stream().anyMatch(s->s.contains("越界")));
    }
    @Test void conflictingFabricParentsCannotLeakTheirChildren()throws Exception {
        Path world=world();for(String name:List.of("a","b")) {
            byte[] middle=archive(Map.of("fabric.mod.json",bytes(fabric("shared","lib/leaf.jar")),"lib/leaf.jar",inner("leaf_"+name,name)));
            write(root.resolve("instance/mods/"+name+".jar"),Map.of("fabric.mod.json",bytes(fabric("outer_"+name,"lib/middle.jar")),"lib/middle.jar",middle));
        }
        var located=new InstanceResourceLocator().locate(world,null,CancellationSignal.NONE);assertTrue(located.layers().stream().noneMatch(l->!l.nested().isEmpty()));assertTrue(located.warnings().stream().anyMatch(s->s.contains("fabric:shared")));
    }
    @Test void repeatedDirectoryHeadersDoNotDiscardOtherwiseValidResources()throws Exception {
        Path world=world(),client=client();Path jar=write(root.resolve("instance/mods/directories.jar"),Map.of("assets/",new byte[0],"assetx/",new byte[0],ENTRY,bytes("valid")));
        byte[] data=Files.readAllBytes(jar),needle=bytes("assetx/");
        for(int i=0;i<=data.length-needle.length;i++){boolean match=true;for(int j=0;j<needle.length;j++)if(data[i+j]!=needle[j]){match=false;break;}if(match)data[i+5]='s';}
        Files.write(jar,data);
        try(var source=open(world,client)){assertEquals("valid",source.readJson(ResourceLocation.Kind.MODEL,ID).orElseThrow());assertTrue(source.warnings().stream().noneMatch(s->s.contains("重复素材")));}
    }
    @Test void lruEvictionAndCancellationReleaseAllArchives()throws Exception {
        var archives=new ArrayList<Path>();try(var access=new NestedZipAccess(CancellationSignal.NONE)) {
            for(int i=0;i<6;i++){Path path=write(root.resolve("archive"+i+".jar"),Map.of("inner.jar",inner("nested","value"+i)));archives.add(path);assertEquals("value"+i,Files.readString(access.root(path,List.of("inner.jar")).resolve(ENTRY)));}
            assertEquals("value0",Files.readString(access.root(archives.getFirst(),List.of("inner.jar")).resolve(ENTRY)));
        }
        for(Path path:archives)Files.move(path,path.resolveSibling(path.getFileName()+".closed"));
        try(var cancelled=new NestedZipAccess(()->true)){assertThrows(java.util.concurrent.CancellationException.class,()->cancelled.root(archives.getFirst(),List.of("inner.jar")));}
    }
    @Test void actualDeclaredNestedModelAndTextureBytesMatchTheirContainer()throws Exception {
        String configured=System.getenv("MCWORLD_MOD_INSTANCE_DIR"),configuredClient=System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured!=null&&configuredClient!=null);
        Path instance=Path.of(configured);var located=new InstanceResourceLocator().locate(root,instance,CancellationSignal.NONE);
        var layers=located.layers().stream().filter(l->!l.nested().isEmpty()).toList();assertFalse(layers.isEmpty());
        int models=0,textures=0,states=0;
        try(var access=new NestedZipAccess(CancellationSignal.NONE);var source=InstanceResourceSource.open(root,Path.of(configuredClient),"1.21.1",instance,CancellationSignal.NONE,s->{})) {
            for(var layer:layers) {
                Path archiveRoot=access.root(layer.path(),layer.nested());List<Path> files;try(var walk=Files.walk(archiveRoot)){files=walk.filter(Files::isRegularFile).toList();}
                for(String folder:List.of("models/","textures/","blockstates/")) {
                    var match=files.stream().filter(p->{String name=archiveRoot.relativize(p).toString();return name.startsWith("assets/")&&name.substring(7).contains("/"+folder);}).findFirst();if(match.isEmpty())continue;
                    Path file=match.get();String logical=archiveRoot.relativize(file).toString().substring(7);int slash=logical.indexOf('/');var id=new ResourceLocation(logical.substring(0,slash),logical.substring(slash+1));
                    byte[] actual=source.read(ResourceLocation.Kind.RAW_ASSET,id).orElseThrow();String origin=source.origin(ResourceLocation.Kind.RAW_ASSET,id).orElseThrow();
                    // Overridden resource layers must be checked against the winning layer, not an inactive copy.
                    if(!origin.contains("!/"+String.join("!/",layer.nested())))continue;
                    assertArrayEquals(Files.readAllBytes(file),actual);
                    if(folder.equals("models/"))models++;else if(folder.equals("textures/"))textures++;else states++;
                }
            }
            source.validateAll();
        }
        assertTrue(models>0&&textures>0&&states>0,"实际嵌套模型/贴图/状态均应有原始字节见证");
        System.out.println("Nested evidence: "+layers.size()+" layers, "+models+" models, "+textures+" textures, "+states+" blockstates; warnings="+located.warnings());
    }
}
