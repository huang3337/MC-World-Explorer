package com.mcworldexplorer.preview;

import com.mcworldexplorer.storage.PortablePaths;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public final class PreviewExporter {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_WORLD_NAME_CODE_POINTS = 80;
    private static final int MAX_UNIQUE_ATTEMPTS = 10_000;

    private final Clock clock;

    public PreviewExporter() {
        this(Clock.systemDefaultZone());
    }

    PreviewExporter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Path exportToDefault(
            Path sourceImage,
            String worldName,
            Path worldDirectory) throws ExportException {
        validateSource(sourceImage);
        Path exportDirectory = PortablePaths.exportsDirectory().toAbsolutePath().normalize();
        try {
            Files.createDirectories(exportDirectory);
            if (!Files.isDirectory(exportDirectory)) {
                throw new IOException("The export path is not a directory");
            }
        } catch (IOException | SecurityException e) {
            throw new ExportException(
                    FailureReason.TARGET_DIRECTORY_UNAVAILABLE,
                    "无法创建或使用默认导出目录：" + exportDirectory,
                    e);
        }

        String baseName = safeWorldName(worldName) + "-" + currentTimestamp();
        for (int attempt = 1; attempt <= MAX_UNIQUE_ATTEMPTS; attempt++) {
            String suffix = attempt == 1 ? "" : "-" + attempt;
            Path target = exportDirectory.resolve(baseName + suffix + ".png");
            try {
                return exportToFile(sourceImage, target, worldDirectory);
            } catch (ExportException e) {
                if (e.reason() == FailureReason.TARGET_EXISTS) {
                    continue;
                }
                if (e.reason() == FailureReason.WRITE_FAILED) {
                    throw new ExportException(
                            FailureReason.TARGET_DIRECTORY_UNAVAILABLE,
                            "无法写入默认导出目录：" + exportDirectory,
                            e);
                }
                throw e;
            }
        }
        throw new ExportException(
                FailureReason.TARGET_DIRECTORY_UNAVAILABLE,
                "默认导出目录中同名文件过多：" + exportDirectory);
    }

    public Path exportToFile(
            Path sourceImage,
            Path targetFile,
            Path worldDirectory) throws ExportException {
        validateSource(sourceImage);
        Objects.requireNonNull(targetFile, "targetFile");
        Objects.requireNonNull(worldDirectory, "worldDirectory");

        Path target = targetFile.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new ExportException(
                    FailureReason.TARGET_DIRECTORY_UNAVAILABLE,
                    "导出目录不存在：" + parent);
        }
        if (isInsideWorld(target, worldDirectory)) {
            throw new ExportException(
                    FailureReason.TARGET_INSIDE_WORLD,
                    "不能把导出文件写入 Minecraft 存档目录：" + target);
        }
        if (Files.exists(target)) {
            throw new ExportException(
                    FailureReason.TARGET_EXISTS,
                    "目标文件已经存在：" + target);
        }

        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, ".mcwe-export-", ".tmp");
            Files.copy(sourceImage, temporary, StandardCopyOption.REPLACE_EXISTING);
            moveWithoutOverwrite(temporary, target);
            return target;
        } catch (FileAlreadyExistsException e) {
            throw new ExportException(
                    FailureReason.TARGET_EXISTS,
                    "目标文件已经存在：" + target,
                    e);
        } catch (IOException | SecurityException e) {
            throw new ExportException(
                    FailureReason.WRITE_FAILED,
                    "导出 PNG 失败：" + target,
                    e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The original export failure remains the useful error for the caller.
                }
            }
        }
    }

    public String suggestedFileName(String worldName) {
        return safeWorldName(worldName) + "-" + currentTimestamp() + ".png";
    }

    private String currentTimestamp() {
        return TIMESTAMP_FORMAT.format(LocalDateTime.now(clock));
    }

    private static void validateSource(Path sourceImage) throws ExportException {
        if (sourceImage == null || !Files.isRegularFile(sourceImage) || !Files.isReadable(sourceImage)) {
            throw new ExportException(
                    FailureReason.SOURCE_UNAVAILABLE,
                    "当前缩略图文件不存在或无法读取：" + sourceImage);
        }
    }

    private static void moveWithoutOverwrite(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static boolean isInsideWorld(Path target, Path worldDirectory) {
        Path normalizedWorld = worldDirectory.toAbsolutePath().normalize();
        if (target.startsWith(normalizedWorld)) {
            return true;
        }

        try {
            Path realWorld = normalizedWorld.toRealPath();
            Path realParent = target.getParent().toRealPath();
            Path effectiveTarget = realParent.resolve(target.getFileName()).normalize();
            return effectiveTarget.startsWith(realWorld);
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private static String safeWorldName(String worldName) {
        String safe = worldName == null
                ? ""
                : worldName.replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_")
                        .strip()
                        .replaceAll("[. ]+$", "");
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) {
            return "world";
        }

        int codePointCount = safe.codePointCount(0, safe.length());
        if (codePointCount > MAX_WORLD_NAME_CODE_POINTS) {
            safe = safe.substring(0, safe.offsetByCodePoints(0, MAX_WORLD_NAME_CODE_POINTS))
                    .stripTrailing()
                    .replaceAll("[. ]+$", "");
        }
        return safe.isEmpty() ? "world" : safe;
    }

    public enum FailureReason {
        SOURCE_UNAVAILABLE,
        TARGET_DIRECTORY_UNAVAILABLE,
        TARGET_EXISTS,
        TARGET_INSIDE_WORLD,
        WRITE_FAILED
    }

    public static final class ExportException extends IOException {
        private final FailureReason reason;

        private ExportException(FailureReason reason, String message) {
            super(message);
            this.reason = reason;
        }

        private ExportException(FailureReason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public FailureReason reason() {
            return reason;
        }
    }
}
