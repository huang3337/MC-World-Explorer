package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.*;
import com.mcworldexplorer.voxel.io.*;
import java.util.*;

/** Read-only snapshot access. Missing or corrupt neighbors remain distinguishable from air. */
public final class ModelBlockContext {
    public static final int MAX_RADIUS = 32;
    public record Position(long x, int y, long z) { }
    public record Offset(int x,int y,int z) {
        public Offset {if(x < -MAX_RADIUS || x > MAX_RADIUS || y < -MAX_RADIUS || y > MAX_RADIUS || z < -MAX_RADIUS || z > MAX_RADIUS)throw new IllegalArgumentException("邻域依赖超出32格");}
    }
    public record Sample(Optional<BlockState> state, Optional<BlockEntityData> entity,
                         ChunkLoadStatus status, String detail) {
        public Sample { Objects.requireNonNull(state); Objects.requireNonNull(entity); Objects.requireNonNull(status); Objects.requireNonNull(detail); }
        public boolean available() { return status == ChunkLoadStatus.SUCCESS; }
        /** Complete saved NBT for a model rule; truncation is a visible failure, never an empty success. */
        public Optional<net.kyori.adventure.nbt.CompoundBinaryTag> modelData(){return entity.map(value->value.modelData().requireComplete());}
    }
    private final VoxelAreaLoadResult area;
    private final Position position;
    private final CancellationSignal cancellation;
    private final Set<Offset> allowed;
    private final boolean exposePosition;
    private final View view;
    private final Map<Position,String> fixtureBiomes;
    /** Internal transport snapshot; only declared offsets cross the adapter process boundary. */
    record View(Position position,String dimension,Map<Offset,Sample> samples) {
        View { Objects.requireNonNull(dimension);samples=Map.copyOf(samples);if(samples.size()>257||!samples.containsKey(new Offset(0,0,0)))throw new IllegalArgumentException("插件上下文快照无效"); }
    }
    ModelBlockContext(View view){
        this(view,Map.of());
    }
    ModelBlockContext(View view,Map<Position,String> fixtureBiomes){
        this.view=Objects.requireNonNull(view);this.position=view.position();this.area=null;
        this.cancellation=CancellationSignal.NONE;this.allowed=view.samples().keySet();this.exposePosition=position!=null;
        this.fixtureBiomes=Map.copyOf(fixtureBiomes);if(fixtureBiomes.size()>512)throw new IllegalArgumentException("离线群系夹具超过512单元");
    }
    View snapshot(){
        if(view!=null)return view;
        var samples=new HashMap<Offset,Sample>();var offsets=new HashSet<>(allowed==null?Set.<Offset>of():allowed);offsets.add(new Offset(0,0,0));
        for(var offset:offsets)samples.put(offset,at(offset.x(),offset.y(),offset.z()));
        return new View(exposePosition?position:null,dimension(),samples);
    }

    public ModelBlockContext(VoxelAreaLoadResult area, Position position, CancellationSignal cancellation) {
        this(area,position,cancellation,null,true);
    }
    private ModelBlockContext(VoxelAreaLoadResult area,Position position,CancellationSignal cancellation,Set<Offset> allowed,boolean exposePosition) {
        this.area = Objects.requireNonNull(area);
        this.position = Objects.requireNonNull(position);
        this.cancellation = Objects.requireNonNull(cancellation);
        this.allowed=allowed;this.exposePosition=exposePosition;
        this.view=null;
        this.fixtureBiomes=Map.of();
    }
    public Position position() { if(!exposePosition)throw new IllegalStateException("规则未声明绝对位置依赖");return position; }
    ModelBlockContext restricted(ModelRule rule){
        if(view==null)return new ModelBlockContext(area,position,cancellation,rule.neighbors(),rule.positionDependent());
        var samples=new HashMap<Offset,Sample>();samples.put(new Offset(0,0,0),at(0,0,0));
        for(var offset:rule.neighbors())samples.put(offset,at(offset.x(),offset.y(),offset.z()));
        return new ModelBlockContext(new View(rule.positionDependent()?position():null,dimension(),samples),fixtureBiomes);
    }
    String signature(Set<Offset> dependencies,boolean includePosition) {
        return signature(dependencies,includePosition,false);
    }
    String signature(Set<Offset> dependencies,boolean includePosition,boolean includeBiomes) {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            var all=new HashSet<>(dependencies);all.add(new Offset(0,0,0));
            var ordered=new ArrayList<>(all);ordered.sort(Comparator.comparingInt(Offset::x).thenComparingInt(Offset::y).thenComparingInt(Offset::z));
            List<String> values=new ArrayList<>();values.add(dimension());if(includePosition||includeBiomes)values.add(position.toString());
            for(var offset:ordered){var sample=at(offset.x(),offset.y(),offset.z());values.add(offset.toString());values.add(sample.status().name());
                if(sample.state().isPresent()){var state=sample.state().orElseThrow();values.add(state.name());values.add(Integer.toString(state.properties().size()));for(var entry:new TreeMap<>(state.properties()).entrySet()){values.add(entry.getKey());values.add(entry.getValue());}}
                values.add(sample.entity().map(value->value.modelData().fingerprint()).orElse(""));}
            if(includeBiomes){
                // A maximum blend radius of seven needs at most 6x2x6 saved quart cells.
                // Capture all candidates, not only today's selected cell; source identity owns seed/settings.
                if(position.x()<Integer.MIN_VALUE+16L||position.x()>Integer.MAX_VALUE-16L||position.z()<Integer.MIN_VALUE+16L||position.z()>Integer.MAX_VALUE-16L||position.y()<Integer.MIN_VALUE+16||position.y()>Integer.MAX_VALUE-16)values.add("biome-coordinate-overflow");
                else for(int qy=Math.floorDiv(position.y()-2,4);qy<=Math.floorDiv(position.y()-2,4)+1;qy++)
                    for(int qz=(int)Math.floorDiv(position.z()-9,4);qz<=(int)Math.floorDiv(position.z()+5,4)+1;qz++)
                        for(int qx=(int)Math.floorDiv(position.x()-9,4);qx<=(int)Math.floorDiv(position.x()+5,4)+1;qx++){
                            var sample=biomeAtQuart(qx,qy,qz);values.add(qx+","+qy+","+qz);values.add(sample.status().name());values.add(sample.id().orElse(""));values.add(sample.detail());
                        }
            }
            for(String value:values){byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}
            return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public String dimension() { return view!=null?view.dimension():area.dimension().id(); }
    /** Host-only saved biome lookup. No raw area or undeclared inputs are sent to the plugin worker. */
    record BiomeSample(Optional<String> id,ChunkLoadStatus status,String detail) { }
    BiomeSample biomeAtQuart(int x,int y,int z){
        if(cancellation.isCancelled()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("biome context cancelled");
        if(area==null){var id=Optional.ofNullable(fixtureBiomes.get(new Position(x,y,z)));return new BiomeSample(id,id.isPresent()?ChunkLoadStatus.SUCCESS:ChunkLoadStatus.UNSUPPORTED,id.isPresent()?"":"上下文没有该保存群系单元");}
        var result=area.results().get(new ChunkCoordinate(Math.floorDiv(x,4),Math.floorDiv(z,4)));
        if(result==null)return new BiomeSample(Optional.empty(),ChunkLoadStatus.UNSUPPORTED,"群系邻域未包含在本次读取快照中");
        if(result.status()!=ChunkLoadStatus.SUCCESS)return new BiomeSample(Optional.empty(),result.status(),result.detail());
        var section=result.chunk().biomeSection(Math.floorDiv(y,4));
        if(section.isEmpty())return new BiomeSample(Optional.empty(),ChunkLoadStatus.UNSUPPORTED,"缺少保存群系区段；不推测维度边界或默认群系");
        if(section.orElseThrow().status()!=BiomeSection.Status.AVAILABLE)return new BiomeSample(Optional.empty(),ChunkLoadStatus.CORRUPT,"保存群系区段无效");
        return new BiomeSample(result.chunk().biomeAtQuart(Math.floorMod(x,4),y,Math.floorMod(z,4)),ChunkLoadStatus.SUCCESS,"");
    }
    public Sample at(int dx, int dy, int dz) {
        if (cancellation.isCancelled() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("model context cancelled");
        if(allowed!=null&&(dx!=0||dy!=0||dz!=0)&&!allowed.contains(new Offset(dx,dy,dz)))throw new IllegalStateException("规则读取未声明的邻域依赖");
        if (dx < -MAX_RADIUS || dx > MAX_RADIUS || dy < -MAX_RADIUS || dy > MAX_RADIUS || dz < -MAX_RADIUS || dz > MAX_RADIUS)
            throw new IllegalArgumentException("模型邻域查询超出32格半径");
        if(view!=null)return Objects.requireNonNull(view.samples().get(new Offset(dx,dy,dz)),"缺少已声明的插件邻域快照");
        final long x, z;
        final int y, cx, cz;
        try {
            x = Math.addExact(position.x(), dx); y = Math.addExact(position.y(), dy); z = Math.addExact(position.z(), dz);
            cx = Math.toIntExact(Math.floorDiv(x, 16)); cz = Math.toIntExact(Math.floorDiv(z, 16));
        } catch (ArithmeticException overflow) {
            return unavailable(ChunkLoadStatus.UNSUPPORTED, "邻域坐标超出范围");
        }
        var result = area.results().get(new ChunkCoordinate(cx, cz));
        if (result == null) return unavailable(ChunkLoadStatus.UNSUPPORTED, "邻域未包含在本次读取快照中");
        if (result.status() != ChunkLoadStatus.SUCCESS) return unavailable(result.status(), result.detail());
        try {
            return new Sample(Optional.of(result.chunk().blockState((int)Math.floorMod(x, 16), y, (int)Math.floorMod(z, 16))),
                    result.chunk().blockEntity(x, y, z), ChunkLoadStatus.SUCCESS, "");
        } catch (ChunkDecodeException corrupt) {
            return unavailable(ChunkLoadStatus.CORRUPT, corrupt.getMessage());
        }
    }
    private static Sample unavailable(ChunkLoadStatus status, String detail) {
        return new Sample(Optional.empty(), Optional.empty(), status, detail);
    }
}
