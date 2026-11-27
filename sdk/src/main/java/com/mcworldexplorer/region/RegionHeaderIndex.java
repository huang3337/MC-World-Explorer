package com.mcworldexplorer.region;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.Objects;

/** 只读位置索引。PRESENT 仅表示区块头位置有效，不代表正文解析成功。 */
public final class RegionHeaderIndex {
    public enum State { MISSING, PRESENT, INVALID }

    public record Signature(Path path, long size, FileTime modified, String fileKey, String headerHash) { }

    private final Signature signature;
    private final BitSet occupied;
    private final BitSet invalid;

    private RegionHeaderIndex(Signature signature, BitSet occupied, BitSet invalid) {
        this.signature = signature;
        this.occupied = occupied;
        this.invalid = invalid;
    }

    private record Header(Signature signature, ByteBuffer bytes) { }

    public static RegionHeaderIndex read(Path path) throws IOException {
        Header data = readHeader(path);
        ByteBuffer header = data.bytes();
        BitSet occupied = new BitSet(1024);
        BitSet invalid = new BitSet(1024);
        if (data.signature().size() == 0) return new RegionHeaderIndex(data.signature(), occupied, invalid);
        for (int slot = 0; slot < 1024; slot++) {
            int location = header.getInt(slot * Integer.BYTES);
            if (location == 0) continue;
            occupied.set(slot);
            int offset = location >>> 8;
            int count = location & 255;
            if (offset < 2 || count == 0
                    || (long) offset * RegionFileReader.SECTOR_BYTES > data.signature().size() - 5) {
                invalid.set(slot);
            }
        }
        return new RegionHeaderIndex(data.signature(), occupied, invalid);
    }

    /** Full header hash and both attribute checks, without rebuilding the slot index. */
    public static Signature readSignature(Path path) throws IOException {
        return readHeader(path).signature();
    }

    private static Header readHeader(Path path) throws IOException {
        Path real = path.toRealPath();
        BasicFileAttributes before = Files.readAttributes(real, BasicFileAttributes.class);
        if (!before.isRegularFile()) throw new IOException("Region is not a regular file: " + real);
        // A stable zero-length file contains no saved terrain. Do not invent a location table.
        ByteBuffer header = ByteBuffer.allocate(before.size() == 0 ? 0 : RegionFileReader.HEADER_BYTES);
        try (FileChannel channel = FileChannel.open(real, StandardOpenOption.READ)) {
            while (header.hasRemaining()) {
                if (channel.read(header) < 0) throw new EOFException("Truncated Region header: " + real);
            }
        }
        BasicFileAttributes after = Files.readAttributes(real, BasicFileAttributes.class);
        if (before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw new IOException("Region changed while indexing: " + real);
        }
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(header.array()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        return new Header(new Signature(real, after.size(), after.lastModifiedTime(),
                String.valueOf(after.fileKey()), hash), header);
    }

    public Signature signature() { return signature; }

    public State state(int localX, int localZ) {
        if (localX < 0 || localX >= 32 || localZ < 0 || localZ >= 32) {
            throw new IllegalArgumentException("Local chunk coordinate outside 0..31");
        }
        int slot = localX + localZ * 32;
        return invalid.get(slot) ? State.INVALID : occupied.get(slot) ? State.PRESENT : State.MISSING;
    }

    /** 返回副本，调用方不能修改索引。损坏位置也需由调用方报告，不能跳过。 */
    public int[] occupiedSlots() { return occupied.stream().toArray(); }

    public void verifyUnchanged() throws IOException {
        if (!signature.equals(readSignature(signature.path()))) {
            throw new IOException("Region changed during map sampling: " + signature.path());
        }
    }
}
