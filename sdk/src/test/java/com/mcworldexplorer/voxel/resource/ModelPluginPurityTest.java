package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelPluginPurityTest {
    @TempDir Path temporary;
    private static final String HOST="0.7.4";
    private static final ResourceLocation ID=ResourceLocation.parse("outside:worker");
    private ExternalModelPluginTest fixture(){var fixture=new ExternalModelPluginTest();fixture.temporary=temporary;return fixture;}

    @Test void emptyStartupAndShutdownDoNotCreateLibraryOrChangeCoreFingerprint()throws Exception {
        Path library=temporary.resolve("unused-library");
        try(var store=new ModelPluginStore(library,HOST)){
            assertTrue(store.restore().external().isEmpty());
            assertEquals(ModelRuleRegistry.defaults().fingerprint(),store.snapshot().selection().rules().fingerprint());
            assertTrue(store.snapshot().processIds().isEmpty());assertFalse(Files.exists(library));
        }
        assertFalse(Files.exists(library));
    }

    @Test void fiveInstallEnableDisableUninstallCyclesLeaveNoManagedFilesOrWorkers()throws Exception {
        var fixture=fixture();Path original=fixture.build();byte[] originalBytes=Files.readAllBytes(original);
        var receipt=ExternalModelPluginPackage.inspect(original,HOST);Path library=temporary.resolve("cycles");
        try(var store=new ModelPluginStore(library,HOST)){
            for(int cycle=0;cycle<5;cycle++){
                var installed=store.install(receipt,false);
                assertTrue(installed.processIds().isEmpty());assertTrue(installed.selection().enabled().isEmpty());
                var active=store.apply(Set.of(ID));long pid=active.processIds().get(ID);
                assertTrue(new BlockModelResolver(fixture.source("plain",false),active.selection().rules()).resolve(new BlockState("another:shape",Map.of())).resolved());
                var disabled=store.apply(Set.of());
                assertTrue(disabled.processIds().isEmpty());assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
                assertEquals(ModelRuleRegistry.defaults().fingerprint(),disabled.selection().rules().fingerprint());
                assertFalse(new BlockModelResolver(fixture.source("plain",false),disabled.selection().rules()).resolve(new BlockState("another:shape",Map.of())).resolved());
                try(var paths=Files.walk(library)){
                    assertEquals(Set.of("plugins.json","packages/"+receipt.sha256()+".jar"),paths.filter(Files::isRegularFile).map(p->library.relativize(p).toString().replace('\\','/')).collect(java.util.stream.Collectors.toSet()));
                }
                var reenabled=store.apply(Set.of(ID));long secondPid=reenabled.processIds().get(ID);
                assertNotEquals(active.executions(),reenabled.executions());
                var removed=store.uninstall(ID);assertTrue(removed.external().isEmpty());assertTrue(removed.notices().isEmpty());
                assertFalse(ProcessHandle.of(secondPid).map(ProcessHandle::isAlive).orElse(false));
                assertFalse(Files.exists(library),"last uninstall must remove the index and empty directories");
                assertArrayEquals(originalBytes,Files.readAllBytes(original));
                assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&(t.getName().equals("mcwe-plugin-replies")||t.getName().equals("mcwe-plugin-diagnostics"))),"worker readers must terminate");
            }
        }
    }

    @Test void interruptedTransactionFilesAreRemovedButUnrelatedUserFilesRemain()throws Exception {
        Path library=temporary.resolve("recovery"),packages=library.resolve("packages");Files.createDirectories(packages);
        Path orphan=packages.resolve("a".repeat(64)+".jar"),partial=packages.resolve(".install-1234.tmp"),indexTemp=library.resolve(".plugins-1234.tmp"),userFile=library.resolve("user-note.txt");
        Files.writeString(orphan,"orphan");Files.writeString(partial,"partial");Files.writeString(indexTemp,"partial");Files.writeString(userFile,"keep");
        try(var store=new ModelPluginStore(library,HOST)){
            assertTrue(store.restore().notices().isEmpty());assertFalse(Files.exists(orphan));assertFalse(Files.exists(partial));assertFalse(Files.exists(indexTemp));
            assertEquals("keep",Files.readString(userFile));assertFalse(Files.exists(packages));assertFalse(Files.exists(library.resolve("plugins.json")));
        }
    }

    @Test void disabledPluginRemainsDisabledAfterRestartWithoutStartingWorker()throws Exception {
        var receipt=ExternalModelPluginPackage.inspect(fixture().build(),HOST);Path library=temporary.resolve("disabled");
        try(var store=new ModelPluginStore(library,HOST)){store.install(receipt,false);store.apply(Set.of(ID));store.apply(Set.of());}
        try(var store=new ModelPluginStore(library,HOST)){
            assertTrue(store.restore().processIds().isEmpty());assertTrue(store.snapshot().selection().enabled().isEmpty());
            store.uninstall(ID);
        }
        assertFalse(Files.exists(library));
    }

    @Test void failedDisableAndUninstallKeepActiveWorkerAndPersistedState()throws Exception {
        var receipt=ExternalModelPluginPackage.inspect(fixture().build(),HOST);Path library=temporary.resolve("rollback");
        try(var store=new ModelPluginStore(library,HOST)){
            store.install(receipt,false);var active=store.apply(Set.of(ID));
            Path index=library.resolve("plugins.json"),saved=library.resolve("saved-index.json");byte[] before=Files.readAllBytes(index);
            Files.move(index,saved);Files.createDirectory(index);Files.writeString(index.resolve("blocker"),"locked");
            assertThrows(java.io.IOException.class,()->store.apply(Set.of()));
            assertThrows(java.io.IOException.class,()->store.uninstall(ID));
            assertSame(active,store.snapshot());assertTrue(store.running(ID));assertArrayEquals(before,Files.readAllBytes(saved));
            Files.delete(index.resolve("blocker"));Files.delete(index);Files.move(saved,index);
            store.uninstall(ID);
        }
        assertFalse(Files.exists(library));
    }

    @Test void pluginCannotCloseBorrowedHostResourcesThroughCallbacks(){
        assertFalse(ModelPluginWire.methods(BlockResourceSource.class).values().stream().anyMatch(m->m.getName().equals("close")));
    }
}
