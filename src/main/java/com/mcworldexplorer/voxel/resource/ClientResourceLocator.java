package com.mcworldexplorer.voxel.resource;

import com.mcworldexplorer.nbt.LevelDatReader;
import com.mcworldexplorer.voxel.io.CancellationSignal;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded discovery of existing local resources. No recursive search, downloads or launcher execution. */
public final class ClientResourceLocator {
    public static final int MAX_CANDIDATES = 16;
    private static final Pattern PRE_16_RELEASE = Pattern.compile("^1\\.[2-5](?:\\.[0-9]+)?(?:[._+-].*)?$");
    private static final Pattern PRE_FLATTENING_RELEASE = Pattern.compile("^1\\.(?:[2-9]|1[0-2])(?:\\.[0-9]+)?(?:[._+-].*)?$");
    public enum Status { FOUND, MISSING, AMBIGUOUS, INVALID_VERSION }
    public record Result(Status status, Optional<Path> path, String message, int checkedCandidates) { }

    public Result locate(Path world, String version, Optional<Path> binding, List<Path> roots,
                         CancellationSignal cancellation) {
        Objects.requireNonNull(world); Objects.requireNonNull(binding);
        Objects.requireNonNull(roots); Objects.requireNonNull(cancellation);
        check(cancellation);
        if (version != null && PRE_16_RELEASE.matcher(version).matches()) {
            return new Result(Status.INVALID_VERSION, Optional.empty(),
                    "1.6 之前旧版存档，三维预览使用简化颜色底座", 0);
        }
        if (!isValidVersion(version)) return new Result(Status.INVALID_VERSION, Optional.empty(),
                "无法识别存档版本，暂用简化颜色", 0);
        boolean preFlattening = isPreFlatteningVersion(version);
        boolean pathSafeVersion = isPathSafeVersion(version);
        List<List<Path>> tiers = new ArrayList<>();
        binding.ifPresent(path -> tiers.add(List.of(path)));
        Path ancestor = world.toAbsolutePath().normalize().getParent();
        for (int depth = 0; ancestor != null && depth < 8; depth++, ancestor = ancestor.getParent()) {
            String name = ancestor.getFileName() == null ? "" : ancestor.getFileName().toString();
            if (name.equalsIgnoreCase("saves") && ancestor.getParent() != null) {
                Path owner = ancestor.getParent();
                List<Path> local = new ArrayList<>();
                if (pathSafeVersion) local.add(owner.resolve(version + ".jar"));
                if (owner.getFileName() != null) local.add(owner.resolve(owner.getFileName() + ".jar"));
                if (!local.isEmpty()) tiers.add(local);
                if (pathSafeVersion) tiers.add(candidates(owner, version));
            }
            if (pathSafeVersion && name.equalsIgnoreCase("versions") && ancestor.getParent() != null)
                tiers.add(candidates(ancestor.getParent(), version));
        }
        // Equally plausible unrelated installations are a single tier: do not silently pick a different pack.
        if (pathSafeVersion) {
            List<Path> fallback = new ArrayList<>();
            for (Path root : roots.stream().limit(8).toList()) fallback.addAll(candidates(root, version));
            if (!fallback.isEmpty()) tiers.add(fallback);
        }
        Set<Path> visited = new HashSet<>();
        int checked = 0;
        List<String> rejected = new ArrayList<>();
        for (List<Path> tier : tiers) {
            Map<String, Path> valid = new LinkedHashMap<>();
            for (Path candidate : tier) {
                check(cancellation);
                Path path;
                try { path = candidate.toRealPath(); }
                catch (IOException | SecurityException e) { continue; }
                if (!visited.add(path) || !Files.isRegularFile(path)) continue;
                if (checked >= MAX_CANDIDATES) return new Result(Status.MISSING, Optional.empty(),
                        "本地资源检查达到 16 项上限，请手动选择客户端 JAR", checked);
                checked++;
                try (var archive = ClientResourceArchive.open(path, version)) {
                    valid.putIfAbsent(archive.fingerprint().sha256(), archive.fingerprint().path());
                } catch (IOException | SecurityException e) {
                    if (rejected.size() < 3) rejected.add(path.getFileName() + "：" + e.getMessage());
                }
            }
            check(cancellation);
            if (valid.size() > 1) return new Result(Status.AMBIGUOUS, Optional.empty(),
                    "找到多份内容不同的 " + version + " 客户端资源，请手动选择", checked);
            if (valid.size() == 1) return new Result(Status.FOUND, Optional.of(valid.values().iterator().next()),
                    preFlattening
                            ? "已自动匹配本地 " + version + " 旧版客户端资源；未覆盖的方块将回退简化颜色"
                            : "已自动匹配本地 " + version + " 原版资源；未兼容的方块使用简化颜色",
                    checked);
        }
        String missingPrefix = preFlattening
                ? "未找到可用的 " + version + " 旧版客户端资源，三维预览将回退简化颜色底座；可手动绑定对应旧版客户端 JAR"
                : "未找到可用的 " + version + " 客户端资源，可手动选择或使用简化颜色";
        return new Result(Status.MISSING, Optional.empty(), missingPrefix
                + (rejected.isEmpty() ? "" : "\n已跳过：" + String.join("；", rejected)), checked);
    }

    public static boolean isPreFlatteningVersion(String version) {
        if (version == null || version.isBlank()) return false;
        return LevelDatReader.LEGACY_PRE_1_9_VERSION.equals(version)
                || PRE_FLATTENING_RELEASE.matcher(version).matches();
    }

    public static boolean isValidVersion(String version) {
        if (version != null && PRE_16_RELEASE.matcher(version).matches()) return false;
        return LevelDatReader.LEGACY_PRE_1_9_VERSION.equals(version) || isPathSafeVersion(version);
    }

    private static boolean isPathSafeVersion(String version) {
        return version != null && version.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");
    }

    private static List<Path> candidates(Path root, String version) {
        Path normalized = root.toAbsolutePath().normalize();
        List<Path> result = new ArrayList<>();
        result.add(normalized.resolve("versions").resolve(version).resolve(version + ".jar"));
        if (normalized.getFileName() != null && normalized.getFileName().toString().equalsIgnoreCase("versions"))
            result.add(normalized.resolve(version).resolve(version + ".jar"));
        if (normalized.getFileName() != null && normalized.getFileName().toString().equals(version))
            result.add(normalized.resolve(version + ".jar"));
        return result;
    }
    private static void check(CancellationSignal cancellation) {
        if (cancellation.isCancelled() || Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("取消本地资源查找");
    }
}
