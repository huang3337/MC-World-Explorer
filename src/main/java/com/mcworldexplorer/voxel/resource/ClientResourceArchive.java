package com.mcworldexplorer.voxel.resource;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static com.mcworldexplorer.voxel.resource.ResourceAccessException.Reason.*;

/** Explicitly selected local client JAR. No extraction, downloading, or asset execution. */
public final class ClientResourceArchive implements AutoCloseable, BlockResourceSource {
    public static final long MAX_ARCHIVE_BYTES = 512L * 1024 * 1024;
    public static final int MAX_ENTRIES = 100_000;
    public static final int MAX_INDEX_NAME_CHARS = 8 * 1024 * 1024;
    public static final int MAX_JSON_NESTING = 32;
    public static final int MAX_METADATA_BYTES = 64 * 1024;
    public static final long MAX_SESSION_READ_BYTES = 64L * 1024 * 1024;

    public record Metadata(String versionId, int worldVersion, int resourcePackVersion) { }
    public record Fingerprint(Path path, long size, long modifiedMillis, String sha256) { }

    private final ZipFile zip;
    private final Fingerprint fingerprint;
    private final Object fileKey;
    private final Metadata metadata;
    private long readBytes;
    private boolean closed;

    private ClientResourceArchive(ZipFile zip, Fingerprint fingerprint, Object fileKey, Metadata metadata,
                                  long readBytes) {
        this.zip = zip;
        this.fingerprint = fingerprint;
        this.fileKey = fileKey;
        this.metadata = metadata;
        this.readBytes = readBytes;
    }

    public static ClientResourceArchive open(Path selectedFile, String expectedVersion) throws IOException {
        java.util.Objects.requireNonNull(selectedFile, "selectedFile");
        if (expectedVersion == null || expectedVersion.isBlank())
            throw new IllegalArgumentException("需要明确的客户端资源版本");
        ZipFile zip = null;
        try {
            Path path = selectedFile.toRealPath();
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) throw new ResourceAccessException(INVALID_ARCHIVE, "资源来源必须是客户端 JAR 文件");
            if (attributes.size() > MAX_ARCHIVE_BYTES) throw new ResourceAccessException(LIMIT_EXCEEDED, "客户端资源文件超过 512 MiB 上限");
            Fingerprint fingerprint = new Fingerprint(path, attributes.size(), attributes.lastModifiedTime().toMillis(), hash(path));
            zip = new ZipFile(path.toFile());
            validateIndex(zip);
            ZipEntry version = zip.getEntry("version.json");
            if (version == null || version.isDirectory()) throw new ResourceAccessException(INVALID_METADATA, "资源文件缺少 version.json");
            byte[] bytes = readEntry(zip, version, MAX_METADATA_BYTES);
            Metadata metadata = parseMetadata(bytes);
            for (String required : new String[]{"assets/minecraft/blockstates/stone.json",
                    "assets/minecraft/models/block/stone.json", "assets/minecraft/textures/block/stone.png"}) {
                ZipEntry asset = zip.getEntry(required);
                if (asset == null || asset.isDirectory() || asset.getSize() < 1)
                    throw new ResourceAccessException(INVALID_ARCHIVE, "所选文件缺少基础客户端资源");
            }
            if (!expectedVersion.equals(metadata.versionId())) throw new ResourceAccessException(VERSION_MISMATCH,
                    "资源版本不匹配：需要 " + expectedVersion + "，实际 " + metadata.versionId());
            ClientResourceArchive archive = new ClientResourceArchive(zip, fingerprint, attributes.fileKey(), metadata, bytes.length);
            archive.verifyUnchanged();
            zip = null; // Ownership transfers only after every validation succeeds.
            return archive;
        } catch (ResourceAccessException e) {
            closeOnFailure(zip, e);
            throw e;
        } catch (NoSuchFileException e) {
            closeOnFailure(zip, e);
            throw new ResourceAccessException(SOURCE_MISSING, "找不到所选客户端资源文件", e);
        } catch (ZipException e) {
            closeOnFailure(zip, e);
            throw new ResourceAccessException(INVALID_ARCHIVE, "客户端资源不是有效的 JAR/ZIP 文件", e);
        } catch (IOException e) {
            closeOnFailure(zip, e);
            throw new ResourceAccessException(IO_FAILURE, "无法读取客户端资源文件", e);
        } catch (RuntimeException e) {
            closeOnFailure(zip, e);
            throw e;
        }
    }

    private static void closeOnFailure(ZipFile zip, Throwable failure) {
        if (zip != null) try { zip.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
    }

    @Override public Optional<String> origin(ResourceLocation.Kind kind,ResourceLocation id){return Optional.of(fingerprint.path().toString());}
    public Metadata metadata() { return metadata; }
    public Fingerprint fingerprint() { return fingerprint; }

    public synchronized Optional<byte[]> read(ResourceLocation.Kind kind, ResourceLocation id) throws IOException {
        if (closed) throw new ResourceAccessException(CLOSED, "客户端资源读取器已关闭");
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(id, "id");
        verifyUnchanged();
        ZipEntry entry = zip.getEntry(id.entryName(kind));
        if (entry == null || entry.isDirectory()) return Optional.empty();
        if(entry.getSize()>kind.maxBytes())throw new ResourceAccessException(LIMIT_EXCEEDED,"单个资源条目超过读取上限："+id,ResourceAccessException.Scope.RESOURCE);
        long remaining = MAX_SESSION_READ_BYTES - readBytes;
        if (remaining < 1 || entry.getSize() > remaining)
            throw new ResourceAccessException(LIMIT_EXCEEDED, "本次资源读取超过 64 MiB 上限");
        byte[] bytes;
        try {
            bytes = readEntry(zip, entry, (int) Math.min(kind.maxBytes(), remaining));
        } catch (ResourceAccessException e) {
            throw e;
        } catch (IOException e) {
            throw new ResourceAccessException(IO_FAILURE, "无法读取资源 " + id, e);
        }
        readBytes += bytes.length;
        verifyUnchanged();
        return Optional.of(bytes);
    }

    public synchronized Optional<String> readJson(ResourceLocation.Kind kind, ResourceLocation id) throws IOException {
        if (kind == ResourceLocation.Kind.TEXTURE) throw new IllegalArgumentException("纹理不能作为 JSON 读取");
        Optional<byte[]> bytes = read(kind, id);
        return bytes.isEmpty() ? Optional.empty() : Optional.of(decodeUtf8(bytes.orElseThrow()));
    }

    private static void validateIndex(ZipFile zip) throws IOException {
        Set<String> names = new HashSet<>();
        var entries = zip.entries();
        long nameChars = 0;
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            nameChars += name.length();
            if (nameChars > MAX_INDEX_NAME_CHARS) throw new ResourceAccessException(LIMIT_EXCEEDED, "资源索引名称总量超过上限");
            if (names.size() >= MAX_ENTRIES) throw new ResourceAccessException(LIMIT_EXCEEDED, "资源条目超过 100000 个上限");
            if (!names.add(name)) throw new ResourceAccessException(INVALID_ARCHIVE, "资源文件包含重复条目");
            if (name.length() > 1024 || name.startsWith("/") || name.indexOf('\\') >= 0
                    || name.indexOf(':') >= 0 || name.chars().anyMatch(c -> c < 32))
                throw new ResourceAccessException(INVALID_ARCHIVE, "资源文件包含非法条目路径");
            String path = entry.isDirectory() ? name.substring(0, name.length() - 1) : name;
            for (String segment : path.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                    throw new ResourceAccessException(INVALID_ARCHIVE, "资源文件包含越界或别名路径");
            }
        }
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry entry, int maximum) throws IOException {
        if (entry.getSize() < 0 || entry.getSize() > maximum)
            throw new ResourceAccessException(LIMIT_EXCEEDED, "资源条目超过读取上限");
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new ResourceAccessException(LIMIT_EXCEEDED, "资源解压后超过读取上限");
            if (bytes.length != entry.getSize()) throw new ResourceAccessException(INVALID_ARCHIVE, "资源条目长度与目录不符");
            return bytes;
        }
    }

    private static Metadata parseMetadata(byte[] bytes) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(decodeUtf8(bytes)))) {
            reader.setStrictness(Strictness.STRICT);
            reader.setNestingLimit(MAX_JSON_NESTING);
            String id = null;
            int world = -1;
            int pack = -1;
            Set<String> fields = new HashSet<>();
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!fields.add(field)) throw new IOException("duplicate metadata field");
                switch (field) {
                    case "id" -> {
                        if (reader.peek() != JsonToken.STRING) throw new IOException("version id must be text");
                        id = reader.nextString();
                    }
                    case "world_version" -> {
                        if (reader.peek() != JsonToken.NUMBER) throw new IOException("world version must be numeric");
                        world = reader.nextInt();
                    }
                    case "pack_version" -> {
                        Set<String> packFields = new HashSet<>();
                        reader.beginObject();
                        while (reader.hasNext()) {
                            String name = reader.nextName();
                            if (!packFields.add(name)) throw new IOException("duplicate pack metadata field");
                            if (name.equals("resource")) {
                                if (reader.peek() != JsonToken.NUMBER) throw new IOException("pack version must be numeric");
                                pack = reader.nextInt();
                            } else reader.skipValue();
                        }
                        reader.endObject();
                    }
                    default -> reader.skipValue();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || id == null || id.isBlank()
                    || id.length() > 128 || world < 0 || pack < 1) throw new IOException("incomplete version metadata");
            return new Metadata(id, world, pack);
        } catch (IOException | IllegalStateException | NumberFormatException e) {
            throw new ResourceAccessException(INVALID_METADATA, "客户端版本元数据无效", e);
        }
    }

    private static String decodeUtf8(byte[] bytes) throws ResourceAccessException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new ResourceAccessException(INVALID_METADATA, "资源 JSON 不是有效的 UTF-8", e);
        }
    }

    @Override public synchronized void validate() throws IOException {
        if(closed)throw new ResourceAccessException(CLOSED,"客户端资源读取器已关闭");
        verifyUnchanged();
    }

    private void verifyUnchanged() throws IOException {
        BasicFileAttributes current;
        try {
            current = Files.readAttributes(fingerprint.path(), BasicFileAttributes.class);
        } catch (NoSuchFileException missing) {
            throw new ResourceAccessException(SOURCE_CHANGED, "客户端资源文件已移除，请重新选择", missing);
        } catch (IOException failure) {
            throw new ResourceAccessException(IO_FAILURE, "无法检查客户端资源来源状态", failure);
        }
        if (!current.isRegularFile() || current.size() != fingerprint.size()
                || current.lastModifiedTime().toMillis() != fingerprint.modifiedMillis()
                || (fileKey != null && !fileKey.equals(current.fileKey())))
            throw new ResourceAccessException(SOURCE_CHANGED, "客户端资源文件已变化，请重新打开");
    }

    private static String hash(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                long total = 0;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("取消客户端资源校验");
                    total += read;
                    if (total > MAX_ARCHIVE_BYTES) throw new ResourceAccessException(LIMIT_EXCEEDED, "客户端资源超过读取上限");
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            zip.close();
        }
    }
}
