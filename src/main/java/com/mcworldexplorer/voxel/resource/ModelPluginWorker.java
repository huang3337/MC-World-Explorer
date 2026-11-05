package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static com.mcworldexplorer.voxel.resource.ModelPluginWire.*;

/** Dedicated adapter JVM entrypoint. Minecraft/client archives are never on this classpath. */
public final class ModelPluginWorker {
    private final DataInputStream input;
    private final DataOutputStream output;
    private final Map<ResourceLocation,ModelRule> rules=new HashMap<>();
    private long callbackSequence;
    private ModelPluginWorker(DataInputStream input,DataOutputStream output){this.input=input;this.output=output;}
    public static void main(String[] arguments)throws Exception {
        // Keep a private protocol stream before redirecting adapter prints away from stdout.
        var output=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out)));
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err),true,StandardCharsets.UTF_8));
        var input=new DataInputStream(new BufferedInputStream(new FileInputStream(FileDescriptor.in)));
        ModelPlugin plugin=null;
        try{
            if(arguments.length!=3)throw new IllegalArgumentException("Expected package, hash and host version");
            var archive=ExternalModelPluginPackage.inspect(Path.of(arguments[0]),arguments[2]);
            if(!archive.sha256().equals(arguments[1]))throw new IOException("插件工作进程包身份不符");
            try(var loader=new ApiLoader(archive)){
                Class<?> entry=Class.forName(archive.manifest().entrypoint(),true,loader);
                plugin=(ModelPlugin)entry.getConstructor().newInstance();
                try {
                if(!archive.manifest().plugin().equals(plugin.descriptor()))throw new IOException("插件入口描述与包清单不一致");
                var registry=new ModelRuleRegistry(plugin.rules());
                var worker=new ModelPluginWorker(input,output);for(var rule:registry.rules())worker.rules.put(rule.id(),rule);
                var ready=message("ready");ready.add("rules",JSON.toJsonTree(registry.rules().stream().map(ModelPluginRuleInfo::of).toList()));
                write(output,ready);
                while(true){var request=read(input);if("close".equals(request.get("kind").getAsString()))break;worker.execute(request);}
                }finally{ModelPlugin closing=plugin;plugin=null;if(closing instanceof AutoCloseable closeable)closeable.close();}
            }
        }catch(Throwable failure){try{write(output,error(failure));}catch(IOException ignored){} }
        finally{if(plugin instanceof AutoCloseable closeable)try{closeable.close();}catch(Exception ignored){}}
    }
    private void execute(JsonObject request)throws IOException {
        JsonObject response;
        try{
            if(!"request".equals(request.get("kind").getAsString()))throw new IOException("插件请求类型无效");
            String operation=request.get("operation").getAsString();
            JsonElement result;
            ModelRule rule=Objects.requireNonNull(rules.get(JSON.fromJson(request.get("rule"),ResourceLocation.class)),"插件规则未登记");
            if(!operation.equals("resolve"))throw new IOException("未知插件操作："+operation);
            result=JSON.toJsonTree(rule.resolve(proxy(ModelRule.Context.class)));
            response=message("result");response.add("value",result);
        }catch(Throwable failure){response=error(failure);}
        response.add("sequence",request.get("sequence"));write(output,response);
    }
    private <T> T proxy(Class<T> contract){
        return contract.cast(java.lang.reflect.Proxy.newProxyInstance(ModelRule.class.getClassLoader(),new Class<?>[]{contract},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class)return switch(method.getName()){case "toString"->"Borrowed adapter service: "+contract.getSimpleName();case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->throw new IllegalStateException();};
            if((contract==ModelRule.Context.class)&&method.getName().equals("resources"))return proxy(BlockResourceSource.class);
            long sequence=++callbackSequence;var request=message("callback");request.addProperty("sequence",sequence);
            request.addProperty("target",contract==ModelRule.Context.class?"context":"source");request.addProperty("method",methodId(method));
            var arguments=new JsonArray();for(int i=0;args!=null&&i<args.length;i++)arguments.add(JSON.toJsonTree(args[i],method.getGenericParameterTypes()[i]));request.add("arguments",arguments);write(output,request);
            while(true){
                var response=read(input);
                // A host callback can recursively resolve the same adapter. Service it before the outer reply.
                if("request".equals(response.get("kind").getAsString())){execute(response);continue;}
                if(!response.has("sequence")||response.get("sequence").getAsLong()!=sequence)throw new IOException("插件回调序号不匹配");
                check(response);return method.getReturnType()==void.class?null:JSON.fromJson(response.get("value"),method.getGenericReturnType());
            }
        }));
    }
    /** API visibility boundary only. Process isolation is not an OS permission sandbox. */
    private static final class ApiLoader extends ClassLoader implements AutoCloseable {
        private static final Set<String> API=Set.of("ResourceLocation","FaceMaterial","TextureBinding","ModelParts","ModelRule","ModelPlugin","ModelPluginManifest","BlockResourceSource","BlockModelResolver","ClientResourceArchive","ModelBlockContext");
        private final Map<String,byte[]> content=new HashMap<>();
        ApiLoader(ExternalModelPluginPackage archive)throws IOException {
            super(ModelRule.class.getClassLoader());long total=0;
            try(var zip=new java.util.zip.ZipFile(archive.file().toFile())){
                var entries=zip.entries();while(entries.hasMoreElements()){
                    var entry=entries.nextElement();if(entry.isDirectory())continue;
                    byte[] bytes;try(var stream=zip.getInputStream(entry)){bytes=stream.readNBytes(ExternalModelPluginPackage.MAX_ENTRY_BYTES+1);}
                    if(bytes.length>ExternalModelPluginPackage.MAX_ENTRY_BYTES||(total+=bytes.length)>ExternalModelPluginPackage.MAX_EXPANDED_BYTES)throw new IOException("插件内存映像超过预算");
                    content.put(entry.getName(),bytes);
                }
            }
            archive.validate();
        }
        @Override protected Class<?> findClass(String name)throws ClassNotFoundException {
            byte[] bytes=content.get(name.replace('.','/')+".class");if(bytes==null)throw new ClassNotFoundException(name);return defineClass(name,bytes,0,bytes.length);
        }
        @Override public InputStream getResourceAsStream(String name){byte[] bytes=content.get(name);return bytes==null?null:new ByteArrayInputStream(bytes);}
        @Override public URL getResource(String name){
            byte[] bytes=content.get(name);if(bytes==null)return null;
            try{return URL.of(new URI("mcwe-plugin",null,"/"+name,null),new URLStreamHandler(){protected URLConnection openConnection(URL url){return new URLConnection(url){public void connect(){}public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}public int getContentLength(){return bytes.length;}};}});}
            catch(MalformedURLException|URISyntaxException invalid){return null;}
        }
        @Override public Enumeration<URL> getResources(String name){URL value=getResource(name);return Collections.enumeration(value==null?List.of():List.of(value));}
        public void close(){content.clear();}
        @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
            synchronized(getClassLoadingLock(name)){
                Class<?> type=findLoadedClass(name);
                if(type==null){
                    boolean shared=name.startsWith("java.")||name.startsWith("javax.")||name.startsWith("jdk.")||name.startsWith("com.google.gson.")||name.startsWith("net.kyori.")||name.startsWith("org.joml.")||name.startsWith("org.jetbrains.annotations.");
                    if(name.startsWith("com.mcworldexplorer.voxel.resource.")){
                        String local=name.substring("com.mcworldexplorer.voxel.resource.".length());String[] parts=local.split("\\$",2);
                        shared=API.contains(parts[0]);
                        if(parts.length==2)shared=switch(parts[0]){
                            case "BlockModelResolver" -> Set.of("Reason","Direction","Uv","Vec3","ElementRotation","TexCoord","Face","Part").contains(parts[1]);
                            case "ClientResourceArchive" -> parts[1].equals("Fingerprint");
                            case "ModelBlockContext" -> Set.of("Position","Offset","Sample").contains(parts[1]);
                            case "ModelRule" -> Set.of("Context","Failure","RootTransformMode","BiomeColor").contains(parts[1]);
                            case "BlockResourceSource" -> parts[1].equals("DimensionHeight");
                            case "ModelPlugin" -> Set.of("Descriptor","Capability").contains(parts[1]);
                            case "ResourceLocation" -> parts[1].equals("Kind");
                            default -> false;
                        };
                    }
                    if(Set.of("com.mcworldexplorer.voxel.data.BlockState","com.mcworldexplorer.voxel.data.BlockEntityData","com.mcworldexplorer.voxel.data.BlockEntityData$ModelData","com.mcworldexplorer.voxel.io.ChunkLoadStatus").contains(name))shared=true;
                    if(shared)type=getParent().loadClass(name);
                    else if(name.startsWith("com.mcworldexplorer."))throw new ClassNotFoundException("插件不可访问宿主内部类："+name);
                    else type=findClass(name);
                }
                if(resolve)resolveClass(type);return type;
            }
        }
    }
}
