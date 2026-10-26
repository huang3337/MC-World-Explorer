package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.Direction;

/** Small fixed palette for 1.21.1; grass layers are composited before meshing. */
public final class BlockTextureSet {
    public static final int GRASS_TINT = 0x91BD59;
    public record Surface(Direction direction, List<Float> positions, List<Float> uv) {
        public Surface { positions = List.copyOf(positions); uv = List.copyOf(uv); }
    }
    private record Pending(BlockModelResolver.Face face, TextureAtlas.Tile tile) { }
    private final Map<BlockState, Map<Direction, Surface>> models;
    private final Map<BlockState, String> failures;
    private final TextureAtlas atlas;
    private final ClientResourceArchive.Fingerprint fingerprint;

    private BlockTextureSet(Map<BlockState, Map<Direction, Surface>> models, Map<BlockState, String> failures,
                            TextureAtlas atlas, ClientResourceArchive.Fingerprint fingerprint) {
        this.models = Map.copyOf(models); this.failures = Map.copyOf(failures);
        this.atlas = atlas; this.fingerprint = fingerprint;
    }
    public TextureAtlas atlas() { return atlas; }
    public ClientResourceArchive.Fingerprint fingerprint() { return fingerprint; }
    public Map<Direction, Surface> surfaces(BlockState state) { return models.get(canonical(state)); }
    public String fallbackReason(BlockState state) {
        return failures.getOrDefault(canonical(state), "方块或状态不在完整方块纹理支持清单");
    }
    private static BlockState canonical(BlockState state) {
        String property = switch (state.name()) {
            case "minecraft:grass_block" -> "snowy";
            case "minecraft:oak_log" -> "axis";
            default -> null;
        };
        return new BlockState(state.name(), property == null || !state.properties().containsKey(property)
                ? Map.of() : Map.of(property, state.properties().get(property)));
    }

    public static List<BlockState> supportedStates() {
        List<BlockState> states = new ArrayList<>();
        for (String name : List.of("stone", "cobblestone", "dirt", "oak_planks", "bricks", "sand"))
            states.add(new BlockState("minecraft:" + name, Map.of()));
        for (String snowy : List.of("false", "true")) states.add(new BlockState("minecraft:grass_block", Map.of("snowy", snowy)));
        for (String axis : List.of("x", "y", "z")) states.add(new BlockState("minecraft:oak_log", Map.of("axis", axis)));
        return List.copyOf(states);
    }

    public static BlockTextureSet load(ClientResourceArchive archive, CancellationSignal cancel) throws IOException {
        var resolver = new BlockModelResolver(archive);
        var builder = new TextureAtlas.Builder();
        Map<ResourceLocation, BufferedImage> images = new HashMap<>();
        Map<BlockState, List<Pending>> pending = new LinkedHashMap<>();
        Map<BlockState, BlockModelResolver.Model> parsed = new HashMap<>();
        Map<BlockState, String> failures = new HashMap<>();
        for (BlockState state : supportedStates()) {
            check(cancel);
            var result = resolver.resolve(state);
            if (!result.resolved()) { failures.put(state, result.reason() + ": " + result.detail()); continue; }
            var model = result.model().orElseThrow();
            try {
                if (model.uvLock()) throw new IOException("本阶段固定样本暂不支持 uvlock=true");
                List<Pending> surfaces = new ArrayList<>();
                for (var face : model.faces()) {
                    if (face.layer() != 0) continue;
                    check(cancel);
                    var overlay = model.faces().stream().filter(f -> f.layer() == 1 && f.direction() == face.direction()).findFirst();
                    if (overlay.isPresent() && (!overlay.get().uv().equals(face.uv()) || overlay.get().rotation() != face.rotation()))
                        throw new IOException("覆盖层与底纹 UV 不一致，暂不支持合成");
                    String key = face.texture() + ":" + face.tintIndex() + ":" + overlay.map(Object::toString).orElse("");
                    BufferedImage base = image(archive, face.texture(), images);
                    BufferedImage top = overlay.isPresent() ? image(archive, overlay.get().texture(), images) : null;
                    surfaces.add(new Pending(face, builder.add(key, composite(base, face.tintIndex(), top,
                            overlay.map(BlockModelResolver.Face::tintIndex).orElse(-1)))));
                }
                pending.put(state, surfaces); parsed.put(state, model);
            } catch (ResourceAccessException e) { throw e; }
            catch (IOException e) { failures.put(state, e.getMessage()); }
        }
        TextureAtlas atlas = builder.build();
        Map<BlockState, Map<Direction, Surface>> models = new HashMap<>();
        for (var entry : pending.entrySet()) {
            var model = parsed.get(entry.getKey());
            Map<Direction, Surface> faces = new EnumMap<>(Direction.class);
            for (var p : entry.getValue()) {
                var surface = surface(p.face(), p.tile(), atlas, model.xRotation(), model.yRotation());
                faces.put(surface.direction(), surface);
            }
            models.put(entry.getKey(), Map.copyOf(faces));
        }
        check(cancel);
        return new BlockTextureSet(models, failures, atlas, archive.fingerprint());
    }

    private static BufferedImage image(ClientResourceArchive archive, ResourceLocation id,
                                        Map<ResourceLocation, BufferedImage> images) throws IOException {
        BufferedImage image = images.get(id);
        if (image == null) {
            if (images.size() >= TextureAtlas.MAX_TILES) throw new IOException("解码纹理数量超限");
            byte[] data = archive.read(ResourceLocation.Kind.TEXTURE, id)
                    .orElseThrow(() -> new IOException("缺少 PNG：" + id));
            image = TextureAtlas.decode(data); images.put(id, image);
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
            if ((bottom >>> 24) != 255) throw new IOException("完整方块底纹存在透明像素，暂不支持");
            if (overlay == null) { output.setRGB(x, y, bottom); continue; }
            int top = tinted(overlay.getRGB(x, y), overlayTint), a = top >>> 24;
            int rgb = 0xFF000000;
            for (int shift : new int[]{0, 8, 16}) rgb |= ((((top >>> shift) & 255) * a
                    + ((bottom >>> shift) & 255) * (255 - a) + 127) / 255) << shift;
            output.setRGB(x, y, rgb);
        }
        return output;
    }
    private static int tinted(int color, int tint) {
        if (tint < 0) return color;
        int result = color & 0xFF000000;
        for (int shift : new int[]{0, 8, 16}) result |= (((color >>> shift) & 255) * ((GRASS_TINT >>> shift) & 255) / 255) << shift;
        return result;
    }

    static Surface surface(BlockModelResolver.Face face, TextureAtlas.Tile tile, TextureAtlas atlas, int x, int y) {
        float[] positions = vertices(face.direction());
        float[] normal = switch (face.direction()) {
            case DOWN -> new float[]{0,-1,0}; case UP -> new float[]{0,1,0};
            case NORTH -> new float[]{0,0,-1}; case SOUTH -> new float[]{0,0,1};
            case WEST -> new float[]{-1,0,0}; case EAST -> new float[]{1,0,0};
        };
        rotate(normal, x, y);
        Direction direction = normal[1] < -.5 ? Direction.DOWN : normal[1] > .5 ? Direction.UP
                : normal[0] < -.5 ? Direction.WEST : normal[0] > .5 ? Direction.EAST
                : normal[2] < -.5 ? Direction.NORTH : Direction.SOUTH;
        List<Float> rotated = new ArrayList<>(), uv = new ArrayList<>();
        boolean horizontal = face.direction() == Direction.UP || face.direction() == Direction.DOWN;
        float[][] corners = horizontal ? new float[][]{{0,0},{0,1},{1,1},{1,0}}
                : new float[][]{{0,1},{1,1},{1,0},{0,0}};
        for (int i = 0; i < 4; i++) {
            float[] vertex = {positions[i*3]-.5f, positions[i*3+1]-.5f, positions[i*3+2]-.5f};
            rotate(vertex, x, y);
            for (float v : vertex) rotated.add(v + .5f);
            float u = corners[i][0], v = corners[i][1];
            for (int turn = 0; turn < face.rotation()/90; turn++) { float old = u; u = v; v = 1-old; }
            var f = face.uv();
            uv.add((float)((tile.x() + (f.u0() + u*(f.u1()-f.u0()))/16*tile.width()) / atlas.width()));
            uv.add((float)((tile.y() + (f.v0() + v*(f.v1()-f.v0()))/16*tile.height()) / atlas.height()));
        }
        return new Surface(direction, rotated, uv);
    }
    private static void rotate(float[] v, int x, int y) {
        // Minecraft blockstate rotations are clockwise about the positive axes, x then y.
        for (int i=0; i<x/90; i++) { float old=v[1]; v[1]=v[2]; v[2]=-old; }
        for (int i=0; i<y/90; i++) { float old=v[0]; v[0]=-v[2]; v[2]=old; }
    }
    private static float[] vertices(Direction direction) {
        return switch (direction) {
            case WEST -> new float[]{0,0,0, 0,0,1, 0,1,1, 0,1,0};
            case EAST -> new float[]{1,0,1, 1,0,0, 1,1,0, 1,1,1};
            case DOWN -> new float[]{0,0,1, 0,0,0, 1,0,0, 1,0,1};
            case UP -> new float[]{0,1,0, 0,1,1, 1,1,1, 1,1,0};
            case NORTH -> new float[]{1,0,0, 0,0,0, 0,1,0, 1,1,0};
            case SOUTH -> new float[]{0,0,1, 1,0,1, 1,1,1, 0,1,1};
        };
    }
    private static void check(CancellationSignal cancel) {
        if (cancel.isCancelled() || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
    }
}
