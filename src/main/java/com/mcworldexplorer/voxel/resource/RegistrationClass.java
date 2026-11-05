package com.mcworldexplorer.voxel.resource;

import java.util.Objects;

/** A bounded, inert class-file receipt. This type never defines or initializes a Java class. */
public record RegistrationClass(String internalName,byte[] bytes,String origin,String sourceFingerprint) {
    public static final int MAX_BYTES=1024*1024;
    public RegistrationClass {
        checkName(internalName);Objects.requireNonNull(bytes);Objects.requireNonNull(origin);Objects.requireNonNull(sourceFingerprint);
        if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("注册类文件超过1MiB预算");bytes=bytes.clone();
    }
    @Override public byte[] bytes(){return bytes.clone();}
    public static void checkName(String name){
        Objects.requireNonNull(name);
        if(name.length()>512||!name.matches("[A-Za-z_$][A-Za-z0-9_$]*(/[A-Za-z_$][A-Za-z0-9_$]*)*"))throw new IllegalArgumentException("注册类内部名称无效");
    }
}
