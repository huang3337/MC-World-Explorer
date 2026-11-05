package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.io.CancellationSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class BuiltinResourcePacksCacheTest {
    @TempDir Path temporary;

    @Test void resourceAndDataPackCallerOrderSharesOneValidatedCatalog() throws Exception {
        Path a=archive("a.jar","first","shared"), b=archive("b.jar","second","other");
        var first=locate("first:shared",List.of(a,b));
        Object catalog=cachedCatalog();
        assertEquals(first,locate("first:shared",List.of(b,a)));
        assertSame(catalog,cachedCatalog(),"同一来源集合的反序请求不应重扫所有归档");
        assertEquals(first,locate("first:shared",List.of(temporary.resolve(".").resolve("a.jar"),b)));
        assertSame(catalog,cachedCatalog(),"等价路径不能使资源/数据包调用者反复失效");
    }

    @Test void changedAddedAndRemovedSourcesInvalidateAndKeepOwnerAmbiguity() throws Exception {
        Path a=archive("a.jar","first","shared"),b=archive("b.jar","second","other");
        assertTrue(locate("first:shared",List.of(a,b)).isPresent());Object original=cachedCatalog();
        archive("a.jar","renamed","replacement");
        Files.setLastModifiedTime(a,FileTime.fromMillis(System.currentTimeMillis()+2000));
        assertTrue(locate("first:shared",List.of(b,a)).isEmpty());assertNotSame(original,cachedCatalog());
        assertTrue(locate("renamed:replacement",List.of(a,b)).isPresent());
        Path duplicate=archive("duplicate.jar","renamed","replacement");
        var warnings=new ArrayList<String>();
        assertTrue(BuiltinResourcePacks.locate("renamed:replacement",temporary,List.of(a,b,duplicate),CancellationSignal.NONE,warnings).isEmpty());
        assertTrue(warnings.stream().anyMatch(s->s.contains("来源不唯一")));
        assertTrue(locate("renamed:replacement",List.of(b,a)).isPresent());
        Files.delete(duplicate);Files.delete(b);
        assertTrue(locate("renamed:replacement",List.of(a)).isPresent(),"缓存不得持有归档句柄");
    }

    @Test void warmCatalogStillChecksCancellationAndMissingFiles() throws Exception {
        Path a=archive("a.jar","first","shared");locate("first:shared",List.of(a));
        assertThrows(CancellationException.class,()->BuiltinResourcePacks.locate("first:shared",temporary,List.of(a),()->true,new ArrayList<>()));
        Files.delete(a);
        assertThrows(java.io.IOException.class,()->locate("first:shared",List.of(a)));
    }

    private Optional<InstanceResourceLocator.Layer> locate(String id,List<Path> jars)throws Exception {
        return BuiltinResourcePacks.locate(id,temporary,jars,CancellationSignal.NONE,new ArrayList<>());
    }
    @Test void oneSelectionBatchesChecksButRejectsChangesBeforeReturningTheManifest()throws Exception {
        Path a=archive("a.jar","first","shared"),b=archive("b.jar","second","other");
        var checks=new java.util.concurrent.atomic.AtomicInteger();
        var lookup=new BuiltinResourcePacks.Lookup(temporary,List.of(a,b),()->{checks.incrementAndGet();return false;});
        assertTrue(lookup.locate("first:shared",new ArrayList<>()).isPresent());int before=checks.get();
        for(int i=0;i<100;i++)assertTrue(lookup.locate("second:other",new ArrayList<>()).isPresent());
        assertTrue(checks.get()-before<=200,"同一选择批次不能对每个包ID重新遍历所有归档");
        Files.setLastModifiedTime(a,FileTime.fromMillis(System.currentTimeMillis()+5000));
        var changed=assertThrows(ResourceAccessException.class,lookup::close);
        assertEquals(ResourceAccessException.Reason.SOURCE_CHANGED,changed.reason());
        assertThrows(IllegalStateException.class,()->lookup.locate("first:shared",new ArrayList<>()));
    }

    @Test void cancellationAndRemovalAtBatchBoundaryFailAndNoHandlesAreRetained()throws Exception {
        Path a=archive("a.jar","first","shared");var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        var lookup=new BuiltinResourcePacks.Lookup(temporary,List.of(a),cancelled::get);
        assertTrue(lookup.locate("first:shared",new ArrayList<>()).isPresent());cancelled.set(true);
        assertThrows(CancellationException.class,lookup::close);Files.delete(a);
        a=archive("a.jar","first","shared");
        var removed=new BuiltinResourcePacks.Lookup(temporary,List.of(a),CancellationSignal.NONE);
        removed.locate("first:shared",new ArrayList<>());Files.delete(a);
        assertEquals(ResourceAccessException.Reason.SOURCE_CHANGED,assertThrows(ResourceAccessException.class,removed::close).reason());
    }
    // Inspect identity rather than asserting a machine-dependent elapsed-time threshold.
    private Object cachedCatalog()throws Exception {
        var field=BuiltinResourcePacks.class.getDeclaredField("CACHE");field.setAccessible(true);
        var cache=(Map<?,?>)field.get(null);synchronized(cache){return cache.get(temporary);}
    }
    private Path archive(String name,String owner,String pack)throws Exception {
        Path path=temporary.resolve(name);
        try(var zip=new ZipOutputStream(Files.newOutputStream(path))){
            for(var e:Map.of("fabric.mod.json","{\"id\":\""+owner+"\"}","resourcepacks/"+pack+"/assets/"+owner+"/models/block/probe.json","{}").entrySet()){
                zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            }
        }
        return path;
    }
}
