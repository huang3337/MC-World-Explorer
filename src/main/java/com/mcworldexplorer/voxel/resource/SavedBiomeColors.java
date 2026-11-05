package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;
import static com.mcworldexplorer.voxel.resource.ModelRule.BiomeColor;

/** Shared biome resolvers. Definitions cache independently of position-dependent grass modifiers. */
final class SavedBiomeColors {
    private record Key(ResourceLocation biome,BiomeColor kind) { }
    private record Color(int rgb,String modifier,List<Source> sources) {
        int at(int x,int z){return switch(modifier){case "dark_forest"->((rgb&0xfefefe)+0x28340a)>>1;case "swamp"->BiomeGrassNoise.color(x,z);default->rgb;};}
    }
    private final Map<Key,Color> colors=new LinkedHashMap<>(16,.75f,true);
    private final Map<BiomeColor,int[]> colormaps=new EnumMap<>(BiomeColor.class);
    int foliage(ModelRule.Context context,ModelBlockContext block,List<Source> sources)throws IOException,ModelRule.Failure {
        return color(BiomeColor.FOLIAGE,context,block,sources);
    }
    int color(BiomeColor kind,ModelRule.Context context,ModelBlockContext block,List<Source> sources)throws IOException,ModelRule.Failure {
        var source=context.resources();source.validate();
        try{
            return color(kind,source,context::dataJson,context::note,block,block.position(),sources,inputs(source,block.dimension()));
        }finally{source.validate();}
    }
    @FunctionalInterface interface DataReader {Optional<JsonObject> read(ResourceLocation id)throws IOException,ModelRule.Failure;}
    record Inputs(String version,long seed,int radius,BlockResourceSource.DimensionHeight height) { }
    static Inputs inputs(BlockResourceSource source,String dimensionId)throws IOException,ModelRule.Failure {
        String version=source.baseClientVersion().orElse("");
        if(!Set.of("1.20.1","1.21.1").contains(version))throw failure(Reason.UNSUPPORTED_MODEL,"保存群系颜色尚未核对此客户端："+version);
        long seed=source.worldSeed().orElseThrow(()->failure(Reason.CONTEXT_REQUIRED,"缺少保存世界种子，不能用0替代"));
        int radius=source.biomeBlendRadius().orElseThrow(()->failure(Reason.CONTEXT_REQUIRED,"缺少当前实例群系混合设置"));
        if(radius<0||radius>7)throw invalid("群系混合半径须为0..7");
        ResourceLocation dimension;try{dimension=ResourceLocation.parse(dimensionId);}catch(IllegalArgumentException invalid){throw invalid("保存维度ID无效");}
        var height=source.dimensionHeight(dimension).orElseThrow(()->failure(Reason.CONTEXT_REQUIRED,"缺少保存维度类型/有效高度，不能从现有区段推测"));
        return new Inputs(version,seed,radius,height);
    }
    // Host preparation owns source validation; no filesystem validation per block sample.
    int color(BiomeColor kind,BlockResourceSource source,DataReader data,java.util.function.Consumer<String> note,
              ModelBlockContext block,ModelBlockContext.Position position,List<Source> sources,Inputs inputs)throws IOException,ModelRule.Failure {
            Objects.requireNonNull(kind);
            String version=inputs.version();long seed=inputs.seed();int radius=inputs.radius();var height=inputs.height();
            if(position.x()<Integer.MIN_VALUE+16L||position.x()>Integer.MAX_VALUE-16L||position.z()<Integer.MIN_VALUE+16L||position.z()>Integer.MAX_VALUE-16L||position.y()<Integer.MIN_VALUE+16||position.y()>Integer.MAX_VALUE-16)throw failure(Reason.LIMIT_EXCEEDED,"群系采样坐标超出游戏整数范围");
            if(!height.contains(position.y()))throw failure(Reason.CONTEXT_REQUIRED,"颜色目标位置不在保存维度的有效高度内");
            long hashed=SavedBiomeSampler.obfuscateSeed(seed);int red=0,green=0,blue=0,count=(2*radius+1)*(2*radius+1);
            for(int dz=-radius;dz<=radius;dz++)for(int dx=-radius;dx<=radius;dx++){
                int x=(int)position.x()+dx,z=(int)position.z()+dz;
                var q=SavedBiomeSampler.sample(hashed,x,position.y(),z);var biome=block.biomeAtQuart(q.x(),height.clampQuart(q.y()),q.z());
                if(biome.id().isEmpty())throw failure(Reason.CONTEXT_REQUIRED,"保存群系采样不可用："+biome.detail());
                ResourceLocation id;try{id=ResourceLocation.parse(biome.id().orElseThrow());}catch(IllegalArgumentException invalid){throw invalid("保存群系ID无效");}
                int rgb=definition(new Key(id,kind),source,data,sources).at(x,z);red+=(rgb>>16)&255;green+=(rgb>>8)&255;blue+=rgb&255;
            }
            note.accept("保存群系颜色："+version+" "+kind+"；种子扰动与混合半径"+radius+"；维度高度"+height.minY()+".."+(height.minY()+height.height()-1)+"；有效数据包/颜色表，未执行游戏代码");
            return (red/count)<<16|(green/count)<<8|blue/count;
    }
    private Color definition(Key key,BlockResourceSource source,DataReader data,List<Source> sources)throws IOException,ModelRule.Failure {
        var cached=colors.get(key);if(cached!=null){sources.addAll(cached.sources());return cached;}
        int start=sources.size();var id=key.biome();
        var json=data.read(new ResourceLocation(id.namespace(),"worldgen/biome/"+id.path()+".json")).orElseThrow(()->failure(Reason.MISSING_RESOURCE,"缺少有效群系定义："+id));
        var effect=json.get("effects");if(effect==null||!effect.isJsonObject())throw invalid("群系effects须为对象："+id);
        var effects=effect.getAsJsonObject();String field=key.kind().name().toLowerCase(Locale.ROOT)+"_color";var override=effects.get(field);int rgb;
        if(override!=null)rgb=rgb(override,field);
        else if(key.kind()==BiomeColor.WATER)throw invalid("群系缺少water_color："+id);
        else{
            double temperature=climate(json,"temperature"),downfall=climate(json,"downfall");
            int x=(int)((1-temperature)*255),y=(int)((1-downfall*temperature)*255);
            var colormap=new ResourceLocation("minecraft","colormap/"+key.kind().name().toLowerCase(Locale.ROOT));var pixels=colormaps.get(key.kind());
            if(pixels==null){
                int textureTraceStart=sources.size();
                var bytes=source.read(ResourceLocation.Kind.TEXTURE,colormap).orElseThrow(()->failure(Reason.MISSING_RESOURCE,"缺少有效颜色表："+colormap));
                var reference=new Source(ResourceLocation.Kind.TEXTURE,colormap);
                if(!sources.subList(textureTraceStart,sources.size()).contains(reference))sources.add(reference);
                try{var image=TextureAtlas.decode(bytes,false);if(image.getWidth()!=256||image.getHeight()!=256)throw new IOException("颜色表须为256×256");pixels=image.getRGB(0,0,256,256,null,0,256);colormaps.put(key.kind(),pixels);}
                catch(IOException invalid){throw invalid("颜色表无效："+invalid.getMessage());}
            }else sources.add(new Source(ResourceLocation.Kind.TEXTURE,colormap));
            rgb=pixels[y*256+x]&0xffffff;
        }
        String modifier="none";
        if(key.kind()==BiomeColor.GRASS&&effects.has("grass_color_modifier")){
            var raw=effects.get("grass_color_modifier");if(!raw.isJsonPrimitive()||!raw.getAsJsonPrimitive().isString())throw invalid("grass_color_modifier须为字符串");
            modifier=raw.getAsString();if(!Set.of("none","dark_forest","swamp").contains(modifier))throw failure(Reason.UNSUPPORTED_MODEL,"未知草色修饰协议："+modifier);
        }
        var value=new Color(rgb,modifier,List.copyOf(new LinkedHashSet<>(sources.subList(start,sources.size()))));
        if(colors.size()>=128)colors.remove(colors.keySet().iterator().next());colors.put(key,value);return value;
    }
    private static int rgb(JsonElement value,String field)throws ModelRule.Failure {
        try{if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw invalid(field+"须为整数");return value.getAsBigDecimal().intValueExact()&0xffffff;}
        catch(ArithmeticException|NumberFormatException invalid){throw invalid(field+"超出整数范围");}
    }
    private static double climate(JsonObject json,String key)throws ModelRule.Failure {
        var value=json.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw invalid("群系"+key+"须为数值");
        float number=value.getAsFloat();if(!Float.isFinite(number))throw invalid("群系气候数值不是有限float");return Math.max(0f,Math.min(1f,number));
    }
    private static ModelRule.Failure failure(Reason reason,String detail){return new ModelRule.Failure(reason,detail);}
    private static ModelRule.Failure invalid(String detail){return failure(Reason.INVALID_MODEL,detail);}
}
