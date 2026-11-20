package com.mcworldexplorer.voxel.resource;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mcworldexplorer.voxel.data.BlockState;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;

/** Static entity model joints in model pixels. Wax changes the block identity, oxidation changes the sprite. */
final class CopperBlockModels {
    private CopperBlockModels(){}
    static String finish(String block,String family){
        if(!block.endsWith(family))return null;
        String prefix=block.substring(0,block.length()-family.length());
        if(prefix.startsWith("waxed_"))prefix=prefix.substring(6);
        return switch(prefix){case ""->"";case "exposed_"->"_exposed";case "weathered_"->"_weathered";case "oxidized_"->"_oxidized";default->null;};
    }

    private record Joint(String name,String parent,double[] pose,double[][] cubes){}
    private static final class Poses {
        static final Map<String,List<Joint>> ALL=load();
        static Map<String,List<Joint>> load(){
            try(var input=CopperBlockModels.class.getResourceAsStream("/model-protocols/copper-golem-statue.json");
                var reader=new InputStreamReader(Objects.requireNonNull(input),StandardCharsets.UTF_8)){
                return new Gson().fromJson(reader,new TypeToken<Map<String,List<Joint>>>(){}.getType());
            }catch(IOException e){throw new UncheckedIOException(e);}
        }
    }
    static Part statue(ResourceLocation id,BlockState state,String oxidation,int facing) throws IOException {
        var pose=state.properties().getOrDefault("copper_golem_pose","standing");
        var joints=Poses.ALL.get(pose);if(joints==null)throw new IOException("无法识别铜傀儡雕像姿态："+pose);
        var sprite=ResourceLocation.parse("entity/copper_golem/copper_golem"+oxidation);
        Map<String,Matrix4d> transforms=new HashMap<>();var faces=new ArrayList<Face>();
        transforms.put("root",new Matrix4d().translation(8,0,8).scale(-1,-1,1));
        int layer=0;
        for(var joint:joints){
            var p=joint.pose();var matrix=new Matrix4d(transforms.get(joint.parent())).translate(p[0],p[1],p[2]);
            if(p.length==6)matrix.rotateZYX(p[5],p[4],p[3]);
            transforms.put(joint.name(),matrix);
            for(var cube:joint.cubes())cube(faces,sprite,matrix,cube,layer++);
        }
        return new Part(id,0,facing,false,faces,"minecraft:cutout");
    }

    private static final int[][] CORNERS={{5,4,0,1},{2,3,7,6},{1,0,3,2},{4,5,6,7},{0,4,7,3},{5,1,2,6}};
    private static void cube(List<Face> faces,ResourceLocation sprite,Matrix4d matrix,double[] box,int layer){
        double u=box[0],v=box[1],x=box[2],y=box[3],z=box[4],w=box[5],h=box[6],d=box[7],inflate=box[8];
        Vec3[] corners=new Vec3[8];
        for(int i=0;i<8;i++){
            var p=matrix.transformPosition(new Vector3d(x+(i%4==1||i%4==2?w+inflate:-inflate),
                    y+(i%4>=2?h+inflate:-inflate),z+(i>=4?d+inflate:-inflate)));
            corners[i]=new Vec3(p.x,p.y,p.z);
        }
        double[][] rects={{u+d,v,u+d+w,v+d},{u+d+w,v+d,u+d+2*w,v},{u+d,v+d,u+d+w,v+d+h},
                {u+2*d+w,v+d,u+2*d+2*w,v+d+h},{u,v+d,u+d,v+d+h},{u+d+w,v+d,u+2*d+w,v+d+h}};
        for(Direction side:Direction.values()){
            int i=side.ordinal();var rect=rects[i];var order=CORNERS[i];
            var uv=List.of(new TexCoord(rect[2]/64,rect[1]/64),new TexCoord(rect[0]/64,rect[1]/64),
                    new TexCoord(rect[0]/64,rect[3]/64),new TexCoord(rect[2]/64,rect[3]/64));
            faces.add(new Face(side,sprite,new Uv(0,0,16,16),0,-1,null,layer,corners[0],corners[6],null,true,
                    List.of(corners[order[0]],corners[order[1]],corners[order[2]],corners[order[3]]),uv,1));
        }
    }
}
