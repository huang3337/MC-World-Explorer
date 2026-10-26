package com.mcworldexplorer.voxel.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.mcworldexplorer.voxel.resource.ResourceAccessException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ClientResourceArchiveTest {
    private static final String VERSION = "{\"id\":\"1.21.1\",\"world_version\":3955,\"pack_version\":{\"resource\":34,\"data\":48}}";
    private static final String MODEL = "assets/minecraft/models/block/stone.json";
    private static final String TEXTURE = "assets/minecraft/textures/block/stone.png";
    @TempDir Path home;
    private int sequence;

    @Test void cancelsFingerprintingWhenTheLookupThreadIsInterrupted() throws Exception {
        Path file=fixture(Map.of());
        try {
            Thread.currentThread().interrupt();
            assertThrows(java.util.concurrent.CancellationException.class,()->ClientResourceArchive.open(file,"1.21.1"));
        } finally { Thread.interrupted(); }
        try(var archive=ClientResourceArchive.open(file,"1.21.1")) { assertEquals("1.21.1",archive.metadata().versionId()); }
    }

    @Test void readsTypedAssetsAndPreservesTheSource() throws Exception {
        Path file = fixture(Map.of());
        String before = stamp(file);
        try (ClientResourceArchive archive = ClientResourceArchive.open(file, "1.21.1")) {
            assertEquals(new ClientResourceArchive.Metadata("1.21.1", 3955, 34), archive.metadata());
            assertEquals(64, archive.fingerprint().sha256().length());
            assertEquals("{\"parent\":\"minecraft:block/cube_all\"}", archive.readJson(
                    ResourceLocation.Kind.MODEL, ResourceLocation.parse("block/stone")).orElseThrow());
            assertArrayEquals(new byte[]{(byte) 137, 80, 78, 71}, archive.read(
                    ResourceLocation.Kind.TEXTURE, ResourceLocation.parse("block/stone")).orElseThrow());
            assertTrue(archive.read(ResourceLocation.Kind.MODEL, ResourceLocation.parse("example:block/missing")).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> archive.readJson(
                    ResourceLocation.Kind.TEXTURE, ResourceLocation.parse("block/stone")));
        }
        assertEquals(before, stamp(file));
    }

    @Test void reportsMissingWrongVersionAndIncompleteClientResources() throws Exception {
        assertReason(SOURCE_MISSING, () -> ClientResourceArchive.open(home.resolve("absent.jar"), "1.21.1"));
        Path text = home.resolve("not-a-client.jar"); Files.writeString(text, "not zip");
        assertReason(INVALID_ARCHIVE, () -> ClientResourceArchive.open(text, "1.21.1"));
        assertReason(VERSION_MISMATCH, () -> ClientResourceArchive.open(fixture(Map.of()), "1.20.1"));
        Path server = archive(Map.of("version.json", VERSION.getBytes(StandardCharsets.UTF_8)));
        assertReason(INVALID_ARCHIVE, () -> ClientResourceArchive.open(server, "1.21.1"));
    }

    @Test void rejectsInvalidMetadataWithoutLeakingItsArchiveHandle() throws Exception {
        for (String invalid : new String[]{"{}", "{broken", VERSION + " true", "// comment\n" + VERSION,
                VERSION.replace("3955", "\"3955\""), VERSION.replace("34", "\"34\""),
                VERSION.replace("\"1.21.1\"", "123"), VERSION.replace("3955", "-1"),
                VERSION.replace("\"id\":", "\"id\":\"duplicate\",\"id\":"),
                VERSION.replace("\"resource\":34", "\"resource\":34,\"resource\":35"),
                VERSION.substring(0, VERSION.length() - 1) + ",\"extra\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}"}) {
            Path file = fixture(Map.of("version.json", invalid.getBytes(StandardCharsets.UTF_8)));
            assertReason(INVALID_METADATA, () -> ClientResourceArchive.open(file, "1.21.1"));
            Files.move(file, file.resolveSibling(file.getFileName() + ".checked"));
        }
        assertReason(INVALID_METADATA, () -> ClientResourceArchive.open(
                fixture(Map.of("version.json", new byte[]{(byte) 0xc3, 40})), "1.21.1"));
    }

    @Test void rejectsUnsafeAndDuplicateArchivePaths() throws Exception {
        for (String name : new String[]{"../escape", "/absolute", "C:/drive", "assets/minecraft/./stone.json",
                "assets\\minecraft\\stone.json", "assets//stone.json"}) {
            assertReason(INVALID_ARCHIVE, () -> ClientResourceArchive.open(fixture(Map.of(name, new byte[]{1})), "1.21.1"));
        }
        Path file = fixture(Map.of("extra/a", new byte[]{1}, "extra/b", new byte[]{2}));
        byte[] bytes = Files.readAllBytes(file);
        byte[] needle = "extra/b".getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index <= bytes.length - needle.length; index++) {
            boolean matches = true;
            for (int j = 0; j < needle.length; j++) if (bytes[index + j] != needle[j]) matches = false;
            if (matches) bytes[index + needle.length - 1] = 'a';
        }
        Files.write(file, bytes);
        assertReason(INVALID_ARCHIVE, () -> ClientResourceArchive.open(file, "1.21.1"));
    }

    @Test void boundsDecompressionAndTheTotalSessionReadBudget() throws Exception {
        Path metadata = fixture(Map.of("version.json", new byte[ClientResourceArchive.MAX_METADATA_BYTES + 1]));
        assertReason(LIMIT_EXCEEDED, () -> ClientResourceArchive.open(metadata, "1.21.1"));
        Path model = fixture(Map.of(MODEL, new byte[ResourceLocation.Kind.MODEL.maxBytes() + 1]));
        try (var archive = ClientResourceArchive.open(model, "1.21.1")) {
            assertReason(LIMIT_EXCEEDED, () -> archive.read(ResourceLocation.Kind.MODEL, ResourceLocation.parse("block/stone")));
        }
        Path texture = fixture(Map.of(TEXTURE, new byte[ResourceLocation.Kind.TEXTURE.maxBytes()]));
        try (var archive = ClientResourceArchive.open(texture, "1.21.1")) {
            for (int index = 0; index < 7; index++)
                assertTrue(archive.read(ResourceLocation.Kind.TEXTURE, ResourceLocation.parse("block/stone")).isPresent());
            assertReason(LIMIT_EXCEEDED, () -> archive.read(ResourceLocation.Kind.TEXTURE, ResourceLocation.parse("block/stone")));
        }
    }

    @Test void detectsChangedSourcesAndMakesCloseIdempotent() throws Exception {
        Path file = fixture(Map.of());
        var archive = ClientResourceArchive.open(file, "1.21.1");
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 10_000));
            assertReason(SOURCE_CHANGED, () -> archive.read(ResourceLocation.Kind.MODEL, ResourceLocation.parse("block/stone")));
        } finally { archive.close(); archive.close(); }
        assertReason(CLOSED, () -> archive.read(ResourceLocation.Kind.MODEL, ResourceLocation.parse("block/stone")));
        Files.move(file, file.resolveSibling("closed.jar"));
    }

    @Test void rejectsInvalidUtf8AssetJson() throws Exception {
        try (var archive = ClientResourceArchive.open(fixture(Map.of(MODEL, new byte[]{(byte) 0xc3, 40})), "1.21.1")) {
            assertReason(INVALID_METADATA, () -> archive.readJson(ResourceLocation.Kind.MODEL, ResourceLocation.parse("block/stone")));
        }
    }

    @Test void readsConfiguredRealClientResourcesWithoutChangingTheJar() throws Exception {
        String configured = System.getenv("MCWORLD_CLIENT_RESOURCE_JAR");
        assumeTrue(configured != null && !configured.isBlank());
        Path file = Path.of(configured).toRealPath();
        String before = stamp(file);
        try (var archive = ClientResourceArchive.open(file, "1.21.1")) {
            assertEquals(3955, archive.metadata().worldVersion());
            assertEquals(34, archive.metadata().resourcePackVersion());
            for (String block : new String[]{"stone", "cobblestone", "dirt", "grass_block", "oak_planks", "oak_log", "bricks", "sand"}) {
                assertTrue(archive.readJson(ResourceLocation.Kind.BLOCKSTATE, ResourceLocation.parse(block)).isPresent(), block);
                assertTrue(archive.readJson(ResourceLocation.Kind.MODEL, ResourceLocation.parse("block/" + block)).isPresent(), block);
            }
            byte[] png = archive.read(ResourceLocation.Kind.TEXTURE, ResourceLocation.parse("block/stone")).orElseThrow();
            assertEquals(137, Byte.toUnsignedInt(png[0]));
            System.out.printf("Read-only client resources: version=%s worldVersion=%d pack=%d sha256=%s%n",
                    archive.metadata().versionId(), archive.metadata().worldVersion(),
                    archive.metadata().resourcePackVersion(), archive.fingerprint().sha256());
        } finally { assertEquals(before, stamp(file), "客户端资源发生变化"); }
    }

    private Path fixture(Map<String, byte[]> overrides) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("version.json", VERSION.getBytes(StandardCharsets.UTF_8));
        entries.put("assets/minecraft/blockstates/stone.json", "{}".getBytes(StandardCharsets.UTF_8));
        entries.put(MODEL, "{\"parent\":\"minecraft:block/cube_all\"}".getBytes(StandardCharsets.UTF_8));
        // Synthetic bytes only; PNG decoding belongs to R3.
        entries.put(TEXTURE, new byte[]{(byte) 137, 80, 78, 71});
        entries.putAll(overrides);
        return archive(entries);
    }

    private Path archive(Map<String, byte[]> entries) throws IOException {
        Path file = home.resolve("client-" + (++sequence) + ".jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        return file;
    }

    private static String stamp(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return Files.size(file) + ":" + Files.getLastModifiedTime(file) + ":" + HexFormat.of().formatHex(digest.digest());
    }

    private static void assertReason(ResourceAccessException.Reason reason, Checked action) {
        assertEquals(reason, assertThrows(ResourceAccessException.class, action::run).reason());
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
