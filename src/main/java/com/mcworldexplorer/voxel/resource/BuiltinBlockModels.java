package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.data.*;
import java.io.IOException;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;

/** Static adapters for renderer families whose vanilla resources declare no element geometry.
 * Geometry/UV rules are per renderer type; material variants resolve resource IDs at runtime.
 */
final class BuiltinBlockModels implements BlockResourceSource {
    private final BlockResourceSource source;
    private final Map<String,BlockEntityData> entities;
    private final Map<ResourceLocation,byte[]> generated=new HashMap<>();
    private final Map<BlockState,Set<String>> notes=new HashMap<>();
    List<String> notes(BlockState state){return List.copyOf(notes.getOrDefault(state,Set.of()));}
    void note(BlockState state,String detail){notes.computeIfAbsent(state,ignored->new LinkedHashSet<>()).add(detail);}

    BuiltinBlockModels(BlockResourceSource source,Map<String,BlockEntityData> entities){this.source=source;this.entities=Map.copyOf(entities);}
    @Override public Optional<String> origin(ResourceLocation.Kind kind,ResourceLocation id){return source.origin(kind,id);}
    @Override public void validate()throws IOException{source.validate();}
    @Override public ClientResourceArchive.Fingerprint fingerprint(){return source.fingerprint();}
    @Override public Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id)throws IOException{return kind==ResourceLocation.Kind.TEXTURE&&generated.containsKey(id)?Optional.of(generated.get(id)):source.read(kind,id);}
    @Override public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id)throws IOException{return source.readJson(kind,id);}
    Optional<List<Part>> resolve(BlockState state,Map<String,String> inherited) throws IOException {
        var id=ResourceLocation.parse(state.name());if(!id.namespace().equals("minecraft"))return Optional.empty();
        String name=id.path();
        var metadata=entities.get(state.properties().getOrDefault("__mcwe_entity",""));
        if(metadata!=null&&!metadata.visual().getString("__visual_warning").isBlank())note(state,metadata.visual().getString("__visual_warning"));
        List<Face> faces=new ArrayList<>();int x=0,y=facing(state.properties().get("facing"));String renderType=null;
        if(Set.of("air","cave_air","void_air","barrier","light","structure_void").contains(name))return Optional.of(List.of(part(id,faces,0,0,null)));
        if(name.equals("chest")||name.equals("trapped_chest")||name.equals("ender_chest")) {
            String kind=name.equals("trapped_chest")?"trapped":name.equals("ender_chest")?"ender":"normal";
            String type=state.properties().getOrDefault("type","single"),suffix=type.equals("single")?"":"_"+type;
            String texture="entity/chest/"+kind+suffix;
            double left=type.equals("left")?0:1,right=type.equals("right")?16:15,width=right-left;
            box(faces,texture,64,64,left,0,1,right,10,15,0,19,width,10,14);
            box(faces,texture,64,64,left,9,1,right,14,15,0,0,width,5,14);
            double lock=type.equals("left")?0:type.equals("right")?15:7,lockWidth=type.equals("single")?2:1;
            box(faces,texture,64,64,lock,7,15,lock+lockWidth,11,16,0,0,lockWidth,4,1);
            y=(y+180)%360;
        } else if(name.endsWith("_bed")) {
            String color=name.substring(0,name.length()-4),texture="entity/bed/"+color;
            boolean head=state.properties().getOrDefault("part","foot").equals("head");
            box(faces,texture,64,64,0,3,0,16,9,16,0,head?0:22,16,16,6);
            double v=head?0:22;
            double[][] uv={{28,v+6,44,v+22},{6,v+6,22,v+22},{6,v,22,v+6},{22,v,38,v+6},{0,v+6,6,v+22},{22,v+6,28,v+22}};
            for(int i=0;i<6;i++){var f=faces.get(i);faces.set(i,new Face(f.direction(),f.texture(),new Uv(uv[i][0]/4,uv[i][1]/4,uv[i][2]/4,uv[i][3]/4),i==4?90:i==5?270:0,-1,null,f.layer(),f.from(),f.to(),null,true));}
            for(int i=0;i<2;i++){int leg=i*13;box(faces,texture,64,64,leg,0,head?0:13,leg+3,3,head?3:16,50,(head?6:0)+i*12,3,3,3);}
        } else if(name.equals("shulker_box")||name.endsWith("_shulker_box")) {
            String color=name.equals("shulker_box")?"":"_"+name.substring(0,name.length()-12),texture="entity/shulker/shulker"+color;
            box(faces,texture,64,64,0,0,0,16,8,16,0,28,16,8,16);
            box(faces,texture,64,64,0,4,0,16,16,16,0,0,16,12,16);
            String facing=state.properties().getOrDefault("facing","up");
            if(facing.equals("down")){x=180;y=0;}else if(!facing.equals("up")){x=90;y=facing(facing);}else y=0;
        } else if(name.endsWith("_sign")) {
            boolean hanging=name.contains("hanging"),wall=name.contains("wall");
            String wood=name.replace("_wall_hanging_sign","").replace("_hanging_sign","").replace("_wall_sign","").replace("_sign","");
            String texture="entity/signs/"+(hanging?"hanging/":"")+wood;
            if(hanging) {
                box(faces,texture,64,32,1,1,7,15,11,9,0,12,14,10,2);
                if(!wall&&state.properties().getOrDefault("attached","false").equals("true")) {
                    for(Direction side:List.of(Direction.NORTH,Direction.SOUTH))faces.add(new Face(side,ResourceLocation.parse(texture),new Uv(3.5,3,6.5,6),0,-1,null,faces.size(),new Vec3(2,11,8),new Vec3(14,17,8),null,true));
                }else for(int chain:new int[]{3,12}) {
                    for(Direction side:List.of(Direction.NORTH,Direction.SOUTH))faces.add(new Face(side,ResourceLocation.parse(texture),new Uv(0,3,.75,6),0,-1,null,faces.size(),new Vec3(chain-1.5,11,8),new Vec3(chain+1.5,17,8),null,true));
                }
                if(wall)box(faces,texture,64,32,0,14,7,16,16,9,0,0,16,2,2);
            }else{
                box(faces,texture,64,32,0,wall?6:8,wall?14.667:7.333,16,wall?14:16,wall?16:8.667,0,0,24,12,2);
                if(!wall)box(faces,texture,64,32,7.333,0,7.333,8.667,8,8.667,0,14,2,14,2);
            }
            var entity=entities.get(state.properties().getOrDefault("__mcwe_entity",""));
            if(entity!=null) {
                note(state,"告示文字使用本机字体静态排版；特殊字体、发光和富文本样式暂用基本文字");
                double y0=hanging?2:wall?6.5:8.5,y1=hanging?10:wall?13.5:15.5,z0=hanging?7:wall?14.667:7.333,z1=hanging?9:wall?16:8.667;
                signText(faces,state,entity,false,y0,y1,z0-.01);
                signText(faces,state,entity,true,y0,y1,z1+.01);
            }
            if(!wall)rotateAll(faces,number(state,"rotation",0)*22.5);
        } else if(name.endsWith("_banner")) {
            boolean wall=name.contains("wall");String color=name.replace("_wall_banner","").replace("_banner","");
            var entity=entities.get(state.properties().getOrDefault("__mcwe_entity",""));
            String cloth=entity==null?"entity/banner/base":bannerTexture(state,entity,color).toString();
            int start=faces.size();box(faces,cloth,64,64,1.333,wall?-13:2.667,wall?13.667:8.667,14.667,wall?13.667:29.333,wall?14.333:9.333,0,0,20,40,1);
            if(entity==null)tint(faces,start,dye(color));
            box(faces,"entity/banner_base",64,64,1.333,wall?12.333:28,wall?14.333:7.333,14.667,wall?13.667:29.333,wall?15.667:8.667,0,42,20,2,2);
            if(!wall)box(faces,"entity/banner_base",64,64,7.333,0,7.333,8.667,28,8.667,44,0,2,42,2);
            if(!wall)rotateAll(faces,number(state,"rotation",0)*22.5);
        } else if(name.endsWith("_head")||name.endsWith("_skull")) {
            boolean wall=name.contains("_wall_");String type=name.replace("_wall_head","").replace("_wall_skull","").replace("_head","").replace("_skull","");
            String texture=switch(type){case "skeleton"->"entity/skeleton/skeleton";case "wither_skeleton"->"entity/skeleton/wither_skeleton";case "zombie"->"entity/zombie/zombie";case "creeper"->"entity/creeper/creeper";case "piglin"->"entity/piglin/piglin";case "dragon"->"entity/enderdragon/dragon";default->"entity/player/wide/steve";};
            double z0=wall?8:4,y0=wall?4:0;
            if(type.equals("dragon")) {
                box(faces,texture,256,256,4,y0,z0-1,12,y0+8,z0+7,112,30,16,16,16);
                box(faces,texture,256,256,5,y0+3.5,z0-8,11,y0+6,z0,176,44,12,5,16);
                box(faces,texture,256,256,5,y0+1.5,z0-8,11,y0+3.5,z0,176,65,12,4,16);
                for(int horn:new int[]{5,9})box(faces,texture,256,256,horn,y0+8,z0+2,horn+1,y0+10,z0+5,0,0,2,4,6);
            } else box(faces,texture,64,type.contains("skeleton")||type.equals("creeper")?32:64,4,y0,z0,12,y0+8,z0+8,0,0,8,8,8);
            if(type.equals("player"))note(state,"玩家头颅使用本地默认皮肤，未下载档案中的外部皮肤");
            if(type.equals("player")||type.equals("zombie"))box(faces,texture,64,64,3.75,y0-.25,z0-.25,12.25,y0+8.25,z0+8.25,32,0,8,8,8);
            if(type.equals("piglin")){for(int ear:new int[]{1,12})box(faces,texture,64,64,ear,y0+4,z0+3,ear+3,y0+8,z0+5,0,0,3,4,2);}
            if(!wall)rotateAll(faces,number(state,"rotation",0)*22.5);
        } else if(name.equals("conduit")) {
            note(state,"潮涌核心显示静态核心，未还原激活结构及动画");
            box(faces,"entity/conduit/base",32,16,5,5,5,11,11,11,0,0,6,6,6);
        } else if(name.equals("decorated_pot")) {
            box(faces,"entity/decorated_pot/decorated_pot_base",32,32,1,0,1,15,16,15,0,0,8,16,8);
            var entity=entities.get(state.properties().getOrDefault("__mcwe_entity",""));
            var sherds=entity==null?net.kyori.adventure.nbt.ListBinaryTag.empty():entity.visual().getList("sherds",net.kyori.adventure.nbt.BinaryTagTypes.STRING);
            if(sherds.size()==0&&entity!=null)sherds=entity.visual().getList("Sherds",net.kyori.adventure.nbt.BinaryTagTypes.STRING);
            for(int i=0;i<6;i++){var f=faces.get(i);String texture="entity/decorated_pot/decorated_pot_side";int decoration=switch(f.direction()){case NORTH->0;case WEST->1;case EAST->2;case SOUTH->3;default->-1;};
                if(decoration>=0&&sherds.size()>decoration){String item=sherds.getString(decoration);try{var resource=ResourceLocation.parse(item);if(resource.path().endsWith("_pottery_sherd"))texture=new ResourceLocation(resource.namespace(),"entity/decorated_pot/"+resource.path().replace("_pottery_sherd","_pottery_pattern")).toString();}catch(IllegalArgumentException invalid){/* Keep the unadorned side. */}}
                faces.set(i,new Face(f.direction(),ResourceLocation.parse(decoration<0?"entity/decorated_pot/decorated_pot_base":texture),decoration<0?new Uv(0,6.5,7,13.5):new Uv(1,0,15,16),0,-1,null,0,f.from(),f.to(),null,true));
            }
            box(faces,"entity/decorated_pot/decorated_pot_base",32,32,4.1,17.1,4.1,11.9,19.9,11.9,0,0,8,3,8);
            box(faces,"entity/decorated_pot/decorated_pot_base",32,32,4.8,15.8,4.8,11.2,17.2,11.2,0,5,6,1,6);
        } else if(name.equals("end_portal")||name.equals("end_gateway")) {
            note(state,"传送门使用本地纹理静态预览，未运行客户端动态着色器");
            box(faces,"entity/end_portal",256,256,0,0,0,16,name.equals("end_portal")?12:16,16,0,0,16,1,16);
            renderType="minecraft:translucent";
        } else if(name.equals("water")||name.equals("lava")||name.equals("bubble_column")) {
            note(state,"流体使用静态首帧和方块液位，流向与相邻液面坡度暂用平面近似");
            String particle=inherited.getOrDefault("particle","block/water_still");Set<String> visited=new HashSet<>();while(particle.startsWith("#")){if(!visited.add(particle)||visited.size()>128)throw new IOException("流体纹理引用循环或超限");particle=inherited.getOrDefault(particle.substring(1),"block/water_still");}
            int level=number(state,"level",0);
            double height=name.equals("bubble_column")||level>=8?16:Math.max(1,16*(8-Math.min(7,level))/9.0);
            box(faces,particle,16,16,0,0,0,16,height,16,0,0,4,4,4);
            // A fluid face samples the whole sprite; box-unwrapping is not the fluid material convention.
            for(int i=0;i<faces.size();i++){var f=faces.get(i);faces.set(i,new Face(f.direction(),f.texture(),new Uv(0,0,16,16),0,-1,f.direction(),f.layer(),f.from(),f.to(),null,false));}
            if(!name.equals("lava"))tint(faces,0,0x3F76E4);
            renderType=name.equals("lava")?"minecraft:solid":"mcwe:fluid";
        } else if(name.equals("moving_piston")) {
            // The stored carried block state, when present, is handled by the entity context below.
            var entity=entities.get(state.properties().getOrDefault("__mcwe_entity",""));
            if(entity!=null){var carried=entity.visual().getCompound("blockState");String carriedName=carried.getString("Name");
                if(!carriedName.isBlank()&&!carriedName.equals(state.name()))try{
                    Map<String,String> properties=new TreeMap<>();var p=carried.getCompound("Properties");for(String key:p.keySet())properties.put(key,p.getString(key));
                    var resolved=new BlockModelResolver(this).resolve(new BlockState(carriedName,properties));
                    if(resolved.resolved()) {
                        double progress=entity.visual().getFloat("progress");
                        if(!Double.isFinite(progress))throw new IOException("移动活塞进度不是有限值");
                        progress=Math.max(0,Math.min(1,progress));
                        double distance=entity.visual().getBoolean("extending")?progress-1:1-progress;
                        Direction[] directions={Direction.DOWN,Direction.UP,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST};
                        int direction=entity.visual().getInt("facing");
                        if(direction<0||direction>=directions.length)throw new IOException("移动活塞方向无效");
                        double[] normal=ModelGeometry.normal(directions[direction]);
                        Vec3 offset=new Vec3(normal[0]*distance,normal[1]*distance,normal[2]*distance);
                        return Optional.of(resolved.model().orElseThrow().parts().stream().map(part->new Part(part.id(),part.xRotation(),part.yRotation(),part.uvLock(),part.faces(),part.renderType(),offset)).toList());
                    }
                }catch(IllegalArgumentException invalid){throw new IOException("移动活塞承载状态无效",invalid);}
            }
            note(state,"移动活塞缺少可解析的承载状态，暂不显示承载几何");
            return Optional.of(List.of(part(id,faces,0,0,null))); // No carried state: no invented opaque cube.
        } else return Optional.empty();
        return Optional.of(List.of(part(id,faces,x,y,renderType)));
    }
    private void signText(List<Face> faces,BlockState state,BlockEntityData entity,boolean back,double y0,double y1,double z)throws IOException {
        var data=entity.visual();var side=data.getCompound(back?"back_text":"front_text");
        List<String> lines=new ArrayList<>();
        var messages=side.getList("messages",net.kyori.adventure.nbt.BinaryTagTypes.STRING);
        for(int i=0;i<Math.min(4,messages.size());i++)lines.add(plainText(messages.getString(i)));
        if(lines.isEmpty()&&!back)for(int i=1;i<=4;i++)lines.add(plainText(data.getString("Text"+i)));
        if(lines.stream().allMatch(String::isBlank))return;
        var image=new java.awt.image.BufferedImage(128,64,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var graphics=image.createGraphics();
        try {
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setFont(new java.awt.Font("Microsoft YaHei",java.awt.Font.PLAIN,12));
            String color=side.getString("color");if(color.isBlank())color=data.getString("Color");
            graphics.setColor(new java.awt.Color(color.isBlank()||color.equals("black")?0x1D1D21:dye(color)));
            var metrics=graphics.getFontMetrics();
            for(int i=0;i<lines.size();i++){String line=lines.get(i);while(metrics.stringWidth(line)>122&&!line.isEmpty())line=line.substring(0,line.length()-1);graphics.drawString(line,(128-metrics.stringWidth(line))/2,13+i*14);}
        }finally{graphics.dispose();}
        ResourceLocation texture=store("sign/"+entity.fingerprint()+"/"+back,image);
        faces.add(new Face(back?Direction.SOUTH:Direction.NORTH,texture,new Uv(0,0,16,16),0,-1,null,1000+faces.size(),new Vec3(1,y0,z),new Vec3(15,y1,z),null,false));
    }
    private ResourceLocation bannerTexture(BlockState state,BlockEntityData entity,String color)throws IOException {
        var bytes=source.read(ResourceLocation.Kind.TEXTURE,ResourceLocation.parse("entity/banner/base"));
        if(bytes.isEmpty())return ResourceLocation.parse("entity/banner/base");
        var image=TextureAtlas.decode(bytes.orElseThrow(),false);
        image=tintImage(image,dye(color));
        var list=entity.visual().getList("patterns",net.kyori.adventure.nbt.BinaryTagTypes.COMPOUND);
        if(list.size()==0)list=entity.visual().getList("Patterns",net.kyori.adventure.nbt.BinaryTagTypes.COMPOUND);
        if(list.size()>32)note(state,"旗帜超过 32 层图案，仅显示前 32 层");
        for(int i=0;i<Math.min(32,list.size());i++) {
            var pattern=list.getCompound(i);String patternName=pattern.getString("pattern");if(patternName.isBlank())patternName=pattern.getString("Pattern");
            String dye=pattern.getString("color");if(dye.isBlank())dye=legacyDye(pattern.getInt("Color"));
            if(patternName.isBlank())continue;
            ResourceLocation id;
            try{id=ResourceLocation.parse(patternName);}catch(IllegalArgumentException invalid){note(state,"旗帜包含无效图案资源 ID，保留其余图案");continue;}
            String path=id.path();
            try {
                var definition=source.readJson(ResourceLocation.Kind.BANNER_PATTERN,id);
                if(definition.isPresent())try(var reader=new com.google.gson.stream.JsonReader(new java.io.StringReader(definition.orElseThrow()))) {
                    reader.setStrictness(com.google.gson.Strictness.STRICT);reader.setNestingLimit(32);
                    var parsed=com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                    if(reader.peek()!=com.google.gson.stream.JsonToken.END_DOCUMENT)throw new com.google.gson.JsonParseException("尾随图案声明");
                    if(parsed.has("asset_id")){id=ResourceLocation.parse(parsed.get("asset_id").getAsString());path=id.path();}
                }
            }catch(RuntimeException invalid){if(invalid instanceof java.util.concurrent.CancellationException)throw invalid;note(state,"旗帜图案声明无效："+id);continue;}
            var overlay=source.read(ResourceLocation.Kind.TEXTURE,new ResourceLocation(id.namespace(),"entity/banner/"+path));
            if(overlay.isPresent())image=BlockTextureSet.composite(image,-1,tintImage(TextureAtlas.decode(overlay.orElseThrow(),false),dye(dye)),-1);
            else note(state,"旗帜图案资源缺失："+id);
        }
        return store("banner/"+entity.fingerprint()+"/"+color,image);
    }
    private static java.awt.image.BufferedImage tintImage(java.awt.image.BufferedImage image,int color) {
        var copy=new java.awt.image.BufferedImage(image.getWidth(),image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){int p=image.getRGB(x,y),rgb=p&0xFF000000;for(int shift:new int[]{0,8,16})rgb|=(((p>>>shift)&255)*((color>>>shift)&255)/255)<<shift;copy.setRGB(x,y,rgb);}return copy;
    }
    private ResourceLocation store(String key,java.awt.image.BufferedImage image)throws IOException {
        var id=new ResourceLocation("mcwe_generated","entity/"+key);if(generated.containsKey(id))return id;
        if(generated.size()>=1024)throw new IOException("动态外观纹理超过 1024 项");
        var output=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",output);
        if(generated.values().stream().mapToLong(data->data.length).sum()+output.size()>16L*1024*1024)throw new IOException("动态外观 PNG 超过 16 MiB");
        generated.put(id,output.toByteArray());return id;
    }
    private static String plainText(String text) {
        if(text.length()>8192)return "";
        try(var reader=new com.google.gson.stream.JsonReader(new java.io.StringReader(text))){
            reader.setStrictness(com.google.gson.Strictness.STRICT);reader.setNestingLimit(32);
            return component(com.google.gson.JsonParser.parseReader(reader),0);
        }catch(IOException|RuntimeException invalid){return text;}
    }
    private static String component(com.google.gson.JsonElement value,int depth) {
        if(depth>16)return "";if(value.isJsonPrimitive())return value.getAsString();
        StringBuilder text=new StringBuilder();
        if(value.isJsonArray()){for(var child:value.getAsJsonArray())text.append(component(child,depth+1));}
        else if(value.isJsonObject()){var object=value.getAsJsonObject();if(object.has("text"))text.append(component(object.get("text"),depth+1));if(object.has("extra"))text.append(component(object.get("extra"),depth+1));}
        return text.length()>8192?text.substring(0,8192):text.toString();
    }
    private static String legacyDye(int index){String[] colors={"white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black"};return colors[Math.floorMod(index,16)];}

    private static Part part(ResourceLocation id,List<Face> faces,int x,int y,String type){return new Part(id,x,y,false,faces,type);}
    private static int facing(String facing){if(facing==null)return 0;return switch(facing){case "south"->180;case "west"->270;case "east"->90;default->0;};}
    private static int number(BlockState state,String key,int fallback){try{return Integer.parseInt(state.properties().getOrDefault(key,""+fallback));}catch(NumberFormatException e){return fallback;}}
    private static void rotateAll(List<Face> faces,double angle){for(int i=0;i<faces.size();i++){var f=faces.get(i);faces.set(i,new Face(f.direction(),f.texture(),f.uv(),f.rotation(),f.tintIndex(),f.cullFace(),f.layer(),f.from(),f.to(),new ElementRotation(new Vec3(8,8,8),"y",angle,false),f.shade()));}}
    private static void tint(List<Face> faces,int start,int color){for(int i=start;i<faces.size();i++){var f=faces.get(i);faces.set(i,new Face(f.direction(),f.texture(),f.uv(),f.rotation(),0x1000000|color,f.cullFace(),f.layer(),f.from(),f.to(),f.elementRotation(),f.shade()));}}
    static int dye(String name){return switch(name){case "orange"->0xF9801D;case "magenta"->0xC74EBD;case "light_blue"->0x3AB3DA;case "yellow"->0xFED83D;case "lime"->0x80C71F;case "pink"->0xF38BAA;case "gray"->0x474F52;case "light_gray"->0x9D9D97;case "cyan"->0x169C9C;case "purple"->0x8932B8;case "blue"->0x3C44AA;case "brown"->0x835432;case "green"->0x5E7C16;case "red"->0xB02E26;case "black"->0x1D1D21;default->0xF9FFFE;};}
    private static void box(List<Face> out,String texture,int tw,int th,double x0,double y0,double z0,double x1,double y1,double z1,double u,double v,double dx,double dy,double dz) {
        double[][] uv={{u+dz+dx,v,u+dz+2*dx,v+dz},{u+dz,v,u+dz+dx,v+dz},
                {u+dz,v+dz,u+dz+dx,v+dz+dy},{u+2*dz+dx,v+dz,u+2*dz+2*dx,v+dz+dy},
                {u,v+dz,u+dz,v+dz+dy},{u+dz+dx,v+dz,u+2*dz+dx,v+dz+dy}};
        Direction[] directions={Direction.DOWN,Direction.UP,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST};
        int element=out.size()/6;
        for(int i=0;i<6;i++)out.add(new Face(directions[i],ResourceLocation.parse(texture),new Uv(uv[i][0]*16/tw,uv[i][1]*16/th,uv[i][2]*16/tw,uv[i][3]*16/th),0,-1,null,element,new Vec3(x0,y0,z0),new Vec3(x1,y1,z1),null,true));
    }
}
