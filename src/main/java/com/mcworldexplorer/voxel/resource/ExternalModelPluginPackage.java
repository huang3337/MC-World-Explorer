package com.mcworldexplorer.voxel.resource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.Manifest;
import java.util.zip.*;

/** Bounded, data-only package inspection. No adapter class is defined by the application process. */
public record ExternalModelPluginPackage(Path file,ModelPluginManifest manifest,String sha256,long size) {
    public static final long MAX_FILE_BYTES=16L*1024*1024,MAX_EXPANDED_BYTES=64L*1024*1024;
    public static final int MAX_ENTRIES=4096,MAX_ENTRY_BYTES=4*1024*1024;
    public ExternalModelPluginPackage {file=file.toAbsolutePath().normalize();Objects.requireNonNull(manifest);Objects.requireNonNull(sha256);}
    public static ExternalModelPluginPackage inspect(Path selected,String hostVersion)throws IOException {
        Path file=selected.toRealPath();long size=Files.size(file);
        if(!Files.isRegularFile(file)||size>MAX_FILE_BYTES)throw new IOException("模型插件包不是普通文件或超过16MiB");
        String before=hash(file);ModelPluginManifest descriptor=null;var names=new HashSet<String>();long expanded=0;
        try(var zip=new ZipFile(file.toFile(),StandardCharsets.UTF_8)){
            var entries=zip.entries();int count=0;
            while(entries.hasMoreElements()){
                var entry=entries.nextElement();String name=entry.getName();
                if(++count>MAX_ENTRIES)throw new IOException("模型插件包条目超过4096项");
                checkName(name);
                if(!names.add(name))throw new IOException("模型插件包包含重复条目："+name);
                if(entry.isDirectory())continue;
                if(name.endsWith(".jar")||name.startsWith("META-INF/versions/")||name.startsWith("META-INF/services/")||name.matches("(?i).*\\.(dll|so|dylib|exe)$"))
                    throw new IOException("模型插件包包含未支持的嵌套库、服务或本机代码："+name);
                if(name.endsWith(".class")&&(name.startsWith("java/")||name.startsWith("javax/")||name.startsWith("jdk/")||name.startsWith("sun/")||name.startsWith("com/mcworldexplorer/")||name.startsWith("com/google/gson/")||name.startsWith("net/kyori/")||name.startsWith("org/joml/")))
                    throw new IOException("插件不得打包宿主或公共API类："+name);
                int limit=name.equals(ModelPluginManifest.ENTRY)?ModelPluginManifest.MAX_JSON_CHARS:MAX_ENTRY_BYTES;
                if(entry.getSize()>limit)throw new IOException("插件条目超过预算："+name);
                byte[] bytes;
                try(var input=zip.getInputStream(entry)){bytes=input.readNBytes(limit+1);}
                if(bytes.length>limit||(expanded+=bytes.length)>MAX_EXPANDED_BYTES)throw new IOException("插件解压数据超过预算");
                if(name.equals(ModelPluginManifest.ENTRY))descriptor=ModelPluginManifest.parse(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString());
                if(name.equalsIgnoreCase("META-INF/MANIFEST.MF")){
                    var attributes=new Manifest(new ByteArrayInputStream(bytes)).getMainAttributes();
                    if(attributes.getValue("Class-Path")!=null||attributes.getValue("Multi-Release")!=null||attributes.getValue("Main-Class")!=null)
                        throw new IOException("插件JAR不能声明外部Class-Path、多版本或应用启动入口");
                }
            }
        }catch(IllegalArgumentException invalid){throw new IOException("模型插件包格式无效："+invalid.getMessage(),invalid);}
        if(descriptor==null)throw new IOException("缺少模型插件清单；Minecraft Mod JAR不能作为模型插件安装");
        if(!names.contains(descriptor.entrypoint().replace('.','/')+".class"))throw new IOException("插件入口类未包含在包中");
        try{descriptor.requireHost(hostVersion);}catch(IllegalArgumentException incompatible){throw new IOException(incompatible.getMessage(),incompatible);}
        if(Files.size(file)!=size||!hash(file).equals(before))throw new IOException("读取期间模型插件包已改变");
        return new ExternalModelPluginPackage(file,descriptor,before,size);
    }
    public void validate()throws IOException {if(Files.size(file)!=size||!hash(file).equals(sha256))throw new IOException("已选模型插件包发生变化，请重新安装");}
    private static String hash(Path file)throws IOException {
        try(var input=Files.newInputStream(file)){
            var digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[8192];int length;long count=0;
            while((length=input.read(buffer))!=-1){if((count+=length)>MAX_FILE_BYTES)throw new IOException("模型插件包超过16MiB");digest.update(buffer,0,length);}
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void checkName(String name)throws IOException {
        if(name.length()>512||name.startsWith("/")||name.contains("\\")||name.codePoints().anyMatch(c->c<32||c==127))throw new IOException("插件条目路径无效");
        String path=name.endsWith("/")?name.substring(0,name.length()-1):name;
        for(String part:path.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals("..")||part.contains(":"))throw new IOException("插件条目路径含相对段或盘符");
    }
}
