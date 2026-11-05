package com.mcworldexplorer.voxel.resource;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.kyori.adventure.nbt.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Private same-host transport. No class name from a packet is used for class loading. */
final class ModelPluginWire {
    static final int MAX_FRAME=16*1024*1024;
    static final Gson JSON=new GsonBuilder().enableComplexMapKeySerialization().serializeNulls()
        .registerTypeHierarchyAdapter(Path.class,new PathCodec())
        .registerTypeHierarchyAdapter(CompoundBinaryTag.class,new NbtCodec())
        .registerTypeAdapter(byte[].class,new BytesCodec())
        .registerTypeAdapterFactory(new OptionalCodec())
        .registerTypeAdapter(ModelBlockContext.class,new ContextCodec()).create();
    private ModelPluginWire() { }
    static void write(DataOutputStream output,JsonObject message)throws IOException {
        byte[] bytes=JSON.toJson(message).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_FRAME)throw new IOException("插件通信帧超过16MiB");output.writeInt(bytes.length);output.write(bytes);output.flush();
    }
    static JsonObject read(DataInputStream input)throws IOException {
        int length=input.readInt();if(length<2||length>MAX_FRAME)throw new IOException("插件通信帧长度无效");
        byte[] bytes=input.readNBytes(length);if(bytes.length!=length)throw new EOFException("插件通信帧不完整");
        try(var reader=new com.google.gson.stream.JsonReader(new StringReader(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()))){
            reader.setStrictness(Strictness.STRICT);JsonElement parsed=readValue(reader,0,new int[]{0});
            if(reader.peek()!=com.google.gson.stream.JsonToken.END_DOCUMENT)throw new IOException("插件通信帧含尾随内容");return parsed.getAsJsonObject();
        }
        catch(RuntimeException invalid){throw new IOException("插件通信帧无效",invalid);}
    }
    private static JsonElement readValue(com.google.gson.stream.JsonReader reader,int depth,int[] nodes)throws IOException {
        if(depth>96||++nodes[0]>1_000_000)throw new IOException("插件通信结构超过预算");
        return switch(reader.peek()){
            case BEGIN_OBJECT -> {var value=new JsonObject();reader.beginObject();while(reader.hasNext()){String key=reader.nextName();if(value.has(key))throw new IOException("插件通信重复字段");value.add(key,readValue(reader,depth+1,nodes));}reader.endObject();yield value;}
            case BEGIN_ARRAY -> {var value=new JsonArray();reader.beginArray();while(reader.hasNext())value.add(readValue(reader,depth+1,nodes));reader.endArray();yield value;}
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {reader.nextNull();yield JsonNull.INSTANCE;}
            default -> throw new IOException("插件通信值无效");
        };
    }
    static JsonObject message(String kind){var result=new JsonObject();result.addProperty("kind",kind);return result;}
    static JsonObject error(Throwable failure){
        while((failure instanceof InvocationTargetException||failure instanceof UndeclaredThrowableException)&&failure.getCause()!=null)failure=failure.getCause();
        var result=message("failure");
        result.addProperty("category",failure instanceof ModelRule.Failure?"model":failure instanceof ResourceAccessException?"resource":failure instanceof java.util.concurrent.CancellationException?"cancelled":failure instanceof IOException?"io":"invalid");
        if(failure instanceof ModelRule.Failure model)result.addProperty("reason",model.reason().name());
        if(failure instanceof ResourceAccessException resource){result.addProperty("reason",resource.reason().name());result.addProperty("scope",resource.scope().name());}
        String detail=Objects.toString(failure.getMessage(),failure.getClass().getSimpleName());result.addProperty("detail",detail.substring(0,Math.min(4096,detail.length())));return result;
    }
    static void check(JsonObject message)throws IOException,ModelRule.Failure {
        if(!"failure".equals(message.get("kind").getAsString()))return;
        String detail=message.get("detail").getAsString();
        switch(message.get("category").getAsString()){
            case "model" -> throw new ModelRule.Failure(BlockModelResolver.Reason.valueOf(message.get("reason").getAsString()),detail);
            case "cancelled" -> throw new java.util.concurrent.CancellationException(detail);
            case "resource" -> throw new ResourceAccessException(ResourceAccessException.Reason.valueOf(message.get("reason").getAsString()),detail,ResourceAccessException.Scope.valueOf(message.get("scope").getAsString()));
            case "io" -> throw new IOException(detail);
            default -> throw new ModelRule.Failure(BlockModelResolver.Reason.INVALID_MODEL,"插件执行失败："+detail);
        }
    }
    static String methodId(Method method){return method.getName()+Arrays.toString(Arrays.stream(method.getParameterTypes()).map(Class::getName).toArray());}
    static Map<String,Method> methods(Class<?> contract){
        var result=new HashMap<String,Method>();for(var method:contract.getMethods())if(method.getDeclaringClass()!=Object.class&&!Modifier.isStatic(method.getModifiers())&&!method.getName().equals("close"))result.put(methodId(method),method);return Map.copyOf(result);
    }
    private static final class PathCodec implements JsonSerializer<Path>,JsonDeserializer<Path>{
        public JsonElement serialize(Path value,Type type,JsonSerializationContext c){return new JsonPrimitive(value.toString());}
        public Path deserialize(JsonElement value,Type type,JsonDeserializationContext c){return Path.of(value.getAsString());}
    }
    private static final class BytesCodec implements JsonSerializer<byte[]>,JsonDeserializer<byte[]>{
        public JsonElement serialize(byte[] value,Type type,JsonSerializationContext c){return new JsonPrimitive(Base64.getEncoder().encodeToString(value));}
        public byte[] deserialize(JsonElement value,Type type,JsonDeserializationContext c){return Base64.getDecoder().decode(value.getAsString());}
    }
    private static final class NbtCodec implements JsonSerializer<CompoundBinaryTag>,JsonDeserializer<CompoundBinaryTag>{
        public JsonElement serialize(CompoundBinaryTag value,Type type,JsonSerializationContext c){
            try(var output=new ByteArrayOutputStream()){BinaryTagIO.writer().write(value,output,BinaryTagIO.Compression.NONE);if(output.size()>1024*1024)throw new JsonParseException("插件NBT超过1MiB");return c.serialize(output.toByteArray());}
            catch(IOException invalid){throw new JsonParseException("NBT编码失败",invalid);}
        }
        public CompoundBinaryTag deserialize(JsonElement value,Type type,JsonDeserializationContext c){
            byte[] bytes=c.deserialize(value,byte[].class);if(bytes.length>1024*1024)throw new JsonParseException("插件NBT超过1MiB");
            try(var input=new ByteArrayInputStream(bytes)){return BinaryTagIO.reader(1024*1024).read(input,BinaryTagIO.Compression.NONE);}
            catch(IOException invalid){throw new JsonParseException("NBT解码失败",invalid);}
        }
    }
    private static final class ContextCodec implements JsonSerializer<ModelBlockContext>,JsonDeserializer<ModelBlockContext>{
        public JsonElement serialize(ModelBlockContext value,Type type,JsonSerializationContext c){return c.serialize(value.snapshot());}
        public ModelBlockContext deserialize(JsonElement value,Type type,JsonDeserializationContext c){return new ModelBlockContext(c.deserialize(value,ModelBlockContext.View.class));}
    }
    private static final class OptionalCodec implements TypeAdapterFactory {
        public <T> TypeAdapter<T> create(Gson gson,TypeToken<T> type){
            if(type.getRawType()!=Optional.class)return null;
            Type nested=((ParameterizedType)type.getType()).getActualTypeArguments()[0];
            @SuppressWarnings("unchecked") TypeAdapter<Object> adapter=(TypeAdapter<Object>)gson.getAdapter(TypeToken.get(nested));
            @SuppressWarnings("unchecked") TypeAdapter<T> result=(TypeAdapter<T>)new TypeAdapter<Optional<?>>() {
                public void write(com.google.gson.stream.JsonWriter out,Optional<?> value)throws IOException{if(value==null||value.isEmpty())out.nullValue();else adapter.write(out,value.get());}
                public Optional<?> read(com.google.gson.stream.JsonReader in)throws IOException{if(in.peek()==com.google.gson.stream.JsonToken.NULL){in.nextNull();return Optional.empty();}return Optional.ofNullable(adapter.read(in));}
            };return result;
        }
    }
}
