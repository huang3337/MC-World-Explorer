package com.mcworldexplorer.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("systemProperties")
class SavedPlacesTest {
    @TempDir Path temp;
    private final SavedPlaces.Position home = new SavedPlaces.Position("minecraft:overworld", -17, 9);
    private final SavedPlaces.Position portal = new SavedPlaces.Position("minecraft:the_nether", -17, 9);
    private String previous;

    @org.junit.jupiter.api.BeforeEach void setup() {
        previous = System.getProperty("mcworldexplorer.home");
        System.setProperty("mcworldexplorer.home", temp.resolve("app").toString());
    }

    @org.junit.jupiter.api.AfterEach void restore() {
        if (previous == null) System.clearProperty("mcworldexplorer.home");
        else System.setProperty("mcworldexplorer.home", previous);
    }

    @Test void persistsEditsAndKeepsOtherWorldAndDimensionIndependent() throws IOException {
        Path world = Files.createDirectories(temp.resolve("instance-a/world"));
        Path twin = Files.createDirectories(temp.resolve("instance-b/world"));
        SavedPlaces first = new SavedPlaces(world);
        first.change(home, null, " 家 = 山间基地 ");
        SavedPlaces reopened = new SavedPlaces(world);
        assertEquals(Map.of(home, "家 = 山间基地"), reopened.read());
        reopened.change(portal, null, "门");
        first.change(home, "家 = 山间基地", "新家");
        assertThrows(IOException.class, () -> reopened.change(home, "家 = 山间基地", null));
        assertThrows(IOException.class, () -> reopened.change(home, null, "重复"));
        assertEquals(Map.of(home, "新家", portal, "门"), reopened.read());
        new SavedPlaces(twin).change(home, null, "另一处");
        first.change(home, "新家", null);
        assertEquals(Map.of(portal, "门"), new SavedPlaces(world).read());
        assertEquals(Map.of(home, "另一处"), new SavedPlaces(twin).read());
        try (var entries = Files.list(world)) { assertEquals(0, entries.count()); }
        assertTrue(Files.notExists(PortablePaths.cacheDirectory()));
    }

    @Test void linksSurviveReopeningAndUseOneAtomicEditForBothEndpoints() throws IOException {
        Path world = Files.createDirectory(temp.resolve("world"));
        var store = new SavedPlaces(world);
        var end = new SavedPlaces.Position(home.dimension(), 18, -14);
        var link = new SavedPlaces.Link(home, end);
        var reverse = new SavedPlaces.Link(end, home);
        assertEquals(link, reverse);
        store.change(home, null, "原有地点");
        assertThrows(IOException.class, () -> store.connect(home, end, null));
        assertThrows(IllegalArgumentException.class, () -> store.connect(home, portal, "跨维度"));
        assertEquals(Map.of(home, "原有地点"), store.read());
        store.connect(home, end, "村庄");
        var style = new SavedPlaces.LineData("#FF0088", "主线 = 第一段");
        store.editLink(reverse, SavedPlaces.LineData.DEFAULT, style);
        var reopened = new SavedPlaces(world);
        assertEquals(Map.of(link, style), reopened.connect(end, home, null).lines());
        assertThrows(IOException.class, () -> reopened.editLink(link, SavedPlaces.LineData.DEFAULT, null));
        assertThrows(IOException.class, () -> store.changePlace(home, "原有地点", null, Map.of()));
        reopened.change(end, "村庄", "南村");
        assertEquals(style, store.snapshot().lines().get(link));
        assertEquals("X 轴相距 35 格\nZ 轴相距 23 格", link.distances());
        store.editLink(link, style, null);
        assertEquals(Map.of(home, "原有地点", end, "南村"), reopened.read());
        reopened.connect(end, home, null);
        store.change(home, "原有地点", null);
        assertEquals(Map.of(), reopened.snapshot().lines());
        assertEquals(Map.of(end, "南村"), reopened.read());
        try (var entries = Files.list(world)) { assertEquals(0, entries.count()); }
    }

    @Test void axisDistanceSupportsExtremeCoordinatesAndZeroOnOneAxis() {
        var west = new SavedPlaces.Position("dim", Integer.MIN_VALUE, 2);
        var east = new SavedPlaces.Position("dim", Integer.MAX_VALUE, 2);
        assertEquals("X 轴相距 4294967295 格\nZ 轴相距 0 格", new SavedPlaces.Link(east, west).distances());
    }

    @Test void legacyLinksKeepTheirPlacesAndMixedLinksKeepSourceNamesAfterReopen() throws IOException {
        Path world = Files.createDirectory(temp.resolve("legacy"));
        var store = new SavedPlaces(world);
        var end = new SavedPlaces.Position(home.dimension(), 12, 8);
        var remote = new SavedPlaces.Position(home.dimension(), 80, -20);
        store.change(home, null, "世界出生点");
        Path file;
        try (var files = Files.list(PortablePaths.configDirectory().resolve("places"))) {
            file = files.filter(path -> path.toString().endsWith(".properties")).findFirst().orElseThrow();
        }
        var legacy = new java.util.Properties();
        try (var reader = Files.newBufferedReader(file)) { legacy.load(reader); }
        legacy.setProperty("format", "2");
        legacy.setProperty(home.dimension() + "\t12\t8", "小屋");
        legacy.setProperty("link\t" + home.dimension() + "\t-17\t9\t12\t8", "#112233\n原备注");
        try (var writer = Files.newBufferedWriter(file)) { legacy.store(writer, null); }
        var oldLine = new SavedPlaces.Link(home, end);
        assertEquals(new SavedPlaces.LineData("#112233", "原备注"), store.snapshot().lines().get(oldLine));
        var mixed = new SavedPlaces.Link(home, remote);
        store.connect(new SavedPlaces.Endpoint(remote, "玩家\"甲\""), new SavedPlaces.Endpoint(home, null), null);
        assertEquals(Map.of(home, "世界出生点", end, "小屋"), store.read());
        var data = store.snapshot().lines().get(mixed);
        assertEquals("世界出生点 — 玩家\"甲\"", data.title(mixed, store.read()));
        store.editLink(mixed, data, data.withColor("#443322").withNote("回程\n待定"));
        var loaded = new SavedPlaces(world).snapshot();
        assertEquals("回程\n待定", loaded.lines().get(mixed).note());
        assertEquals("玩家\"甲\"", loaded.lines().get(mixed).sourceB());
        assertEquals(2, loaded.places().size());
        assertThrows(IOException.class, () -> store.change(remote, null, null));
        store.change(remote, null, "同坐标手工点");
        store.change(remote, "同坐标手工点", null);
        assertEquals(loaded, store.snapshot());
        store.change(home, "世界出生点", null);
        assertTrue(store.snapshot().lines().isEmpty());
        assertEquals(Map.of(end, "小屋"), store.read());
    }

    @Test void damagedFileIsNotReplacedByAnEmptyLibrary() throws IOException {
        SavedPlaces places = new SavedPlaces(Files.createDirectory(temp.resolve("world")));
        places.change(home, null, "原名");
        Path file;
        try (var entries = Files.list(PortablePaths.configDirectory().resolve("places"))) {
            file = entries.filter(path -> path.toString().endsWith(".properties")).findFirst().orElseThrow();
        }
        Files.writeString(file, "format=unsupported");
        assertThrows(IOException.class, places::read);
        assertThrows(IOException.class, () -> places.change(home, null, "新地点"));
        assertEquals("format=unsupported", Files.readString(file));
    }

    @Test void refusesConfigurationInsideTheWorldBeforeCreatingDirectories() throws IOException {
        Path world = Files.createDirectory(temp.resolve("world"));
        System.setProperty("mcworldexplorer.home", world.resolve("app").toString());
        SavedPlaces places = new SavedPlaces(world);
        assertThrows(IOException.class, () -> places.change(home, null, "家"));
        assertTrue(Files.notExists(world.resolve("app")));
    }
}
