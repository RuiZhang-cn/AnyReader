package dev.local.docinspector.ui;

import dev.local.docinspector.model.ReaderSettings;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * 老板键伪装遮罩层：逼真的 HTTP / JVM / Regex 技术速查表多 Tab 面板，
 * 使用当前 IDE 原生编辑器字体与配色渲染。
 */
public final class BossMaskPanel extends JPanel {

    private final JTabbedPane tabbedPane = new JTabbedPane();
    private final List<JTextArea> tabAreas = new ArrayList<>();

    public BossMaskPanel() {
        super(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder());

        addCheatTab("HTTP Status & Headers", HTTP_CHEAT_SHEET);
        addCheatTab("JVM GC & Flags", JVM_CHEAT_SHEET);
        addCheatTab("Java Regex & Time", REGEX_TIME_CHEAT_SHEET);

        add(tabbedPane, BorderLayout.CENTER);
        applyPalette(ReaderThemePalette.resolve(new ReaderSettings()));
    }

    private void addCheatTab(String title, String content) {
        JTextArea area = new JTextArea(content);
        area.setEditable(false);
        area.setCaretPosition(0);
        area.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        area.setLineWrap(false);
        tabAreas.add(area);

        JScrollPane scroll = UiChrome.wrapScrollPane(area);
        tabbedPane.addTab(title, scroll);
    }

    public void applyPalette(ReaderThemePalette palette) {
        if (palette == null) {
            return;
        }
        setBackground(palette.getBgColor());
        tabbedPane.setBackground(palette.getBgColor());
        tabbedPane.setForeground(palette.getFgColor());

        ReaderThemePalette.IdeThemeSnapshot ide = ReaderThemePalette.readIdeThemeSnapshot();
        Font mono = new Font(ide.fontFamily(), Font.PLAIN, Math.max(12, Math.min(15, ide.fontSize())));
        for (JTextArea area : tabAreas) {
            area.setBackground(palette.getBgColor());
            area.setForeground(palette.getFgColor());
            area.setCaretColor(palette.getFgColor());
            area.setFont(mono);
        }
    }

    public int getTabCount() {
        return tabbedPane.getTabCount();
    }

    public String getTabTitle(int index) {
        return tabbedPane.getTitleAt(index);
    }

    public void selectTab(int index) {
        if (index >= 0 && index < tabbedPane.getTabCount()) {
            tabbedPane.setSelectedIndex(index);
        }
    }

    private static final String HTTP_CHEAT_SHEET = """
            HTTP/1.1 & HTTP/2 Reference
            =============================================================
            [Status Codes]
              200 OK                  Standard success response
              201 Created             Resource created (Location header)
              204 No Content          Success without response body
              301 Moved Permanently   Cacheable permanent redirect
              304 Not Modified        Conditional GET cache hit (ETag/IMS)
              400 Bad Request         Malformed syntax / validation error
              401 Unauthorized        Missing or invalid credentials
              403 Forbidden           Authenticated but lacks permission
              404 Not Found           Resource does not exist
              409 Conflict            State conflict (optimistic lock)
              429 Too Many Requests   Rate limit exceeded (Retry-After)
              500 Internal Error      Unhandled server exception
              502 Bad Gateway         Invalid upstream response
              503 Service Unavailable Overloaded / maintenance window
              504 Gateway Timeout     Upstream did not respond in time

            [Key Headers]
              Cache-Control: no-store | no-cache | max-age=3600, private
              Content-Security-Policy: default-src 'self'
              Strict-Transport-Security: max-age=31536000; includeSubDomains
              X-Request-Id / Traceparent: W3C distributed trace context
              If-None-Match / ETag: Strong vs weak entity tag validation
            """;

    private static final String JVM_CHEAT_SHEET = """
            JVM 21 GC & Diagnostic Flags
            =============================================================
            [Heap & GC Selection]
              -Xms2g -Xmx2g                     Fixed heap size
              -XX:+UseG1GC                      Default balanced collector
              -XX:MaxGCPauseMillis=200          Target G1 pause time
              -XX:+UseZGC -XX:+ZGenerational    Low-latency generational ZGC
              -XX:MetaspaceSize=256m            Initial metaspace threshold

            [OOM & Flight Recorder]
              -XX:+HeapDumpOnOutOfMemoryError
              -XX:HeapDumpPath=/var/log/heapdump.hprof
              -XX:+ExitOnOutOfMemoryError
              -XX:StartFlightRecording=duration=60s,filename=rec.jfr

            [CLI Diagnostics]
              jcmd <pid> VM.flags               Print effective VM flags
              jcmd <pid> Thread.print           Dump all thread stacks
              jcmd <pid> GC.heap_info           Current heap & metaspace usage
              jstat -gcutil <pid> 1000          1s GC utilization summary
            """;

    private static final String REGEX_TIME_CHEAT_SHEET = """
            Java Pattern & java.time Quick Reference
            =============================================================
            [Pattern Constructs]
              (?i)          Case-insensitive flag
              (?m)          Multiline (^ and $ match line boundaries)
              (?<name>...)  Named capturing group -> matcher.group("name")
              (?=...)       Positive lookahead
              (?!...)       Negative lookahead
              (?<=...)      Positive lookbehind
              \\p{IsHan}     Unicode Han (CJK) script character

            [DateTimeFormatter Patterns]
              yyyy-MM-dd'T'HH:mm:ss.SSSXXX   ISO-8601 with offset
              yyyy-MM-dd HH:mm:ss            Standard log timestamp
              Instant.now()                  UTC epoch timestamp
              ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
              Duration.between(t1, t2)       Time-based duration
            """;
}
