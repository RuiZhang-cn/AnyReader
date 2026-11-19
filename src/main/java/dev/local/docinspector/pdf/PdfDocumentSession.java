package dev.local.docinspector.pdf;

import dev.local.docinspector.model.PdfColorFilter;
import dev.local.docinspector.search.BookSearchTarget;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Dimension2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PDFBox 3.0.5 文档会话：封装惰性分页渲染、10 帧线程安全 LRU 缓存、
 * 相邻页后台预取、大纲书签解析、按页文本缓存与字形坐标关键词高亮定位。
 */
public final class PdfDocumentSession implements AutoCloseable, BookSearchTarget {

    public static final int RENDER_CACHE_CAPACITY = 10;
    private static final float MIN_SCALE = 0.25f;
    private static final float MAX_SCALE = 6.0f;

    private final File file;
    private final PDDocument document;
    private final PDFRenderer renderer;
    private final int pageCount;
    private final String title;

    private final Object pdfLock = new Object();
    private final Object cacheLock = new Object();
    private final Map<RenderCacheKey, BufferedImage> renderCache;
    private final Map<Integer, String> pageTextCache = new ConcurrentHashMap<>();
    private final ExecutorService renderExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile List<PdfOutlineItem> cachedOutlines;

    public PdfDocumentSession(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("PDF file does not exist: " + file);
        }
        this.file = file;
        this.document = Loader.loadPDF(file);
        this.renderer = new PDFRenderer(this.document);
        this.pageCount = Math.max(0, this.document.getNumberOfPages());

        String docTitle = this.document.getDocumentInformation() != null
                ? this.document.getDocumentInformation().getTitle()
                : null;
        this.title = (docTitle != null && !docTitle.isBlank()) ? docTitle.trim() : file.getName();

        this.renderCache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<RenderCacheKey, BufferedImage> eldest) {
                return size() > RENDER_CACHE_CAPACITY;
            }
        };

        this.renderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "doc-inspector-pdf-render");
            t.setDaemon(true);
            return t;
        });
    }

    public static PdfDocumentSession open(File file) throws IOException {
        return new PdfDocumentSession(file);
    }

    public File getFile() {
        return file;
    }

    public int getPageCount() {
        return pageCount;
    }

    public String getTitle() {
        return title;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 规范化旋转角度至 0 / 90 / 180 / 270。
     */
    public static int normalizeRotation(int rotationDeg) {
        int r = rotationDeg % 360;
        if (r < 0) {
            r += 360;
        }
        return (r / 90) * 90;
    }

    /**
     * 获取指定页在 72 DPI 下的逻辑宽高（已考虑页面自带旋转与用户旋转）。
     */
    public Dimension2D getPageSizePoints(int pageIndex, int userRotationDeg) {
        if (closed.get() || pageIndex < 0 || pageIndex >= pageCount) {
            return new DoubleDimension(595.0, 842.0);
        }
        synchronized (pdfLock) {
            if (closed.get()) {
                return new DoubleDimension(595.0, 842.0);
            }
            PDPage page = document.getPage(pageIndex);
            PDRectangle box = page.getCropBox();
            if (box == null) {
                box = page.getMediaBox();
            }
            double w = box != null ? box.getWidth() : 595.0;
            double h = box != null ? box.getHeight() : 842.0;
            int totalRot = normalizeRotation(page.getRotation() + userRotationDeg);
            if (totalRot == 90 || totalRot == 270) {
                return new DoubleDimension(h, w);
            }
            return new DoubleDimension(w, h);
        }
    }

    /**
     * 提取 PDF 层级书签大纲，兼容 {@link PDPageDestination} 与 {@link PDNamedDestination}。
     */
    public List<PdfOutlineItem> getOutlines() {
        List<PdfOutlineItem> existing = this.cachedOutlines;
        if (existing != null) {
            return existing;
        }
        if (closed.get()) {
            return Collections.emptyList();
        }
        synchronized (pdfLock) {
            if (this.cachedOutlines != null) {
                return this.cachedOutlines;
            }
            if (closed.get()) {
                return Collections.emptyList();
            }
            PDDocumentCatalog catalog = document.getDocumentCatalog();
            PDDocumentOutline outline = catalog != null ? catalog.getDocumentOutline() : null;
            if (outline == null) {
                this.cachedOutlines = Collections.emptyList();
                return this.cachedOutlines;
            }
            List<PdfOutlineItem> items = readOutlineSiblings(outline.getFirstChild(), catalog);
            this.cachedOutlines = Collections.unmodifiableList(items);
            return this.cachedOutlines;
        }
    }

    private List<PdfOutlineItem> readOutlineSiblings(PDOutlineItem first, PDDocumentCatalog catalog) {
        List<PdfOutlineItem> result = new ArrayList<>();
        PDOutlineItem current = first;
        while (current != null) {
            int pageIdx = resolveOutlinePageIndex(current, catalog);
            List<PdfOutlineItem> children = current.getFirstChild() != null
                    ? readOutlineSiblings(current.getFirstChild(), catalog)
                    : Collections.emptyList();
            String itemTitle = current.getTitle() != null ? current.getTitle().trim() : "";
            result.add(new PdfOutlineItem(itemTitle, pageIdx, children));
            current = current.getNextSibling();
        }
        return result;
    }

    private int resolveOutlinePageIndex(PDOutlineItem item, PDDocumentCatalog catalog) {
        try {
            PDDestination dest = item.getDestination();
            if (dest == null) {
                PDAction action = item.getAction();
                if (action instanceof PDActionGoTo goTo) {
                    dest = goTo.getDestination();
                }
            }
            if (dest instanceof PDNamedDestination named && catalog != null) {
                dest = catalog.findNamedDestinationPage(named);
            }
            if (dest instanceof PDPageDestination pageDest) {
                int num = pageDest.retrievePageNumber();
                if (num >= 0 && num < pageCount) {
                    return num;
                }
                PDPage targetPage = pageDest.getPage();
                if (targetPage != null) {
                    int idx = document.getPages().indexOf(targetPage);
                    if (idx >= 0 && idx < pageCount) {
                        return idx;
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return -1;
    }

    /**
     * 同步渲染指定页（优先命中 10 帧 LRU 缓存），并在后台预取相邻页 {@code pageIndex ± 1}。
     */
    public BufferedImage renderPage(int pageIndex, float renderScale, int rotationDeg, PdfColorFilter filter)
            throws IOException {
        if (closed.get()) {
            throw new IOException("PdfDocumentSession is closed");
        }
        if (pageIndex < 0 || pageIndex >= pageCount) {
            throw new IndexOutOfBoundsException("Page index out of range: " + pageIndex);
        }
        float clampedScale = clampScale(renderScale);
        int normRot = normalizeRotation(rotationDeg);
        PdfColorFilter effectiveFilter = filter != null ? filter : PdfColorFilter.ORIGINAL;
        RenderCacheKey key = RenderCacheKey.of(pageIndex, clampedScale, normRot, effectiveFilter);

        BufferedImage cached = getFromCache(key);
        if (cached != null) {
            schedulePrefetch(pageIndex, clampedScale, normRot, effectiveFilter);
            return cached;
        }

        BufferedImage rendered = renderAndCache(pageIndex, clampedScale, normRot, effectiveFilter, key);
        schedulePrefetch(pageIndex, clampedScale, normRot, effectiveFilter);
        return rendered;
    }

    /**
     * 异步渲染指定页，适用于 Swing UI 线程非阻塞调用。
     */
    public CompletableFuture<BufferedImage> renderPageAsync(int pageIndex,
                                                            float renderScale,
                                                            int rotationDeg,
                                                            PdfColorFilter filter) {
        if (closed.get() || pageIndex < 0 || pageIndex >= pageCount) {
            return CompletableFuture.completedFuture(null);
        }
        float clampedScale = clampScale(renderScale);
        int normRot = normalizeRotation(rotationDeg);
        PdfColorFilter effectiveFilter = filter != null ? filter : PdfColorFilter.ORIGINAL;
        RenderCacheKey key = RenderCacheKey.of(pageIndex, clampedScale, normRot, effectiveFilter);

        BufferedImage cached = getFromCache(key);
        if (cached != null) {
            schedulePrefetch(pageIndex, clampedScale, normRot, effectiveFilter);
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                BufferedImage img = renderAndCache(pageIndex, clampedScale, normRot, effectiveFilter, key);
                schedulePrefetch(pageIndex, clampedScale, normRot, effectiveFilter);
                return img;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, renderExecutor);
    }

    private BufferedImage getFromCache(RenderCacheKey key) {
        synchronized (cacheLock) {
            return renderCache.get(key);
        }
    }

    private BufferedImage renderAndCache(int pageIndex,
                                         float clampedScale,
                                         int normRot,
                                         PdfColorFilter effectiveFilter,
                                         RenderCacheKey key) throws IOException {
        BufferedImage doubleChecked = getFromCache(key);
        if (doubleChecked != null) {
            return doubleChecked;
        }
        BufferedImage raw;
        synchronized (pdfLock) {
            if (closed.get()) {
                throw new IOException("PdfDocumentSession is closed");
            }
            raw = renderer.renderImageWithDPI(pageIndex, 72f * clampedScale, ImageType.RGB);
        }
        BufferedImage rotated = rotateImageIfNeeded(raw, normRot);
        BufferedImage filtered = PdfColorFilterProcessor.apply(rotated, effectiveFilter);
        synchronized (cacheLock) {
            if (!closed.get()) {
                renderCache.put(key, filtered);
            }
        }
        return filtered;
    }

    private void schedulePrefetch(int centerPage,
                                  float clampedScale,
                                  int normRot,
                                  PdfColorFilter effectiveFilter) {
        if (closed.get() || renderExecutor.isShutdown()) {
            return;
        }
        int[] neighbors = {centerPage + 1, centerPage - 1};
        for (int neighbor : neighbors) {
            if (neighbor < 0 || neighbor >= pageCount) {
                continue;
            }
            RenderCacheKey key = RenderCacheKey.of(neighbor, clampedScale, normRot, effectiveFilter);
            if (getFromCache(key) != null) {
                continue;
            }
            try {
                renderExecutor.submit(() -> {
                    if (closed.get() || getFromCache(key) != null) {
                        return;
                    }
                    try {
                        renderAndCache(neighbor, clampedScale, normRot, effectiveFilter, key);
                    } catch (IOException ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isCached(int pageIndex, float renderScale, int rotationDeg, PdfColorFilter filter) {
        RenderCacheKey key = RenderCacheKey.of(
                pageIndex,
                clampScale(renderScale),
                normalizeRotation(rotationDeg),
                filter != null ? filter : PdfColorFilter.ORIGINAL
        );
        synchronized (cacheLock) {
            return renderCache.containsKey(key);
        }
    }

    public int getCacheSize() {
        synchronized (cacheLock) {
            return renderCache.size();
        }
    }

    @Override
    public int getUnitCount() {
        return getPageCount();
    }

    @Override
    public String getUnitLabel(int unitIndex) {
        if (unitIndex < 0 || unitIndex >= pageCount) {
            return "";
        }
        return "P." + (unitIndex + 1);
    }

    @Override
    public String getUnitPlainText(int unitIndex) {
        return getPageText(unitIndex);
    }

    /**
     * 按页提取纯文本（带缓存），供全文检索与书签摘录使用。
     */
    public String getPageText(int pageIndex) {
        if (closed.get() || pageIndex < 0 || pageIndex >= pageCount) {
            return "";
        }
        String cached = pageTextCache.get(pageIndex);
        if (cached != null) {
            return cached;
        }
        synchronized (pdfLock) {
            if (closed.get()) {
                return "";
            }
            String rechecked = pageTextCache.get(pageIndex);
            if (rechecked != null) {
                return rechecked;
            }
            try {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(pageIndex + 1);
                stripper.setEndPage(pageIndex + 1);
                stripper.setSortByPosition(true);
                String text = stripper.getText(document);
                String normalized = text != null ? text.trim() : "";
                pageTextCache.put(pageIndex, normalized);
                return normalized;
            } catch (IOException e) {
                return "";
            }
        }
    }

    /**
     * 基于 {@link PDFTextStripper} 字形坐标计算页内关键词高亮矩形列表（72 DPI 左上角原点页面坐标系）。
     */
    public List<Rectangle2D> findHighlightRects(int pageIndex, String keyword, boolean caseSensitive) {
        if (closed.get()
                || pageIndex < 0
                || pageIndex >= pageCount
                || keyword == null
                || keyword.isEmpty()) {
            return Collections.emptyList();
        }

        synchronized (pdfLock) {
            if (closed.get()) {
                return Collections.emptyList();
            }
            try {
                GlyphCollectorStripper stripper = new GlyphCollectorStripper();
                stripper.setStartPage(pageIndex + 1);
                stripper.setEndPage(pageIndex + 1);
                stripper.setSortByPosition(true);
                stripper.getText(document);
                return stripper.findMatches(keyword, caseSensitive);
            } catch (IOException e) {
                return Collections.emptyList();
            }
        }
    }

    private static BufferedImage rotateImageIfNeeded(BufferedImage src, int normRot) {
        if (src == null || normRot == 0) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int dstW = (normRot == 90 || normRot == 270) ? h : w;
        int dstH = (normRot == 90 || normRot == 270) ? w : h;
        BufferedImage dst = new BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = dst.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.translate(dstW / 2.0, dstH / 2.0);
            g2.rotate(Math.toRadians(normRot));
            g2.translate(-w / 2.0, -h / 2.0);
            g2.drawImage(src, 0, 0, null);
        } finally {
            g2.dispose();
        }
        return dst;
    }

    private static float clampScale(float scale) {
        if (Float.isNaN(scale) || Float.isInfinite(scale)) {
            return 1.0f;
        }
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        renderExecutor.shutdownNow();
        synchronized (cacheLock) {
            renderCache.clear();
        }
        pageTextCache.clear();
        synchronized (pdfLock) {
            document.close();
        }
    }

    private static final class GlyphCollectorStripper extends PDFTextStripper {
        private final StringBuilder textBuffer = new StringBuilder();
        private final List<TextPosition> charPositions = new ArrayList<>();

        GlyphCollectorStripper() {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            if (text == null || text.isEmpty()) {
                return;
            }
            int posSize = textPositions != null ? textPositions.size() : 0;
            for (int i = 0; i < text.length(); i++) {
                textBuffer.append(text.charAt(i));
                TextPosition tp = (posSize > 0) ? textPositions.get(Math.min(i, posSize - 1)) : null;
                charPositions.add(tp);
            }
        }

        @Override
        protected void writeWordSeparator() throws IOException {
            textBuffer.append(' ');
            charPositions.add(null);
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            textBuffer.append('\n');
            charPositions.add(null);
        }

        List<Rectangle2D> findMatches(String keyword, boolean caseSensitive) {
            String fullText = textBuffer.toString();
            if (fullText.isEmpty()) {
                return Collections.emptyList();
            }
            String haystack = caseSensitive ? fullText : fullText.toLowerCase(Locale.ROOT);
            String needle = caseSensitive ? keyword : keyword.toLowerCase(Locale.ROOT);
            if (needle.isEmpty()) {
                return Collections.emptyList();
            }

            List<Rectangle2D> rects = new ArrayList<>();
            int fromIndex = 0;
            while (fromIndex <= haystack.length() - needle.length()) {
                int idx = haystack.indexOf(needle, fromIndex);
                if (idx < 0) {
                    break;
                }
                appendMatchRects(idx, idx + needle.length(), rects);
                fromIndex = idx + Math.max(1, needle.length());
            }
            return rects;
        }

        private void appendMatchRects(int start, int end, List<Rectangle2D> out) {
            Rectangle2D currentLineRect = null;
            float prevBaselineY = Float.NaN;
            float prevHeight = 0f;

            for (int i = start; i < end && i < charPositions.size(); i++) {
                TextPosition tp = charPositions.get(i);
                if (tp == null) {
                    continue;
                }
                float x = tp.getXDirAdj();
                float baselineY = tp.getYDirAdj();
                float h = tp.getHeightDir();
                if (h <= 0.5f) {
                    h = Math.max(8f, tp.getFontSizeInPt());
                }
                float w = Math.max(1f, tp.getWidthDirAdj());
                Rectangle2D glyphRect = new Rectangle2D.Double(x, baselineY - h, w, h * 1.2);

                if (currentLineRect == null) {
                    currentLineRect = glyphRect;
                    prevBaselineY = baselineY;
                    prevHeight = h;
                } else if (Math.abs(baselineY - prevBaselineY) <= Math.max(3f, Math.max(prevHeight, h) * 0.6f)) {
                    currentLineRect = currentLineRect.createUnion(glyphRect);
                    prevBaselineY = baselineY;
                    prevHeight = Math.max(prevHeight, h);
                } else {
                    out.add(currentLineRect);
                    currentLineRect = glyphRect;
                    prevBaselineY = baselineY;
                    prevHeight = h;
                }
            }
            if (currentLineRect != null) {
                out.add(currentLineRect);
            }
        }
    }

    private record RenderCacheKey(int pageIndex, int quantScale, int rotationDeg, PdfColorFilter filter) {
        static RenderCacheKey of(int pageIndex, float scale, int rotationDeg, PdfColorFilter filter) {
            int qScale = Math.round(scale * 100f);
            return new RenderCacheKey(pageIndex, qScale, rotationDeg, filter);
        }
    }

    private static final class DoubleDimension extends Dimension2D {
        private double width;
        private double height;

        DoubleDimension(double width, double height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public double getWidth() {
            return width;
        }

        @Override
        public double getHeight() {
            return height;
        }

        @Override
        public void setSize(double width, double height) {
            this.width = width;
            this.height = height;
        }
    }
}
