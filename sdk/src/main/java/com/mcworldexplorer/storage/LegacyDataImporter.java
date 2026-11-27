package com.mcworldexplorer.storage;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.Objects;

/**
 * Safely copies user configuration (settings, saved places, installed plugins) and 2D map cache
 * from an older portable MC World Explorer directory into the current installation without
 * modifying or deleting any files in the source directory.
 */
public final class LegacyDataImporter {
    public static final long MAX_CONFIG_FILE_BYTES = 64L * 1024L * 1024L;
    public static final long MAX_CACHE_FILE_BYTES = 256L * 1024L * 1024L;
    public static final int MAX_TOTAL_FILES = 200_000;

    public record Options(boolean importConfig, boolean importCache) {
        public static final Options ALL = new Options(true, true);
    }

    public record ImportResult(int configFilesCopied, int cacheFilesCopied, int skippedFiles) {
        public int totalCopied() {
            return configFilesCopied + cacheFilesCopied;
        }
    }

    private LegacyDataImporter() { }

    public static ImportResult importFrom(Path sourceRoot, Options options) throws IOException {
        return importFrom(sourceRoot, PortablePaths.applicationRoot(), options);
    }

    public static ImportResult importFrom(Path sourceRoot, Path targetRoot, Options options) throws IOException {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(targetRoot, "targetRoot");
        Objects.requireNonNull(options, "options");
        if (!options.importConfig() && !options.importCache()) {
            throw new IllegalArgumentException("请至少勾选一项要导入的数据。");
        }

        Path normalizedSource = sourceRoot.toAbsolutePath().normalize();
        Path normalizedTarget = targetRoot.toAbsolutePath().normalize();
        validateRootDirectories(normalizedSource, normalizedTarget);

        Path sourceConfig = normalizedSource.resolve("config");
        Path sourceCache = normalizedSource.resolve("cache");
        boolean hasConfig = isSafeDirectory(sourceConfig);
        boolean hasCache = isSafeDirectory(sourceCache);
        if ((options.importConfig() && !hasConfig) && (options.importCache() && !hasCache)) {
            throw new IOException("所选目录中未找到可导入的 config/ 或 cache/ 文件夹，请选择旧版 MC World Explorer 根目录。");
        }
        if (options.importConfig() && !options.importCache() && !hasConfig) {
            throw new IOException("所选目录中未找到 config/ 文件夹。");
        }
        if (!options.importConfig() && options.importCache() && !hasCache) {
            throw new IOException("所选目录中未找到 cache/ 文件夹。");
        }

        int[] counters = new int[3]; // [0]=configCopied, [1]=cacheCopied, [2]=skipped
        if (options.importConfig() && hasConfig) {
            Path targetConfig = normalizedTarget.resolve("config");
            Files.createDirectories(targetConfig);
            copyIfRegularFile(sourceConfig.resolve("settings.properties"),
                    targetConfig.resolve("settings.properties"), MAX_CONFIG_FILE_BYTES, counters, 0);
            copySubtreeIfPresent(sourceConfig.resolve("places"),
                    targetConfig.resolve("places"), MAX_CONFIG_FILE_BYTES, counters, 0);
            copySubtreeIfPresent(sourceConfig.resolve("model-plugins"),
                    targetConfig.resolve("model-plugins"), MAX_CONFIG_FILE_BYTES, counters, 0);
        }
        if (options.importCache() && hasCache) {
            Path targetCache = normalizedTarget.resolve("cache");
            Files.createDirectories(targetCache);
            copySubtreeIfPresent(sourceCache, targetCache, MAX_CACHE_FILE_BYTES, counters, 1);
        }

        return new ImportResult(counters[0], counters[1], counters[2]);
    }

    private static void validateRootDirectories(Path source, Path target) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("所选旧版目录不存在。");
        }
        if (isReparseOrSymlink(source)) {
            throw new IOException("拒绝从符号链接或重解析点目录导入数据。");
        }
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("所选路径不是有效文件夹。");
        }
        Path realSource = source.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path realTarget = canonicalizeTarget(target);
        if (realSource.equals(realTarget)) {
            throw new IOException("不能选择当前程序所在目录作为旧版目录。");
        }
        if (realSource.startsWith(realTarget) || realTarget.startsWith(realSource)) {
            throw new IOException("不能选择当前目录的子目录或父目录作为旧版目录。");
        }
    }

    private static Path canonicalizeTarget(Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        Path existing = normalized;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return normalized;
        }
        Path realAncestor = existing.toRealPath(LinkOption.NOFOLLOW_LINKS);
        return existing.equals(normalized)
                ? realAncestor
                : realAncestor.resolve(existing.relativize(normalized)).normalize();
    }

    private static boolean isSafeDirectory(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (isReparseOrSymlink(path)) {
            throw new IOException("检测到不受支持的符号链接或重解析点：" + path.getFileName());
        }
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isReparseOrSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            return true;
        }
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attrs.isSymbolicLink() || attrs.isOther();
    }

    private static void copyIfRegularFile(Path src, Path dst, long maxBytes, int[] counters, int bucket)
            throws IOException {
        if (!Files.exists(src, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (isReparseOrSymlink(src)) {
            throw new IOException("拒绝复制符号链接或重解析点文件：" + src.getFileName());
        }
        BasicFileAttributes attrs = Files.readAttributes(src, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) {
            return;
        }
        if (attrs.size() > maxBytes) {
            throw new IOException("文件体积超出安全上限，已中止导入：" + src.getFileName());
        }
        checkTotalLimit(counters);
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
        counters[bucket]++;
    }

    private static void copySubtreeIfPresent(Path srcRoot, Path dstRoot, long maxBytes, int[] counters, int bucket)
            throws IOException {
        if (!Files.exists(srcRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (isReparseOrSymlink(srcRoot)) {
            throw new IOException("拒绝访问符号链接或重解析点目录：" + srcRoot.getFileName());
        }
        if (!Files.isDirectory(srcRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(srcRoot, EnumSet.noneOf(FileVisitOption.class), 64, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || attrs.isOther() || Files.isSymbolicLink(dir)) {
                    throw new IOException("拒绝穿越符号链接或重解析点目录：" + dir.getFileName());
                }
                Path relative = srcRoot.relativize(dir);
                Path targetDir = dstRoot.resolve(relative).normalize();
                if (!targetDir.startsWith(dstRoot)) {
                    throw new IOException("检测到越界路径，已中止导入。");
                }
                Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink() || attrs.isOther() || Files.isSymbolicLink(file)) {
                    throw new IOException("拒绝复制符号链接或重解析点文件：" + file.getFileName());
                }
                if (!attrs.isRegularFile()) {
                    counters[2]++;
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.size() > maxBytes) {
                    throw new IOException("文件体积超出安全上限，已中止导入：" + file.getFileName());
                }
                checkTotalLimit(counters);
                Path relative = srcRoot.relativize(file);
                Path targetFile = dstRoot.resolve(relative).normalize();
                if (!targetFile.startsWith(dstRoot)) {
                    throw new IOException("检测到越界路径，已中止导入。");
                }
                Files.createDirectories(targetFile.getParent());
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                counters[bucket]++;
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void checkTotalLimit(int[] counters) throws IOException {
        if (counters[0] + counters[1] >= MAX_TOTAL_FILES) {
            throw new IOException("待导入文件数量超出安全上限，已中止导入。");
        }
    }
}
