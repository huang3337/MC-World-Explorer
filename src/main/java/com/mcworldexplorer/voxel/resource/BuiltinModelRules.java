package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.BlockModelResolver.*;

/** Format rules shared by all namespaces and resource containers. */
final class BuiltinModelRules {
    @FunctionalInterface private interface Reader {List<Part> read(ModelRule.Context context)throws IOException,ModelRule.Failure;}
    private record Rule(ResourceLocation id,Set<ResourceLocation> loaders,Set<String> fields,Reader reader)implements ModelRule {
        public String version(){return "2";}
        public RootTransformMode rootTransformMode(){return RootTransformMode.NONE;}
        public boolean inheritRenderType(){return true;}
        public List<Part> resolve(Context context)throws IOException,Failure{return reader.read(context);}
    }
    static final ModelRuleRegistry REGISTRY=new ModelRuleRegistry(List.of(
        rule("obj",Set.of("model","flip_v","mtl_override","automatic_culling","shade_quads","emissive_ambient","visibility"),BuiltinModelRules::obj)
    ));
    private static ModelRule rule(String type,Set<String> fields,Reader reader) {
        Set<ResourceLocation> ids=new HashSet<>();for(String namespace:List.of("forge","neoforge","porting_lib"))ids.add(new ResourceLocation(namespace,type));
        return new Rule(new ResourceLocation("mcwe",type),Set.copyOf(ids),fields,reader);
    }
    private static List<Part> obj(ModelRule.Context c)throws IOException,ModelRule.Failure {
        if(c.uvLock()){
            String loader=c.definition().has("loader")?c.definition().get("loader").getAsString():"";
            if(!ModelDefinitionProtocol.objKeepsExplicitUv(loader,c.resources().platform(c.model().namespace()).orElse(""),c.resources().baseClientVersion().orElse("")))
                throw failure(Reason.UNSUPPORTED_MODEL,"OBJ uvlock 当前仅核对Forge 1.20.1与NeoForge 1.21.1的显式UV语义");
            c.note("OBJ加载器使用文件中的逐顶点UV，不应用blockstate uvlock");
        }
        if(c.textureBindings().values().stream().anyMatch(TextureBinding::forceTranslucent))throw failure(Reason.UNSUPPORTED_MODEL,"OBJ强制透明贴图绑定尚需加载器材质适配");
        try {
            for(String key:List.of("automatic_culling","emissive_ambient"))if(c.definition().has(key)){var value=c.definition().get(key);if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw failure(Reason.INVALID_MODEL,"OBJ布尔字段无效："+key);}
            var faces=new ObjModelReader(c.resources(),new ArrayList<>(),c.textures()).read(c.definition());
            c.note("OBJ 使用静态几何与平面法线，保留所有面；环境/镜面光照与运行时部件变化未还原");return List.of(new Part(c.model(),c.xRotation(),c.yRotation(),false,faces,c.renderType()));
        }catch(ObjModelReader.Problem problem){throw failure(problem.reason,problem.getMessage());}
    }
    private static ModelRule.Failure failure(Reason reason,String message){return new ModelRule.Failure(reason,message);}
}
