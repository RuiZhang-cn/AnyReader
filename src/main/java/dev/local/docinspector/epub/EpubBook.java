package dev.local.docinspector.epub;

import dev.local.docinspector.search.BookSearchTarget;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 已解析的 EPUB 书籍会话，持有只读 {@link ZipFile} 句柄并按需惰性读取章节与静态资源。
 */
public final class EpubBook implements AutoCloseable, BookSearchTarget {

    private static final int BUFFER_SIZE = 8192;

    private final ZipFile zipFile;
    private final File sourceFile;
    private final String bookId;
    private final String title;
    private final String creator;
    private final String opfDirectory;
    private final List<SpineChapter> spineChapters;
    private final List<TocItem> tocItems;
    private final Map<String, String> manifestMimeTypes;
    private final Map<String, Integer> pathToSpineIndex;
    private volatile boolean closed;

    EpubBook(ZipFile zipFile,
             File sourceFile,
             String bookId,
             String title,
             String creator,
             String opfDirectory,
             List<SpineChapter> spineChapters,
             List<TocItem> tocItems,
             Map<String, String> manifestMimeTypes,
             Map<String, Integer> pathToSpineIndex) {
        this.zipFile = zipFile;
        this.sourceFile = sourceFile;
        this.bookId = bookId != null ? bookId : "";
        this.title = title != null ? title : "";
        this.creator = creator != null ? creator : "";
        this.opfDirectory = opfDirectory != null ? opfDirectory : "";
        this.spineChapters = Collections.unmodifiableList(new ArrayList<>(spineChapters));
        this.tocItems = Collections.unmodifiableList(new ArrayList<>(tocItems));
        this.manifestMimeTypes = Collections.unmodifiableMap(new HashMap<>(manifestMimeTypes));
        this.pathToSpineIndex = Collections.unmodifiableMap(new HashMap<>(pathToSpineIndex));
    }

    public File getSourceFile() {
        return sourceFile;
    }

    public String getBookId() {
        return bookId;
    }

    public String getTitle() {
        return title;
    }

    public String getCreator() {
        return creator;
    }

    public String getOpfDirectory() {
        return opfDirectory;
    }

    public List<SpineChapter> getSpineChapters() {
        return spineChapters;
    }

    public List<TocItem> getTocItems() {
        return tocItems;
    }

    public int getChapterCount() {
        return spineChapters.size();
    }

    @Override
    public int getUnitCount() {
        return getChapterCount();
    }

    @Override
    public String getUnitLabel(int unitIndex) {
        if (unitIndex < 0 || unitIndex >= spineChapters.size()) {
            return "";
        }
        String chapterTitle = spineChapters.get(unitIndex).getTitle();
        String base = "Ch." + (unitIndex + 1);
        if (chapterTitle != null && !chapterTitle.isBlank()) {
            return base + " · " + chapterTitle.trim();
        }
        return base;
    }

    @Override
    public String getUnitPlainText(int unitIndex) {
        return extractChapterPlainText(unitIndex);
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * 根据规范化后的 ZIP 内部路径查找对应 Spine 索引；未找到返回 -1。
     */
    public int findSpineIndexByPath(String normalizedEntryPath) {
        if (normalizedEntryPath == null || normalizedEntryPath.isBlank()) {
            return -1;
        }
        String clean = stripFragmentAndQuery(normalizedEntryPath);
        Integer idx = pathToSpineIndex.get(clean);
        if (idx != null) {
            return idx;
        }
        String lower = clean.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Integer> entry : pathToSpineIndex.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(lower)) {
                return entry.getValue();
            }
        }
        return -1;
    }

    /**
     * 按需从 ZipFile 读取指定规范化路径的资源字节与 MIME 类型，严格防御路径穿越。
     *
     * @throws SecurityException        路径包含 {@code ..} 穿越、绝对路径前缀或非法控制字符
     * @throws IllegalArgumentException 路径为空
     * @throws IOException              资源不存在或读取失败
     */
    public ResourceData readResource(String normalizedEntryPath) throws IOException {
        ensureOpen();
        String safePath = validateEntryPath(normalizedEntryPath);
        ZipEntry entry = findZipEntry(safePath);
        if (entry == null || entry.isDirectory()) {
            throw new IOException("EPUB resource not found: " + safePath);
        }

        byte[] bytes;
        try (InputStream in = zipFile.getInputStream(entry);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            bytes = out.toByteArray();
        }

        String mimeType = resolveMimeType(safePath);
        return new ResourceData(bytes, mimeType);
    }

    /**
     * 读取并自动嗅探解码文本资源（HTML/XHTML/CSS/XML）。
     */
    public String readTextResource(String normalizedEntryPath) throws IOException {
        ResourceData data = readResource(normalizedEntryPath);
        return CharsetSniffer.decode(data.getBytes());
    }

    /**
     * 提取指定 Spine 章节的纯文本（剥离 script/style），供全文检索与摘要使用。
     */
    public String extractChapterPlainText(int spineIndex) {
        if (spineIndex < 0 || spineIndex >= spineChapters.size()) {
            return "";
        }
        SpineChapter chapter = spineChapters.get(spineIndex);
        try {
            String html = readTextResource(chapter.getNormalizedEntryPath());
            Document doc = Jsoup.parse(html);
            doc.select("script, style, head").remove();
            return doc.body() != null ? doc.body().text() : doc.text();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 校验已规范化的 ZIP 内部路径，拒绝任何 {@code ..} 穿越或绝对路径。
     */
    public static String validateEntryPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Entry path must not be blank");
        }
        String trimmed = stripFragmentAndQuery(rawPath.trim());
        if (trimmed.indexOf('\0') >= 0 || trimmed.contains("\\")) {
            throw new SecurityException("Illegal characters in EPUB entry path: " + rawPath);
        }
        if (trimmed.startsWith("/") || trimmed.matches("^[A-Za-z]:.*")) {
            throw new SecurityException("Absolute EPUB entry path is forbidden: " + rawPath);
        }
        String[] segments = trimmed.split("/");
        List<String> cleanSegments = new ArrayList<>(segments.length);
        for (String seg : segments) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                throw new SecurityException("Path traversal is forbidden: " + rawPath);
            }
            cleanSegments.add(seg);
        }
        if (cleanSegments.isEmpty()) {
            throw new IllegalArgumentException("Empty EPUB entry path: " + rawPath);
        }
        return String.join("/", cleanSegments);
    }

    /**
     * 将相对链接解析为规范化 ZIP 内部路径；若越出书籍根目录则抛出 {@link SecurityException}。
     */
    public static String resolveRelativeEntryPath(String baseDir, String relativeHref) {
        if (relativeHref == null || relativeHref.isBlank()) {
            return "";
        }
        String cleanHref = stripFragmentAndQuery(relativeHref.trim()).replace('\\', '/');
        if (cleanHref.startsWith("/")) {
            return validateEntryPath(cleanHref.substring(1));
        }

        List<String> stack = new ArrayList<>();
        if (baseDir != null && !baseDir.isBlank()) {
            for (String seg : baseDir.replace('\\', '/').split("/")) {
                if (!seg.isEmpty() && !".".equals(seg)) {
                    stack.add(seg);
                }
            }
        }
        for (String seg : cleanHref.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (stack.isEmpty()) {
                    throw new SecurityException("Relative path escapes EPUB root: " + relativeHref);
                }
                stack.remove(stack.size() - 1);
            } else {
                stack.add(seg);
            }
        }
        return String.join("/", stack);
    }

    public static String parentDir(String entryPath) {
        if (entryPath == null || entryPath.isBlank()) {
            return "";
        }
        int slash = entryPath.lastIndexOf('/');
        return slash > 0 ? entryPath.substring(0, slash) : "";
    }

    public static String guessMimeType(String path) {
        if (path == null) {
            return "application/octet-stream";
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html";
        }
        if (lower.endsWith(".css")) {
            return "text/css";
        }
        if (lower.endsWith(".js")) {
            return "text/javascript";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (lower.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (lower.endsWith(".woff")) {
            return "font/woff";
        }
        if (lower.endsWith(".ttf")) {
            return "font/ttf";
        }
        if (lower.endsWith(".otf")) {
            return "font/otf";
        }
        if (lower.endsWith(".xml") || lower.endsWith(".ncx") || lower.endsWith(".opf")) {
            return "application/xml";
        }
        return "application/octet-stream";
    }

    private String resolveMimeType(String normalizedPath) {
        String declared = manifestMimeTypes.get(normalizedPath);
        if (declared != null && !declared.isBlank()) {
            int semi = declared.indexOf(';');
            String bare = (semi >= 0 ? declared.substring(0, semi) : declared).trim().toLowerCase(Locale.ROOT);
            if ("application/xhtml+xml".equals(bare) || "text/html".equals(bare)) {
                return "text/html";
            }
            if ("text/css".equals(bare)) {
                return "text/css";
            }
            if ("application/javascript".equals(bare) || "text/javascript".equals(bare)) {
                return "text/javascript";
            }
            if (!bare.isEmpty()) {
                return bare;
            }
        }
        return guessMimeType(normalizedPath);
    }

    private ZipEntry findZipEntry(String safePath) {
        ZipEntry entry = zipFile.getEntry(safePath);
        if (entry != null) {
            return entry;
        }
        String lower = safePath.toLowerCase(Locale.ROOT);
        var entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry candidate = entries.nextElement();
            if (candidate.getName().toLowerCase(Locale.ROOT).equals(lower)) {
                return candidate;
            }
        }
        return null;
    }

    private static String stripFragmentAndQuery(String path) {
        int hash = path.indexOf('#');
        String noFrag = hash >= 0 ? path.substring(0, hash) : path;
        int query = noFrag.indexOf('?');
        return query >= 0 ? noFrag.substring(0, query) : noFrag;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("EpubBook is already closed");
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            zipFile.close();
        }
    }

    /**
     * 内存按需读取的资源载荷。
     */
    public static final class ResourceData {
        private final byte[] bytes;
        private final String mimeType;

        public ResourceData(byte[] bytes, String mimeType) {
            this.bytes = bytes != null ? bytes : new byte[0];
            this.mimeType = mimeType != null ? mimeType : "application/octet-stream";
        }

        public byte[] getBytes() {
            return bytes;
        }

        public String getMimeType() {
            return mimeType;
        }
    }
}
