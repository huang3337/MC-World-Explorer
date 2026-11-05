package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic packages exercise host lifecycle only; no Mod implementation is included. */
class ExternalModelPluginTest {
    @TempDir Path temporary;
    private static final String CODE="""
        package outside;
        import java.util.*;
        import com.mcworldexplorer.voxel.resource.*;
        import com.mcworldexplorer.voxel.resource.BlockModelResolver.Part;
        public final class WorkerPlugin implements ModelPlugin {
            public Descriptor descriptor(){return new Descriptor(ResourceLocation.parse("outside:worker"),"Worker fixture","VERSION",1,"Worker test",Set.of("outside"),DEPENDENCIES,Set.of());}
            public Collection<? extends ModelRule> rules(){return List.of(new Rule());}
            private record Rule() implements ModelRule {
                public ResourceLocation id(){return ResourceLocation.parse("outside:plain");}
                public String version(){return "1";}
                public Set<ResourceLocation> loaders(){return Set.of(id());}
                public Set<String> fields(){return Set.of("nodes","mode");}
                public List<Part> resolve(Context c)throws java.io.IOException,Failure {
                    var definition=c.definition();String mode=definition.get("mode").getAsString();
                    System.out.println("Plugin output must not corrupt transport");
                    if(mode.equals("crash"))System.exit(7);
                    if(mode.equals("loop"))while(true)Thread.onSpinWait();
                    if(mode.equals("recursive")){definition.addProperty("mode","plain");return c.independentChild(definition);}
                    if(mode.equals("closed"))c.resources().read(ResourceLocation.Kind.MODEL,ResourceLocation.parse("fixture:closed"));
                    c.note("Synthetic external rule");
                    return List.of(new Part(c.model(),c.xRotation(),c.yRotation(),c.uvLock(),c.elements(definition.getAsJsonArray("nodes")),c.renderType()));
                }
            }
        }
        """;
    static String manifestJson(){
        return new ModelPluginManifest(1,ModelPluginManifest.SDK_REVISION,Set.of("0.7.4"),"isolated-jvm","example.PlanePlugin",
            new ModelPlugin.Descriptor(ResourceLocation.parse("example:planes"),"Example","1",1,"Fixture",Set.of("example"),Map.of(),Set.of())).json();
    }
    Path build()throws Exception{return build("outside","1",Map.of());}
    Path build(String namespace,String version,Map<ResourceLocation,String> dependencies)throws Exception {
        var descriptor=new ModelPlugin.Descriptor(ResourceLocation.parse(namespace+":worker"),"Worker fixture",version,1,"Worker test",Set.of(namespace),dependencies,Set.of());
        String dependencyCode=dependencies.isEmpty()?"Map.of()":"Map.of("+dependencies.entrySet().stream().map(e->"ResourceLocation.parse(\""+e.getKey()+"\"),\""+e.getValue()+"\"").collect(java.util.stream.Collectors.joining(","))+")";
        String code=CODE.replace("outside",namespace).replace("VERSION",version).replace("DEPENDENCIES",dependencyCode);
        Path directory=temporary.resolve(UUID.randomUUID().toString()),source=directory.resolve("WorkerPlugin.java"),classes=directory.resolve("classes");
        Files.createDirectories(classes);Files.writeString(source,code);
        String classpath=System.getProperty("mcwe.plugin.testCompileClasspath");assertNotNull(classpath);
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","21","-encoding","UTF-8","-proc:none","-cp",classpath,"-d",classes.toString(),source.toString()));
        var manifest=new ModelPluginManifest(1,ModelPluginManifest.SDK_REVISION,Set.of("0.7.4"),"isolated-jvm",namespace+".WorkerPlugin",descriptor);
        Path jar=directory.resolve("plugin.jar");
        try(var output=new JarOutputStream(Files.newOutputStream(jar));var paths=Files.walk(classes)){
            for(Path path:paths.filter(Files::isRegularFile).toList()){
                output.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\','/')));Files.copy(path,output);output.closeEntry();
            }
            output.putNextEntry(new JarEntry(ModelPluginManifest.ENTRY));output.write(manifest.json().getBytes(StandardCharsets.UTF_8));output.closeEntry();
        }
        return jar;
    }
    BlockResourceSource source(String mode,boolean ignored){
        String model="""
            {"loader":"outside:plain","mode":"%s","textures":{"p":"another:stone"},
             "nodes":[{"from":[0,0,0],"to":[16,8,16],"faces":{"up":{"texture":"#p"}}}]}
            """.formatted(mode);
        return new BlockResourceSource(){
            public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id)throws IOException{return readJson(kind,id).map(value->value.getBytes(StandardCharsets.UTF_8));}
            public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id)throws IOException {
                if(id.path().equals("closed"))throw new ResourceAccessException(ResourceAccessException.Reason.CLOSED,"fixture closed");
                if(kind==ResourceLocation.Kind.RAW_ASSET)return Optional.empty();
                return Optional.of(kind==ResourceLocation.Kind.BLOCKSTATE?"{\"variants\":{\"\":{\"model\":\"another:block/shape\"}}}":model);
            }
            public ClientResourceArchive.Fingerprint fingerprint(){return new ClientResourceArchive.Fingerprint(Path.of("worker-fixture"),1,0,mode);}
        };
    }
    @Test void separateWorkerResolvesCallbacksAndRecursionThenReleasesPackage()throws Exception {
        Path jar=build();var receipt=ExternalModelPluginPackage.inspect(jar,"0.7.4");long pid;
        try(var plugin=new ExternalModelPlugin(receipt,"0.7.4",Duration.ofSeconds(10))){
            pid=plugin.processId();var registry=ModelPluginCatalog.core().with(plugin).select(Set.of(plugin.descriptor().id())).rules();
            for(String mode:List.of("plain","recursive")){
                var resolved=new BlockModelResolver(source(mode,false),registry).resolve(new BlockState("another:shape",Map.of()));
                assertTrue(resolved.resolved(),resolved.detail());assertEquals(1,resolved.model().orElseThrow().faces().size());
            }
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));Files.delete(jar);
    }
    @Test void timeoutCrashAndClosedResourceAreVisibleWithoutHostTermination()throws Exception {
        var receipt=ExternalModelPluginPackage.inspect(build(),"0.7.4");
        for(String mode:List.of("loop","crash","closed"))try(var plugin=new ExternalModelPlugin(receipt,"0.7.4",Duration.ofSeconds(3))){
            var registry=ModelPluginCatalog.core().with(plugin).select(Set.of(plugin.descriptor().id())).rules();
            var resolver=new BlockModelResolver(source(mode,false),registry);var state=new BlockState("another:shape",Map.of());
            if(mode.equals("closed"))assertThrows(ResourceAccessException.class,()->resolver.resolve(state));
            else assertFalse(resolver.resolve(state).resolved());
            if(!mode.equals("closed"))assertFalse(plugin.running());
        }
    }
}
