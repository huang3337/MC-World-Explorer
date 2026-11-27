package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.io.CancellationSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RealInstanceResourceIntegrationTest {
    @TempDir Path root;
    @Test void readsRealModResourcesAcrossMoreThanSixteenArchivesWithoutExecutingCode() throws Exception {
        String instance=System.getenv("MCWORLD_MOD_INSTANCE_DIR"),client=System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");assumeTrue(instance!=null&&client!=null);
        Path mods=Path.of(instance).resolve("mods");Path jar;
        try(var files=Files.list(mods)){jar=files.filter(p->p.getFileName().toString().startsWith("chipped-")).findFirst().orElseThrow();}
        String state;
        try(var zip=new ZipFile(jar.toFile())){state=zip.stream().map(java.util.zip.ZipEntry::getName).filter(name->name.startsWith("assets/chipped/blockstates/")&&name.endsWith(".json")).sorted().findFirst().orElseThrow();}
        String block=state.substring("assets/chipped/blockstates/".length(),state.length()-5);
        long start=System.nanoTime();
        try(var source=InstanceResourceSource.open(root,Path.of(client),"1.21.1",Path.of(instance),CancellationSignal.NONE,message->{})) {
            assertTrue(source.sourceCount()>16);assertTrue(source.readJson(ResourceLocation.Kind.BLOCKSTATE,ResourceLocation.parse("chipped:"+block)).isPresent());
            assertTrue(source.origin(ResourceLocation.Kind.BLOCKSTATE,ResourceLocation.parse("chipped:"+block)).orElseThrow().contains("chipped-"));
            source.validateAll();System.out.printf("Real instance: %d sources, %.1f ms, warnings=%d, block=%s%n",source.sourceCount(),(System.nanoTime()-start)/1e6,source.warnings().size(),block);
        }
    }
}
