package dev.local.docinspector.epub;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯 Java 可单测的 EPUB 虚拟协议路由与安全拦截器：
 * 仅放行 {@code https://doclens.local/<bookId>/...}，严格阻断外网请求与路径穿越。
 */
public final class EpubVirtualResourceResolver {

    public static final String VIRTUAL_ORIGIN = "https://doclens.local";
    public static final String VIRTUAL_HOST = "doclens.local";
    private static final Pattern VIRTUAL_CHAPTER_PATTERN = Pattern.compile("^__chapter__/(\\d+)\\.html$");

    private EpubVirtualResourceResolver() {
    }

    public enum RouteKind {
        ALLOWED_VIRTUAL_CHAPTER,
        ALLOWED_STATIC_RESOURCE,
        BLOCKED_EXTERNAL,
        BLOCKED_INVALID_PATH,
        BLOCKED_BOOK_MISMATCH
    }

    public record ResolutionResult(RouteKind kind,
                                   int spineIndex,
                                   String normalizedEntryPath,
                                   String fragmentId,
                                   String reason) {
        public boolean isAllowed() {
            return kind == RouteKind.ALLOWED_VIRTUAL_CHAPTER || kind == RouteKind.ALLOWED_STATIC_RESOURCE;
        }
    }

    public static String buildChapterUrl(String bookId, int spineIndex) {
        return VIRTUAL_ORIGIN + "/" + safeBookId(bookId) + "/__chapter__/" + Math.max(0, spineIndex) + ".html";
    }

    public static String buildBaseUrl(String bookId, String chapterDir) {
        String prefix = VIRTUAL_ORIGIN + "/" + safeBookId(bookId) + "/";
        if (chapterDir == null || chapterDir.isBlank()) {
            return prefix;
        }
        String cleanDir = chapterDir.replace('\\', '/');
        while (cleanDir.startsWith("/")) {
            cleanDir = cleanDir.substring(1);
        }
        if (!cleanDir.endsWith("/")) {
            cleanDir = cleanDir + "/";
        }
        return prefix + cleanDir;
    }

    public static String buildResourceUrl(String bookId, String normalizedEntryPath) {
        String safe = EpubBook.validateEntryPath(normalizedEntryPath);
        return VIRTUAL_ORIGIN + "/" + safeBookId(bookId) + "/" + safe;
    }

    public static boolean isAllowedNavigationUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String trimmed = url.trim();
        if ("about:blank".equalsIgnoreCase(trimmed)) {
            return true;
        }
        return isVirtualOriginUrl(trimmed);
    }

    public static boolean isVirtualOriginUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            if (uri.getUserInfo() != null) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || !VIRTUAL_HOST.equalsIgnoreCase(host)) {
                return false;
            }
            int port = uri.getPort();
            return port == -1 || port == 443;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 解析并校验请求 URL。
     *
     * @param url               请求 URL
     * @param activeBookId      当前打开书籍 ID
     * @param book              当前书籍（可为 null，仅做纯路由校验时）
     * @param currentSpineIndex 当前所在章节索引（用于相对 __chapter__/ 路径回退）
     */
    public static ResolutionResult resolve(String url,
                                           String activeBookId,
                                           EpubBook book,
                                           int currentSpineIndex) {
        if (!isVirtualOriginUrl(url)) {
            return new ResolutionResult(RouteKind.BLOCKED_EXTERNAL, -1, "", "",
                    "External or non-virtual origin blocked: " + url);
        }

        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return new ResolutionResult(RouteKind.BLOCKED_INVALID_PATH, -1, "", "",
                    "Malformed URI: " + e.getMessage());
        }

        String rawPath = uri.getRawPath();
        if (rawPath == null || rawPath.isEmpty() || "/".equals(rawPath)) {
            return new ResolutionResult(RouteKind.BLOCKED_INVALID_PATH, -1, "", "", "Empty virtual path");
        }

        String decodedPath = decodePathSafe(rawPath);
        if (decodedPath == null || containsTraversalRaw(rawPath) || containsTraversalRaw(decodedPath)) {
            return new ResolutionResult(RouteKind.BLOCKED_INVALID_PATH, -1, "", "",
                    "Path traversal detected in URL: " + url);
        }

        // 期望格式: /<bookId>/<subPath>
        String withoutLeadingSlash = decodedPath.startsWith("/") ? decodedPath.substring(1) : decodedPath;
        int firstSlash = withoutLeadingSlash.indexOf('/');
        if (firstSlash <= 0 || firstSlash == withoutLeadingSlash.length() - 1) {
            return new ResolutionResult(RouteKind.BLOCKED_BOOK_MISMATCH, -1, "", "",
                    "Missing bookId or resource subPath: " + decodedPath);
        }

        String urlBookId = withoutLeadingSlash.substring(0, firstSlash);
        String expectedBookId = safeBookId(activeBookId);
        if (!expectedBookId.equals(urlBookId)) {
            return new ResolutionResult(RouteKind.BLOCKED_BOOK_MISMATCH, -1, "", "",
                    "BookId mismatch: expected " + expectedBookId + " but got " + urlBookId);
        }

        String subPath = withoutLeadingSlash.substring(firstSlash + 1);
        String fragment = uri.getFragment() != null ? uri.getFragment() : "";

        Matcher chapterMatcher = VIRTUAL_CHAPTER_PATTERN.matcher(subPath);
        if (chapterMatcher.matches()) {
            int idx;
            try {
                idx = Integer.parseInt(chapterMatcher.group(1));
            } catch (NumberFormatException e) {
                return new ResolutionResult(RouteKind.BLOCKED_INVALID_PATH, -1, "", "", "Invalid chapter index");
            }
            if (book != null && (idx < 0 || idx >= book.getChapterCount())) {
                return new ResolutionResult(RouteKind.BLOCKED_INVALID_PATH, idx, "", fragment,
                        "Chapter index out of range: " + idx);
            }
            String entryPath = (book != null && idx >= 0 && idx < book.getChapterCount())
                    ? book.getSpineChapters().get(idx).getNormalizedEntryPath()
                    : "";
            return new ResolutionResult(RouteKind.ALLOWED_VIRTUAL_CHAPTER, idx, entryPath, fragment, "OK");
        }

        String resolvedEntryPath;
        try {
            if (subPath.startsWith("__chapter__/")) {
                String rel = subPath.substring("__chapter__/".length());
                String baseDir = "";
                if (book != null && currentSpineIndex >= 0 && currentSpineIndex < book.getChapterCount()) {
                    baseDir = EpubBook.parentDir(book.getSpineChapters().get(currentSpineIndex).getNormalizedEntryPath());
                }
                resolvedEntryPath = EpubBook.resolveRelativeEntryPath(baseDir, rel);
            } else {
                resolvedEntryPath = EpubBook.validateEntryPath(subPath);
            }
        } catch (RuntimeException ex) {
            return new ResolutionResult(RouteKind.BLOCKED_INVALID_PATH, -1, "", "", ex.getMessage());
        }

        int mappedSpine = book != null ? book.findSpineIndexByPath(resolvedEntryPath) : -1;
        return new ResolutionResult(RouteKind.ALLOWED_STATIC_RESOURCE, mappedSpine, resolvedEntryPath, fragment, "OK");
    }

    private static boolean containsTraversalRaw(String path) {
        if (path == null) {
            return true;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e%2e") || lower.contains("\\") || lower.indexOf('\0') >= 0) {
            return true;
        }
        for (String seg : path.split("/")) {
            if ("..".equals(seg)) {
                return true;
            }
        }
        return false;
    }

    private static String decodePathSafe(String rawPath) {
        try {
            return URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String safeBookId(String bookId) {
        return (bookId == null || bookId.isBlank()) ? "default" : bookId.trim();
    }
}
