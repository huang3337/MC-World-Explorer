package com.mcworldexplorer.voxel.resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** First source wins. Non-owning view: callers retain/close each source's archive. */
public final class LayeredResourceSource implements BlockResourceSource {
    private final List<BlockResourceSource> sources;
    private final ClientResourceArchive.Fingerprint fingerprint;
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
    @Override public void validate() throws IOException {for(var source:sources)source.validate();}
    @Override public ClientResourceArchive.Fingerprint fingerprint(){return fingerprint;}
    @Override public synchronized Optional<byte[]> read(ResourceLocation.Kind kind,ResourceLocation id)throws IOException {
        Objects.requireNonNull(kind);Objects.requireNonNull(id);
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
        for(var source:sources){var value=source.read(kind,id);if(value.isPresent()){
            byte[] data=value.orElseThrow();
            if(data.length>kind.maxBytes()||bytesRead+data.length>ClientResourceArchive.MAX_SESSION_READ_BYTES)
                throw new ResourceAccessException(ResourceAccessException.Reason.LIMIT_EXCEEDED,"资源源叠加读取超过预算");
            bytesRead+=data.length;return Optional.of(data);
        }}return Optional.empty();
    }
    @Override public Optional<String> readJson(ResourceLocation.Kind kind,ResourceLocation id)throws IOException {
        if(kind==ResourceLocation.Kind.TEXTURE)throw new IllegalArgumentException("PNG 不能按 JSON 读取");
        var data=read(kind,id);if(data.isEmpty())return Optional.empty();
        try{return Optional.of(StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(data.orElseThrow())).toString());}
        catch(java.nio.charset.CharacterCodingException e){throw new ResourceAccessException(ResourceAccessException.Reason.INVALID_METADATA,"资源 JSON 不是 UTF-8",e);}
    }
}
