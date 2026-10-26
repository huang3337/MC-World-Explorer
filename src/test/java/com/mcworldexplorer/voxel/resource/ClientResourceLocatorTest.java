package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.io.CancellationSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static com.mcworldexplorer.voxel.resource.ClientResourceLocator.Status.*;

class ClientResourceLocatorTest {
    @TempDir Path home;
    private final ClientResourceLocator locator = new ClientResourceLocator();

    @Test void prefersTheWorldOwnerOverUnrelatedInstallations() throws Exception {
        Path owner=home.resolve("game/versions/custom-profile");
        Path jar=archive(owner.resolve("custom-profile.jar"),"1.21.1","owner");
        Path other=home.resolve("other");archive(other.resolve("versions/1.21.1/1.21.1.jar"),"1.21.1","other");
        var result=locate(owner.resolve("saves/world"),Optional.empty(),List.of(other));
        assertEquals(FOUND,result.status());assertEquals(jar.toRealPath(),result.path().orElseThrow());
        assertEquals(1,result.checkedCandidates());
    }
    @Test void rejectsWrongVersionAndFallsBackToTheExactVersionUnderTheSameGame() throws Exception {
        Path root=home.resolve("game");Path owner=root.resolve("versions/modded");
        archive(owner.resolve("modded.jar"),"1.20.1","wrong-version");
        Path expected=archive(root.resolve("versions/1.21.1/1.21.1.jar"),"1.21.1","base");
        var result=locate(owner.resolve("saves/world"),Optional.empty(),List.of());
        assertEquals(expected.toRealPath(),result.path().orElseThrow());assertEquals(2,result.checkedCandidates());
    }
    @Test void usesValidatedBindingAndIgnoresAStaleOrCorruptBinding() throws Exception {
        Path owner=home.resolve("game/versions/1.21.1");
        Path own=archive(owner.resolve("1.21.1.jar"),"1.21.1","owner");
        Path bound=archive(home.resolve("manual.jar"),"1.21.1","manual");
        assertEquals(bound.toRealPath(),locate(owner.resolve("saves/world"),Optional.of(bound),List.of()).path().orElseThrow());
        Files.writeString(bound,"broken archive");
        assertEquals(own.toRealPath(),locate(owner.resolve("saves/world"),Optional.of(bound),List.of()).path().orElseThrow());
        assertEquals(own.toRealPath(),locate(owner.resolve("saves/world"),Optional.of(home.resolve("absent.jar")),List.of()).path().orElseThrow());
    }
    @Test void asksForManualChoiceWhenEquallyPlausibleArchivesDiffer() throws Exception {
        Path a=home.resolve("a"),b=home.resolve("b");
        Path one=archive(a.resolve("versions/1.21.1/1.21.1.jar"),"1.21.1","one");
        Path two=archive(b.resolve("versions/1.21.1/1.21.1.jar"),"1.21.1","two");
        assertEquals(AMBIGUOUS,locate(home.resolve("standalone"),Optional.empty(),List.of(a,b)).status());
        Files.copy(one,two,StandardCopyOption.REPLACE_EXISTING);
        var same=locate(home.resolve("standalone"),Optional.empty(),List.of(a,b,a.resolve("../a")));
        assertEquals(FOUND,same.status());assertEquals(2,same.checkedCandidates());
    }
    @Test void handlesMissingInvalidAndCancelledRequestsWithoutSearchingEverywhere() {
        assertEquals(MISSING,locate(home.resolve("missing"),Optional.empty(),List.of()).status());
        var wrong=locator.locate(home,"../1.20.1",Optional.empty(),List.of(home),CancellationSignal.NONE);
        assertEquals(INVALID_VERSION,wrong.status());assertEquals(0,wrong.checkedCandidates());
        assertThrows(java.util.concurrent.CancellationException.class,
                ()->locator.locate(home,"1.21.1",Optional.empty(),List.of(home),()->true));
    }
    @Test void matchesDifferentReleaseAndSnapshotVersionsWithoutAWhitelist() throws Exception {
        for (String version : List.of("1.20.1", "1.21.4", "25w14a")) {
            Path root = home.resolve(version);
            Path expected = archive(root.resolve("versions").resolve(version).resolve(version + ".jar"), version, version);
            var result = locator.locate(root.resolve("saves/world"), version, Optional.empty(), List.of(root), CancellationSignal.NONE);
            assertEquals(FOUND, result.status());
            assertEquals(expected.toRealPath(), result.path().orElseThrow());
            assertTrue(result.message().contains(version));
        }
        for (String version : List.of("../escape", "C:\\escape", "/absolute", "", "..")) {
            var result = locator.locate(home, version, Optional.empty(), List.of(home), CancellationSignal.NONE);
            assertEquals(INVALID_VERSION, result.status());
            assertEquals(0, result.checkedCandidates());
        }
    }

    @Test void boundsExistingArchiveChecks() throws Exception {
        Path world=home;
        for(int i=0;i<8;i++)world=world.resolve("saves");
        Files.createDirectories(world);
        for(Path p=world;p!=null && p.startsWith(home);p=p.getParent()) {
            Files.writeString(p.resolve("1.21.1.jar"),"invalid");
            if(p.getFileName()!=null)Files.writeString(p.resolve(p.getFileName()+".jar"),"invalid");
        }
        List<Path> roots=new ArrayList<>();
        for(int i=0;i<8;i++){
            Path p=home.resolve("external-"+i).resolve("1.21.1");Files.createDirectories(p.resolve("versions/1.21.1"));
            Files.writeString(p.resolve("1.21.1.jar"),"invalid");Files.writeString(p.resolve("versions/1.21.1/1.21.1.jar"),"invalid");roots.add(p);
        }
        var result=locate(world.resolve("world"),Optional.empty(),roots);
        assertEquals(MISSING,result.status());assertEquals(ClientResourceLocator.MAX_CANDIDATES,result.checkedCandidates());
    }
    @Test void locatesTheConfiguredRealClientFromItsSaveWithoutExplicitJarSelection() throws Exception {
        String world=System.getenv("MCWORLD_UI_TEST_WORLD"),jar=System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");
        assumeTrue(world!=null && jar!=null);
        var result=locate(Path.of(world),Optional.empty(),List.of());
        assertEquals(FOUND,result.status());assertEquals(Path.of(jar).toRealPath(),result.path().orElseThrow());
    }
    private ClientResourceLocator.Result locate(Path world,Optional<Path> binding,List<Path> roots){
        return locator.locate(world,"1.21.1",binding,roots,CancellationSignal.NONE);
    }
    private static Path archive(Path file,String version,String marker)throws Exception {
        Files.createDirectories(file.getParent());
        Map<String,String> entries=Map.of("version.json","{\"id\":\""+version+"\",\"world_version\":3955,\"pack_version\":{\"resource\":34}}",
                "assets/minecraft/blockstates/stone.json","{}", "assets/minecraft/models/block/stone.json","{}",
                "assets/minecraft/textures/block/stone.png","synthetic archive probe: "+marker);
        try(var zip=new ZipOutputStream(Files.newOutputStream(file))){for(var e:entries.entrySet()){
            zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}
        return file;
    }
}
