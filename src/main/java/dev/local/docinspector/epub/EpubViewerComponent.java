package dev.local.docinspector.epub;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefClient;
import com.intellij.ui.jcef.JBCefJSQuery;
import dev.local.docinspector.model.ReaderSettings;
import dev.local.docinspector.viewer.BookViewer;
import dev.local.docinspector.viewer.ViewerViewportListener;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EPUB 阅读视图组件：优先使用 IDEA 2026.3 JCEF 内存协议渲染，
 * 在无 JCEF 的无头环境中自动安全降级至 {@link JEditorPane}。
 */
public final class EpubViewerComponent implements BookViewer {

    private static final Pattern RATIO_JSON_PATTERN =
            Pattern.compile("\"ratio\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern SNIPPET_JSON_PATTERN =
            Pattern.compile("\"snippet\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern NAV_JSON_PATTERN =
            Pattern.compile("\"nav\"\\s*:\\s*\"(prev|next)\"");

    private final JPanel rootPanel;
    private final boolean jcefSupported;
    private JBCefClient cefClient;
    private JBCefBrowser cefBrowser;
    private JBCefJSQuery bridgeQuery;
    private JEditorPane fallbackPane;

    private volatile EpubBook currentBook;
    private volatile ReaderSettings currentSettings = new ReaderSettings();
    private volatile ViewerViewportListener viewportListener;
    private volatile int currentUnitIndex = 0;
    private volatile double currentOffsetRatio = 0.0;
    private volatile String pendingAnchor = "";
    private volatile String pendingHighlightKeyword = "";
    private volatile boolean pendingHighlightCaseSensitive = false;
    private volatile int pendingHighlightOrdinal = 0;
    private volatile String currentSnippet = "";
    private volatile boolean disposed = false;

    public EpubViewerComponent() {
        this.rootPanel = new JPanel(new BorderLayout());
        boolean supported = false;
        try {
            supported = JBCefApp.isSupported();
        } catch (Throwable ignored) {
            supported = false;
        }
        this.jcefSupported = supported;

        if (this.jcefSupported) {
            initJcefBrowser();
        } else {
            initFallbackPane();
        }
    }

    private void initJcefBrowser() {
        this.cefClient = JBCefApp.getInstance().createClient();
        this.cefClient.setProperty(JBCefClient.Properties.JS_QUERY_POOL_SIZE, 16);
        this.cefBrowser = JBCefBrowser.createBuilder()
                .setClient(this.cefClient)
                .setOffScreenRendering(false)
                .setEnableOpenDevToolsMenuItem(false)
                .build();
        this.bridgeQuery = JBCefJSQuery.create((JBCefBrowserBase) this.cefBrowser);
        this.bridgeQuery.addHandler(this::handleJsBridgeMessage);

        EpubCefResourceHandler requestHandler = new EpubCefResourceHandler(
                () -> this.currentBook,
                () -> this.currentUnitIndex,
                this::renderDecoratedChapterHtml,
                (targetSpine, fragment) -> SwingUtilities.invokeLater(() -> goToUnit(targetSpine, 0.0, fragment))
        );
        this.cefClient.addRequestHandler(requestHandler, this.cefBrowser.getCefBrowser());
        this.cefClient.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                if (frame != null && frame.isMain() && !disposed) {
                    String kw = pendingHighlightKeyword;
                    if (kw != null && !kw.isEmpty()) {
                        boolean cs = pendingHighlightCaseSensitive;
                        int ord = pendingHighlightOrdinal;
                        pendingHighlightKeyword = "";
                        SwingUtilities.invokeLater(() -> highlightKeyword(kw, cs, ord));
                    }
                }
            }
        }, this.cefBrowser.getCefBrowser());
        applyBackgroundColors(buildPalette(this.currentSettings));
        this.rootPanel.add(this.cefBrowser.getComponent(), BorderLayout.CENTER);
    }

    private void initFallbackPane() {
        this.fallbackPane = new JEditorPane();
        this.fallbackPane.setEditable(false);
        this.fallbackPane.setContentType("text/html; charset=UTF-8");
        this.rootPanel.add(new JScrollPane(this.fallbackPane), BorderLayout.CENTER);
    }

    /**
     * 打开已解析的 {@link EpubBook} 并定位至指定章节与滚动位置。
     */
    public void openBook(EpubBook book, int spineIndex, double offsetRatio, String anchorId) {
        if (disposed) {
            return;
        }
        EpubBook previous = this.currentBook;
        this.currentBook = book;
        if (previous != null && previous != book) {
            try {
                previous.close();
            } catch (IOException ignored) {
            }
        }
        if (book == null || book.getChapterCount() == 0) {
            this.currentUnitIndex = 0;
            this.currentOffsetRatio = 0.0;
            this.currentSnippet = "";
            return;
        }
        goToUnit(spineIndex, offsetRatio, anchorId);
    }

    public EpubBook getCurrentBook() {
        return currentBook;
    }

    @Override
    public JComponent getComponent() {
        return rootPanel;
    }

    @Override
    public int getCurrentUnitIndex() {
        return currentUnitIndex;
    }

    @Override
    public int getUnitCount() {
        EpubBook book = currentBook;
        return book != null ? book.getChapterCount() : 0;
    }

    @Override
    public double getCurrentOffsetRatio() {
        return currentOffsetRatio;
    }

    @Override
    public String getCurrentViewportSnippet() {
        return currentSnippet;
    }

    @Override
    public void goToUnit(int unitIndex, double offsetRatio, String anchorId) {
        if (disposed) {
            return;
        }
        EpubBook book = currentBook;
        if (book == null || book.getChapterCount() == 0) {
            return;
        }
        int clampedIndex = Math.max(0, Math.min(book.getChapterCount() - 1, unitIndex));
        double clampedRatio = Math.max(0.0, Math.min(1.0, offsetRatio));

        this.currentUnitIndex = clampedIndex;
        this.currentOffsetRatio = clampedRatio;
        this.pendingAnchor = anchorId != null ? anchorId.trim() : "";

        applyBackgroundColors(buildPalette(currentSettings));
        if (jcefSupported && cefBrowser != null) {
            String url = EpubVirtualResourceResolver.buildChapterUrl(book.getBookId(), clampedIndex);
            cefBrowser.loadURL(url);
        } else if (fallbackPane != null) {
            renderFallbackChapter(book, clampedIndex);
        }

        ViewerViewportListener listener = this.viewportListener;
        if (listener != null) {
            String title = book.getSpineChapters().get(clampedIndex).getTitle();
            SwingUtilities.invokeLater(() -> listener.onUnitChanged(clampedIndex, book.getChapterCount(), title));
        }
    }

    @Override
    public void applySettings(ReaderSettings settings) {
        if (disposed || settings == null) {
            return;
        }
        this.currentSettings = settings;
        EpubHtmlDecorator.StylePalette palette = buildPalette(settings);
        applyBackgroundColors(palette);
        if (jcefSupported && cefBrowser != null) {
            String applyJs = EpubHtmlDecorator.buildApplyStyleCallJs(palette);
            String autoScrollJs = "window.__dlAutoScroll && window.__dlAutoScroll("
                    + settings.getAutoScrollPxPerSec() + ");";
            executeJavaScriptSafe(applyJs + "\n" + autoScrollJs);
        } else if (fallbackPane != null) {
            fallbackPane.setBackground(Color.decode(palette.bgHex()));
            fallbackPane.setForeground(Color.decode(palette.fgHex()));
            fallbackPane.setFont(new Font(palette.fontFamily(), Font.PLAIN, palette.fontSize()));
        }
    }

    private void applyBackgroundColors(EpubHtmlDecorator.StylePalette palette) {
        if (palette == null) {
            return;
        }
        try {
            rootPanel.setBackground(Color.decode(palette.bgHex()));
        } catch (IllegalArgumentException ignored) {
        }
        if (jcefSupported && cefBrowser != null) {
            try {
                cefBrowser.setPageBackgroundColor(palette.bgHex());
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void setViewportListener(ViewerViewportListener listener) {
        this.viewportListener = listener;
    }

    @Override
    public int highlightKeyword(String keyword, boolean caseSensitive, int targetOrdinal) {
        if (disposed || keyword == null || keyword.isEmpty()) {
            clearHighlights();
            return 0;
        }
        EpubBook book = currentBook;
        int matchCount = 0;
        if (book != null) {
            String plain = book.extractChapterPlainText(currentUnitIndex);
            matchCount = countMatches(plain, keyword, caseSensitive);
        }
        if (jcefSupported && cefBrowser != null) {
            String escaped = keyword.replace("\\", "\\\\").replace("\"", "\\\"");
            String js = "window.__dlHighlight && window.__dlHighlight(\""
                    + escaped + "\", " + caseSensitive + ", " + targetOrdinal + ");";
            executeJavaScriptSafe(js);
        }
        return matchCount;
    }

    @Override
    public int jumpToSearchHit(int unitIndex, String keyword, boolean caseSensitive, int matchOrdinalInUnit) {
        if (disposed || keyword == null || keyword.isEmpty()) {
            clearHighlights();
            return 0;
        }
        EpubBook book = currentBook;
        if (book == null || book.getChapterCount() == 0) {
            return 0;
        }
        int clampedIndex = Math.max(0, Math.min(book.getChapterCount() - 1, unitIndex));
        if (clampedIndex == currentUnitIndex) {
            this.pendingHighlightKeyword = "";
            return highlightKeyword(keyword, caseSensitive, matchOrdinalInUnit);
        }
        this.pendingHighlightKeyword = keyword;
        this.pendingHighlightCaseSensitive = caseSensitive;
        this.pendingHighlightOrdinal = Math.max(0, matchOrdinalInUnit);
        goToUnit(clampedIndex, 0.0, null);
        return countMatches(book.extractChapterPlainText(clampedIndex), keyword, caseSensitive);
    }

    @Override
    public void clearHighlights() {
        if (disposed) {
            return;
        }
        this.pendingHighlightKeyword = "";
        if (jcefSupported && cefBrowser != null) {
            executeJavaScriptSafe("window.__dlClearHighlight && window.__dlClearHighlight();");
        }
    }

    private void executeJavaScriptSafe(String js) {
        JBCefBrowser browser = this.cefBrowser;
        if (browser == null || js == null || js.isEmpty()) {
            return;
        }
        CefBrowser rawCef = browser.getCefBrowser();
        if (rawCef != null) {
            rawCef.executeJavaScript(js, "", 0);
        }
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        EpubBook book = this.currentBook;
        this.currentBook = null;
        if (book != null) {
            try {
                book.close();
            } catch (IOException ignored) {
            }
        }
        if (bridgeQuery != null) {
            bridgeQuery.dispose();
            bridgeQuery = null;
        }
        if (cefBrowser != null) {
            cefBrowser.dispose();
            cefBrowser = null;
        }
        if (cefClient != null) {
            cefClient.dispose();
            cefClient = null;
        }
    }

    private String renderDecoratedChapterHtml(int spineIndex) {
        EpubBook book = currentBook;
        if (book == null || spineIndex < 0 || spineIndex >= book.getChapterCount()) {
            return "<html><body><p>Chapter unavailable.</p></body></html>";
        }
        SpineChapter chapter = book.getSpineChapters().get(spineIndex);
        try {
            String rawHtml = book.readTextResource(chapter.getNormalizedEntryPath());
            EpubHtmlDecorator.StylePalette palette = buildPalette(currentSettings);
            String bridgeCall = bridgeQuery != null ? bridgeQuery.inject("payload") : "";
            return EpubHtmlDecorator.decorateChapterHtml(
                    rawHtml,
                    book.getBookId(),
                    chapter.getNormalizedEntryPath(),
                    palette,
                    currentOffsetRatio,
                    pendingAnchor,
                    bridgeCall
            );
        } catch (IOException e) {
            return "<html><body><p>Failed to load chapter: " + escapeHtml(e.getMessage()) + "</p></body></html>";
        }
    }

    private void renderFallbackChapter(EpubBook book, int spineIndex) {
        String plain = book.extractChapterPlainText(spineIndex);
        EpubHtmlDecorator.StylePalette palette = buildPalette(currentSettings);
        String html = "<html><body style='background:" + palette.bgHex()
                + ";color:" + palette.fgHex()
                + ";font-family:sans-serif;padding:16px;'>"
                + "<p style='opacity:0.65;font-size:12px;'>[JCEF 不可用，已切换基础文本降级视图]</p>"
                + "<div>" + escapeHtml(plain).replace("\n", "<br/>") + "</div>"
                + "</body></html>";
        fallbackPane.setText(html);
        fallbackPane.setCaretPosition(0);
        currentSnippet = plain.length() > 80 ? plain.substring(0, 80) : plain;
    }

    public JBCefJSQuery.Response handleJsBridgeMessage(String json) {
        if (disposed || json == null || json.isBlank()) {
            return new JBCefJSQuery.Response("");
        }
        Matcher nm = NAV_JSON_PATTERN.matcher(json);
        if (nm.find()) {
            String dir = nm.group(1);
            SwingUtilities.invokeLater(() -> {
                if (!disposed) {
                    if ("prev".equals(dir)) {
                        prevUnit();
                    } else if ("next".equals(dir)) {
                        nextUnit();
                    }
                }
            });
            return new JBCefJSQuery.Response("");
        }
        double ratio = currentOffsetRatio;
        Matcher rm = RATIO_JSON_PATTERN.matcher(json);
        if (rm.find()) {
            try {
                ratio = Math.max(0.0, Math.min(1.0, Double.parseDouble(rm.group(1))));
            } catch (NumberFormatException ignored) {
            }
        }
        String snippet = currentSnippet;
        Matcher sm = SNIPPET_JSON_PATTERN.matcher(json);
        if (sm.find()) {
            snippet = unescapeJsonString(sm.group(1));
        }

        this.currentOffsetRatio = ratio;
        this.currentSnippet = snippet;

        EpubBook book = currentBook;
        ViewerViewportListener listener = viewportListener;
        if (book != null && listener != null) {
            int total = Math.max(1, book.getChapterCount());
            int unit = currentUnitIndex;
            double overall = Math.max(0.0, Math.min(1.0, (unit + ratio) / total));
            String finalSnippet = snippet;
            double finalRatio = ratio;
            SwingUtilities.invokeLater(() ->
                    listener.onViewportChanged(unit, finalRatio, total, overall, finalSnippet));
        }
        return new JBCefJSQuery.Response("");
    }

    private EpubHtmlDecorator.StylePalette buildPalette(ReaderSettings settings) {
        String ideBg = "#2B2D30";
        String ideFg = "#DFE1E5";
        try {
            if (ApplicationManager.getApplication() != null) {
                EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
                if (scheme != null) {
                    Color bg = scheme.getDefaultBackground();
                    Color fg = scheme.getDefaultForeground();
                    if (bg != null) {
                        ideBg = String.format("#%02X%02X%02X", bg.getRed(), bg.getGreen(), bg.getBlue());
                    }
                    if (fg != null) {
                        ideFg = String.format("#%02X%02X%02X", fg.getRed(), fg.getGreen(), fg.getBlue());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return EpubHtmlDecorator.StylePalette.fromSettings(settings, ideBg, ideFg);
    }

    private static int countMatches(String text, String keyword, boolean caseSensitive) {
        if (text == null || text.isEmpty() || keyword == null || keyword.isEmpty()) {
            return 0;
        }
        String haystack = caseSensitive ? text : text.toLowerCase(Locale.ROOT);
        String needle = caseSensitive ? keyword : keyword.toLowerCase(Locale.ROOT);
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static String unescapeJsonString(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\\"", "\"")
                .replace("\\n", " ")
                .replace("\\r", "")
                .replace("\\\\", "\\");
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
