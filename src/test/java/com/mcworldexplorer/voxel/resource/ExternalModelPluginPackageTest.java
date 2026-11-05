package com.mcworldexplorer.voxel.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ExternalModelPluginPackageTest {
    @TempDir Path temporary;
    private Map<String,byte[]> entries()throws IOException {
        return new LinkedHashMap<>(Map.of(ModelPluginManifest.ENTRY,ExternalModelPluginTest.manifestJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),"example/PlanePlugin.class",new byte[]{0,1,2,3}));
    }
    private Path write(Map<String,byte[]> entries)throws IOException {
        Path file=temporary.resolve(UUID.randomUUID()+".jar");try(var zip=new ZipOutputStream(Files.newOutputStream(file))){for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}return file;
    }
    @Test void inspectionIsInertAndTracksExactBytes()throws Exception {
        Path jar=write(entries());var inspected=ExternalModelPluginPackage.inspect(jar,"0.7.4");
        assertEquals("example.PlanePlugin",inspected.manifest().entrypoint());inspected.validate();
        // Deliberately invalid class bytes prove inspection never tries defining the entrypoint.
        Files.write(jar,new byte[]{1},StandardOpenOption.APPEND);
        assertThrows(IOException.class,inspected::validate);
        assertThrows(IOException.class,()->ExternalModelPluginPackage.inspect(jar,"0.7.5"));
    }
    @Test void ordinaryModsEscapesEmbeddedApiAndExternalClasspathAreRejected()throws Exception {
        assertThrows(IOException.class,()->ExternalModelPluginPackage.inspect(write(Map.of("fabric.mod.json","{}".getBytes(StandardCharsets.UTF_8))),"0.7.4"));
        for(String invalid:List.of("../escape","C:/escape","nested\\escape","com/mcworldexplorer/voxel/resource/ModelRule.class","lib/other.jar","META-INF/services/java.lang.Runnable","native/library.dll")){
            var values=entries();values.put(invalid,new byte[]{1});
            assertThrows(IOException.class,()->ExternalModelPluginPackage.inspect(write(values),"0.7.4"),invalid);
        }
        var values=entries();values.put("META-INF/MANIFEST.MF","Manifest-Version: 1.0\r\nClass-Path: outside.jar\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class,()->ExternalModelPluginPackage.inspect(write(values),"0.7.4"));
    }
    @Test void missingEntrypointAndOversizedCompressedEntryFail()throws Exception {
        var missing=entries();missing.remove("example/PlanePlugin.class");assertThrows(IOException.class,()->ExternalModelPluginPackage.inspect(write(missing),"0.7.4"));
        var large=entries();large.put("payload.bin",new byte[ExternalModelPluginPackage.MAX_ENTRY_BYTES+1]);
        assertThrows(IOException.class,()->ExternalModelPluginPackage.inspect(write(large),"0.7.4"));
    }
}
