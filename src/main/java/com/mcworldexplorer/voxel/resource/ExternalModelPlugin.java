package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.mcworldexplorer.voxel.resource.ModelPluginWire.*;

/** Owns one bounded adapter JVM. Host callbacks retain resource ownership and tracing. */
public final class ExternalModelPlugin implements ModelPlugin,AutoCloseable {
    private static final Map<String,Method> CONTEXT=methods(ModelRule.Context.class),SOURCE=methods(BlockResourceSource.class);
    private final ExternalModelPluginPackage archive;
    private final Process process;
    private final Thread replyReader,diagnosticReader;
    private final DataOutputStream output;
    private final BlockingQueue<Object> replies=new ArrayBlockingQueue<>(2);
    private final AtomicBoolean closed=new AtomicBoolean();
    private final List<ModelRule> rules;
    private final long timeoutNanos;
    private final StringBuilder diagnostics=new StringBuilder();
    private long sequence;
    private final String executionIdentity=UUID.randomUUID().toString();
    public ExternalModelPlugin(ExternalModelPluginPackage archive,String hostVersion,Duration timeout)throws IOException {
        this.archive=Objects.requireNonNull(archive);archive.validate();
        if(timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofMinutes(2))>0)throw new IllegalArgumentException("插件调用预算必须在0至120秒之间");timeoutNanos=timeout.toNanos();
        String classpath=System.getProperty("mcwe.plugin.workerClasspath",System.getProperty("java.class.path"));
        process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx256m","-XX:MaxMetaspaceSize=128m","-cp",classpath,
            ModelPluginWorker.class.getName(),archive.file().toString(),archive.sha256(),hostVersion).start();
        output=new DataOutputStream(new BufferedOutputStream(process.getOutputStream()));
        replyReader=Thread.ofPlatform().daemon().name("mcwe-plugin-replies").start(()->{
            try(var input=new DataInputStream(new BufferedInputStream(process.getInputStream()))){while(!closed.get())replies.put(read(input));}
            catch(Throwable failure){if(!closed.get())replies.offer(failure);}
        });
        diagnosticReader=Thread.ofPlatform().daemon().name("mcwe-plugin-diagnostics").start(()->{
            try(var input=new InputStreamReader(process.getErrorStream(),StandardCharsets.UTF_8)){char[] chars=new char[1024];int length;while((length=input.read(chars))!=-1)synchronized(diagnostics){diagnostics.append(chars,0,length);if(diagnostics.length()>8192)diagnostics.delete(0,diagnostics.length()-8192);}}
            catch(IOException ignored){}
        });
        try{
            JsonObject ready=await(System.nanoTime()+timeoutNanos);check(ready);
            if(!"ready".equals(ready.get("kind").getAsString()))throw new IOException("插件进程启动协议无效");
            List<ModelPluginRuleInfo> metadata=JSON.fromJson(ready.get("rules"),new TypeToken<List<ModelPluginRuleInfo>>(){}.getType());
            // Revalidate all metadata in the parent before exposing rules to the resolver.
            new ModelRuleRegistry(metadata);
            this.rules=metadata.stream().map(info->(ModelRule)new RemoteRule(info)).toList();
        }catch(Exception failure){close();throw failure instanceof IOException io?io:new IOException("模型插件启动失败："+failure.getMessage(),failure);}
    }
    public Descriptor descriptor(){return archive.manifest().plugin();}
    @Override public String contentFingerprint(){return archive.sha256();}
    public Collection<? extends ModelRule> rules(){return rules;}
    public boolean running(){return !closed.get()&&process.isAlive();}
    public long processId(){return process.pid();}
    public String executionIdentity(){return executionIdentity;}
    public String diagnostics(){synchronized(diagnostics){return diagnostics.toString();}}

    private synchronized JsonElement call(JsonObject request,ModelRule.Context context,BlockResourceSource source)throws IOException,ModelRule.Failure {
        if(!running())throw new IOException("插件进程已停止："+descriptor().name());
        if(Thread.currentThread().isInterrupted()){close();throw new CancellationException("插件调用已取消");}
        long id=++sequence,deadline=System.nanoTime()+timeoutNanos;request.addProperty("sequence",id);int callbacks=0;
        JsonObject completed;
        try{
            write(output,request);
            while(true){
                JsonObject response=await(deadline);String kind=response.get("kind").getAsString();
                if(kind.equals("callback")){
                    if(++callbacks>4096)throw new IOException("插件单次调用超过4096次宿主服务请求");
                    callback(response,context,source);continue;
                }
                if(!response.has("sequence")||response.get("sequence").getAsLong()!=id)throw new IOException("插件结果序号不匹配");
                if(!kind.equals("result")&&!kind.equals("failure"))throw new IOException("插件结果类型无效");completed=response;break;
            }
        }catch(CancellationException failure){close();throw failure;}
        catch(IOException failure){close();throw failure;}
        catch(RuntimeException invalid){close();throw new IOException("插件通信数据无效："+invalid.getMessage(),invalid);}
        // A completed adapter/service error is not a broken transport. Keep the worker reusable.
        check(completed);return completed.get("value");
    }
    private void callback(JsonObject request,ModelRule.Context context,BlockResourceSource source)throws IOException {
        JsonObject response;
        try{
            String target=request.get("target").getAsString();
            Map<String,Method> allowed;Object receiver;
            if(target.equals("context")){allowed=CONTEXT;receiver=Objects.requireNonNull(context,"此阶段没有模型上下文");}
            else if(target.equals("source")){allowed=SOURCE;receiver=Objects.requireNonNull(source,"此阶段没有资源源");}
            else throw new IOException("未知插件服务目标");
            Method method=Objects.requireNonNull(allowed.get(request.get("method").getAsString()),"未公开的插件服务方法");
            if(method.getReturnType()==BlockResourceSource.class)throw new IOException("资源对象不能作为通信值发送");
            JsonArray values=request.getAsJsonArray("arguments");if(values.size()!=method.getParameterCount())throw new IOException("插件服务参数数量不匹配");
            Object[] arguments=new Object[values.size()];for(int i=0;i<arguments.length;i++)arguments[i]=JSON.fromJson(values.get(i),method.getGenericParameterTypes()[i]);
            Object value=method.invoke(receiver,arguments);response=message("reply");response.add("value",method.getReturnType()==void.class?JsonNull.INSTANCE:JSON.toJsonTree(value,method.getGenericReturnType()));
        }catch(Throwable failure){response=error(failure);}
        response.add("sequence",request.get("sequence"));write(output,response);
    }
    private JsonObject await(long deadline)throws IOException {
        while(true){
            if(closed.get())throw new IOException("插件进程已释放");long remaining=deadline-System.nanoTime();
            if(remaining<=0){close();throw new IOException("模型插件 "+descriptor().name()+" 调用超时，工作进程已终止");}
            try{
                Object result=replies.poll(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(50)),TimeUnit.NANOSECONDS);
                if(result instanceof JsonObject message)return message;
                if(result instanceof Throwable failure)throw new IOException("模型插件进程通信中断",failure);
                if(!process.isAlive()&&replies.isEmpty())throw new IOException("模型插件进程已退出："+diagnostics());
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();close();throw new CancellationException("模型插件调用已取消");}
        }
    }
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        // Cancellation often arrives with this flag already set. Still perform bounded cleanup,
        // then return the interruption to the owner instead of skipping every wait immediately.
        boolean interrupted=Thread.interrupted();
        try{
            process.descendants().forEach(child->{try{child.destroyForcibly();}catch(RuntimeException ignored){}});process.destroyForcibly();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(process.isAlive()){
                long remaining=deadline-System.nanoTime();if(remaining<=0)break;
                try{process.waitFor(remaining,TimeUnit.NANOSECONDS);}catch(InterruptedException cancelled){interrupted=true;}
            }
            try{output.close();}catch(IOException ignored){}
            replyReader.interrupt();diagnosticReader.interrupt();
            try{process.getInputStream().close();process.getErrorStream().close();}catch(IOException ignored){}
            for(Thread reader:List.of(replyReader,diagnosticReader))if(reader!=Thread.currentThread()){
                deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(reader.isAlive()){
                    long remaining=deadline-System.nanoTime();if(remaining<=0)break;
                    try{TimeUnit.NANOSECONDS.timedJoin(reader,remaining);}catch(InterruptedException cancelled){interrupted=true;}
                }
            }
            replies.clear();synchronized(diagnostics){diagnostics.setLength(0);}
        }finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    private final class RemoteRule implements ModelRule {
        private final ModelPluginRuleInfo info;
        RemoteRule(ModelPluginRuleInfo info){this.info=info;}
        public ResourceLocation id(){return info.id();}public String version(){return info.version();}public int apiVersion(){return info.apiVersion();}
        public Set<ResourceLocation> loaders(){return info.loaders();}public Set<String> fields(){return info.fields();}
        public Set<String> stateSelectors(){return info.stateSelectors();}
        public Set<ModelBlockContext.Offset> neighbors(){return info.neighbors();}public boolean positionDependent(){return info.positionDependent();}
        public boolean biomeColorDependent(){return info.biomeColorDependent();}
        public boolean savedEntityDependent(){return info.savedEntityDependent();}
        public RootTransformMode rootTransformMode(){return info.rootTransformMode();}
        public boolean inheritRenderType(){return info.inheritRenderType();}
        private JsonObject request(String operation){var request=message("request");request.addProperty("operation",operation);request.add("rule",JSON.toJsonTree(id()));return request;}
        public List<BlockModelResolver.Part> resolve(Context context)throws IOException,Failure {
            return JSON.fromJson(call(request("resolve"),context,context.resources()),new TypeToken<List<BlockModelResolver.Part>>(){}.getType());
        }
    }
}
