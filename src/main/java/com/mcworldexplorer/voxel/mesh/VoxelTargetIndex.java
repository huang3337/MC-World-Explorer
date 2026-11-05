package com.mcworldexplorer.voxel.mesh;

import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.data.ChunkCoordinate;
import com.mcworldexplorer.voxel.resource.BlockTextureSet;
import java.util.*;

/** Immutable, bounded selection data. No NBT, resource handles or plugin callbacks survive here. */
public final class VoxelTargetIndex {
    public static final int MAX_DISTANCE = 128, MAX_STEPS = 512;
    public static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final int UNKNOWN = 65535;
    private static final MeshBounds CELL = new MeshBounds(0, 0, 0, 1, 1, 1);
    private final Map<ChunkCoordinate, ChunkData> chunks;
    private final Set<String> modIds;
    private final long anchorX, anchorZ;

    public VoxelTargetIndex(VoxelSceneSnapshot scene) {
        var indexed = new HashMap<ChunkCoordinate, ChunkData>();
        for (var chunk : scene.chunks()) indexed.put(chunk.coordinate(), chunk.targets());
        chunks = Map.copyOf(indexed); modIds = scene.confirmedModIds();
        anchorX = scene.anchorWorldX(); anchorZ = scene.anchorWorldZ();
    }

    public record Entry(BlockState original, MeshBounds box, String status, String reason) { }
    public record Hit(long x, int y, long z, Entry entry, double distance) { }
    public record Query(Optional<Hit> hit, String notice) {
        static Query empty(String notice) { return new Query(Optional.empty(), notice); }
        public boolean sameTarget(Query previous) {
            if(previous==null || !notice.equals(previous.notice) || hit.isPresent()!=previous.hit.isPresent())return false;
            if(hit.isEmpty())return true;
            Hit a=hit.orElseThrow(),b=previous.hit.orElseThrow();
            return a.x()==b.x() && a.y()==b.y() && a.z()==b.z() && a.entry().equals(b.entry());
        }
    }

    /** Coordinates are scene-relative, direction need not be normalized. Boundary ties step together. */
    public Query query(double x, double y, double z, double dx, double dy, double dz) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(x + y + z + length) || length < 1e-12
                || Math.abs(x) > 1_000_000 || Math.abs(z) > 1_000_000 || Math.abs(y) > 1_000_000)
            return Query.empty("定位数据不可用");
        dx /= length; dy /= length; dz /= length;
        x += anchorX; z += anchorZ;
        long bx = (long)Math.floor(x), bz = (long)Math.floor(z); int by = (int)Math.floor(y);
        if(dx<0 && x==bx)bx--; if(dy<0 && y==by)by--; if(dz<0 && z==bz)bz--;
        int sx = Double.compare(dx, 0), sy = Double.compare(dy, 0), sz = Double.compare(dz, 0);
        double tx = crossing(x, bx, dx), ty = crossing(y, by, dy), tz = crossing(z, bz, dz);
        double stepX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double stepY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double stepZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        boolean entered = false; double travelled = 0;
        for (int step = 0; step < MAX_STEPS && travelled <= MAX_DISTANCE; step++) {
            long cx = Math.floorDiv(bx, 16), cz = Math.floorDiv(bz, 16);
            ChunkData chunk = cx < Integer.MIN_VALUE || cx > Integer.MAX_VALUE || cz < Integer.MIN_VALUE || cz > Integer.MAX_VALUE
                    ? null : chunks.get(new ChunkCoordinate((int)cx, (int)cz));
            if (chunk != null) {
                entered = true;
                int index = chunk.index((int)Math.floorMod(bx, 16), by, (int)Math.floorMod(bz, 16));
                if (index == UNKNOWN) return Query.empty("定位数据不可用（缺失或超过预算）");
                if (index != 0) {
                    Entry entry = chunk.entries.get(index - 1);
                    if (entry.box() != null) {
                        double distance = intersect(entry.box(), x - bx, y - by, z - bz, dx, dy, dz);
                        if (distance <= MAX_DISTANCE && distance >= Math.max(0, travelled - 1e-8))
                            return new Query(Optional.of(new Hit(bx, by, bz, entry, distance)), "");
                    }
                }
            } else if (entered) return Query.empty("射线进入未加载区块");
            double next = Math.min(tx, Math.min(ty, tz));
            if (tx <= next + 1e-10) { bx += sx; tx += stepX; }
            if (ty <= next + 1e-10) { by += sy; ty += stepY; }
            if (tz <= next + 1e-10) { bz += sz; tz += stepZ; }
            travelled = next;
        }
        return Query.empty(entered ? "无目标（查询距离 128 格）" : "查询范围内没有已加载区块");
    }

    public List<String> lines(Query query) {
        if (query.hit().isEmpty()) return List.of(query.notice());
        Hit hit = query.hit().orElseThrow(); Entry entry = hit.entry();
        String id = entry.original().name(); int colon = id.indexOf(':');
        String namespace = colon > 0 ? id.substring(0, colon) : "未知";
        String owner = namespace.equals("minecraft") ? "Minecraft（原版）"
                : modIds.contains(namespace) ? namespace + "（Mod ID）"
                : "未确认（命名空间 " + namespace + "）";
        var lines = new ArrayList<>(List.of(id, "方块归属：" + owner,
                "坐标：" + hit.x() + ", " + hit.y() + ", " + hit.z(), "显示状态：" + displayStatus(entry)));
        if (!entry.reason().isEmpty()) {
            String reason = entry.reason().replace("静态原型随机候选模型固定选择第一项", "引用模型的随机外观使用固定样式")
                    .replace("随机候选模型固定选择第一项", "随机外观使用固定样式");
            lines.add("说明：" + shortText(reason.replaceAll("(?i)[a-z]:[\\\\/][^；\\s]+", "[资源路径]"), 120));
        }
        return List.copyOf(lines);
    }

    // Presentation only: keep cached selection data and full diagnostics unchanged.
    private static String displayStatus(Entry entry) {
        String status = entry.status().split("／", 2)[0];
        return switch (status) {
            case "简化色块", "解析降级" -> "简化显示";
            case "无几何", "有意空模型" -> "无可见模型";
            case "模型已生成（含诊断）" -> {
                if (entry.reason().contains("颜色未解析")) yield "部分解析";
                boolean onlyRandom = !entry.reason().isBlank() && Arrays.stream(entry.reason().split("；"))
                        .allMatch(note -> note.equals("随机候选模型固定选择第一项") || note.equals("静态原型随机候选模型固定选择第一项"));
                yield onlyRandom ? "已解析（外观近似）" : "模型已显示（见说明）";
            }
            default -> status;
        };
    }

    private static double crossing(double origin, long cell, double direction) {
        if (direction == 0) return Double.POSITIVE_INFINITY;
        return ((direction > 0 ? cell + 1 : cell) - origin) / direction;
    }
    private static double intersect(MeshBounds box, double x, double y, double z, double dx, double dy, double dz) {
        double lo = 0, hi = MAX_DISTANCE;
        double[] origins = {x,y,z}, directions = {dx,dy,dz};
        double[] mins = {box.minX(),box.minY(),box.minZ()}, maxs = {box.maxX(),box.maxY(),box.maxZ()};
        for (int axis = 0; axis < 3; axis++) {
            if (directions[axis] == 0) {
                if (origins[axis] < mins[axis] || origins[axis] > maxs[axis]) return Double.POSITIVE_INFINITY;
            } else {
                double a = (mins[axis] - origins[axis]) / directions[axis], b = (maxs[axis] - origins[axis]) / directions[axis];
                lo = Math.max(lo, Math.min(a,b)); hi = Math.min(hi, Math.max(a,b));
                if (lo > hi) return Double.POSITIVE_INFINITY;
            }
        }
        return lo;
    }
    private static String shortText(String text, int length) {
        String clean = text.replaceAll("[\\p{Cntrl}]", " ");
        return clean.length() <= length ? clean : clean.substring(0, length - 1) + "…";
    }

    public static final class ChunkData {
        public static final ChunkData UNAVAILABLE = new ChunkData(Map.of(), List.of(), true, 0);
        private final Map<Integer, short[]> sections;
        private final List<Entry> entries;
        private final boolean incomplete;
        private final long bytes;
        private ChunkData(Map<Integer,short[]> sections, List<Entry> entries, boolean incomplete, long bytes) {
            this.sections = Map.copyOf(sections); this.entries = List.copyOf(entries);
            this.incomplete = incomplete; this.bytes = bytes;
        }
        public long estimatedBytes() { return bytes; }
        public int entryCount() { return entries.size(); }
        private int index(int x, int y, int z) {
            short[] cells = sections.get(Math.floorDiv(y,16));
            return cells == null ? (incomplete ? UNKNOWN : 0) : Short.toUnsignedInt(cells[Math.floorMod(y,16)*256+z*16+x]);
        }
    }
    static final class Budget {
        private final long limit; private long used;
        Budget() { this(MAX_BYTES); }
        Budget(long limit) { this.limit = limit; }
        boolean take(long bytes) { if (bytes > limit - used) return false; used += bytes; return true; }
    }
    static final class Builder {
        private record PreparedIdentity(BlockState original,BlockState rendered) { }
        private final Budget budget;
        private final Map<Integer,short[]> sections = new HashMap<>();
        private final List<Entry> entries = new ArrayList<>();
        private final Map<PreparedIdentity,Integer> rendered = new HashMap<>();
        private final Map<Entry,Integer> shared = new HashMap<>();
        private boolean incomplete, finished; private long bytes;
        Builder(Budget budget) { this.budget = budget; }
        void add(int x, int y, int z, BlockState raw, BlockState render, BlockTextureSet textures, List<BlockTextureSet.Surface> surfaces) {
            if (finished) throw new IllegalStateException("selection builder already transferred");
            int section = Math.floorDiv(y,16); short[] cells = sections.get(section);
            if (cells == null) {
                long cost = 4096L * Short.BYTES + 128;
                if (!budget.take(cost)) { incomplete = true; return; }
                cells = new short[4096]; sections.put(section,cells); bytes += cost;
            }
            // Preparation can overwrite internal properties; never replace the raw saved identity.
            var identity=new PreparedIdentity(raw,render);
            Integer entry = rendered.get(identity);
            if (entry == null) {
                Entry detail = describe(raw, render, textures, surfaces);
                entry = shared.get(detail);
                if (entry == null) {
                    long cost = 256L + 96L * raw.properties().size() + 2L * (raw.name().length() + raw.properties().toString().length() + detail.reason().length() + detail.status().length());
                    if (entries.size() >= UNKNOWN-1 || !budget.take(cost)) entry = UNKNOWN;
                    else { entries.add(detail); entry = entries.size(); shared.put(detail,entry); bytes += cost; }
                }
                // Rendering has its own 4096-state budget; keep this preparation cache bounded too.
                if (rendered.size() < 8192) rendered.put(identity,entry);
            }
            cells[Math.floorMod(y,16)*256+z*16+x] = (short)(int)entry;
        }
        ChunkData build() { finished = true; return new ChunkData(sections,entries,incomplete,bytes); }
    }

    // Existing notes include provenance as well as limitations. Provenance alone is not a failure;
    // full receipts stay in the existing scene diagnostics, not the compact HUD.
    private static boolean provenanceOnly(String note) {
        return List.of("保存群系颜色：","标准颜色绑定：","颜色输入：","注册模型资源提供器 ","注册方块渲染绑定 ",
                "缺少静态blockstate，使用已注册生成规则：","方块注册提供器 ").stream().anyMatch(note::startsWith);
    }
    private static String compactNote(String note) {
        int missing=note.indexOf("颜色未解析");
        if(missing>=0){int end=note.indexOf('；',missing);return note.substring(missing,end<0?note.length():end);}
        return provenanceOnly(note)?"":note;
    }
    private static Entry describe(BlockState raw, BlockState render, BlockTextureSet textures, List<BlockTextureSet.Surface> surfaces) {
        if (textures == null) return new Entry(raw,CELL,"简化色块／方块格定位","未启用纹理与模型资源");
        if (surfaces == null) return new Entry(raw,CELL,"解析降级／方块格定位",shortText(textures.fallbackReason(render).replaceFirst("（资源 .*", "").replaceFirst("^[A-Z_]+: ", ""),512));
        String reason = shortText(textures.notes(render).stream().map(VoxelTargetIndex::compactNote).filter(note -> !note.isBlank()).collect(java.util.stream.Collectors.joining("；")),512);
        if (surfaces.isEmpty()) return reason.isEmpty() ? new Entry(raw,null,"有意空模型","")
                : new Entry(raw,CELL,"无几何／方块格定位（含诊断）",reason);
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        for (var surface:surfaces) for(int i=0;i<surface.positions().size();i+=3) {
            minX=Math.min(minX,surface.positions().get(i)); maxX=Math.max(maxX,surface.positions().get(i));
            minY=Math.min(minY,surface.positions().get(i+1)); maxY=Math.max(maxY,surface.positions().get(i+1));
            minZ=Math.min(minZ,surface.positions().get(i+2)); maxZ=Math.max(maxZ,surface.positions().get(i+2));
        }
        if(maxX<0||maxY<0||maxZ<0||minX>1||minY>1||minZ>1)return new Entry(raw,null,"几何位于本格之外","仅在保存方块格内选取");
        // Selection is the model envelope clipped to its saved block cell, not an alpha/triangle pick.
        MeshBounds box = new MeshBounds(Math.clamp(minX,0,1),Math.clamp(minY,0,1),Math.clamp(minZ,0,1),
                Math.clamp(maxX,0,1),Math.clamp(maxY,0,1),Math.clamp(maxZ,0,1));
        return new Entry(raw,box,reason.isEmpty()?"已解析／模型包围盒定位":"模型已生成（含诊断）／包围盒定位",reason);
    }
}
