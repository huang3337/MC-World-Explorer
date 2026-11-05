package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import org.joml.*;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;

/** Data-only Forge/NeoForge root TRSR codec. Matrices use block units; output keeps the host's face budgets. */
final class RootModelTransform {
    private static final Set<String> FIELDS=Set.of("origin","translation","rotation","left_rotation","scale","right_rotation","post-rotation");
    private RootModelTransform() { }

    static Matrix4d read(JsonElement value)throws ModelRule.Failure {
        if(value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()&&value.getAsString().equals("identity"))return new Matrix4d();
        if(value!=null&&value.isJsonArray())return matrix(value);
        if(value==null||!value.isJsonObject())throw invalid("根变换必须是对象、矩阵或identity");
        var object=value.getAsJsonObject();
        if(object.has("matrix")){
            if(object.size()!=1)throw invalid("根变换matrix不能与其他字段混用");
            return matrix(object.get("matrix"));
        }
        for(String field:object.keySet())if(!FIELDS.contains(field))throw invalid("未知根变换字段："+field);
        if(object.has("rotation")&&object.has("left_rotation")||object.has("right_rotation")&&object.has("post-rotation"))throw invalid("根变换旋转别名冲突");
        Vector3d origin=new Vector3d(1),translation=new Vector3d(),scale=new Vector3d(1);
        if(object.has("origin")){
            var entry=object.get("origin");
            if(entry.isJsonPrimitive()&&entry.getAsJsonPrimitive().isString()){
                origin=switch(entry.getAsString()){case "corner"->new Vector3d();case "center"->new Vector3d(.5);case "opposing-corner"->new Vector3d(1);default->throw invalid("未知根变换原点");};
            }else origin=vector(entry);
        }
        if(object.has("translation"))translation=vector(object.get("translation"));
        if(object.has("scale")){var entry=object.get("scale");scale=entry.isJsonArray()?vector(entry):new Vector3d(number(entry));}
        var left=rotation(object.has("rotation")?object.get("rotation"):object.get("left_rotation"));
        var right=rotation(object.has("right_rotation")?object.get("right_rotation"):object.get("post-rotation"));
        return ModelPlacement.copy(new Matrix4d().translate(origin).translate(translation).rotate(left).scale(scale).rotate(right).translate(new Vector3d(origin).negate()));
    }

    private static Matrix4d matrix(JsonElement value)throws ModelRule.Failure {
        if(value==null||!value.isJsonArray()||value.getAsJsonArray().size()!=3)throw invalid("根变换矩阵需要3行4列");
        var matrix=new Matrix4d();
        for(int row=0;row<3;row++){
            var columns=value.getAsJsonArray().get(row);
            if(!columns.isJsonArray()||columns.getAsJsonArray().size()!=4)throw invalid("根变换矩阵需要3行4列");
            for(int column=0;column<4;column++)matrix.set(column,row,number(columns.getAsJsonArray().get(column)));
        }
        return ModelPlacement.copy(matrix);
    }
    private static Vector3d vector(JsonElement value)throws ModelRule.Failure {
        if(value==null||!value.isJsonArray()||value.getAsJsonArray().size()!=3)throw invalid("根变换向量需要3个数值");
        var v=value.getAsJsonArray();return new Vector3d(number(v.get(0)),number(v.get(1)),number(v.get(2)));
    }
    private static Quaterniond rotation(JsonElement value)throws ModelRule.Failure {
        if(value==null)return new Quaterniond();
        if(value.isJsonObject())return axis(value.getAsJsonObject());
        if(!value.isJsonArray())throw invalid("根变换旋转格式无效");
        var array=value.getAsJsonArray();
        if(array.size()>32)throw new ModelRule.Failure(Reason.LIMIT_EXCEEDED,"根变换旋转序列超过32项");
        if(array.size()>0&&array.get(0).isJsonObject()){
            var result=new Quaterniond();for(var entry:array){if(!entry.isJsonObject())throw invalid("根变换旋转序列必须全为单轴对象");result.mul(axis(entry.getAsJsonObject()));}return result;
        }
        if(array.size()==3){var v=vector(array);return new Quaterniond().rotationXYZ(java.lang.Math.toRadians(v.x),java.lang.Math.toRadians(v.y),java.lang.Math.toRadians(v.z));}
        if(array.size()==4){var q=new Quaterniond(number(array.get(0)),number(array.get(1)),number(array.get(2)),number(array.get(3)));if(q.lengthSquared()<1e-12)throw invalid("根变换四元数退化");return q;}
        throw invalid("根变换旋转需要Euler向量、四元数或单轴对象序列");
    }
    private static Quaterniond axis(JsonObject value)throws ModelRule.Failure {
        if(value.size()!=1)throw invalid("根变换单轴旋转需要一个轴");
        var entry=value.entrySet().iterator().next();double angle=java.lang.Math.toRadians(number(entry.getValue()));
        // JOML 1.10.5 Quaterniond.rotationX writes the cosine to z, not w. Use the XYZ constructor.
        return switch(entry.getKey()){case "x"->new Quaterniond().rotationXYZ(angle,0,0);case "y"->new Quaterniond().rotationXYZ(0,angle,0);case "z"->new Quaterniond().rotationXYZ(0,0,angle);default->throw invalid("根变换旋转轴无效");};
    }
    private static double number(JsonElement value)throws ModelRule.Failure {
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw invalid("根变换需要有限数值");
        double number=value.getAsDouble();if(!Double.isFinite(number)||java.lang.Math.abs(number)>1_000_000)throw invalid("根变换数值无效或超限");return number;
    }

    static List<Part> apply(List<Part> parts,Matrix4d root,int x,int y,boolean uvLock,ModelRule.RootTransformMode mode)throws ModelRule.Failure {
        if(root.equals(new Matrix4d(),1e-12))return parts;
        if(mode==ModelRule.RootTransformMode.COMPOSITE&&uvLock)throw new ModelRule.Failure(Reason.UNSUPPORTED_MODEL,"合入模型状态的根变换uvlock尚需完整变换UV协议");
        var state=new Matrix4d().translate(.5,.5,.5).rotateY(java.lang.Math.toRadians(-y)).rotateX(java.lang.Math.toRadians(-x)).translate(-.5,-.5,-.5);
        var post=ModelPlacement.copy(new Matrix4d(state).mul(root).mul(new Matrix4d(state).invert()));
        var result=new ArrayList<Part>();
        for(var part:parts){
            if(mode==ModelRule.RootTransformMode.COMPOSITE&&!preservesDirections(post)&&part.faces().stream().anyMatch(face->face.cullFace()!=null&&!face.vertices().isEmpty()))
                throw new ModelRule.Failure(Reason.UNSUPPORTED_MODEL,"嵌套非正交根变换的面剔除需要保留未量化方向");
            var transformed=GeometryTransform.transform(part,post);var faces=new ArrayList<Face>();
            for(int i=0;i<part.faces().size();i++){
                var face=transformed.faces().get(i);var cull=ModelGeometry.bake(part.faces().get(i),part).cull();
                if(cull!=null&&mode==ModelRule.RootTransformMode.COMPOSITE){double[] n=ModelGeometry.normal(cull);var direction=post.transformDirection(new Vector3d(n));cull=nearest(direction);}
                faces.add(new Face(face.direction(),face.texture(),face.uv(),face.rotation(),face.tintIndex(),cull,face.layer(),face.from(),face.to(),face.elementRotation(),face.shade(),face.vertices(),face.vertexUv(),face.opacity(),face.material()));
            }
            result.add(new Part(part.id(),0,0,false,faces,part.renderType()));
        }
        return List.copyOf(result);
    }
    private static boolean preservesDirections(Matrix4d matrix){
        double length=-1;Set<Integer> axes=new HashSet<>();
        for(int column=0;column<3;column++){
            int axis=-1;double magnitude=0;
            for(int row=0;row<3;row++){double value=java.lang.Math.abs(matrix.get(column,row));if(value>1e-9){if(axis!=-1)return false;axis=row;magnitude=value;}}
            if(axis<0||!axes.add(axis)||length>=0&&java.lang.Math.abs(length-magnitude)>1e-9)return false;length=magnitude;
        }
        return true;
    }
    private static Direction nearest(Vector3d v){double x=java.lang.Math.abs(v.x),y=java.lang.Math.abs(v.y),z=java.lang.Math.abs(v.z);return y>=x&&y>=z?(v.y<0?Direction.DOWN:Direction.UP):z>=x?(v.z<0?Direction.NORTH:Direction.SOUTH):(v.x<0?Direction.WEST:Direction.EAST);}
    private static ModelRule.Failure invalid(String message){return new ModelRule.Failure(Reason.INVALID_MODEL,message);}
}
