package com.mcworldexplorer.update;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Strict allowlist for external browser links opened by the application. */
public final class AllowedExternalUrls {
    public static final String GITEE_RELEASES_URL = "https://gitee.com/huang3337/MC-World-Explorer/releases";
    public static final String GITHUB_RELEASES_URL = "https://github.com/huang3337/MC-World-Explorer/releases";
    public static final String GITEE_LATEST_RELEASE_URL = "https://gitee.com/huang3337/MC-World-Explorer/releases/latest";
    public static final String GITHUB_LATEST_RELEASE_URL = "https://github.com/huang3337/MC-World-Explorer/releases/latest";
    public static final String GITHUB_PROJECT_URL = "https://github.com/huang3337/MC-World-Explorer";
    public static final String BILIBILI_SPACE_URL = "https://space.bilibili.com/424431265";

    private static final String REPO_RELEASES_PREFIX = "/huang3337/MC-World-Explorer/releases";
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "gitee.com",
            "github.com",
            "space.bilibili.com"
    );

    private AllowedExternalUrls() { }

    public static boolean isAllowed(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.strip());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || !"https".equalsIgnoreCase(scheme) || host == null || host.isBlank()) {
                return false;
            }
            return ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public static String requireAllowed(String url) {
        if (!isAllowed(url)) {
            throw new IllegalArgumentException("不允许打开受限外部链接：" + url);
        }
        return url.strip();
    }

    public static String sanitizeRepoReleaseUrl(String candidate, String expectedHost, String fallbackUrl) {
        if (candidate == null || candidate.isBlank()) {
            return fallbackUrl;
        }
        try {
            URI uri = URI.create(candidate.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return fallbackUrl;
            }
            String host = uri.getHost();
            String path = uri.getRawPath();
            if (host == null || !host.equalsIgnoreCase(expectedHost) || path == null) {
                return fallbackUrl;
            }
            if (path.equals(REPO_RELEASES_PREFIX) || path.startsWith(REPO_RELEASES_PREFIX + "/")) {
                return uri.toString();
            }
        } catch (IllegalArgumentException ignored) {
            // Fall back to the stable repository releases page.
        }
        return fallbackUrl;
    }
}
