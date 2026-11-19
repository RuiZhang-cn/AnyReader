package dev.local.docinspector.search;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 异步全书跨章/跨页关键词检索引擎。
 * <p>
 * 特性：
 * <ul>
 *     <li>通过 {@link BookSearchTarget} 统一检索 EPUB、PDF 及其他文档格式；</li>
 *     <li>支持大小写切换、{@link AtomicBoolean} 随时取消旧查询；</li>
 *     <li>单次最多返回 {@value #MAX_HITS} 条命中，避免内存与 UI 过载；</li>
 *     <li>生成前后各约 {@value #CONTEXT_CHARS} 字上下文摘要，并精确记录 {@code unitIndex + matchOrdinalInUnit}；</li>
 *     <li>按批次（每 {@value #BATCH_SIZE} 条或 {@value #BATCH_INTERVAL_MS}ms）通过 {@link SwingUtilities#invokeLater} 增量推送结果。</li>
 * </ul>
 */
public final class FullTextSearchService implements AutoCloseable {

    public static final int MAX_HITS = 500;
    public static final int CONTEXT_CHARS = 30;
    public static final int BATCH_SIZE = 20;
    public static final long BATCH_INTERVAL_MS = 150L;

    public interface SearchProgressListener {
        void onHitsBatch(List<SearchHit> batch, int totalFoundSoFar, int scannedUnits, int totalUnits);

        void onCompleted(int totalFound, int totalUnits, boolean truncated, boolean cancelled);

        default void onError(Throwable error) {
        }
    }

    private final ExecutorService executor;

    public FullTextSearchService() {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "doc-inspector-search");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 异步执行全书关键词检索，结果分批回调至 Swing EDT。
     */
    public Future<?> searchAsync(BookSearchTarget target,
                                 String keyword,
                                 boolean caseSensitive,
                                 AtomicBoolean cancelFlag,
                                 SearchProgressListener listener) {
        AtomicBoolean effectiveCancel = cancelFlag != null ? cancelFlag : new AtomicBoolean(false);
        return executor.submit(() -> {
            try {
                executeSearch(target, keyword, caseSensitive, effectiveCancel, listener, true);
            } catch (Throwable t) {
                if (listener != null) {
                    SwingUtilities.invokeLater(() -> listener.onError(t));
                }
            }
        });
    }

    /**
     * 同步执行检索并返回全部命中结果（主要用于测试或非 UI 场景）。
     */
    public List<SearchHit> searchSync(BookSearchTarget target,
                                      String keyword,
                                      boolean caseSensitive,
                                      AtomicBoolean cancelFlag) {
        AtomicBoolean effectiveCancel = cancelFlag != null ? cancelFlag : new AtomicBoolean(false);
        List<SearchHit> collected = new ArrayList<>();
        executeSearch(target, keyword, caseSensitive, effectiveCancel, new SearchProgressListener() {
            @Override
            public void onHitsBatch(List<SearchHit> batch, int totalFoundSoFar, int scannedUnits, int totalUnits) {
                collected.addAll(batch);
            }

            @Override
            public void onCompleted(int totalFound, int totalUnits, boolean truncated, boolean cancelled) {
            }
        }, false);
        return Collections.unmodifiableList(collected);
    }

    private void executeSearch(BookSearchTarget target,
                               String keyword,
                               boolean caseSensitive,
                               AtomicBoolean cancelFlag,
                               SearchProgressListener listener,
                               boolean dispatchOnEdt) {
        if (target == null || keyword == null || keyword.isEmpty()) {
            notifyCompleted(listener, dispatchOnEdt, 0, 0, false, cancelFlag.get());
            return;
        }

        int totalUnits = Math.max(0, target.getUnitCount());
        if (totalUnits == 0 || cancelFlag.get()) {
            notifyCompleted(listener, dispatchOnEdt, 0, totalUnits, false, cancelFlag.get());
            return;
        }

        String needle = caseSensitive ? keyword : keyword.toLowerCase(Locale.ROOT);
        int needleLen = needle.length();
        List<SearchHit> batch = new ArrayList<>(BATCH_SIZE);
        int totalFound = 0;
        boolean truncated = false;
        long lastFlushMs = System.currentTimeMillis();

        for (int unitIndex = 0; unitIndex < totalUnits; unitIndex++) {
            if (cancelFlag.get() || Thread.currentThread().isInterrupted()) {
                flushBatch(listener, dispatchOnEdt, batch, totalFound, unitIndex, totalUnits);
                notifyCompleted(listener, dispatchOnEdt, totalFound, totalUnits, truncated, true);
                return;
            }

            String plainText = target.getUnitPlainText(unitIndex);
            if (plainText == null || plainText.isEmpty()) {
                continue;
            }

            String haystack = caseSensitive ? plainText : plainText.toLowerCase(Locale.ROOT);
            String label = safeLabel(target.getUnitLabel(unitIndex), unitIndex);

            int searchFrom = 0;
            int ordinalInUnit = 0;
            while (true) {
                if (cancelFlag.get() || Thread.currentThread().isInterrupted()) {
                    flushBatch(listener, dispatchOnEdt, batch, totalFound, unitIndex, totalUnits);
                    notifyCompleted(listener, dispatchOnEdt, totalFound, totalUnits, truncated, true);
                    return;
                }

                int matchIdx = haystack.indexOf(needle, searchFrom);
                if (matchIdx < 0) {
                    break;
                }

                if (totalFound >= MAX_HITS) {
                    truncated = true;
                    break;
                }

                String matchedOriginal = plainText.substring(matchIdx, Math.min(plainText.length(), matchIdx + needleLen));
                String before = extractBeforeContext(plainText, matchIdx);
                String after = extractAfterContext(plainText, matchIdx + needleLen);

                batch.add(new SearchHit(unitIndex, ordinalInUnit, label, before, matchedOriginal, after));
                totalFound++;
                ordinalInUnit++;
                searchFrom = matchIdx + needleLen;

                long now = System.currentTimeMillis();
                if (batch.size() >= BATCH_SIZE || (now - lastFlushMs) >= BATCH_INTERVAL_MS) {
                    flushBatch(listener, dispatchOnEdt, batch, totalFound, unitIndex + 1, totalUnits);
                    lastFlushMs = now;
                }
            }

            if (truncated) {
                break;
            }
        }

        flushBatch(listener, dispatchOnEdt, batch, totalFound, totalUnits, totalUnits);
        notifyCompleted(listener, dispatchOnEdt, totalFound, totalUnits, truncated, cancelFlag.get());
    }

    private static void flushBatch(SearchProgressListener listener,
                                   boolean dispatchOnEdt,
                                   List<SearchHit> batch,
                                   int totalFound,
                                   int scannedUnits,
                                   int totalUnits) {
        if (batch.isEmpty() || listener == null) {
            batch.clear();
            return;
        }
        List<SearchHit> snapshot = List.copyOf(batch);
        batch.clear();
        if (dispatchOnEdt) {
            SwingUtilities.invokeLater(() -> listener.onHitsBatch(snapshot, totalFound, scannedUnits, totalUnits));
        } else {
            listener.onHitsBatch(snapshot, totalFound, scannedUnits, totalUnits);
        }
    }

    private static void notifyCompleted(SearchProgressListener listener,
                                        boolean dispatchOnEdt,
                                        int totalFound,
                                        int totalUnits,
                                        boolean truncated,
                                        boolean cancelled) {
        if (listener == null) {
            return;
        }
        if (dispatchOnEdt) {
            SwingUtilities.invokeLater(() -> listener.onCompleted(totalFound, totalUnits, truncated, cancelled));
        } else {
            listener.onCompleted(totalFound, totalUnits, truncated, cancelled);
        }
    }

    static String extractBeforeContext(String text, int matchStart) {
        if (text == null || matchStart <= 0) {
            return "";
        }
        int start = Math.max(0, matchStart - CONTEXT_CHARS);
        String raw = text.substring(start, matchStart);
        return collapseWhitespace(raw);
    }

    static String extractAfterContext(String text, int matchEnd) {
        if (text == null || matchEnd >= text.length()) {
            return "";
        }
        int end = Math.min(text.length(), matchEnd + CONTEXT_CHARS);
        String raw = text.substring(matchEnd, end);
        return collapseWhitespace(raw);
    }

    private static String collapseWhitespace(String s) {
        return s.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').replaceAll(" {2,}", " ");
    }

    private static String safeLabel(String label, int unitIndex) {
        if (label != null && !label.isBlank()) {
            return label.trim();
        }
        return "#" + (unitIndex + 1);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
