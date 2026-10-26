package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;

/** Data-only bounded OBJ/MTL interpretation. Resource IDs never become host file paths. */
final class ObjModelReader {
    static final class Problem extends IOException {
        final Reason reason;
        Problem(Reason reason,String detail){super(detail);this.reason=reason;}
    }
    private static final int MAX_VERTICES=65536,MAX_FACES=8192;
    private static final class Material {
        String texture;int rgb=0xFFFFFF;float opacity=1;boolean emissive;
    }
    private final BlockResourceSource source;
    private final List<Source> trace;
    private final Map<String,String> textures;
    ObjModelReader(BlockResourceSource source,List<Source> trace,Map<String,String> textures){this.source=source;this.trace=trace;this.textures=textures;}
    List<Face> read(JsonObject definition)throws IOException {
        var model=asset(required(definition,"model"));if(!model.path().endsWith(".obj"))throw problem(Reason.INVALID_MODEL,"OBJ 引用必须以 .obj 结尾");
        boolean flip=bool(definition,"flip_v",false),shade=bool(definition,"shade_quads",true);
        String override=definition.has("mtl_override")?definition.get("mtl_override").getAsString():null;
        Map<String,Material> materials=new HashMap<>();
        if(override!=null)materials.putAll(materials(relative(model,override)));
        var vertices=new ArrayList<Vec3>();var uv=new ArrayList<TexCoord>();int normals=0;var faces=new ArrayList<Face>();
        String materialName="",group="";Map<String,Boolean> visibility=new HashMap<>();
        if(definition.has("visibility"))for(var entry:definition.getAsJsonObject("visibility").entrySet())visibility.put(entry.getKey(),entry.getValue().getAsBoolean());
        int lineNumber=0;
        for(String raw:text(model).split("\\R")) {
            lineNumber++;if(raw.length()>65536)throw problem(Reason.LIMIT_EXCEEDED,"OBJ 单行超过预算");
            String line=raw.trim();if(line.isEmpty()||line.startsWith("#"))continue;String[] fields=line.split("\\s+");
            try {
                switch(fields[0]) {
                    case "v" -> {
                        if(vertices.size()>=MAX_VERTICES)throw problem(Reason.LIMIT_EXCEEDED,"OBJ 顶点超过 65536");
                        if(fields.length>5&&!fields[5].startsWith("#"))throw problem(Reason.UNSUPPORTED_MODEL,"OBJ 顶点色扩展暂未支持");
                        double w=fields.length>4&&!fields[4].startsWith("#")?number(fields[4]):1;if(Math.abs(w)<1e-9)throw problem(Reason.INVALID_MODEL,"OBJ 齐次坐标为零");
                        double x=number(fields[1])/w,y=number(fields[2])/w,z=number(fields[3])/w;
                        if(Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z)))>32)throw problem(Reason.LIMIT_EXCEEDED,"OBJ 坐标超过 32 方块预算");
                        vertices.add(new Vec3(x*16,y*16,z*16));
                    }
                    case "vt" -> {if(uv.size()>=MAX_VERTICES)throw problem(Reason.LIMIT_EXCEEDED,"OBJ UV 数量超限");double u=number(fields[1]),v=fields.length>2&&!fields[2].startsWith("#")?number(fields[2]):0;if(Math.abs(u)>16||Math.abs(v)>16)throw problem(Reason.LIMIT_EXCEEDED,"OBJ UV 超过重复采样范围");uv.add(new TexCoord(u,flip?1-v:v));}
                    case "vn" -> {number(fields[1]);number(fields[2]);number(fields[3]);if(++normals>MAX_VERTICES)throw problem(Reason.LIMIT_EXCEEDED,"OBJ 法线数量超限");}
                    case "mtllib" -> {if(override==null)for(int i=1;i<fields.length;i++){if(fields[i].startsWith("#"))break;if(i>16)throw problem(Reason.LIMIT_EXCEEDED,"OBJ 材质库超过 16 项");materials.putAll(materials(relative(model,fields[i])));}}
                    case "usemtl" -> materialName=line.substring(6).trim();
                    case "o","g" -> group=fields.length>1?fields[1]:"";
                    case "s" -> { /* Preview uses face normals, independent of smoothing groups. */ }
                    case "f" -> {
                        if(!visibility.getOrDefault(group,true))continue;
                        int count=1;while(count<fields.length&&!fields[count].startsWith("#"))count++;count--;
                        if(count<3||count>64)throw problem(Reason.LIMIT_EXCEEDED,"OBJ 多边形顶点须为 3..64");
                        List<Vec3> points=new ArrayList<>();List<TexCoord> coords=new ArrayList<>();
                        for(int i=1;i<=count;i++) {
                            String[] indices=fields[i].split("/",-1);if(indices.length>3)throw problem(Reason.UNSUPPORTED_MODEL,"OBJ 顶点色扩展暂未支持");
                            points.add(vertices.get(index(indices[0],vertices.size())));
                            coords.add(indices.length>1&&!indices[1].isEmpty()?uv.get(index(indices[1],uv.size())):new TexCoord(i==1||i==4?0:1,i<3?1:0));
                            if(indices.length>2&&!indices[2].isEmpty())index(indices[2],normals);
                        }
                        var mat=materials.get(materialName);if(mat==null)mat=new Material();
                        ResourceLocation texture=texture(mat.texture==null?"#particle":mat.texture);
                        Source reference=new Source(ResourceLocation.Kind.TEXTURE,texture);if(!trace.contains(reference))trace.add(reference);
                        int tint=mat.rgb==0xFFFFFF?-1:0x1000000|mat.rgb;
                        if(count==4)add(faces,points,coords,texture,tint,mat.opacity,shade&&!mat.emissive);
                        else {
                            if(count>4&&!convex(points))throw problem(Reason.UNSUPPORTED_MODEL,"OBJ 凹多边形需先三角化");
                            for(int i=1;i<count-1;i++)add(faces,List.of(points.getFirst(),points.get(i),points.get(i+1),points.get(i+1)),List.of(coords.getFirst(),coords.get(i),coords.get(i+1),coords.get(i+1)),texture,tint,mat.opacity,shade&&!mat.emissive);
                        }
                    }
                    default -> throw problem(Reason.UNSUPPORTED_MODEL,"OBJ 指令未支持："+fields[0]);
                }
            }catch(IndexOutOfBoundsException|IllegalArgumentException invalid){throw problem(Reason.INVALID_MODEL,"OBJ 第 "+lineNumber+" 行数据无效："+invalid.getMessage());}
        }
        return List.copyOf(faces);
    }
    private static void add(List<Face> faces,List<Vec3> vertices,List<TexCoord> uv,ResourceLocation texture,int tint,float opacity,boolean shade)throws Problem {
        if(faces.size()>=MAX_FACES)throw problem(Reason.LIMIT_EXCEEDED,"OBJ 面超过 8192");
        faces.add(new Face(Direction.UP,texture,new Uv(0,0,16,16),0,tint,null,faces.size(),new Vec3(0,0,0),new Vec3(16,16,16),null,shade,vertices,uv,opacity));
    }
    private Map<String,Material> materials(ResourceLocation id)throws IOException {
        if(!id.path().endsWith(".mtl"))throw problem(Reason.INVALID_MODEL,"材质库必须以 .mtl 结尾");
        Map<String,Material> result=new HashMap<>();Material current=null;
        for(String raw:text(id).split("\\R")) {
            String line=raw.trim();if(line.isEmpty()||line.startsWith("#"))continue;String[] fields=line.split("\\s+");
            try {
                if(fields[0].equals("newmtl")){if(result.size()>=1024)throw problem(Reason.LIMIT_EXCEEDED,"MTL 材质超过 1024");current=new Material();result.put(line.substring(6).trim(),current);continue;}
                if(current==null)continue;
                switch(fields[0]) {
                    case "map_Kd" -> {if(fields.length!=2||fields[1].startsWith("-"))throw problem(Reason.UNSUPPORTED_MODEL,"MTL map_Kd 参数暂未支持");current.texture=fields[1];}
                    case "Kd" -> {int rgb=0;for(int i=1;i<=3;i++){double color=number(fields[i]);if(color<0||color>1)throw problem(Reason.INVALID_MODEL,"MTL Kd 超出 0..1");rgb=(rgb<<8)|(int)Math.round(color*255);}current.rgb=rgb;}
                    case "d","Tr" -> {double alpha=number(fields[1]);if(alpha<0||alpha>1)throw problem(Reason.INVALID_MODEL,"MTL 透明度超出 0..1");current.opacity=(float)(fields[0].equals("Tr")?1-alpha:alpha);}
                    case "Ke" -> current.emissive=number(fields[1])>0||number(fields[2])>0||number(fields[3])>0;
                    case "Ka","Ks","Ns","Ni","illum" -> { /* Specular/ambient lighting is outside this directional preview. */ }
                    default -> throw problem(Reason.UNSUPPORTED_MODEL,"MTL 指令未支持："+fields[0]);
                }
            }catch(IndexOutOfBoundsException|IllegalArgumentException invalid){throw problem(Reason.INVALID_MODEL,"MTL 数据无效："+invalid.getMessage());}
        }
        return result;
    }
    private ResourceLocation texture(String value)throws Problem {
        Set<String> seen=new HashSet<>();if(textures.containsKey(value))value="#"+value;
        while(value.startsWith("#")){String key=value.substring(1);if(!seen.add(key)||seen.size()>128)throw problem(Reason.REFERENCE_CYCLE,"OBJ 纹理引用循环");value=textures.get(key);if(value==null)throw problem(Reason.MISSING_RESOURCE,"OBJ 未定义纹理变量："+key);}
        try {if(value.startsWith("textures/")&&value.endsWith(".png"))value=value.substring(9,value.length()-4);return ResourceLocation.parse(value);}
        catch(IllegalArgumentException invalid){throw problem(Reason.INVALID_MODEL,"OBJ 纹理资源 ID 无效");}
    }
    private String text(ResourceLocation id)throws IOException {
        trace.add(new Source(ResourceLocation.Kind.RAW_ASSET,id));var bytes=source.read(ResourceLocation.Kind.RAW_ASSET,id).orElseThrow(()->problem(Reason.MISSING_RESOURCE,"缺少模型文件："+id));
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException invalid){throw problem(Reason.INVALID_MODEL,"OBJ/MTL 不是有效 UTF-8");}
    }
    private static ResourceLocation relative(ResourceLocation model,String value)throws Problem {
        if(value.indexOf(':')>=0)return asset(value);
        String parent=model.path().substring(0,model.path().lastIndexOf('/')+1);Deque<String> path=new ArrayDeque<>();
        for(String segment:(parent+value).split("/")){if(segment.equals("..")){if(path.isEmpty())throw problem(Reason.INVALID_MODEL,"材质库引用越出命名空间");path.removeLast();}else if(!segment.equals("."))path.addLast(segment);}
        return asset(model.namespace()+":"+String.join("/",path));
    }
    private static ResourceLocation asset(String value)throws Problem {
        try{var id=ResourceLocation.parse(value);if(!id.path().startsWith("models/"))throw problem(Reason.INVALID_MODEL,"OBJ/MTL 引用必须位于 models/ 下");return id;}
        catch(IllegalArgumentException invalid){throw problem(Reason.INVALID_MODEL,"OBJ/MTL 资源 ID 无效");}
    }
    private static int index(String value,int size)throws Problem {int index;try{index=Integer.parseInt(value);}catch(NumberFormatException invalid){throw problem(Reason.INVALID_MODEL,"OBJ 索引不是整数");}int actual=index<0?size+index:index-1;if(index==0||actual<0||actual>=size)throw problem(Reason.INVALID_MODEL,"OBJ 索引越界");return actual;}
    private static double number(String value)throws Problem {double result;try{result=Double.parseDouble(value);}catch(NumberFormatException invalid){throw problem(Reason.INVALID_MODEL,"OBJ/MTL 数值无效");}if(!Double.isFinite(result))throw problem(Reason.INVALID_MODEL,"OBJ/MTL 数值不是有限值");return result;}
    private static String required(JsonObject object,String key)throws Problem {if(!object.has(key)||!object.get(key).isJsonPrimitive()||!object.getAsJsonPrimitive(key).isString())throw problem(Reason.INVALID_MODEL,"OBJ 缺少字符串 "+key);return object.get(key).getAsString();}
    private static boolean bool(JsonObject object,String key,boolean fallback)throws Problem {if(!object.has(key))return fallback;if(!object.get(key).isJsonPrimitive()||!object.getAsJsonPrimitive(key).isBoolean())throw problem(Reason.INVALID_MODEL,"OBJ 布尔字段无效："+key);return object.get(key).getAsBoolean();}
    private static boolean convex(List<Vec3> points) {
        var a=points.get(0);var b=points.get(1);var c=points.get(2);double nx=(b.y()-a.y())*(c.z()-a.z())-(b.z()-a.z())*(c.y()-a.y()),ny=(b.z()-a.z())*(c.x()-a.x())-(b.x()-a.x())*(c.z()-a.z()),nz=(b.x()-a.x())*(c.y()-a.y())-(b.y()-a.y())*(c.x()-a.x());
        for(int i=0;i<points.size();i++){a=points.get(i);b=points.get((i+1)%points.size());c=points.get((i+2)%points.size());double x=(b.y()-a.y())*(c.z()-b.z())-(b.z()-a.z())*(c.y()-b.y()),y=(b.z()-a.z())*(c.x()-b.x())-(b.x()-a.x())*(c.z()-b.z()),z=(b.x()-a.x())*(c.y()-b.y())-(b.y()-a.y())*(c.x()-b.x());if(x*nx+y*ny+z*nz< -1e-8)return false;}return true;
    }
    private static Problem problem(Reason reason,String text){return new Problem(reason,text);}
}
