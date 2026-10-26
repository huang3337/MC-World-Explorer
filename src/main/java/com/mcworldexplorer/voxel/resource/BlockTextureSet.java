package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import com.mcworldexplorer.voxel.io.ChunkDecodeException;
import com.mcworldexplorer.voxel.io.VoxelAreaLoadResult;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.Direction;

/** Prepares only requested states; bounded model geometry and texture resources are prepared on demand. */
public final class BlockTextureSet {
    public static final int MAX_STATES = 4096;
    public static final int GRASS_TINT = 0x91BD59;
    public record Surface(Direction direction, List<Float> positions, List<Float> uv,
                          List<Float> normal, Direction cullFace, boolean shade,
                          com.mcworldexplorer.voxel.mesh.RenderLayer layer, int element) {
        public Surface { positions=List.copyOf(positions);uv=List.copyOf(uv);normal=List.copyOf(normal); }
    }
    private record Pending(ModelGeometry.Quad quad, TextureAtlas.Tile tile, boolean shade,
                           com.mcworldexplorer.voxel.mesh.RenderLayer layer, int element) { }
    private record Painted(ModelGeometry.Quad quad, BufferedImage image, boolean shade, int element, String renderType) { }
    private final Map<BlockState, List<Surface>> models;
    private final Map<BlockState, String> failures;
    private final Map<BlockState,List<String>> notes;
    private final TextureAtlas atlas;
    private final boolean stateLimitReached;
    private final ClientResourceArchive.Fingerprint fingerprint;

    private BlockTextureSet(Map<BlockState, List<Surface>> models, Map<BlockState, String> failures,
                            TextureAtlas atlas, ClientResourceArchive.Fingerprint fingerprint, boolean stateLimitReached,Map<BlockState,List<String>> notes) {
        this.notes=Map.copyOf(notes);
        this.models = Map.copyOf(models); this.failures = Map.copyOf(failures);
        this.atlas = atlas; this.fingerprint = fingerprint; this.stateLimitReached = stateLimitReached;
    }
    public List<String> notes(BlockState state){return notes.getOrDefault(state,List.of());}
    public TextureAtlas atlas() { return atlas; }
    public ClientResourceArchive.Fingerprint fingerprint() { return fingerprint; }
    public static BlockState visualState(BlockState state,com.mcworldexplorer.voxel.data.BlockEntityData entity) {
        if(entity==null||entity.visual().keySet().stream().allMatch(key->key.equals("id")))return state;
        Map<String,String> properties=new TreeMap<>(state.properties());properties.put("__mcwe_entity",entity.fingerprint());
        return new BlockState(state.name(),properties);
    }

    public List<Surface> surfaces(BlockState state) { return models.get(state); }
    public String fallbackReason(BlockState state) {
        return failures.getOrDefault(state, stateLimitReached
                ? "本次不同方块状态超过 " + MAX_STATES + " 项准备上限" : "此状态未参与本次资源准备");
    }

    public static BlockTextureSet load(BlockResourceSource archive,
            VoxelAreaLoadResult area, CancellationSignal cancel)
            throws IOException {
        Set<BlockState> states = new LinkedHashSet<>();
        Map<String,com.mcworldexplorer.voxel.data.BlockEntityData> entities=new HashMap<>();
        collect:
        for (var coordinate : area.results().keySet()) {
            check(cancel);
            var result = area.result(coordinate);
            if (result.chunk() == null) continue;
            try {
                for (var section : result.chunk().sections()) {
                    for (int y = 0; y < 16; y++) {
                        check(cancel);
                        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            long wx=(long)coordinate.x()*16+x,wz=(long)coordinate.z()*16+z;
                            if(wx<(long)area.target().minX()*16-1||wx>(long)area.target().maxX()*16+16
                                    ||wz<(long)area.target().minZ()*16-1||wz>(long)area.target().maxZ()*16+16)continue;
                            int worldY=section.sectionY()*16+y;
                            var entity=result.chunk().blockEntity(wx,worldY,wz).orElse(null);
                            BlockState state=visualState(section.blockState(x,y,z),entity);
                            if(entity!=null&&state.properties().containsKey("__mcwe_entity"))entities.put(entity.fingerprint(),entity);
                            states.add(state);
                            // One extra actual state records overflow without retaining the whole area palette.
                            if (states.size() > MAX_STATES) break collect;
                        }
                    }
                }
            } catch (ChunkDecodeException corrupt) {
                // Meshing reports this chunk as CORRUPT; keep other target chunks usable.
            }
        }
        return load(new BuiltinBlockModels(archive,entities), states, cancel);
    }

    public static BlockTextureSet load(BlockResourceSource archive, Collection<BlockState> requested,
                                       CancellationSignal cancel) throws IOException {
        Objects.requireNonNull(archive); Objects.requireNonNull(requested); Objects.requireNonNull(cancel);
        if(!(archive instanceof BuiltinBlockModels))archive=new BuiltinBlockModels(archive,Map.of());
        Set<BlockState> states = new LinkedHashSet<>();
        boolean limited = false;
        for (BlockState state : requested) {
            check(cancel);
            Objects.requireNonNull(state);
            if (states.contains(state)) continue;
            if (states.size() >= MAX_STATES) { limited = true; break; }
            states.add(state);
        }
        var resolver = new BlockModelResolver(archive);
        var builder = new TextureAtlas.Builder();
        Map<ResourceLocation, BufferedImage> images = new HashMap<>();
        Map<BlockState, List<Pending>> pending = new LinkedHashMap<>();
        Map<BlockState, String> failures = new HashMap<>();
        Map<BlockState,List<String>> notes=new HashMap<>();
        int faceBudget = 32768;
        for (BlockState state : states) {
            check(cancel);
            var result = resolver.resolve(state);
            if (!result.resolved()) { failures.put(state, result.reason() + ": " + result.detail()); continue; }
            var model = result.model().orElseThrow();
            var stateNotes=new ArrayList<>(((BuiltinBlockModels)archive).notes(state));
            if(model.fixedFirstAlternative())stateNotes.add("随机候选模型固定选择第一项");
            if(!stateNotes.isEmpty())notes.put(state,List.copyOf(stateNotes));
            try {
                if(model.faces().size()>faceBudget) throw new IOException("本次模型面准备超过 32768 项预算");
                Map<String,Painted> painted = new LinkedHashMap<>();
                long workingBytes=0;
                int elementBase=0;
                for(var part:model.parts()) {
                    for(var face:part.faces()) {
                        check(cancel);
                        var quad=ModelGeometry.bake(face,part);
                        if(quad.normal().stream().allMatch(v->Math.abs(v)<1e-6))continue;
                        BufferedImage sourceImage=image(archive,face.texture(),images);
                        long bytes=(long)sourceImage.getWidth()*sourceImage.getHeight()*4;
                        if(workingBytes+bytes>32L*1024*1024)throw new IOException("单状态面纹理工作内存超过 32 MiB 预算");
                        BufferedImage base=composite(sourceImage,face.tintIndex(),null,-1);
                        String key=quad.positions()+"|"+quad.normal()+"|"+quad.uv()+"|"+face.shade()+"|"+part.renderType();
                        var previous=painted.get(key);
                        if(previous!=null)base=composite(previous.image(),-1,base,-1);
                        else workingBytes+=bytes;
                        painted.put(key,new Painted(quad,base,face.shade(),previous==null?elementBase+face.layer():previous.element(),part.renderType()));
                    }
                    elementBase+=128;
                }
                List<Pending> surfaces=new ArrayList<>();
                for(var paint:painted.values()) {
                    var tile=builder.add(imageKey(paint.image()),paint.image());
                    surfaces.add(new Pending(paint.quad(),tile,paint.shade(),material(paint.image(),paint.renderType()),paint.element()));
                }
                faceBudget-=surfaces.size();
                pending.put(state,surfaces);
            } catch (ResourceAccessException e) { throw e; }
            catch (IOException e) { failures.put(state,e.getMessage()); }
        }
        TextureAtlas atlas=builder.build();
        Map<BlockState,List<Surface>> models=new HashMap<>();
        for(var entry:pending.entrySet()) {
            List<Surface> faces=new ArrayList<>();
            for(var p:entry.getValue()) {
                List<Float> uv=new ArrayList<>(8);
                for(int i=0;i<4;i++) {
                    uv.add((p.tile().x()+p.quad().uv().get(i*2)*p.tile().width())/atlas.width());
                    uv.add((p.tile().y()+p.quad().uv().get(i*2+1)*p.tile().height())/atlas.height());
                }
                faces.add(new Surface(p.quad().direction(),p.quad().positions(),uv,p.quad().normal(),
                        p.quad().cull(),p.shade(),p.layer(),p.element()));
            }
            models.put(entry.getKey(),List.copyOf(faces));
        }
        check(cancel);
        archive.validate();
        return new BlockTextureSet(models, failures, atlas, archive.fingerprint(), limited,notes);
    }

    private static BufferedImage image(BlockResourceSource archive, ResourceLocation id,
                                        Map<ResourceLocation, BufferedImage> images) throws IOException {
        BufferedImage image = images.get(id);
        if (image == null) {
            if (images.size() >= TextureAtlas.MAX_TILES) throw new IOException("解码纹理数量超限");
            byte[] data = archive.read(ResourceLocation.Kind.TEXTURE, id)
                    .orElseThrow(() -> new IOException("缺少 PNG：" + id));
            image = TextureAtlas.decode(data,!id.path().startsWith("entity/"));
            long decoded=images.values().stream().mapToLong(value->(long)value.getWidth()*value.getHeight()*4).sum();
            if(decoded+(long)image.getWidth()*image.getHeight()*4>64L*1024*1024)throw new IOException("解码纹理内存超过 64 MiB 预算");
            images.put(id, image);
        }
        return image;
    }

    static BufferedImage composite(BufferedImage base, int tint, BufferedImage overlay, int overlayTint) throws IOException {
        int w = base.getWidth(), h = base.getHeight();
        if (overlay != null && (overlay.getWidth() != w || overlay.getHeight() != h))
            throw new IOException("覆盖层与底纹尺寸不一致");
        BufferedImage output = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int bottom = tinted(base.getRGB(x, y), tint);
            if (overlay == null) { output.setRGB(x, y, bottom); continue; }
            int top=tinted(overlay.getRGB(x,y),overlayTint),a=top>>>24,bottomAlpha=bottom>>>24;
            int alpha=a+(bottomAlpha*(255-a)+127)/255;
            int rgb=alpha<<24;
            for(int shift:new int[]{0,8,16}) {
                int premultiplied=((top>>>shift)&255)*a+(((bottom>>>shift)&255)*bottomAlpha*(255-a)+127)/255;
                rgb|=(alpha==0?0:(premultiplied+alpha/2)/alpha)<<shift;
            }
            output.setRGB(x, y, rgb);
        }
        return output;
    }
    private static int tinted(int color, int tint) {
        if (tint < 0) return color;
        int colorTint=tint>=0x1000000?tint&0xFFFFFF:GRASS_TINT;
        int result = color & 0xFF000000;
        for (int shift : new int[]{0, 8, 16}) result |= (((color >>> shift) & 255) * ((colorTint >>> shift) & 255) / 255) << shift;
        return result;
    }

    private static String imageKey(BufferedImage image) {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            for(int pixel:image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth()))
                for(int shift:new int[]{0,8,16,24})digest.update((byte)(pixel>>>shift));
            return image.getWidth()+"x"+image.getHeight()+":"+java.util.HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static com.mcworldexplorer.voxel.mesh.RenderLayer material(BufferedImage image,String renderType) {
        if("mcwe:fluid".equals(renderType))return com.mcworldexplorer.voxel.mesh.RenderLayer.FLUID;
        if("minecraft:translucent".equals(renderType))return com.mcworldexplorer.voxel.mesh.RenderLayer.TRANSLUCENT;
        if(renderType!=null&&!renderType.equals("minecraft:solid"))return com.mcworldexplorer.voxel.mesh.RenderLayer.CUTOUT;
        boolean cutout=false;
        for(int pixel:image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth())) {
            int a=pixel>>>24;
            if(a>0&&a<255)return com.mcworldexplorer.voxel.mesh.RenderLayer.TRANSLUCENT;
            if(a==0)cutout=true;
        }
        return cutout?com.mcworldexplorer.voxel.mesh.RenderLayer.CUTOUT:com.mcworldexplorer.voxel.mesh.RenderLayer.OPAQUE;
    }
    private static void check(CancellationSignal cancel) {
        if (cancel.isCancelled() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
    }
}
