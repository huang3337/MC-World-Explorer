package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelPluginStoreTest {
    @TempDir Path temporary;
    private final ResourceLocation id=ResourceLocation.parse("outside:worker");
    private ExternalModelPluginTest fixture(){var fixture=new ExternalModelPluginTest();fixture.temporary=temporary;return fixture;}
    private static ModelPluginCatalog core(){return ModelPluginCatalog.core();}
    private ExternalModelPluginPackage revised(Path original,boolean incompatibleDescriptor)throws Exception {
        Path changed=temporary.resolve("revision-"+UUID.randomUUID()+".jar");
        try(var input=new JarFile(original.toFile());var output=new JarOutputStream(Files.newOutputStream(changed))){
            for(var entry:input.stream().toList()){
                output.putNextEntry(new JarEntry(entry.getName()));
                try(var stream=input.getInputStream(entry)){
                    byte[] bytes=stream.readAllBytes();
                    if(incompatibleDescriptor&&entry.getName().equals(ModelPluginManifest.ENTRY))bytes=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replace("Worker fixture","Different descriptor").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    output.write(bytes);
                }output.closeEntry();
            }
            output.putNextEntry(new JarEntry("revision.txt"));output.write("same visible version, different package bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));output.closeEntry();
        }return ExternalModelPluginPackage.inspect(changed,"0.7.4");
    }
    @Test void installIsDisabledEnablePersistsAndUninstallRemovesManagedBytes()throws Exception {
        var fixture=fixture();Path original=fixture.build(),library=temporary.resolve("library");
        var receipt=ExternalModelPluginPackage.inspect(original,"0.7.4");String fingerprint;
        try(var store=new ModelPluginStore(library,"0.7.4",core())){
            var installed=store.install(receipt,false);assertEquals(Set.of(id),installed.external());assertFalse(installed.selection().enabled().contains(id));
            assertTrue(Files.exists(library.resolve("packages/"+receipt.sha256()+".jar")));
            var enabled=store.apply(Set.of(id));fingerprint=enabled.selection().rules().fingerprint();
            var stale=new BlockModelResolver(fixture.source("plain",false),enabled.selection().rules());
            assertTrue(stale.resolve(new BlockState("another:shape",Map.of())).resolved());
            store.apply(Set.of());assertFalse(stale.resolve(new BlockState("another:shape",Map.of())).resolved(),"retired process must not execute from an old snapshot");
            store.apply(Set.of(id));
        }
        Files.delete(original);
        try(var restored=new ModelPluginStore(library,"0.7.4",core())){
            var selected=restored.restore();assertTrue(selected.notices().isEmpty(),selected.notices().toString());assertEquals(Set.of(id),selected.selection().enabled());assertEquals(fingerprint,selected.selection().rules().fingerprint());
            var removed=restored.uninstall(id);assertTrue(removed.external().isEmpty());assertFalse(Files.exists(library.resolve("packages/"+receipt.sha256()+".jar")));
        }
        try(var empty=new ModelPluginStore(library,"0.7.4",core())){assertTrue(empty.restore().external().isEmpty());}
    }
    @Test void failedPersistenceRetainsCurrentRulesAndNewProcessesAreDiscarded()throws Exception {
        var receipt=ExternalModelPluginPackage.inspect(fixture().build(),"0.7.4");Path library=temporary.resolve("library");
        try(var store=new ModelPluginStore(library,"0.7.4",core())){
            store.install(receipt,false);var before=store.snapshot();
            Path index=library.resolve("plugins.json"),saved=library.resolve("saved-index.json");Files.move(index,saved);Files.createDirectory(index);Files.writeString(index.resolve("blocker"),"nonempty");
            assertThrows(IOException.class,()->store.apply(Set.of(id)));assertSame(before,store.snapshot());assertTrue(store.snapshot().selection().enabled().isEmpty());
            Files.delete(index.resolve("blocker"));Files.delete(index);Files.move(saved,index);
            assertEquals(Set.of(id),store.apply(Set.of(id)).selection().enabled());
        }
    }
    @Test void damagedIndexIsPreservedAndMissingPackageDoesNotResetSavedPreference()throws Exception {
        Path broken=temporary.resolve("broken");Files.createDirectory(broken);Path index=broken.resolve("plugins.json");Files.writeString(index,"{\"format\":1,\"format\":1}");
        assertThrows(IOException.class,()->new ModelPluginStore(broken,"0.7.4",core()));assertEquals("{\"format\":1,\"format\":1}",Files.readString(index));
        var receipt=ExternalModelPluginPackage.inspect(fixture().build(),"0.7.4");Path library=temporary.resolve("missing");
        try(var store=new ModelPluginStore(library,"0.7.4",core())){store.install(receipt,false);store.apply(Set.of(id));}
        byte[] before=Files.readAllBytes(library.resolve("plugins.json"));Files.delete(library.resolve("packages/"+receipt.sha256()+".jar"));
        try(var store=new ModelPluginStore(library,"0.7.4",core())){var snapshot=store.restore();assertFalse(snapshot.notices().isEmpty());assertFalse(snapshot.selection().enabled().contains(id));}
        assertArrayEquals(before,Files.readAllBytes(library.resolve("plugins.json")));
    }
    @Test void sameVersionUpdateChangesFingerprintAndBadActivationRollsBack()throws Exception {
        Path original=fixture().build(),library=temporary.resolve("updates");var first=ExternalModelPluginPackage.inspect(original,"0.7.4");
        try(var store=new ModelPluginStore(library,"0.7.4",core())){
            store.install(first,false);var before=store.apply(Set.of(id));var second=revised(original,false);
            var updated=store.install(second,true);assertEquals("1",updated.catalog().entries().getFirst().descriptor().version());
            assertNotEquals(before.selection().rules().fingerprint(),updated.selection().rules().fingerprint());assertFalse(Files.exists(library.resolve("packages/"+first.sha256()+".jar")));
            var invalid=revised(original,true);byte[] index=Files.readAllBytes(library.resolve("plugins.json"));
            assertThrows(IOException.class,()->store.install(invalid,true));assertSame(updated,store.snapshot());assertArrayEquals(index,Files.readAllBytes(library.resolve("plugins.json")));
            assertTrue(Files.exists(library.resolve("packages/"+second.sha256()+".jar")));assertFalse(Files.exists(library.resolve("packages/"+invalid.sha256()+".jar")));
        }
    }
    @Test void crashedWorkerRestartsWithNewExecutionIdentityAndSameContentContract()throws Exception {
        var fixture=fixture();var receipt=ExternalModelPluginPackage.inspect(fixture.build(),"0.7.4");
        try(var store=new ModelPluginStore(temporary.resolve("recover"),"0.7.4",core())){
            store.install(receipt,false);var before=store.apply(Set.of(id));var state=new BlockState("another:shape",Map.of());
            assertFalse(new BlockModelResolver(fixture.source("crash",false),before.selection().rules()).resolve(state).resolved());assertFalse(store.running(id));
            var after=store.restore();assertTrue(after.notices().isEmpty(),after.notices().toString());assertTrue(store.running(id));
            assertEquals(before.selection().rules().fingerprint(),after.selection().rules().fingerprint());assertNotEquals(before.executions(),after.executions());
            assertTrue(new BlockModelResolver(fixture.source("plain",false),after.selection().rules()).resolve(state).resolved());
        }
    }
    @Test void windowsPendingDeleteRemainsVisibleAcrossReloadAndRetriesAfterLockReleased()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var receipt=ExternalModelPluginPackage.inspect(fixture().build(),"0.7.4");Path library=temporary.resolve("locked-delete");
        try(var store=new ModelPluginStore(library,"0.7.4",core())){
            store.install(receipt,false);store.apply(Set.of(id));Path managed=library.resolve("packages/"+receipt.sha256()+".jar");
            try(var lock=java.nio.channels.FileChannel.open(managed,StandardOpenOption.READ,com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)){
                var removed=store.uninstall(id);assertTrue(removed.external().isEmpty());assertTrue(Files.exists(managed));assertTrue(removed.notices().stream().anyMatch(v->v.contains("待清理")));
                var checked=store.restore();assertTrue(checked.notices().stream().anyMatch(v->v.contains("待清理")),"reload must not hide the still-pending deletion");assertTrue(Files.exists(managed));
            }
            assertTrue(store.restore().notices().isEmpty());assertFalse(Files.exists(managed));
        }
    }
    @Test void dependenciesRollbackTransactionsAndRestoreOnlyAvailableClosure()throws Exception {
        var fixture=fixture();var provider=ResourceLocation.parse("provider:worker");var dependent=ResourceLocation.parse("dependent:worker");
        var transit=ResourceLocation.parse("transit:worker");var unrelated=ResourceLocation.parse("unrelated:worker");
        var first=ExternalModelPluginPackage.inspect(fixture.build("provider","1",Map.of()),"0.7.4");
        var second=ExternalModelPluginPackage.inspect(fixture.build("provider","2",Map.of()),"0.7.4");
        var receipts=List.of(first,
            ExternalModelPluginPackage.inspect(fixture.build("dependent","1",Map.of(provider,"1")),"0.7.4"),
            ExternalModelPluginPackage.inspect(fixture.build("transit","1",Map.of(dependent,"*")),"0.7.4"),
            ExternalModelPluginPackage.inspect(fixture.build("unrelated","1",Map.of()),"0.7.4"));
        Path library=temporary.resolve("dependencies"),index=library.resolve("plugins.json");
        Set<ResourceLocation> all=Set.of(provider,dependent,transit,unrelated);byte[] saved;
        try(var store=new ModelPluginStore(library,"0.7.4",core())){
            for(var receipt:receipts)store.install(receipt,false);
            var before=store.apply(all);saved=Files.readAllBytes(index);
            assertThrows(IllegalArgumentException.class,()->store.apply(Set.of(dependent,transit,unrelated)));
            assertThrows(IllegalArgumentException.class,()->store.uninstall(provider));
            assertThrows(IllegalArgumentException.class,()->store.install(second,true));
            assertSame(before,store.snapshot());assertArrayEquals(saved,Files.readAllBytes(index));
            for(var id:all)assertTrue(store.running(id));
            assertFalse(Files.exists(library.resolve("packages/"+second.sha256()+".jar")));
        }
        Path missing=library.resolve("packages/"+first.sha256()+".jar");Files.delete(missing);
        try(var store=new ModelPluginStore(library,"0.7.4",core())){
            var recovered=store.restore();assertEquals(Set.of(unrelated),recovered.selection().enabled());
            assertEquals(Set.of(unrelated),recovered.processIds().keySet());assertTrue(store.running(unrelated));
            for(var id:Set.of(provider,dependent,transit))assertFalse(store.running(id));
            assertTrue(recovered.notices().stream().anyMatch(s->s.contains("恢复失败")));
            assertEquals(2,recovered.notices().stream().filter(s->s.contains("依赖不可用")).count());
            assertArrayEquals(saved,Files.readAllBytes(index),"missing dependency must preserve desired enablement");
            Files.copy(first.file(),missing);var complete=store.restore();assertEquals(all,complete.selection().enabled());assertTrue(complete.notices().isEmpty(),complete.notices().toString());
        }
    }
}
