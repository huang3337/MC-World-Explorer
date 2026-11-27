package com.mcworldexplorer.map;

import com.mcworldexplorer.world.WorldInfo;
/** Single bounded notice, scoped to the world that encountered a cache limit. */
public final class MapCacheStorageNotice {
    private record State(String world, String message) { }
    private static volatile State state = new State("", "");
    private MapCacheStorageNotice() { }
    static void limited(WorldInfo world) { set(world, "二维缓存达到容量上限，可清理当前存档缓存；仍可继续浏览"); }
    static void unavailable(WorldInfo world) { set(world, "二维缓存暂时无法写入，正在使用内存浏览"); }
    private static void set(WorldInfo world, String message) { state = new State(world.getFolderPath().toAbsolutePath().normalize().toString(), message); }
    static void clear() { state = new State("", ""); }
    public static String message(WorldInfo world) {
        State value = state;
        return world != null && value.world.equals(world.getFolderPath().toAbsolutePath().normalize().toString()) ? value.message : "";
    }
}
