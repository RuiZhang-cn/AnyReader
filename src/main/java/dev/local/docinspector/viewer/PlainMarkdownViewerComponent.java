package dev.local.docinspector.viewer;

import dev.local.docinspector.epub.CharsetSniffer;
import dev.local.docinspector.model.BookFormat;
import dev.local.docinspector.model.ReaderSettings;
import dev.local.docinspector.search.BookSearchTarget;
import dev.local.docinspector.ui.ReaderThemePalette;
import dev.local.docinspector.ui.SideDrawerPanel;
import dev.local.docinspector.ui.UiChrome;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯文本（TXT）与 Markdown（MD）阅读组件。
 * 支持 {@link CharsetSniffer} 编码探测、TXT 章节正则目录、Markdown HTML 渲染、
 * 比例记忆、关键词高亮与 {@link BookSearchTarget} 全文检索。
 */
public final class PlainMarkdownViewerComponent implements BookViewer, BookSearchTarget {

    private static final Pattern TXT_CHAPTER_PATTERN = Pattern.compile(
            "^\\s*(第[零一二三四五六七八九十百千万0-9]+[章节回卷集部篇](?:[\\s：:].*)?|Chapter\\s+\\d+(?:[\\s:：.\\-].*)?|CHAPTER\\s+\\d+(?:[\\s:：.\\-].*)?|Section\\s+\\d+(?:[\\s:：.\\-].*)?)\\s*$"
    );
    private static final Pattern MD_HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*$");
    private static final int TXT_CHUNK_SIZE = 12_000;

    public record TextUnit(String title, String rawContent, String plainText) {
    }

    private final JPanel rootPanel = new JPanel(new BorderLayout());
    private final JEditorPane editorPane = new JEditorPane();
    private final JScrollPane scrollPane = UiChrome.wrapScrollPane(editorPane);
    private final Timer autoScrollTimer;

    private volatile List<TextUnit> units = Collections.emptyList();
    private volatile List<SideDrawerPanel.OutlineTreeNodeData> outlineNodes = Collections.emptyList();
    private volatile boolean markdownMode = false;
    private volatile int currentUnitIndex = 0;
    private volatile double currentOffsetRatio = 0.0;
    private volatile String currentSnippet = "";
    private volatile ReaderSettings currentSettings = new ReaderSettings();
    private volatile ViewerViewportListener viewportListener;
    private volatile boolean suppressScrollEvents = false;
    private volatile boolean disposed = false;

    private volatile String activeKeyword = "";
    private volatile boolean activeCaseSensitive = false;
    private volatile int activeTargetOrdinal = -1;

    public PlainMarkdownViewerComponent() {
        editorPane.setEditable(false);
        editorPane.setContentType("text/html; charset=UTF-8");
        scrollPane.getVerticalScrollBar().setUnitIncrement(24);
        rootPanel.add(scrollPane, BorderLayout.CENTER);

        scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (suppressScrollEvents || disposed || units.isEmpty()) {
                return;
            }
            updateOffsetFromScrollBar();
            notifyViewportChanged();
        });

        editorPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (viewportListener != null) {
                    viewportListener.onUserInteraction();
                }
            }
        });

        this.autoScrollTimer = new Timer(50, e -> tickAutoScroll());
        this.autoScrollTimer.setRepeats(true);
    }

    public void openFile(File file, int unitIndex, double offsetRatio) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("File does not exist: " + file);
        }
        byte[] raw = Files.readAllBytes(file.toPath());
        Charset charset = CharsetSniffer.detect(raw);
        String text = new String(raw, charset);
        BookFormat format = BookFormat.fromFile(file);
        boolean isMd = format == BookFormat.MARKDOWN;
        openText(text, isMd, unitIndex, offsetRatio);
    }

    public void openText(String text, boolean isMarkdown, int unitIndex, double offsetRatio) {
        this.markdownMode = isMarkdown;
        String normalized = text != null ? text.replace("\r\n", "\n").replace("\r", "\n") : "";
        List<TextUnit> parsed = isMarkdown ? splitMarkdownUnits(normalized) : splitTxtUnits(normalized);
        this.units = parsed;
        this.outlineNodes = buildOutlineFromUnits(parsed);

        int safeUnit = parsed.isEmpty() ? 0 : Math.max(0, Math.min(unitIndex, parsed.size() - 1));
        double safeRatio = Math.max(0.0, Math.min(1.0, offsetRatio));
        this.currentUnitIndex = safeUnit;
        this.currentOffsetRatio = safeRatio;
        this.activeKeyword = "";
        this.activeTargetOrdinal = -1;

        renderCurrentUnit(safeRatio);
        notifyUnitChanged();
        notifyViewportChanged();
    }

    public List<SideDrawerPanel.OutlineTreeNodeData> getOutlineEntries() {
        return outlineNodes;
    }

    public List<SideDrawerPanel.OutlineEntry> getGenericOutlineEntries() {
        List<SideDrawerPanel.OutlineEntry> list = new ArrayList<>(outlineNodes.size());
        for (SideDrawerPanel.OutlineTreeNodeData node : outlineNodes) {
            list.add(node.getEntry());
        }
        return list;
    }

    public List<TextUnit> getUnits() {
        return units;
    }

    @Override
    public int getUnitCount() {
        return units.size();
    }

    @Override
    public String getUnitLabel(int unitIndex) {
        if (unitIndex < 0 || unitIndex >= units.size()) {
            return "";
        }
        return units.get(unitIndex).title();
    }

    @Override
    public String getUnitPlainText(int unitIndex) {
        if (unitIndex < 0 || unitIndex >= units.size()) {
            return "";
        }
        return units.get(unitIndex).plainText();
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
    public double getCurrentOffsetRatio() {
        return currentOffsetRatio;
    }

    @Override
    public String getCurrentViewportSnippet() {
        return currentSnippet;
    }

    @Override
    public void goToUnit(int unitIndex, double offsetRatio, String anchorId) {
        if (units.isEmpty()) {
            return;
        }
        int safeUnit = Math.max(0, Math.min(unitIndex, units.size() - 1));
        double safeRatio = Math.max(0.0, Math.min(1.0, offsetRatio));
        boolean unitChanged = safeUnit != this.currentUnitIndex;
        this.currentUnitIndex = safeUnit;
        this.currentOffsetRatio = safeRatio;

        renderCurrentUnit(safeRatio);
        if (anchorId != null && !anchorId.isBlank()) {
            SwingUtilities.invokeLater(() -> {
                try {
                    editorPane.scrollToReference(anchorId);
                } catch (Throwable ignored) {
                }
            });
        }
        if (unitChanged) {
            notifyUnitChanged();
        }
        notifyViewportChanged();
    }

    @Override
    public void applySettings(ReaderSettings settings) {
        this.currentSettings = settings != null ? settings : new ReaderSettings();
        double ratio = this.currentOffsetRatio;
        renderCurrentUnit(ratio);

        int speed = this.currentSettings.getAutoScrollPxPerSec();
        if (speed > 0 && !disposed) {
            if (!autoScrollTimer.isRunning()) {
                autoScrollTimer.start();
            }
        } else {
            autoScrollTimer.stop();
        }
    }

    @Override
    public void setViewportListener(ViewerViewportListener listener) {
        this.viewportListener = listener;
    }

    @Override
    public int highlightKeyword(String keyword, boolean caseSensitive, int targetOrdinal) {
        if (units.isEmpty() || keyword == null || keyword.isEmpty()) {
            clearHighlights();
            return 0;
        }
        this.activeKeyword = keyword;
        this.activeCaseSensitive = caseSensitive;
        String plain = getUnitPlainText(currentUnitIndex);
        int total = countMatches(plain, keyword, caseSensitive);
        this.activeTargetOrdinal = total > 0 ? Math.max(0, Math.min(targetOrdinal, total - 1)) : -1;

        renderCurrentUnit(this.currentOffsetRatio);
        if (total > 0) {
            scrollToMatchApprox(plain, keyword, caseSensitive, this.activeTargetOrdinal);
        }
        return total;
    }

    @Override
    public void clearHighlights() {
        if (!activeKeyword.isEmpty()) {
            this.activeKeyword = "";
            this.activeTargetOrdinal = -1;
            renderCurrentUnit(this.currentOffsetRatio);
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        autoScrollTimer.stop();
        units = Collections.emptyList();
        outlineNodes = Collections.emptyList();
    }

    private void renderCurrentUnit(double targetRatio) {
        if (disposed || units.isEmpty()) {
            return;
        }
        TextUnit unit = units.get(currentUnitIndex);
        ReaderThemePalette palette = ReaderThemePalette.resolve(currentSettings);
        String bodyHtml = markdownMode
                ? markdownToHtml(unit.rawContent(), palette)
                : plainTextToHtml(unit.rawContent());

        if (activeKeyword != null && !activeKeyword.isEmpty()) {
            bodyHtml = injectHighlightsIntoHtml(bodyHtml, activeKeyword, activeCaseSensitive, activeTargetOrdinal);
        }

        String fullHtml = "<html><head><style>"
                + "body { background-color:" + palette.getBgHex() + ";"
                + " color:" + palette.getFgHex() + ";"
                + " font-family:'" + escapeHtml(palette.getFontFamily()) + "',sans-serif;"
                + " font-size:" + palette.getFontSize() + "px;"
                + " line-height:" + palette.getLineHeight() + ";"
                + " margin:0; padding:14px " + palette.getHorizontalPadding() + "px; }"
                + "h1,h2,h3,h4 { color:" + palette.getFgHex() + "; margin-top:16px; margin-bottom:8px; }"
                + "p { margin-top:0; margin-bottom:10px; }"
                + "pre, code { font-family:monospace; background-color:" + palette.getBorderHex() + "; }"
                + "pre { padding:8px; }"
                + "blockquote { border-left:3px solid " + palette.getAccentHex() + "; padding-left:10px; color:" + palette.getMutedHex() + "; }"
                + "a { color:" + palette.getAccentHex() + "; }"
                + "hr { border:0; border-top:1px solid " + palette.getBorderHex() + "; }"
                + "</style></head><body>" + bodyHtml + "</body></html>";

        suppressScrollEvents = true;
        try {
            editorPane.setBackground(palette.getBgColor());
            editorPane.setText(fullHtml);
            updateSnippetFromRatio(unit.plainText(), targetRatio);
            SwingUtilities.invokeLater(() -> {
                if (disposed) {
                    return;
                }
                suppressScrollEvents = true;
                try {
                    JScrollBar bar = scrollPane.getVerticalScrollBar();
                    int max = Math.max(0, bar.getMaximum() - bar.getVisibleAmount());
                    bar.setValue((int) Math.round(max * targetRatio));
                } finally {
                    suppressScrollEvents = false;
                }
            });
        } finally {
            suppressScrollEvents = false;
        }
    }

    private void updateOffsetFromScrollBar() {
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        int max = Math.max(0, bar.getMaximum() - bar.getVisibleAmount());
        double ratio = max > 0 ? Math.max(0.0, Math.min(1.0, (double) bar.getValue() / max)) : 0.0;
        this.currentOffsetRatio = ratio;
        if (!units.isEmpty()) {
            updateSnippetFromRatio(units.get(currentUnitIndex).plainText(), ratio);
        }
    }

    private void updateSnippetFromRatio(String plainText, double ratio) {
        if (plainText == null || plainText.isBlank()) {
            this.currentSnippet = "";
            return;
        }
        int len = plainText.length();
        int start = Math.max(0, Math.min(len - 1, (int) Math.round(len * ratio)));
        int nl = plainText.indexOf('\n', start);
        if (nl >= 0 && nl < len - 1 && start > 0) {
            start = nl + 1;
        }
        int end = Math.min(len, start + 140);
        this.currentSnippet = plainText.substring(start, end).replaceAll("\\s+", " ").trim();
    }

    private void scrollToMatchApprox(String plain, String keyword, boolean caseSensitive, int targetOrdinal) {
        String haystack = caseSensitive ? plain : plain.toLowerCase(Locale.ROOT);
        String needle = caseSensitive ? keyword : keyword.toLowerCase(Locale.ROOT);
        int idx = 0;
        int ord = 0;
        int foundPos = -1;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            if (ord == targetOrdinal) {
                foundPos = idx;
                break;
            }
            ord++;
            idx += needle.length();
        }
        if (foundPos >= 0 && !plain.isEmpty()) {
            double ratio = Math.max(0.0, Math.min(1.0, (double) foundPos / plain.length()));
            this.currentOffsetRatio = ratio;
            SwingUtilities.invokeLater(() -> {
                JScrollBar bar = scrollPane.getVerticalScrollBar();
                int max = Math.max(0, bar.getMaximum() - bar.getVisibleAmount());
                bar.setValue((int) Math.round(max * ratio));
            });
        }
    }

    private void tickAutoScroll() {
        if (disposed || currentSettings.getAutoScrollPxPerSec() <= 0) {
            autoScrollTimer.stop();
            return;
        }
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        int delta = Math.max(1, Math.round(currentSettings.getAutoScrollPxPerSec() / 20.0f));
        int next = bar.getValue() + delta;
        int max = Math.max(0, bar.getMaximum() - bar.getVisibleAmount());
        if (next <= max) {
            bar.setValue(next);
        }
    }

    private void notifyUnitChanged() {
        ViewerViewportListener l = viewportListener;
        if (l != null && !units.isEmpty()) {
            String title = units.get(currentUnitIndex).title();
            l.onUnitChanged(currentUnitIndex, units.size(), title);
        }
    }

    private void notifyViewportChanged() {
        ViewerViewportListener l = viewportListener;
        if (l != null && !units.isEmpty()) {
            int total = Math.max(1, units.size());
            double overall = Math.max(0.0, Math.min(1.0, (currentUnitIndex + currentOffsetRatio) / total));
            l.onViewportChanged(currentUnitIndex, currentOffsetRatio, total, overall, currentSnippet);
        }
    }

    public static List<TextUnit> splitTxtUnits(String text) {
        if (text == null || text.isBlank()) {
            return List.of(new TextUnit("正文", "", ""));
        }
        String[] lines = text.split("\n", -1);
        List<TextUnit> units = new ArrayList<>();
        String currentTitle = null;
        StringBuilder currentBody = new StringBuilder();

        for (String line : lines) {
            Matcher m = TXT_CHAPTER_PATTERN.matcher(line);
            if (m.matches()) {
                if (currentTitle != null || currentBody.length() > 0) {
                    String body = currentBody.toString().trim();
                    String title = currentTitle != null ? currentTitle : "前言";
                    if (!body.isEmpty() || currentTitle != null) {
                        units.add(new TextUnit(title, body, body));
                    }
                }
                currentTitle = m.group(1).trim();
                currentBody.setLength(0);
            } else {
                currentBody.append(line).append('\n');
            }
        }

        if (currentTitle != null || currentBody.length() > 0) {
            String body = currentBody.toString().trim();
            if (units.isEmpty() && currentTitle == null) {
                return chunkLargePlainText(body);
            }
            units.add(new TextUnit(currentTitle != null ? currentTitle : "正文", body, body));
        }

        return units.isEmpty() ? List.of(new TextUnit("正文", "", "")) : List.copyOf(units);
    }

    private static List<TextUnit> chunkLargePlainText(String text) {
        if (text.length() <= TXT_CHUNK_SIZE) {
            return List.of(new TextUnit("正文", text, text));
        }
        List<TextUnit> chunks = new ArrayList<>();
        int start = 0;
        int part = 1;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + TXT_CHUNK_SIZE);
            if (end < text.length()) {
                int nl = text.lastIndexOf('\n', end);
                if (nl > start + TXT_CHUNK_SIZE / 2) {
                    end = nl + 1;
                }
            }
            String slice = text.substring(start, end).trim();
            if (!slice.isEmpty()) {
                chunks.add(new TextUnit("Part " + part++, slice, slice));
            }
            start = end;
        }
        return chunks.isEmpty() ? List.of(new TextUnit("正文", text, text)) : List.copyOf(chunks);
    }

    public static List<TextUnit> splitMarkdownUnits(String text) {
        if (text == null || text.isBlank()) {
            return List.of(new TextUnit("Document", "", ""));
        }
        String[] lines = text.split("\n", -1);
        List<TextUnit> units = new ArrayList<>();
        String currentTitle = null;
        StringBuilder currentBody = new StringBuilder();
        boolean inCodeFence = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence;
            }
            if (!inCodeFence) {
                Matcher m = MD_HEADING_PATTERN.matcher(line);
                if (m.matches() && m.group(1).length() <= 2) {
                    if (currentTitle != null || currentBody.length() > 0) {
                        String body = currentBody.toString().trim();
                        if (!body.isEmpty() || currentTitle != null) {
                            units.add(new TextUnit(currentTitle != null ? currentTitle : "Overview", body, stripMarkdown(body)));
                        }
                    }
                    currentTitle = m.group(2).trim();
                    currentBody.setLength(0);
                    currentBody.append(line).append('\n');
                    continue;
                }
            }
            currentBody.append(line).append('\n');
        }

        if (currentTitle != null || currentBody.length() > 0) {
            String body = currentBody.toString().trim();
            units.add(new TextUnit(currentTitle != null ? currentTitle : "Document", body, stripMarkdown(body)));
        }
        return units.isEmpty() ? List.of(new TextUnit("Document", "", "")) : List.copyOf(units);
    }

    private static List<SideDrawerPanel.OutlineTreeNodeData> buildOutlineFromUnits(List<TextUnit> units) {
        List<SideDrawerPanel.OutlineTreeNodeData> nodes = new ArrayList<>(units.size());
        for (int i = 0; i < units.size(); i++) {
            nodes.add(new SideDrawerPanel.OutlineTreeNodeData(
                    new SideDrawerPanel.OutlineEntry(units.get(i).title(), i, ""),
                    List.of()
            ));
        }
        return List.copyOf(nodes);
    }

    private static String plainTextToHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "<p></p>";
        }
        StringBuilder sb = new StringBuilder();
        String[] paras = text.split("\n{2,}");
        for (String p : paras) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                sb.append("<p>").append(escapeHtml(trimmed).replace("\n", "<br/>")).append("</p>");
            }
        }
        return sb.length() == 0 ? "<p></p>" : sb.toString();
    }

    public static String markdownToHtml(String md, ReaderThemePalette palette) {
        if (md == null || md.isEmpty()) {
            return "<p></p>";
        }
        String[] lines = md.split("\n", -1);
        StringBuilder html = new StringBuilder();
        boolean inCode = false;
        boolean inList = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                if (!inCode) {
                    html.append("<pre><code>");
                    inCode = true;
                } else {
                    html.append("</code></pre>");
                    inCode = false;
                }
                continue;
            }
            if (inCode) {
                html.append(escapeHtml(line)).append("\n");
                continue;
            }
            if (trimmed.isEmpty()) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                continue;
            }
            if (trimmed.matches("^([-*_])\\1{2,}$")) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                html.append("<hr/>");
                continue;
            }
            Matcher hm = MD_HEADING_PATTERN.matcher(line);
            if (hm.matches()) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                int level = Math.min(6, hm.group(1).length());
                html.append("<h").append(level).append(">")
                        .append(renderInlineMarkdown(hm.group(2)))
                        .append("</h").append(level).append(">");
                continue;
            }
            if (trimmed.startsWith("> ")) {
                if (inList) {
                    html.append("</ul>");
                    inList = false;
                }
                html.append("<blockquote>").append(renderInlineMarkdown(trimmed.substring(2))).append("</blockquote>");
                continue;
            }
            if (trimmed.matches("^[-*+]\\s+.+") || trimmed.matches("^\\d+\\.\\s+.+")) {
                if (!inList) {
                    html.append("<ul>");
                    inList = true;
                }
                String item = trimmed.replaceFirst("^([-*+]|\\d+\\.)\\s+", "");
                html.append("<li>").append(renderInlineMarkdown(item)).append("</li>");
                continue;
            }
            if (inList) {
                html.append("</ul>");
                inList = false;
            }
            html.append("<p>").append(renderInlineMarkdown(trimmed)).append("</p>");
        }
        if (inList) {
            html.append("</ul>");
        }
        if (inCode) {
            html.append("</code></pre>");
        }
        return html.toString();
    }

    private static String renderInlineMarkdown(String text) {
        String s = escapeHtml(text);
        s = s.replaceAll("`([^`]+)`", "<code>$1</code>");
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
        s = s.replaceAll("\\*([^*]+)\\*", "<i>$1</i>");
        s = s.replaceAll("\\[([^\\]]+)]\\(([^)]+)\\)", "<a href=\"$2\">$1</a>");
        return s;
    }

    private static String stripMarkdown(String md) {
        if (md == null) {
            return "";
        }
        return md.replaceAll("(?m)^#{1,6}\\s+", "")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("\\*([^*]+)\\*", "$1")
                .replaceAll("\\[([^\\]]+)]\\(([^)]+)\\)", "$1");
    }

    private static String injectHighlightsIntoHtml(String html, String keyword, boolean caseSensitive, int targetOrdinal) {
        String escapedKw = escapeHtml(keyword);
        if (escapedKw.isEmpty()) {
            return html;
        }
        StringBuilder out = new StringBuilder(html.length() + 128);
        String cmpKw = caseSensitive ? escapedKw : escapedKw.toLowerCase(Locale.ROOT);
        int i = 0;
        int ord = 0;
        boolean inTag = false;
        while (i < html.length()) {
            char c = html.charAt(i);
            if (c == '<') {
                inTag = true;
                out.append(c);
                i++;
                continue;
            }
            if (c == '>') {
                inTag = false;
                out.append(c);
                i++;
                continue;
            }
            if (!inTag && i + escapedKw.length() <= html.length()) {
                String sub = html.substring(i, i + escapedKw.length());
                String cmpSub = caseSensitive ? sub : sub.toLowerCase(Locale.ROOT);
                if (cmpSub.equals(cmpKw)) {
                    String bg = (ord == targetOrdinal) ? "#FF9632" : "#FFF37A";
                    out.append("<span style='background-color:").append(bg).append(";color:#111111;'>")
                            .append(sub).append("</span>");
                    ord++;
                    i += escapedKw.length();
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
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

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
