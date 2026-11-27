package com.mcworldexplorer.voxel.resource;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelPluginManifestTest {
    private static String example()throws IOException{return ExternalModelPluginTest.manifestJson();}
    @Test void roundtripAndCompatibilityDoNotRequireAdapterClasses()throws Exception {
        var manifest=ModelPluginManifest.parse(example());
        assertEquals(manifest,ModelPluginManifest.parse(manifest.json()));
        manifest.requireHost("0.7.4");
        assertThrows(IllegalArgumentException.class,()->manifest.requireHost("0.7.5"));
        assertEquals(ResourceLocation.parse("example:planes"),manifest.plugin().id());
        var dependencies=new ModelPlugin.Descriptor(manifest.plugin().id(),"Name","1",1,"Description",Set.of(),Map.of(ResourceLocation.parse("library:shared"),"2"),Set.of(ModelPlugin.Capability.NEIGHBORS));
        var withDependency=new ModelPluginManifest(1,ModelPluginManifest.SDK_REVISION,Set.of("0.7.4"),"isolated-jvm","outside.Plugin",dependencies);
        assertEquals(withDependency,ModelPluginManifest.parse(withDependency.json()));
    }
    @Test void malformedDuplicateUnknownAndIncompatibleDeclarationsFailBeforeCode()throws Exception {
        String input=example();
        for(String invalid:List.of(input.replace("\"format\": 1","\"format\": 1, \"format\": 1"),
            input.replace("\"format\": 1","\"format\": \"1\""),input.replace("\"format\": 1","\"format\": 1.5"),
            input.replace("\"format\": 1","\"format\": 1, \"unknown\": true"),input+"{}",
            input.replace(ModelPluginManifest.SDK_REVISION,"future-api"),input.replace(ModelPluginManifest.SDK_REVISION,"mcwe-model-api-1-preview.1"),input.replace(ModelPluginManifest.SDK_REVISION,"mcwe-model-api-1-preview.2"),input.replace(ModelPluginManifest.SDK_REVISION,"mcwe-model-api-1-preview.3"),input.replace("\"apiVersion\": 1","\"apiVersion\": 2"),
            input.replace("example.PlanePlugin","com.mcworldexplorer.Escape"),input.replace("isolated-jvm","in-process"),
            input.replace("\"example\"","\"example\",\"example\""),
            input.replace("\"dependencies\": {}","\"dependencies\": {\"shared\":\"1\",\"minecraft:shared\":\"2\"}")))
            assertThrows(IOException.class,()->ModelPluginManifest.parse(invalid),invalid);
        assertThrows(IOException.class,()->ModelPluginManifest.parse(" ".repeat(ModelPluginManifest.MAX_JSON_CHARS+1)));
        assertThrows(IOException.class,()->ModelPluginManifest.parse(input.replace(ModelPluginManifest.SDK_REVISION,"mcwe-model-api-1-preview.4")));
        assertThrows(IOException.class,()->ModelPluginManifest.parse(input.replace(ModelPluginManifest.SDK_REVISION,"mcwe-model-api-1-preview.5")));
        assertThrows(IOException.class,()->ModelPluginManifest.parse(input.replace(ModelPluginManifest.SDK_REVISION,"mcwe-model-api-1-preview.6")));
        assertThrows(IOException.class,()->ModelPluginManifest.parse(input.replace(ModelPluginManifest.SDK_REVISION,"mcwe-model-api-1-preview.7")));
        assertThrows(IOException.class,()->ModelPluginManifest.parse(input.replace(ModelPluginManifest.SDK_REVISION,"mcwe-model-api-1-preview.8")));
        assertThrows(IOException.class,()->ModelPluginManifest.parse(input.replace(ModelPluginManifest.SDK_REVISION,"mcwe-model-api-1-preview.9")));
        assertThrows(IOException.class,()->ModelPluginManifest.parse(input.replace(ModelPluginManifest.SDK_REVISION,"mcwe-model-api-1-preview.10")));
        assertThrows(IOException.class,()->ModelPluginManifest.parse("[".repeat(12)+"0"+"]".repeat(12)));
    }
}
