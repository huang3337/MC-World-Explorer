package com.mcworldexplorer.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortableSettingsTest {


    @TempDir
    Path tempDir;

    @Test
    void settingsPageMigratesMissingOrUnknownFieldsAndPreservesWorldBindings() throws IOException {
        System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY, tempDir.toString());
        Path config = Files.createDirectories(tempDir.resolve("config")).resolve("settings.properties");
        Files.writeString(config, "map_pipeline=future\nmap_scan_workers=8\nheap_mib=-1\nother=keep\n");
        var settings = new PortableSettings();
        assertEquals(new RuntimePreferences(RuntimePreferences.MapMode.AUTO, 0, 2), settings.loadRuntimePreferences());
        var world = tempDir.resolve("world");
        var client = tempDir.resolve("client.jar");
        settings.saveClientResourcePath(world, "1.21.5", client);
        var selected = new RuntimePreferences(RuntimePreferences.MapMode.SOFTWARE, 1024, 1);
        settings.savePreferences(selected, true, "enhanced", tempDir);
        var reopened = new PortableSettings();
        assertEquals(selected, reopened.loadRuntimePreferences());
        assertTrue(reopened.usesDarkTheme());
        assertEquals("enhanced", reopened.loadViewerPerformanceProfile().orElseThrow());
        assertEquals(tempDir, reopened.loadCustomSavesPath().orElseThrow());
        assertEquals(client, reopened.loadClientResourcePath(world, "1.21.5").orElseThrow());
        assertTrue(Files.readString(config).contains("other=keep"));
    }

    @Test
    void selectingAModelReplacesLegacySoftwareAndSurvivesReopening() throws IOException {
        System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY, tempDir.toString());
        Path config = Files.createDirectories(tempDir.resolve("config")).resolve("settings.properties");
        Files.writeString(config, "map_pipeline=sw\nother=keep\n");
        var store = new PortableSettings();
        assertTrue(store.loadRuntimePreferences().softwareMap());
        var selection = new RuntimePreferences(RuntimePreferences.MapMode.AUTO, 2048, 1, "8086&A788&802F17AA");
        store.savePreferences(selection, false, "basic", null);
        assertEquals(selection, new PortableSettings().loadRuntimePreferences());
        assertTrue(selection.requestsGpu());
        assertTrue(Files.readString(config).contains("other=keep"));
        store.savePreferences(new RuntimePreferences(false, 2048, 1), false, "basic", null);
        assertFalse(store.gpuResetPending("MCWE.exe"));
        assertEquals("", store.loadRuntimePreferences().mapAdapter());
    }

    @Test
    void oldGpuSettingsMigrateOnceAndPendingClearsStayBoundToTheirExecutable() throws IOException {
        System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY, tempDir.toString());
        var config = Files.createDirectories(tempDir.resolve("config")).resolve("settings.properties");
        Files.writeString(config, "map_adapter=8086&A788&802F17AA\nother=keep\n");
        var store = new PortableSettings();
        var original = store.loadRuntimePreferences();
        assertTrue(store.hasLegacyGpuPreference());
        store.markGpuSessionPreference(original);
        assertFalse(store.hasLegacyGpuPreference());
        assertEquals(original, store.loadRuntimePreferences());
        assertTrue(Files.readString(config).contains("other=keep"));
        Files.writeString(config, "map_gpu_reset=D:/original/MCWE.exe\nother=keep\n");
        store.finishGpuReset("D:/another/MCWE.exe");
        assertTrue(store.gpuResetPending("D:/original/MCWE.exe"));
        store.finishGpuReset("D:/original/MCWE.exe");
        assertFalse(store.gpuResetPending("D:/original/MCWE.exe"));
        assertTrue(Files.readString(config).contains("other=keep"));
    }

    @Test
    void unknownAppearanceDefaultsToLightAndSavingPreservesOtherPreferences() throws IOException {
        System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY, tempDir.toString());
        Path config = Files.createDirectories(tempDir.resolve("config")).resolve("settings.properties");
        Files.writeString(config, "appearance=future\nother.preference=keep\n");
        var settings = new PortableSettings();
        org.junit.jupiter.api.Assertions.assertFalse(settings.usesDarkTheme());
        settings.saveDarkTheme(true);
        assertTrue(new PortableSettings().usesDarkTheme());
        var stored = new java.util.Properties();
        try (var input = Files.newInputStream(config)) {
            stored.load(input);
        }
        assertEquals("keep", stored.getProperty("other.preference"));
    }

    private final String originalHome = System.getProperty(PortablePaths.APPLICATION_HOME_PROPERTY);

    @AfterEach
    void restoreApplicationHome() {
        if (originalHome == null) {
            System.clearProperty(PortablePaths.APPLICATION_HOME_PROPERTY);
        } else {
            System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY, originalHome);
        }
    }

    @Test
    void remembersResourceBindingsPerWorldAndVersionWithoutLosingOtherPreferences() throws Exception {
        System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY,tempDir.resolve("app").toString());
        PortableSettings settings=new PortableSettings();
        Path a=tempDir.resolve("世界甲"),b=tempDir.resolve("世界乙"),jar=tempDir.resolve("客户端.jar");
        settings.saveCustomSavesPath(tempDir);
        settings.saveViewerPerformanceProfile("enhanced");
        settings.saveClientResourcePath(a,"1.21.1",jar);
        settings.saveClientResourcePath(b,"1.21.1",jar);
        assertEquals(jar.toAbsolutePath(),new PortableSettings().loadClientResourcePath(a,"1.21.1").orElseThrow());
        assertTrue(settings.loadClientResourcePath(a,"1.20.1").isEmpty());
        settings.saveClientResourcePath(a,"1.21.1",null);
        assertTrue(settings.loadClientResourcePath(a,"1.21.1").isEmpty());
        assertEquals(jar.toAbsolutePath(),settings.loadClientResourcePath(b,"1.21.1").orElseThrow());
        assertEquals(tempDir.toAbsolutePath(),settings.loadCustomSavesPath().orElseThrow());
        assertEquals("enhanced",settings.loadViewerPerformanceProfile().orElseThrow());
    }

    @Test void instanceBindingsAreIndependentOfClientAndWorldVersion()throws Exception {
        System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY,tempDir.resolve("app").toString());
        var settings=new PortableSettings();Path world=tempDir.resolve("world"),other=tempDir.resolve("other"),instance=tempDir.resolve("实例"),jar=tempDir.resolve("client.jar");
        settings.saveClientResourcePath(world,"1.21.1",jar);settings.saveResourceInstancePath(world,"1.21.1",instance);
        assertEquals(instance.toAbsolutePath(),new PortableSettings().loadResourceInstancePath(world,"1.21.1").orElseThrow());
        assertTrue(settings.loadResourceInstancePath(world,"1.20.1").isEmpty());assertTrue(settings.loadResourceInstancePath(other,"1.21.1").isEmpty());
        settings.saveResourceInstancePath(world,"1.21.1",null);assertTrue(settings.loadResourceInstancePath(world,"1.21.1").isEmpty());
        assertEquals(jar.toAbsolutePath(),settings.loadClientResourcePath(world,"1.21.1").orElseThrow());
    }

    @Test void unifiedResetPreservesOtherWorldsVersionsAndPreferences()throws Exception {
        System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY,tempDir.resolve("app").toString());
        var settings=new PortableSettings();Path a=tempDir.resolve("世界甲"),b=tempDir.resolve("世界乙"),jar=tempDir.resolve("client.jar"),instance=tempDir.resolve("游戏目录");
        settings.saveCustomSavesPath(tempDir);settings.saveViewerPerformanceProfile("enhanced");
        for(var world:java.util.List.of(a,b))for(String version:java.util.List.of("1.21.1","1.20.1")) {
            settings.saveClientResourcePath(world,version,jar);settings.saveResourceInstancePath(world,version,instance);
        }
        settings.clearResourceBindings(a,"1.21.1");var reopened=new PortableSettings();
        assertTrue(reopened.loadClientResourcePath(a,"1.21.1").isEmpty());assertTrue(reopened.loadResourceInstancePath(a,"1.21.1").isEmpty());
        for(var world:java.util.List.of(a,b))for(String version:java.util.List.of("1.21.1","1.20.1"))if(!world.equals(a)||!version.equals("1.21.1")) {
            assertEquals(jar,reopened.loadClientResourcePath(world,version).orElseThrow());assertEquals(instance,reopened.loadResourceInstancePath(world,version).orElseThrow());
        }
        assertEquals(tempDir,reopened.loadCustomSavesPath().orElseThrow());assertEquals("enhanced",reopened.loadViewerPerformanceProfile().orElseThrow());
    }

    @Test
    void missingSettingsDoNotCreateConfigDirectory() throws IOException {
        Path applicationRoot = Files.createDirectory(tempDir.resolve("app"));
        System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY, applicationRoot.toString());

        assertTrue(new PortableSettings().loadCustomSavesPath().isEmpty());
        assertTrue(Files.notExists(applicationRoot.resolve("config")));
    }

    @Test
    void savesAndLoadsCustomPathInsidePortableConfig() throws IOException {
        Path applicationRoot = Files.createDirectory(tempDir.resolve("app"));
        Path selectedPath = tempDir.resolve("整合包/实例");
        System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY, applicationRoot.toString());
        PortableSettings settings = new PortableSettings();

        settings.saveCustomSavesPath(selectedPath);

        assertEquals(
                selectedPath.toAbsolutePath().normalize(),
                settings.loadCustomSavesPath().orElseThrow());
        assertTrue(Files.isRegularFile(applicationRoot.resolve("config/settings.properties")));
    }

    @Test
    void replacesExistingSettingWithoutLeavingTemporaryFiles() throws IOException {
        Path applicationRoot = Files.createDirectory(tempDir.resolve("app"));
        System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY, applicationRoot.toString());
        PortableSettings settings = new PortableSettings();

        settings.saveCustomSavesPath(tempDir.resolve("first"));
        settings.saveCustomSavesPath(tempDir.resolve("second"));

        assertEquals(
                tempDir.resolve("second").toAbsolutePath().normalize(),
                settings.loadCustomSavesPath().orElseThrow());
        try (var files = Files.list(applicationRoot.resolve("config"))) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void viewerProfileAndCustomPathPreserveEachOther() throws IOException {
        Path applicationRoot = Files.createDirectory(tempDir.resolve("app"));
        Path selectedPath = tempDir.resolve("instances");
        System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY, applicationRoot.toString());
        PortableSettings settings = new PortableSettings();

        settings.saveCustomSavesPath(selectedPath);
        settings.saveViewerPerformanceProfile("enhanced");
        settings.saveCustomSavesPath(tempDir.resolve("updated"));

        assertEquals("enhanced", settings.loadViewerPerformanceProfile().orElseThrow());
        assertEquals(
                tempDir.resolve("updated").toAbsolutePath().normalize(),
                settings.loadCustomSavesPath().orElseThrow());
    }

    @Test
    void missingViewerProfileDoesNotCreateConfiguration() throws IOException {
        Path applicationRoot = Files.createDirectory(tempDir.resolve("app"));
        System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY, applicationRoot.toString());

        assertTrue(new PortableSettings().loadViewerPerformanceProfile().isEmpty());
        assertTrue(Files.notExists(applicationRoot.resolve("config")));
    }

    @Test
    void rejectsBlankViewerProfile() throws IOException {
        Path applicationRoot = Files.createDirectory(tempDir.resolve("app"));
        System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY, applicationRoot.toString());

        assertThrows(IllegalArgumentException.class,
                () -> new PortableSettings().saveViewerPerformanceProfile(" "));
    }

    @Test
    void reportsConfigDirectoryFailureWithoutFallback() throws IOException {
        Path applicationRoot = Files.createDirectory(tempDir.resolve("app"));
        Files.writeString(applicationRoot.resolve("config"), "occupied");
        System.setProperty(PortablePaths.APPLICATION_HOME_PROPERTY, applicationRoot.toString());

        assertThrows(
                IOException.class,
                () -> new PortableSettings().saveCustomSavesPath(tempDir.resolve("worlds")));
    }
}
