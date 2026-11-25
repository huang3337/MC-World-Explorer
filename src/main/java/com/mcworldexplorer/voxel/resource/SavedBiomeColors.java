package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;
import static com.mcworldexplorer.voxel.resource.ModelRule.BiomeColor;

/** Shared biome resolvers. Definitions cache independently of position-dependent grass modifiers. */
final class SavedBiomeColors {
    private record Key(String biome,BiomeColor kind) { }
    private record Color(int rgb,String modifier,List<Source> sources,boolean fallbackClimate) {
        int at(int x,int z,boolean floatNoise){return switch(modifier){case "dark_forest"->((rgb&0xfefefe)+0x28340a)>>1;case "swamp"->BiomeGrassNoise.color(x,z,floatNoise);default->rgb;};}
    }
    private final Map<Key,Color> colors=new LinkedHashMap<>(16,.75f,true);
    private BlendWindow window;
    private boolean floatSwampNoise;
    private ClientResourceArchive.Fingerprint checkedSampler;
    // Adjacent blend windows reuse world samples. Keep at most one 16x16 horizontal window per reader.
    static final class BlendWindow {
        final int[] xs=new int[256],ys=new int[256],zs=new int[256];
        final String[] ids=new String[256];
        final int[] quartXs=new int[512],quartYs=new int[512],quartZs=new int[512];
        final ModelBlockContext.BiomeSample[] quartValues=new ModelBlockContext.BiomeSample[512];
        ModelBlockContext previous;
        Inputs settings;
        void select(ModelBlockContext block,Inputs inputs) {
            if (!block.sharesBiomeSnapshot(previous) || !inputs.equals(settings)) {
                Arrays.fill(ids,null);Arrays.fill(quartValues,null);
            }
            previous=block;
            settings=inputs;
        }
        ModelBlockContext.BiomeSample quart(int x,int y,int z) {
            int index=(x&7)|((z&7)<<3)|((y&7)<<6);
            if(quartValues[index]!=null && quartXs[index]==x && quartYs[index]==y && quartZs[index]==z)return quartValues[index];
            quartXs[index]=x;quartYs[index]=y;quartZs[index]=z;
            return quartValues[index]=previous.biomeAtQuart(x,y,z);
        }
        String biome(int x,int y,int z) throws ModelRule.Failure {
            int index=(x&15)|((z&15)<<4);
            if(ids[index]!=null && xs[index]==x && ys[index]==y && zs[index]==z)return ids[index];
            var q=SavedBiomeSampler.sample(settings.hashedSeed(),x,y,z);
            var sample=quart(q.x(),settings.height().clampQuart(q.y()),q.z());
            if(sample.id().isEmpty())throw failure(Reason.CONTEXT_REQUIRED,"保存群系采样不可用："+sample.detail());
            xs[index]=x;ys[index]=y;zs[index]=z;
            return ids[index]=sample.id().get();
        }
    }
    BlendWindow samples(ModelBlockContext block,Inputs inputs) {
        if(window==null)window=new BlendWindow();
        window.select(block,inputs);
        return window;
    }
    private final Map<BiomeColor,int[]> colormaps=new EnumMap<>(BiomeColor.class);
    int foliage(ModelRule.Context context,ModelBlockContext block,List<Source> sources)throws IOException,ModelRule.Failure {
        return color(BiomeColor.FOLIAGE,context,block,sources);
    }
    int color(BiomeColor kind,ModelRule.Context context,ModelBlockContext block,List<Source> sources)throws IOException,ModelRule.Failure {
        var source=context.resources();source.validate();
        verifySampler(source);
        try{
            return color(kind,source,context::dataJson,context::note,block,block.position(),sources,inputs(source,block.dimension()));
        }finally{source.validate();}
    }
    void verifySampler(BlockResourceSource source)throws IOException,ModelRule.Failure {
        if(!source.fingerprint().equals(checkedSampler))useSampler(source,new ClientBlockColors(source));
    }
    void useSampler(BlockResourceSource source,ClientBlockColors protocol){
        checkedSampler=source.fingerprint();
        floatSwampNoise=protocol.floatSwampNoise();
    }
    @FunctionalInterface interface DataReader {Optional<JsonObject> read(ResourceLocation id)throws IOException,ModelRule.Failure;}
    record Inputs(String version,long hashedSeed,int radius,BlockResourceSource.DimensionHeight height,boolean fallbackDimensionHeight) {
        Inputs(String version,long hashedSeed,int radius,BlockResourceSource.DimensionHeight height){
            this(version,hashedSeed,radius,height,false);
        }
    }
    static Inputs inputs(BlockResourceSource source,String dimensionId)throws IOException,ModelRule.Failure {
        String version=source.baseClientVersion().orElse("");

        long seed=source.worldSeed().orElseThrow(()->failure(Reason.CONTEXT_REQUIRED,"缺少保存世界种子，不能用0替代"));
        int radius=source.biomeBlendRadius().orElseThrow(()->failure(Reason.CONTEXT_REQUIRED,"缺少当前实例群系混合设置"));
        if(radius<0||radius>7)throw invalid("群系混合半径须为0..7");
        ResourceLocation dimension;try{dimension=ResourceLocation.parse(dimensionId);}catch(IllegalArgumentException invalid){throw invalid("保存维度ID无效");}
        var heightOpt=source.dimensionHeight(dimension);
        boolean fallbackHeight=false;
        BlockResourceSource.DimensionHeight height;
        if(heightOpt.isPresent()){
            height=heightOpt.get();
        }else{
            height=legacyDimensionHeight(source,dimension)
                    .orElseThrow(()->failure(Reason.CONTEXT_REQUIRED,"缺少保存维度类型/有效高度，不能从现有区段推测"));
            fallbackHeight=true;
        }
        return new Inputs(version,SavedBiomeSampler.obfuscateSeed(seed),radius,height,fallbackHeight);
    }
    private static Optional<BlockResourceSource.DimensionHeight> legacyDimensionHeight(
            BlockResourceSource source, ResourceLocation dimension) throws IOException {
        int format = source.baseResourceFormat();
        if (format < 1 || !source.baseDataRegistry("dimension_type").equals(Optional.of(false))) {
            return Optional.empty();
        }
        String id = dimension.toString();
        if (format <= 7) {
            return switch (id) {
                case "minecraft:overworld", "minecraft:the_nether", "minecraft:the_end" ->
                        Optional.of(new BlockResourceSource.DimensionHeight(0, 256));
                default -> Optional.empty();
            };
        }
        return switch (id) {
            case "minecraft:overworld" -> Optional.of(new BlockResourceSource.DimensionHeight(-64, 384));
            case "minecraft:the_nether", "minecraft:the_end" -> Optional.of(new BlockResourceSource.DimensionHeight(0, 256));
            default -> Optional.empty();
        };
    }
    // Host preparation owns source validation; no filesystem validation per block sample.
    int color(BiomeColor kind,BlockResourceSource source,DataReader data,java.util.function.Consumer<String> note,
              ModelBlockContext block,ModelBlockContext.Position position,List<Source> sources,Inputs inputs)throws IOException,ModelRule.Failure {
            Objects.requireNonNull(kind);
            String version=inputs.version();int radius=inputs.radius();var height=inputs.height();
            if (kind == BiomeColor.DRY_FOLIAGE && ClientBlockColors.hasLegacyRegistry(version))
                throw failure(Reason.UNSUPPORTED_MODEL,"此客户端缺少枯叶群系颜色协议");
            if(position.x()<Integer.MIN_VALUE+16L||position.x()>Integer.MAX_VALUE-16L||position.z()<Integer.MIN_VALUE+16L||position.z()>Integer.MAX_VALUE-16L||position.y()<Integer.MIN_VALUE+16||position.y()>Integer.MAX_VALUE-16)throw failure(Reason.LIMIT_EXCEEDED,"群系采样坐标超出游戏整数范围");
            if(!height.contains(position.y()))throw failure(Reason.CONTEXT_REQUIRED,"颜色目标位置不在保存维度的有效高度内");
            int red=0,green=0,blue=0,count=(2*radius+1)*(2*radius+1);
            samples(block,inputs);
            Color last = null;
            String lastBiome = null;
            boolean usedFallbackClimate = false;
            for(int dz=-radius;dz<=radius;dz++)for(int dx=-radius;dx<=radius;dx++){
                int x=(int)position.x()+dx,z=(int)position.z()+dz;
                String id = window.biome(x,position.y(),z);
                if (!id.equals(lastBiome)) {
                    last = definition(new Key(id,kind),source,data,sources);
                    lastBiome = id;
                    usedFallbackClimate |= last.fallbackClimate();
                }
                int rgb=last.at(x,z,floatSwampNoise);red+=(rgb>>16)&255;green+=(rgb>>8)&255;blue+=rgb&255;
            }
            StringBuilder detail = new StringBuilder("保存群系颜色：").append(version).append(' ').append(kind)
                    .append("；种子扰动与混合半径").append(radius)
                    .append("；维度高度").append(height.minY()).append("..").append(height.minY()+height.height()-1)
                    .append("；有效数据包/颜色表，未执行游戏代码");
            if (inputs.fallbackDimensionHeight()) {
                detail.append("；维度高度取自原版标准默认范围（客户端未内置 dimension_type）");
            }
            if (usedFallbackClimate) {
                detail.append("；群系气候与水色取自内置原版表近似（客户端未内置 worldgen/biome）");
            }
            note.accept(detail.toString());
            return (red/count)<<16|(green/count)<<8|blue/count;
    }
    private Color definition(Key key,BlockResourceSource source,DataReader data,List<Source> sources)throws IOException,ModelRule.Failure {
        var cached=colors.get(key);if(cached!=null){for(var ref:cached.sources())if(!sources.contains(ref))sources.add(ref);return cached;}
        int start=sources.size();ResourceLocation id;
        try {id=ResourceLocation.parse(key.biome());} catch(IllegalArgumentException invalid){throw invalid("保存群系ID无效");}
        var jsonOpt=data.read(new ResourceLocation(id.namespace(),"worldgen/biome/"+id.path()+".json"));
        if(jsonOpt.isEmpty()){
            if(source!=null && source.baseResourceFormat()>=1
                    && source.baseDataRegistry("worldgen/biome").equals(Optional.of(false))
                    && id.namespace().equals("minecraft")){
                var climate=com.mcworldexplorer.voxel.data.VanillaLegacyBiomes.climate(id.toString());
                if(climate.isPresent()){
                    return cacheColor(key, legacyColor(key, climate.get(), source, sources, start));
                }
            }
            throw failure(Reason.MISSING_RESOURCE,"缺少有效群系定义："+id);
        }
        var json=jsonOpt.get();
        var effect=json.get("effects");if(effect==null||!effect.isJsonObject())throw invalid("群系effects须为对象："+id);
        var effects=effect.getAsJsonObject();String field=key.kind().name().toLowerCase(Locale.ROOT)+"_color";var override=effects.get(field);int rgb;
        if(override!=null)rgb=rgb(override,field);
        else if(key.kind()==BiomeColor.WATER)throw invalid("群系缺少water_color："+id);
        else{
            double temperature=climate(json,"temperature"),downfall=climate(json,"downfall");
            rgb=sampleColormap(key.kind(),temperature,downfall,source,sources);
        }
        String modifier="none";
        if(key.kind()==BiomeColor.GRASS&&effects.has("grass_color_modifier")){
            var raw=effects.get("grass_color_modifier");if(!raw.isJsonPrimitive()||!raw.getAsJsonPrimitive().isString())throw invalid("grass_color_modifier须为字符串");
            modifier=raw.getAsString();if(!Set.of("none","dark_forest","swamp").contains(modifier))throw failure(Reason.UNSUPPORTED_MODEL,"未知草色修饰协议："+modifier);
        }
        return cacheColor(key, new Color(rgb,modifier,List.copyOf(new LinkedHashSet<>(sources.subList(start,sources.size()))),false));
    }
    private Color legacyColor(Key key,com.mcworldexplorer.voxel.data.VanillaLegacyBiomes.Climate climate,
                              BlockResourceSource source,List<Source> sources,int start)throws IOException,ModelRule.Failure {
        int rgb;
        String modifier = "none";
        if (key.kind() == BiomeColor.WATER) {
            rgb = climate.waterColor() & 0xffffff;
        } else if (key.kind() == BiomeColor.GRASS && climate.grassColorOverride() != null) {
            rgb = climate.grassColorOverride() & 0xffffff;
            modifier = climate.grassColorModifier();
        } else if (key.kind() == BiomeColor.FOLIAGE && climate.foliageColorOverride() != null) {
            rgb = climate.foliageColorOverride() & 0xffffff;
        } else {
            double temperature = Math.max(0f, Math.min(1f, climate.temperature()));
            double downfall = Math.max(0f, Math.min(1f, climate.downfall()));
            rgb = sampleColormap(key.kind(), temperature, downfall, source, sources);
            if (key.kind() == BiomeColor.GRASS) {
                modifier = climate.grassColorModifier();
            }
        }
        return new Color(rgb, modifier, List.copyOf(new LinkedHashSet<>(sources.subList(start, sources.size()))), true);
    }
    private int sampleColormap(BiomeColor kind,double temperature,double downfall,BlockResourceSource source,List<Source> sources)throws IOException,ModelRule.Failure {
        int x=(int)((1-temperature)*255),y=(int)((1-downfall*temperature)*255);
        var colormap=new ResourceLocation("minecraft","colormap/"+kind.name().toLowerCase(Locale.ROOT));var pixels=colormaps.get(kind);
        if(pixels==null){
            int textureTraceStart=sources.size();
            var bytes=source.read(ResourceLocation.Kind.TEXTURE,colormap).orElseThrow(()->failure(Reason.MISSING_RESOURCE,"缺少有效颜色表："+colormap));
            var reference=new Source(ResourceLocation.Kind.TEXTURE,colormap);
            if(!sources.subList(textureTraceStart,sources.size()).contains(reference))sources.add(reference);
            try{var image=TextureAtlas.decode(bytes,false);if(image.getWidth()!=256||image.getHeight()!=256)throw new IOException("颜色表须为256×256");pixels=image.getRGB(0,0,256,256,null,0,256);colormaps.put(kind,pixels);}
            catch(IOException invalid){throw invalid("颜色表无效："+invalid.getMessage());}
        }else sources.add(new Source(ResourceLocation.Kind.TEXTURE,colormap));
        return pixels[y*256+x]&0xffffff;
    }
    private Color cacheColor(Key key,Color value){
        if(colors.size()>=128)colors.remove(colors.keySet().iterator().next());
        colors.put(key,value);
        return value;
    }
    private static int rgb(JsonElement value,String field)throws ModelRule.Failure {
        try{
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().matches("#[0-9a-fA-F]{6}")) {
                return Integer.parseInt(value.getAsString().substring(1),16);
            }
            if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw invalid(field+"须为整数或 #RRGGBB");
            return value.getAsBigDecimal().intValueExact()&0xffffff;
        }
        catch(ArithmeticException|NumberFormatException invalid){throw invalid(field+"超出整数范围");}
    }
    private static double climate(JsonObject json,String key)throws ModelRule.Failure {
        var value=json.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw invalid("群系"+key+"须为数值");
        float number=value.getAsFloat();if(!Float.isFinite(number))throw invalid("群系气候数值不是有限float");return Math.max(0f,Math.min(1f,number));
    }
    private static ModelRule.Failure failure(Reason reason,String detail){return new ModelRule.Failure(reason,detail);}
    private static ModelRule.Failure invalid(String detail){return failure(Reason.INVALID_MODEL,detail);}
}
