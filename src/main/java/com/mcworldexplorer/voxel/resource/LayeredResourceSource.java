package com.mcworldexplorer.voxel.resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** First source wins. Non-owning view: callers retain/close each source's archive. */
public final class LayeredResourceSource implements BlockResourceSource {
    private final List<BlockResourceSource> sources;
    private final ClientResourceArchive.Fingerprint fingerprint;
    private final Map<String,String> origins=new LinkedHashMap<>(16,.75f,true);
    private long bytesRead;
    public LayeredResourceSource(List<? extends BlockResourceSource> sources) {
        if(sources.isEmpty()||sources.size()>16)throw new IllegalArgumentException("资源源数量须为 1..16");
        this.sources=List.copyOf(sources);
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");long size=0,modified=0;
            for(var source:this.sources){var f=source.fingerprint();digest.update(f.toString().getBytes(StandardCharsets.UTF_8));digest.update((byte)0);size=Math.addExact(size,f.size());modified=Math.max(modified,f.modifiedMillis());}
            fingerprint=new ClientResourceArchive.Fingerprint(this.sources.getFirst().fingerprint().path(),size,modified,HexFormat.of().formatHex(digest.digest()));
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    @Override public int baseResourceFormat(){
        int format=-1;
        for(var source:sources) {
            int candidate=source.baseResourceFormat();
            if(candidate<0)continue;
            if(format>=0 && format!=candidate)return -1;
            format=candidate;
        }
        return format;
    }
    @Override public ClientResourceArchive.ProtocolMode baseProtocolMode(){
        return sources.getLast().baseProtocolMode();
    }
    @Override public Optional<String> baseClientVersion(){
        String version=null;
        for(var source:sources){
            var candidate=source.baseClientVersion();
            if(candidate.isEmpty())continue;
            if(version!=null && !version.equals(candidate.get()))return Optional.empty();
            version=candidate.get();
        }
        return Optional.ofNullable(version);
    }
    @Override public Optional<Boolean> baseDataRegistry(String directory)throws IOException {
        return sources.getLast().baseDataRegistry(directory);
    }
    @Override public Optional<Long> worldSeed()throws IOException {return sources.getLast().worldSeed();}
    @Override public Optional<Integer> biomeBlendRadius()throws IOException {return sources.getLast().biomeBlendRadius();}
    @Override public Optional<DimensionHeight> dimensionHeight(ResourceLocation dimension)throws IOException {
        return sources.getLast().dimensionHeight(dimension);
    }
    @Override public synchronized Optional<RegistrationClass> clientClass(String internalName)throws IOException {
        RegistrationClass.checkName(internalName);
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
        var value=sources.getLast().clientClass(internalName);
        if(value.isPresent()){
            int length=value.get().bytes().length;
            if(bytesRead+length>ClientResourceArchive.MAX_SESSION_READ_BYTES)
                throw new ResourceAccessException(ResourceAccessException.Reason.LIMIT_EXCEEDED,"资源源叠加读取超过预算");
            bytesRead+=length;
        }
        return value;
    }
    @Override public synchronized Optional<String> origin(ResourceLocation.Kind kind,ResourceLocation id){
        return Optional.ofNullable(origins.get(kind+":"+id));
    }
    @Override public void validate() throws IOException {for(var source:sources)source.validate();}
    @Override public boolean hasBaseTexture(ResourceLocation id)throws IOException {
        return sources.getLast().hasBaseTexture(id);
    }
    @Override public ClientResourceArchive.Fingerprint fingerprint(){return fingerprint;}
    @Override public synchronized Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id)throws IOException {
        Objects.requireNonNull(kind);Objects.requireNonNull(id);
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
        for(var source:sources){var value=source.read(kind,id);if(value.isPresent()){
            byte[] data=value.orElseThrow();
            if(data.length>kind.maxBytes())throw new ResourceAccessException(ResourceAccessException.Reason.LIMIT_EXCEEDED,"单个叠加资源超过读取预算",ResourceAccessException.Scope.RESOURCE);
            if(bytesRead+data.length>ClientResourceArchive.MAX_SESSION_READ_BYTES)
                throw new ResourceAccessException(ResourceAccessException.Reason.LIMIT_EXCEEDED,"资源源叠加读取超过预算");
            bytesRead+=data.length;
            source.origin(kind,id).ifPresent(from->{
                if(origins.size()>=256)origins.remove(origins.keySet().iterator().next());
                origins.put(kind+":"+id,from);
            });
            return Optional.of(data);
        }}return Optional.empty();
    }
    @Override public synchronized List<String> dataLayers(ResourceLocation id)throws IOException {
        var layers=new ArrayList<String>();
        for(var source:sources.reversed())for(String layer:source.dataLayers(id)){
            int bytes=layer.getBytes(StandardCharsets.UTF_8).length;
            if(bytes>ResourceLocation.Kind.RAW_DATA.maxBytes())
                throw new ResourceAccessException(ResourceAccessException.Reason.LIMIT_EXCEEDED,"单个数据包资源超过读取预算",ResourceAccessException.Scope.RESOURCE);
            if(bytesRead+bytes>ClientResourceArchive.MAX_SESSION_READ_BYTES)
                throw new ResourceAccessException(ResourceAccessException.Reason.LIMIT_EXCEEDED,"资源源叠加读取超过预算");
            bytesRead+=bytes;layers.add(layer);
        }
        return List.copyOf(layers);
    }
    @Override public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id)throws IOException {
        if(kind==ResourceLocation.Kind.TEXTURE)throw new IllegalArgumentException("PNG 不能按 JSON 读取");
        var data=read(kind,id);if(data.isEmpty())return Optional.empty();
        try{return Optional.of(StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(data.orElseThrow())).toString());}
        catch(java.nio.charset.CharacterCodingException e){throw new ResourceAccessException(ResourceAccessException.Reason.INVALID_METADATA,"资源 JSON 不是 UTF-8",e);}
    }
}
