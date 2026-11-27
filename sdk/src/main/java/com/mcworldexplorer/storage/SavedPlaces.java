package com.mcworldexplorer.storage;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Personal places are bound to the world folder, independently of its name and map cache. */
public final class SavedPlaces {
    public record Position(String dimension, int x, int z) {
        public Position {
            if (dimension == null || dimension.isBlank() || dimension.indexOf('\t') >= 0) {
                throw new IllegalArgumentException("无效的维度标识");
            }
        }

        private String key() {
            return dimension + "\t" + x + "\t" + z;
        }
    }

    /** An undirected segment between two saved block centers in the same dimension. */
    public record Link(Position a, Position b) {
        public Link {
            if (!a.dimension().equals(b.dimension()) || a.equals(b))
                throw new IllegalArgumentException("连线需要同一维度的两个不同地点");
            if (a.x() > b.x() || a.x() == b.x() && a.z() > b.z()) {
                Position swap = a;
                a = b;
                b = swap;
            }
        }

        public String distances() {
            return "X 轴相距 " + Math.abs((long) a.x() - b.x()) + " 格\nZ 轴相距 "
                    + Math.abs((long) a.z() - b.z()) + " 格";
        }
    }

    /** A source label belongs to the measured line. A null label refers to a custom place. */
    public record Endpoint(Position position, String sourceLabel) {
        public Endpoint {
            Objects.requireNonNull(position);
            if (sourceLabel != null && sourceLabel.isBlank()) throw new IllegalArgumentException("端点名称为空");
        }
    }

    public record LineData(String color, String note, String sourceA, String sourceB) {
        public static final LineData DEFAULT = new LineData("#46C7F4", "");
        public LineData(String color, String note) { this(color, note, null, null); }
        public LineData {
            if (color == null || !color.matches("#[0-9a-fA-F]{6}") || note == null)
                throw new IllegalArgumentException("连线颜色或备注无效");
            if ((sourceA != null && sourceA.isBlank()) || (sourceB != null && sourceB.isBlank()))
                throw new IllegalArgumentException("连线端点无效");
        }

        public boolean usesCustomPlace(Link link, Position position) {
            return (sourceA == null && link.a().equals(position)) || (sourceB == null && link.b().equals(position));
        }

        public LineData withColor(String rgb) { return new LineData(rgb, note, sourceA, sourceB); }
        public LineData withNote(String text) { return new LineData(color, text, sourceA, sourceB); }

        public String title(Link link, Map<Position, String> places) {
            return (sourceA == null ? places.get(link.a()) : sourceA) + " — "
                    + (sourceB == null ? places.get(link.b()) : sourceB);
        }
    }

    public record Snapshot(Map<Position, String> places, Map<Link, LineData> lines) {
        public Snapshot {
            places = Map.copyOf(places);
            lines = Map.copyOf(lines);
        }
    }

    private static final Gson JSON = new Gson();
    private final Path world;
    private final Path file;

    public SavedPlaces(Path worldFolder) throws IOException {
        world = worldFolder.toRealPath();
        String id = WorldCachePaths.textHash(world.toUri().normalize().toString());
        file = PortablePaths.configDirectory().resolve("places").resolve(id + ".properties");
    }

    public Map<Position, String> read() throws IOException {
        return snapshot().places();
    }

    public Snapshot snapshot() throws IOException {
        Properties data = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            data.load(reader);
        } catch (NoSuchFileException absent) {
            return new Snapshot(Map.of(), Map.of());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("地点文件内容无效", invalid);
        }
        Object format = data.remove("format");
        if (!("1".equals(format) || "2".equals(format) || "3".equals(format)) || !world.equals(pathFrom(data.remove("world")))) {
            throw new IOException("地点文件版本或存档路径不匹配");
        }
        Map<Position, String> places = new HashMap<>();
        Map<Link, LineData> lines = new HashMap<>();
        try {
            for (String key : data.stringPropertyNames()) {
                String[] parts = key.split("\t", -1);
                if (!"1".equals(format) && parts.length == 6 && parts[0].equals("link")) {
                    var a = new Position(parts[1], Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                    var b = new Position(parts[1], Integer.parseInt(parts[4]), Integer.parseInt(parts[5]));
                    LineData line;
                    if ("2".equals(format)) {
                        String[] fields = data.getProperty(key).split("\n", 2);
                        if (fields.length != 2) throw new IllegalArgumentException();
                        line = new LineData(fields[0], fields[1]);
                    } else {
                        line = JSON.fromJson(data.getProperty(key), LineData.class);
                    }
                    Link keyLink = new Link(a, b);
                    if (line == null || (!keyLink.a().equals(a) && "3".equals(format)) || lines.put(keyLink, line) != null)
                        throw new IllegalArgumentException();
                    continue;
                }
                if (parts.length != 3) throw new IllegalArgumentException();
                Position pos = new Position(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                String name = data.getProperty(key);
                if (name.isBlank() || places.put(pos, name) != null) throw new IllegalArgumentException();
            }
            for (var entry : lines.entrySet()) {
                Link link = entry.getKey();
                LineData line = entry.getValue();
                if ((line.sourceA() == null && !places.containsKey(link.a()))
                        || (line.sourceB() == null && !places.containsKey(link.b()))) throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new IOException("地点文件中有无效记录", invalid);
        }
        return new Snapshot(places, lines);
    }

    private static Path pathFrom(Object stored) throws IOException {
        try {
            return stored == null ? null : Path.of(stored.toString());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("地点文件中的存档路径无效", invalid);
        }
    }

    /** A null expected name creates a place; a null new name removes the matching place. */
    public Map<Position, String> change(Position pos, String expected, String name) throws IOException {
        return changePlace(pos, expected, name, null).places();
    }

    public Snapshot changePlace(Position pos, String expected, String name, Map<Link, LineData> knownLines) throws IOException {
        Objects.requireNonNull(pos);
        if (name != null && name.isBlank()) throw new IllegalArgumentException("请输入地点名称");
        return update((places, lines) -> {
            if (!Objects.equals(expected, places.get(pos)) || (name == null && expected == null))
                throw new IOException("该地点已发生变化，请重新操作");
            if (name == null) {
                if (knownLines != null && lines.entrySet().stream().anyMatch(entry ->
                        entry.getValue().usesCustomPlace(entry.getKey(), pos)
                                && (!knownLines.containsKey(entry.getKey())
                                || !knownLines.get(entry.getKey()).usesCustomPlace(entry.getKey(), pos))))
                    throw new IOException("该地点新增了连线，请重新确认删除");
                places.remove(pos);
                lines.entrySet().removeIf(entry -> entry.getValue().usesCustomPlace(entry.getKey(), pos));
            } else places.put(pos, name.strip());
        });
    }

    /** Optionally create the destination and connect it in the same atomic write. */
    public Snapshot connect(Position from, Position to, String newName) throws IOException {
        return connect(new Endpoint(from, null), new Endpoint(to, null), newName);
    }

    public Snapshot connect(Endpoint from, Endpoint to, String newName) throws IOException {
        Link link = new Link(from.position(), to.position());
        if (newName != null && (to.sourceLabel() != null || newName.isBlank()))
            throw new IllegalArgumentException("只能在空白处创建命名终点");
        return update((places, lines) -> {
            if (from.sourceLabel() == null && !places.containsKey(from.position()))
                throw new IOException("起点已发生变化，请重新连线");
            if (to.sourceLabel() == null && places.containsKey(to.position()) != (newName == null))
                throw new IOException("终点已发生变化，请重新连线");
            if (newName != null) {
                if (lines.containsKey(link)) throw new IOException("连线已存在，请重新操作");
                places.put(to.position(), newName.strip());
            }
            boolean forward = link.a().equals(from.position());
            lines.putIfAbsent(link, new LineData(LineData.DEFAULT.color(), "",
                    forward ? from.sourceLabel() : to.sourceLabel(), forward ? to.sourceLabel() : from.sourceLabel()));
        });
    }

    public Snapshot editLink(Link link, LineData expected, LineData replacement) throws IOException {
        Objects.requireNonNull(expected);
        if (replacement != null && (!Objects.equals(expected.sourceA(), replacement.sourceA())
                || !Objects.equals(expected.sourceB(), replacement.sourceB())))
            throw new IllegalArgumentException("编辑连线时不能更改端点");
        return update((places, lines) -> {
            if (!expected.equals(lines.get(link))) throw new IOException("连线已发生变化，请重新操作");
            if (replacement == null) lines.remove(link);
            else lines.put(link, replacement);
        });
    }

    private interface Edit {
        void apply(Map<Position, String> places, Map<Link, LineData> lines) throws IOException;
    }

    private Snapshot update(Edit edit) throws IOException {
        Path directory = file.getParent();
        // Check the nearest existing ancestor before creating even an empty directory.
        Path ancestor = directory;
        while (Files.notExists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (ancestor.toRealPath().startsWith(world)) throw new IOException("地点配置目录位于存档中");
        Files.createDirectories(directory);
        Path lockPath = file.resolveSibling(file.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("地点正在由另一个窗口保存");
            Snapshot previous = snapshot();
            var places = new HashMap<>(previous.places());
            var lines = new HashMap<>(previous.lines());
            edit.apply(places, lines);
            Properties data = new Properties();
            data.setProperty("format", lines.isEmpty() ? "1" : "3");
            data.setProperty("world", world.toString());
            places.forEach((location, label) -> data.setProperty(location.key(), label));
            lines.forEach((link, style) -> data.setProperty("link\t" + link.a().key() + "\t"
                    + link.b().x() + "\t" + link.b().z(), JSON.toJson(style)));
            Path temporary = Files.createTempFile(directory, ".places-", ".tmp");
            try {
                try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    data.store(writer, null);
                }
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return new Snapshot(places, lines);
        } catch (OverlappingFileLockException busy) {
            throw new IOException("地点正在保存，请重试", busy);
        }
    }
}
