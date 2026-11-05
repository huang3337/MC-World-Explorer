package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.BlockState;
import java.io.IOException;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;
import static com.mcworldexplorer.voxel.resource.ClientBlockColors.Kind;

/** Common index-to-colour preparation, separate from geometry and from Mod callback execution. */
final class BlockTintResolver {
    record Colors(Map<Integer,Integer> values,String detail) {
        Colors {values=Map.copyOf(values);}
        String signature(){return new TreeMap<>(values).toString();}
    }
    private final BlockResourceSource source;
    private final SavedBiomeColors biomes=new SavedBiomeColors();
    private ClientBlockColors protocol;
    private String unavailable;
    private final Map<String,SavedBiomeColors.Inputs> biomeInputs=new HashMap<>();
    BlockTintResolver(BlockResourceSource source){this.source=source;}
    Optional<Kind> kind(BlockState state)throws IOException {
        if(!ResourceLocation.parse(state.name()).namespace().equals("minecraft"))return Optional.empty();
        if(protocol==null&&unavailable==null)try{protocol=new ClientBlockColors(source);}
        catch(ModelRule.Failure failure){unavailable=failure.getMessage();}
        catch(ResourceAccessException failure){
            if(failure.scope()!=ResourceAccessException.Scope.RESOURCE&&failure.reason()!=ResourceAccessException.Reason.INVALID_METADATA)throw failure;
            unavailable="颜色注册输入不可用："+failure.getMessage();
        }catch(IOException malformed){source.validate();unavailable="颜色注册结构不可解析："+malformed.getMessage();}
        return protocol==null?Optional.empty():protocol.kind(ResourceLocation.parse(state.name()));
    }
    static boolean indexed(Model model){return model.faces().stream().anyMatch(f->f.tintIndex()>=0&&f.tintIndex()<256);}
    static boolean contextual(Kind kind){return switch(kind){case GRASS,DOUBLE_GRASS,GRASS_NONZERO,FOLIAGE,WATER->true;default->false;};}
    Colors colors(BlockState state,Model model,ModelBlockContext block)throws IOException {
        if(!indexed(model))return new Colors(Map.of(),"");
        var selected=kind(state);
        if(selected.isEmpty())return new Colors(Map.of(),unavailable==null?"普通索引没有已证明的颜色处理器绑定":unavailable);
        Kind kind=selected.orElseThrow();var result=new TreeMap<Integer,Integer>();var sources=new ArrayList<Source>();var notes=new LinkedHashSet<String>();
        for(int index:model.faces().stream().mapToInt(Face::tintIndex).filter(i->i>=0&&i<256).distinct().sorted().toArray()) {
            try {
                int rgb;
                if(kind==Kind.GRASS_NONZERO&&index==0)rgb=0xFFFFFF;
                else if(contextual(kind)) {
                    if(block==null)throw new ModelRule.Failure(Reason.CONTEXT_REQUIRED,"群系颜色需要实际保存位置；未使用默认色代替");
                    var position=block.position();
                    if(kind==Kind.DOUBLE_GRASS){String half=state.properties().get("half");if(!Set.of("upper","lower").contains(half==null?"":half))throw invalid("双层植物缺少有效half属性");if(half.equals("upper"))position=new ModelBlockContext.Position(position.x(),Math.subtractExact(position.y(),1),position.z());}
                    var biomeKind=kind==Kind.FOLIAGE?ModelRule.BiomeColor.FOLIAGE:kind==Kind.WATER?ModelRule.BiomeColor.WATER:ModelRule.BiomeColor.GRASS;
                    var inputs=biomeInputs.get(block.dimension());if(inputs==null){inputs=SavedBiomeColors.inputs(source,block.dimension());if(biomeInputs.size()>=8)biomeInputs.clear();biomeInputs.put(block.dimension(),inputs);}
                    rgb=biomes.color(biomeKind,source,id->{sources.add(new Source(ResourceLocation.Kind.RAW_DATA,id));var json=source.readJson(ResourceLocation.Kind.RAW_DATA,id);return json.isEmpty()?Optional.empty():Optional.of(BlockModelResolver.parseResourceDefinition(json.orElseThrow()));},notes::add,block,position,sources,inputs);
                } else rgb=constantOrState(kind,state);
                result.put(index,0x1000000|(rgb&0xFFFFFF));
            }catch(ModelRule.Failure failure){notes.add("颜色未解析："+failure.reason()+"："+failure.getMessage());}
            catch(ResourceAccessException failure){if(failure.scope()!=ResourceAccessException.Scope.RESOURCE&&failure.reason()!=ResourceAccessException.Reason.INVALID_METADATA)throw failure;notes.add("颜色未解析："+failure.reason()+"："+failure.getMessage());}
            catch(ArithmeticException invalid){notes.add("颜色未解析：采样坐标超出范围");}
        }
        if(!result.isEmpty())notes.add(protocol.provenance()+"；处理器 "+kind);
        for(var input:new LinkedHashSet<>(sources))notes.add("颜色输入："+input.id()+" ← "+source.origin(input.kind(),input.id()).orElse("来源未提供"));
        return new Colors(result,String.join("；",notes));
    }
    static int constantOrState(Kind kind,BlockState state)throws ModelRule.Failure {
        return switch(kind) {
            case SPRUCE->0x619961;case BIRCH->0x80A755;case ATTACHED_STEM->0xE0C71C;case LILY_PAD->0x208030;
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
            parts.add(new Part(part.id(),part.xRotation(),part.yRotation(),part.uvLock(),faces,part.renderType(),part.offset()));
        }
        return new Result(original.reason(),original.detail(),Optional.of(new Model(model.id(),model.xRotation(),model.yRotation(),model.uvLock(),parts.stream().flatMap(p->p.faces().stream()).toList(),model.fixedFirstAlternative(),parts)),original.sources(),original.fingerprint(),original.dependencies(),original.loaders());
    }
}
