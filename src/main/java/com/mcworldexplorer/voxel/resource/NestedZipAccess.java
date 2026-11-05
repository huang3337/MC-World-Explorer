package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.voxel.io.CancellationSignal;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** JDK ZIP filesystems only: no extraction, discovery of classes, or Mod execution. */
final class NestedZipAccess implements AutoCloseable {
    static final long MAX_NESTED_BYTES=32L*1024*1024;
    private static final long MAX_OPEN_BYTES=96L*1024*1024;
    private record Key(Path path,List<String> chain){Key{chain=List.copyOf(chain);}}
    private record Handle(List<FileSystem> systems,long bytes){Path root(){return systems.getLast().getPath("/");}}
    private final Map<Key,Handle> handles=new LinkedHashMap<>(8,.75f,true);
    private final CancellationSignal cancel;
    private long openBytes;
    private boolean closed;
    NestedZipAccess(CancellationSignal cancel){this.cancel=Objects.requireNonNull(cancel);}
    Set<Path> paths(){Set<Path> paths=new HashSet<>();for(var key:handles.keySet())paths.add(key.path());return paths;}
    Path root(Path path,List<String> chain)throws IOException {
        check();if(chain.isEmpty()||chain.size()>4)throw new IOException("嵌套资源层数须为1..4");
        var key=new Key(path,chain);var cached=handles.get(key);if(cached!=null)return cached.root();
        while(handles.size()>=4)evict();List<FileSystem> opened=new ArrayList<>();long bytes=0;
        try {
            opened.add(FileSystems.newFileSystem(path));
            for(String entry:chain) {
                check();safe(entry);Path child=opened.getLast().getPath("/"+entry);long size=Files.size(child);
                if(size<22||size>MAX_NESTED_BYTES)throw new IOException("嵌套JAR须在22字节..32MiB预算内");
                bytes+=size;if(bytes>MAX_OPEN_BYTES)throw new IOException("嵌套链工作内存超过96MiB");
                while(openBytes+bytes>MAX_OPEN_BYTES&&!handles.isEmpty())evict();
                // Verify actual decompressed size before the provider can buffer a nested archive.
                try(var input=Files.newInputStream(child)){byte[] buffer=new byte[65536];long total=0;int n;while((n=input.read(buffer))!=-1){check();total+=n;if(total>size||total>MAX_NESTED_BYTES)throw new IOException("嵌套JAR解压长度超限");}if(total!=size)throw new IOException("嵌套JAR解压长度不符");}
                opened.add(FileSystems.newFileSystem(child));
            }
            var handle=new Handle(List.copyOf(opened),bytes);handles.put(key,handle);openBytes+=bytes;return handle.root();
        }catch(IOException|RuntimeException|Error error){close(opened,error);throw error;}
    }
    static void safe(String entry)throws IOException {
        if(entry.isBlank()||entry.length()>1024||entry.startsWith("/")||entry.contains("\\")||entry.contains(":")||entry.chars().anyMatch(c->c<32))throw new IOException("嵌套归档路径无效");
        for(String part:entry.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IOException("嵌套归档路径越界");
    }
    private void evict()throws IOException {var iterator=handles.entrySet().iterator();var entry=iterator.next();iterator.remove();openBytes-=entry.getValue().bytes();close(entry.getValue().systems(),null);}
    private static void close(List<FileSystem> systems,Throwable original)throws IOException {
        IOException first=null;for(int i=systems.size()-1;i>=0;i--)try{systems.get(i).close();}catch(IOException error){if(original!=null)original.addSuppressed(error);else if(first==null)first=error;else first.addSuppressed(error);}if(first!=null)throw first;
    }
    private void check()throws IOException {if(closed)throw new ResourceAccessException(ResourceAccessException.Reason.CLOSED,"嵌套资源读取器已关闭");if(cancel.isCancelled()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("取消嵌套资源读取");}
    public void close()throws IOException {if(closed)return;closed=true;IOException error=null;for(var handle:handles.values())try{close(handle.systems(),null);}catch(IOException failure){if(error==null)error=failure;else error.addSuppressed(failure);}handles.clear();openBytes=0;if(error!=null)throw error;}
}
