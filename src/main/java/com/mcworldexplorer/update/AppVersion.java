package com.mcworldexplorer.update;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Semantic version parser and comparator for MC World Explorer releases. */
public record AppVersion(int major, int minor, int patch, boolean snapshot, String raw)
        implements Comparable<AppVersion> {
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(-SNAPSHOT)?$", Pattern.CASE_INSENSITIVE);
    private static final String UNKNOWN_VERSION = "信息不可用";

    public AppVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("Version components must be non-negative");
        }
        Objects.requireNonNull(raw, "raw");
    }

    public static Optional<AppVersion> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        Matcher matcher = VERSION_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            int patch = Integer.parseInt(matcher.group(3));
            boolean snapshot = matcher.group(4) != null;
            return Optional.of(new AppVersion(major, minor, patch, snapshot, trimmed));
        } catch (NumberFormatException overflow) {
            return Optional.empty();
        }
    }

    public static String currentVersionString() {
        try (var stream = AppVersion.class.getResourceAsStream("/mcwe-build.properties")) {
            if (stream != null) {
                var properties = new Properties();
                properties.load(stream);
                String version = properties.getProperty("version", "").strip();
                if (!version.isEmpty()) {
                    return version;
                }
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Missing or invalid metadata must not invent a release version.
        }
        return UNKNOWN_VERSION;
    }

    public static Optional<AppVersion> currentVersion() {
        return parse(currentVersionString());
    }

    public String normalized() {
        return major + "." + minor + "." + patch + (snapshot ? "-SNAPSHOT" : "");
    }

    public boolean isNewerThan(AppVersion other) {
        Objects.requireNonNull(other, "other");
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(AppVersion other) {
        int cmp = Integer.compare(this.major, other.major);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(this.minor, other.minor);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(this.patch, other.patch);
        if (cmp != 0) return cmp;
        // A release build is newer than a SNAPSHOT with the same major.minor.patch.
        return Boolean.compare(other.snapshot, this.snapshot);
    }
}
