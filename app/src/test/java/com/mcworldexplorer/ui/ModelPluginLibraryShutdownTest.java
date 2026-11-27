package com.mcworldexplorer.ui;

import com.mcworldexplorer.voxel.resource.ExternalModelPluginPackage;
import com.mcworldexplorer.voxel.resource.ExternalModelPlugin;
import com.mcworldexplorer.voxel.resource.ModelPlugin;
import com.mcworldexplorer.voxel.resource.ModelPluginManifest;
import com.mcworldexplorer.voxel.resource.ModelPluginStore;
import com.mcworldexplorer.voxel.resource.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Real child JVMs and a synthetic installed plugin; no JavaFX toolkit, windows or game classes. */
class ModelPluginLibraryShutdownTest {
    private static final ResourceLocation ID=ResourceLocation.parse("shutdown_fixture:worker");
    @TempDir Path temporary;

    @Test void hostExitWaitsForStartedWorkerCleanupWithoutBlockingOwner()throws Exception {
        verifyHostExit("running");
    }

    @Test void hostExitCancelsWorkerStillInsidePluginConstructor()throws Exception {
        verifyHostExit("initializing");
    }

    @Test void interruptedCloseStillReleasesWorkerAndPreservesCancellation()throws Exception {
        String version=ModelPluginStore.hostVersion();
        try(var worker=new ExternalModelPlugin(ExternalModelPluginPackage.inspect(buildPlugin(version),version),version,Duration.ofSeconds(10))){
            long pid=worker.processId();
            Thread.currentThread().interrupt();
            try{
                worker.close();
                assertTrue(Thread.currentThread().isInterrupted(),"清理结束必须恢复调用线程的中断标志");
            }finally{Thread.interrupted();}
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"中断关闭不能跳过 worker 终止等待");
        }
    }

    private void verifyHostExit(String mode)throws Exception {
        String version=ModelPluginStore.hostVersion();
        Path applicationHome=Files.createDirectories(temporary.resolve("application"));
        Path plugin=buildPlugin(version);
        // Seed an enabled preference through the actual transaction path, without editing the index.
        try(var store=new ModelPluginStore(applicationHome.resolve("config/model-plugins"),version)){
            store.install(ExternalModelPluginPackage.inspect(plugin,version),false);
            store.apply(Set.of(ID));
        }
        Path workerPid=temporary.resolve("worker.pid"),closeTime=temporary.resolve("close-nanos.txt");
        Path log=temporary.resolve("host.log");
        String runtime=System.getProperty("mcwe.plugin.workerClasspath");
        assertNotNull(runtime);
        String tests=Path.of(ShutdownProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Dmcworldexplorer.home="+applicationHome,"-Dmcwe.plugin.workerClasspath="+runtime,
                "-cp",tests+File.pathSeparator+runtime,ShutdownProbe.class.getName(),mode,workerPid.toString(),closeTime.toString());
        command.environment().put("MCWE_SHUTDOWN_WORKER_PID",workerPid.toString());
        command.environment().put("MCWE_SHUTDOWN_MODE",mode);
        command.redirectErrorStream(true).redirectOutput(log.toFile());
        Process host=command.start();
        try{
            assertTrue(host.waitFor(20,TimeUnit.SECONDS),"宿主关闭没有在预算内完成");
            assertEquals(0,host.exitValue(),()->readLog(log));
            assertTrue(Files.exists(workerPid),"没有真正启动合成插件 worker");
            long pid=Long.parseLong(Files.readString(workerPid).strip());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"宿主已退出，但插件 worker 仍在运行："+pid);
            assertTrue(Long.parseLong(Files.readString(closeTime).strip())<TimeUnit.MILLISECONDS.toNanos(500),
                    "close 在调用线程等待了插件终止");
        }finally{
            host.descendants().forEach(ProcessHandle::destroyForcibly);
            if(host.isAlive()){host.destroyForcibly();host.waitFor(5,TimeUnit.SECONDS);}
            // Also release an orphan after an assertion failure in the old daemon-only implementation.
            if(Files.exists(workerPid)){
                String value=Files.readString(workerPid).strip();
                if(value.matches("[0-9]+")){
                    var worker=ProcessHandle.of(Long.parseLong(value));
                    if(worker.isPresent()&&worker.get().isAlive()){
                        worker.get().destroyForcibly();worker.get().onExit().get(5,TimeUnit.SECONDS);
                    }
                }
            }
        }
    }

    private static String readLog(Path log){
        try{return Files.readString(log);}catch(Exception failure){return failure.toString();}
    }

    private Path buildPlugin(String hostVersion)throws Exception {
        Path source=temporary.resolve("ShutdownPlugin.java"),classes=Files.createDirectories(temporary.resolve("classes"));
        Files.writeString(source,"""
                package shutdownfixture;
                import java.nio.file.*;
                import java.util.*;
                import com.mcworldexplorer.voxel.resource.*;
                public final class ShutdownPlugin implements ModelPlugin {
                    public ShutdownPlugin() throws Exception {
                        String marker=System.getenv("MCWE_SHUTDOWN_WORKER_PID");
                        if(marker==null)return;
                        Files.writeString(Path.of(marker),Long.toString(ProcessHandle.current().pid()));
                        if("initializing".equals(System.getenv("MCWE_SHUTDOWN_MODE")))Thread.sleep(60_000);
                        else {
                            // A plugin-owned thread outlives protocol EOF; the host must terminate the process.
                            var background=new Thread(()->{for(;;)try{Thread.sleep(1_000);}catch(InterruptedException done){return;}},"fixture-background");
                            background.setDaemon(false);background.start();
                        }
                    }
                    public Descriptor descriptor(){return new Descriptor(ResourceLocation.parse("shutdown_fixture:worker"),"Shutdown fixture","1",1,"Synthetic shutdown fixture",Set.of("shutdown_fixture"),Map.of(),Set.of());}
                    public Collection<? extends ModelRule> rules(){return List.of();}
                }
                """,StandardCharsets.UTF_8);
        String classpath=System.getProperty("mcwe.plugin.testCompileClasspath");assertNotNull(classpath);
        var compiler=ToolProvider.getSystemJavaCompiler();assertNotNull(compiler);
        assertEquals(0,compiler.run(null,null,null,"--release","21","-encoding","UTF-8","-proc:none",
                "-cp",classpath,"-d",classes.toString(),source.toString()));
        var descriptor=new ModelPlugin.Descriptor(ID,"Shutdown fixture","1",1,"Synthetic shutdown fixture",Set.of("shutdown_fixture"),Map.of(),Set.of());
        var manifest=new ModelPluginManifest(1,ModelPluginManifest.SDK_REVISION,Set.of(hostVersion),"isolated-jvm","shutdownfixture.ShutdownPlugin",descriptor);
        Path jar=temporary.resolve("shutdown-fixture.jar");
        try(var output=new JarOutputStream(Files.newOutputStream(jar));var files=Files.walk(classes)){
            for(Path file:files.filter(Files::isRegularFile).toList()){
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\','/')));Files.copy(file,output);output.closeEntry();
            }
            output.putNextEntry(new JarEntry(ModelPluginManifest.ENTRY));output.write(manifest.json().getBytes(StandardCharsets.UTF_8));output.closeEntry();
        }
        return jar;
    }

    /** The test owner loop substitutes only FX completion dispatch, not library or worker behavior. */
    public static final class ShutdownProbe {
        public static void main(String[] arguments)throws Exception {
            String mode=arguments[0];Path marker=Path.of(arguments[1]),closeTime=Path.of(arguments[2]);
            var completions=new ConcurrentLinkedQueue<Runnable>();
            var initialized=new AtomicReference<ModelPluginStore.Snapshot>();
            try(var controller=new ModelPluginLibraryController(()->true,initialized::set,message->{},callback->{
                completions.add(callback);
                if(mode.equals("running")){
                    // Hold the owner briefly after delivery so close cleanup is observably queued.
                    long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(700);
                    while(System.nanoTime()<until){Thread.interrupted();LockSupport.parkNanos(until-System.nanoTime());}
                }
            })){
                controller.initialize();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(System.nanoTime()<deadline){
                    Runnable completion;while((completion=completions.poll())!=null)completion.run();
                    if(mode.equals("running")?initialized.get()!=null:Files.exists(marker)&&Files.size(marker)>0)break;
                    Thread.sleep(10);
                }
                if(mode.equals("running")){
                    if(initialized.get()==null||initialized.get().processIds().size()!=1)throw new IllegalStateException("合成插件未恢复");
                }else if(!Files.exists(marker)||Files.size(marker)==0)throw new IllegalStateException("合成插件构造函数未启动");
                long before=System.nanoTime();controller.close();
                Files.writeString(closeTime,Long.toString(System.nanoTime()-before));
                // No executor await here: the production owner keeps the JVM alive until cleanup ends.
            }
        }
    }
}
