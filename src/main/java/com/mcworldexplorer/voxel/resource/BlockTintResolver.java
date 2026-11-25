package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;
import java.io.IOException;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;
import static com.mcworldexplorer.voxel.resource.ClientBlockColors.Kind;

/** Common index-to-colour preparation, separate from geometry and from Mod callback execution. */
final class BlockTintResolver {
    static final int FLUID_WATER = 256; // Reserved for host-generated water surfaces.
    record Colors(Map<Integer,Integer> values,String detail) {
        Colors {values=Map.copyOf(values);}
        String signature(){return new TreeMap<>(values).toString();}
    }
    private final BlockResourceSource source;
    private final SavedBiomeColors biomes=new SavedBiomeColors();
    private ClientBlockColors protocol;
    private String unavailable;
    private final Map<String,ResourceLocation> blockIds = new HashMap<>();
    private final Map<Model,int[]> indicesByModel = new IdentityHashMap<>();
    private final Map<String,SavedBiomeColors.Inputs> biomeInputs=new HashMap<>();
    private final ModelRuleRegistry rules;
    private record Cell(int x,int y,int z){}
    private final Map<Cell,Optional<String>> uniformWaterCells=new HashMap<>();
    private final Map<String,Colors> waterByBiome=new HashMap<>();
    private ModelBlockContext waterSnapshot;
    private SavedBiomeColors.Inputs waterInputs;

    // Every block in a 4³ cell has the same water tint if all possible jitter/blend candidates agree.
    // Missing or mixed quart cells take the usual position-based path.
    private String uniformWaterBiome(ModelBlockContext block,SavedBiomeColors.Inputs inputs) {
        var p=block.position();
        if(!inputs.height().contains(p.y()) || p.x()<Integer.MIN_VALUE+16L || p.x()>Integer.MAX_VALUE-16L
                || p.z()<Integer.MIN_VALUE+16L || p.z()>Integer.MAX_VALUE-16L)return null;
        if(!block.sharesBiomeSnapshot(waterSnapshot)||!inputs.equals(waterInputs)){
            uniformWaterCells.clear();waterByBiome.clear();waterSnapshot=block;waterInputs=inputs;
        }
        int x=(int)p.x() & ~3, y=p.y() & ~3, z=(int)p.z() & ~3, radius=inputs.radius();
        if(uniformWaterCells.size()>=512)uniformWaterCells.clear();
        return uniformWaterCells.computeIfAbsent(new Cell(x,y,z),ignored->{
            var samples=biomes.samples(block,inputs);
            String id=null;
            for(int qy=(y-2)>>2;qy<=((y+1)>>2)+1;qy++)
                for(int qz=(z-2-radius)>>2;qz<=((z+1+radius)>>2)+1;qz++)
                    for(int qx=(x-2-radius)>>2;qx<=((x+1+radius)>>2)+1;qx++){
                        var sample=samples.quart(qx,inputs.height().clampQuart(qy),qz).id();
                        if(sample.isEmpty() || id!=null&&!id.equals(sample.get()))return Optional.empty();
                        id=sample.get();
                    }
            return Optional.ofNullable(id);
        }).orElse(null);
    }
    BlockTintResolver(BlockResourceSource source){this(source,ModelRuleRegistry.defaults());}
    BlockTintResolver(BlockResourceSource source,ModelRuleRegistry rules){this.source=source;this.rules=rules;}
    Optional<Kind> kind(BlockState state,int index)throws IOException {
        if(index==FLUID_WATER)return Optional.of(Kind.WATER);
        if(!state.name().startsWith("minecraft:"))return Optional.empty();
        if(protocol==null&&unavailable==null)try{protocol=new ClientBlockColors(source);biomes.useSampler(source,protocol);}
        catch(ModelRule.Failure failure){unavailable=failure.getMessage();}
        catch(ResourceAccessException failure){
            if(failure.scope()!=ResourceAccessException.Scope.RESOURCE&&failure.reason()!=ResourceAccessException.Reason.INVALID_METADATA)throw failure;
            unavailable="颜色注册输入不可用："+failure.getMessage();
        }catch(IOException malformed){source.validate();unavailable="颜色注册结构不可解析："+malformed.getMessage();}
        if (protocol == null) return Optional.empty();
        if (blockIds.size() >= BlockTextureSet.MAX_STATES) blockIds.clear();
        return protocol.layer(blockIds.computeIfAbsent(state.name(),ResourceLocation::parse),index);
    }
    boolean needsPosition(BlockState state,Model model) throws IOException {
        for (int index : indices(model)) {
            var extension=rules.tint(state.name(),index);
            if(extension!=null){if(extension.biome()!=null)return true;continue;}
            var layer = kind(state,index);
            if (layer.isPresent() && contextual(layer.get())) return true;
        }
        return false;
    }
    private int[] indices(Model model) {
        if (indicesByModel.size() >= BlockTextureSet.MAX_STATES) indicesByModel.clear();
        return indicesByModel.computeIfAbsent(model,m -> m.faces().stream().mapToInt(Face::tintIndex)
                .filter(i->i>=0&&i<=FLUID_WATER).distinct().sorted().toArray());
    }
    static boolean indexed(Model model){return model.faces().stream().anyMatch(f->f.tintIndex()>=0&&f.tintIndex()<=FLUID_WATER);}
    static boolean contextual(Kind kind){return kind.biomeColor != null;}
    Colors colors(BlockState state,Model model,ModelBlockContext block)throws IOException {
        if(indices(model).length==0)return new Colors(Map.of(),"");
        String waterBiome=null;
        var knownInputs=block==null?null:biomeInputs.get(block.dimension());
        if(knownInputs!=null && indices(model).length==1 && indices(model)[0]==FLUID_WATER) {
            waterBiome=uniformWaterBiome(block,knownInputs);
            var existing=waterByBiome.get(waterBiome);
            if(existing!=null)return existing;
        }
        var result=new TreeMap<Integer,Integer>();var sources=new ArrayList<Source>();var notes=new LinkedHashSet<String>();
        boolean usedRegistry=false;
        for(int index:indices(model)) {
            var extension=rules.tint(state.name(),index);
            if(extension!=null && extension.rgb()!=null){
                result.put(index,0x1000000|extension.rgb());notes.add("插件颜色层："+state.name()+"["+index+"]");continue;
            }
            var selected=extension==null?kind(state,index):Optional.of(switch(extension.biome()){
                case WATER -> Kind.WATER; case FOLIAGE -> Kind.FOLIAGE; case DRY_FOLIAGE -> Kind.DRY_FOLIAGE; case GRASS -> Kind.GRASS;
            });
            if(extension!=null)notes.add("插件颜色层："+state.name()+"["+index+"]");
            if (selected.isEmpty()) {
                notes.add(unavailable == null ? "普通索引 "+index+" 没有已核对的颜色绑定" : unavailable);
                continue;
            }
            Kind kind=selected.orElseThrow();
            try {
                int rgb;
                if(contextual(kind)) {
                    if(block==null)throw new ModelRule.Failure(Reason.CONTEXT_REQUIRED,"群系颜色需要实际保存位置；未使用默认色代替");
                    biomes.verifySampler(source);
                    var position=block.position();
                    if(kind==Kind.DOUBLE_GRASS){
                        String half=state.properties().get("half");
                        if(half==null && state.properties().containsKey("data")){
                            try{half=(Integer.parseInt(state.properties().get("data"))&8)!=0?"upper":"lower";}catch(NumberFormatException ignored){}
                        }
                        if(!Set.of("upper","lower").contains(half==null?"":half))throw invalid("双层植物缺少有效half属性");
                        if(half.equals("upper"))position=new ModelBlockContext.Position(position.x(),Math.subtractExact(position.y(),1),position.z());
                    }
                    var inputs=biomeInputs.get(block.dimension());if(inputs==null){inputs=SavedBiomeColors.inputs(source,block.dimension());if(biomeInputs.size()>=8)biomeInputs.clear();biomeInputs.put(block.dimension(),inputs);}
                    rgb=biomes.color(kind.biomeColor,source,id->{sources.add(new Source(ResourceLocation.Kind.RAW_DATA,id));var json=source.readJson(ResourceLocation.Kind.RAW_DATA,id);return json.isEmpty()?Optional.empty():Optional.of(BlockModelResolver.parseResourceDefinition(json.orElseThrow()));},notes::add,block,position,sources,inputs);
                } else rgb=constantOrState(kind,state);
                result.put(index,0x1000000|(rgb&0xFFFFFF));
                usedRegistry|=extension==null&&index!=FLUID_WATER;
            }catch(ModelRule.Failure failure){notes.add("颜色未解析："+failure.reason()+"："+failure.getMessage());}
            catch(ResourceAccessException failure){if(failure.scope()!=ResourceAccessException.Scope.RESOURCE&&failure.reason()!=ResourceAccessException.Reason.INVALID_METADATA)throw failure;notes.add("颜色未解析："+failure.reason()+"："+failure.getMessage());}
            catch(ArithmeticException invalid){notes.add("颜色未解析：采样坐标超出范围");}
        }
        if(usedRegistry && protocol!=null)notes.add(protocol.provenance());
        for(var input:new LinkedHashSet<>(sources))notes.add("颜色输入："+input.id()+" ← "+source.origin(input.kind(),input.id()).orElse("来源未提供"));
        var colors=new Colors(result,String.join("；",notes));
        if(waterBiome!=null && !result.isEmpty()){
            if(waterByBiome.size()>=128)waterByBiome.clear();
            waterByBiome.put(waterBiome,colors);
        }
        return colors;
    }
    static int constantOrState(Kind kind,BlockState state)throws ModelRule.Failure {
        return switch(kind) {
            case WHITE->0xffffff;case SPRUCE->0x619961;case BIRCH->0x80A755;case ATTACHED_STEM->0xE0C71C;case LILY_PAD->0x208030;
            case STEM->{int age=property(state,"age",7);yield (age*32)<<16|(255-age*8)<<8|age*4;}
            case REDSTONE->{float power=property(state,"power",15)/15f;float r=power*.6f+(power>0?.4f:.3f),g=Math.max(0,power*power*.7f-.5f),b=Math.max(0,power*power*.6f-.7f);yield ((int)(r*255))<<16|((int)(g*255))<<8|(int)(b*255);}
            default->throw invalid("处理器需要群系输入");
        };
    }
    private static int property(BlockState state,String name,int max)throws ModelRule.Failure {
        try{String raw=state.properties().get(name);if(raw==null||!raw.matches("0|[1-9][0-9]?"))throw new NumberFormatException();int value=Integer.parseInt(raw);if(value>max)throw new NumberFormatException();return value;}
        catch(NumberFormatException invalid){throw invalid("颜色状态属性无效："+name);}
    }
    private static ModelRule.Failure invalid(String text){return new ModelRule.Failure(Reason.INVALID_MODEL,text);}
    static Result apply(Result original,Colors colors) {
        if(colors.values().isEmpty())return original;
        Model model=original.model().orElseThrow();var parts=new ArrayList<Part>();
        for(var part:model.parts()) {
            var faces=part.faces().stream().map(f->new Face(f.direction(),f.texture(),f.uv(),f.rotation(),colors.values().getOrDefault(f.tintIndex(),f.tintIndex()),f.cullFace(),f.layer(),f.from(),f.to(),f.elementRotation(),f.shade(),f.vertices(),f.vertexUv(),f.opacity(),f.material())).toList();
            parts.add(new Part(part.id(),part.xRotation(),part.yRotation(),part.zRotation(),part.uvLock(),faces,part.renderType(),part.offset()));
        }
        return new Result(original.reason(),original.detail(),Optional.of(new Model(model.id(),model.xRotation(),model.yRotation(),model.uvLock(),parts.stream().flatMap(p->p.faces().stream()).toList(),model.fixedFirstAlternative(),parts)),original.sources(),original.fingerprint(),original.dependencies(),original.loaders());
    }
}
