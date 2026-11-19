package dev.local.docinspector.statusbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 底部状态栏微读广播器：管理句段切片、前后翻句、截断展示（<=36 字符）与瞬隐。
 */
public final class MicroReadBroadcaster {

    public static final int MAX_DISPLAY_CHARS = 36;
    private static final Pattern SENTENCE_SPLIT_PATTERN = Pattern.compile("[^。！？.!?\r\n]+[。！？.!?]?");

    private static final MicroReadBroadcaster INSTANCE = new MicroReadBroadcaster();

    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> hideListeners = new CopyOnWriteArrayList<>();

    private boolean enabled = false;
    private boolean masked = false;
    private List<String> sentences = Collections.emptyList();
    private int currentIndex = 0;

    private MicroReadBroadcaster() {
    }

    public static MicroReadBroadcaster getInstance() {
        return INSTANCE;
    }

    public static MicroReadBroadcaster createForTest() {
        return new MicroReadBroadcaster();
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        synchronized (this) {
            if (this.enabled == enabled) {
                return;
            }
            this.enabled = enabled;
        }
        notifyChange();
    }

    public void setMasked(boolean masked) {
        synchronized (this) {
            if (this.masked == masked) {
                return;
            }
            this.masked = masked;
        }
        notifyChange();
    }

    public void updateFromSnippet(String snippet) {
        List<String> parsed = splitSentences(snippet);
        synchronized (this) {
            this.sentences = parsed;
            this.currentIndex = 0;
        }
        notifyChange();
    }

    public boolean nextSentence() {
        boolean changed = false;
        synchronized (this) {
            if (!sentences.isEmpty() && currentIndex < sentences.size() - 1) {
                currentIndex++;
                changed = true;
            }
        }
        if (changed) {
            notifyChange();
        }
        return changed;
    }

    public boolean prevSentence() {
        boolean changed = false;
        synchronized (this) {
            if (!sentences.isEmpty() && currentIndex > 0) {
                currentIndex--;
                changed = true;
            }
        }
        if (changed) {
            notifyChange();
        }
        return changed;
    }

    public void hideImmediately() {
        synchronized (this) {
            this.enabled = false;
            this.sentences = Collections.emptyList();
            this.currentIndex = 0;
        }
        notifyChange();
        for (Runnable r : hideListeners) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }

    public void clear() {
        synchronized (this) {
            this.sentences = Collections.emptyList();
            this.currentIndex = 0;
        }
        notifyChange();
    }

    public synchronized String getCurrentDisplayText() {
        if (!enabled || masked || sentences.isEmpty()) {
            return "";
        }
        int idx = Math.max(0, Math.min(currentIndex, sentences.size() - 1));
        String raw = sentences.get(idx);
        return truncate(raw, MAX_DISPLAY_CHARS);
    }

    public synchronized int getSentenceCount() {
        return sentences.size();
    }

    public synchronized int getCurrentIndex() {
        return currentIndex;
    }

    public void addChangeListener(Runnable listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public void addHideListener(Runnable listener) {
        if (listener != null) {
            hideListeners.addIfAbsent(listener);
        }
    }

    public void removeHideListener(Runnable listener) {
        hideListeners.remove(listener);
    }

    private void notifyChange() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }

    public static List<String> splitSentences(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        Matcher m = SENTENCE_SPLIT_PATTERN.matcher(text);
        while (m.find()) {
            String s = m.group().replaceAll("\\s+", " ").trim();
            if (!s.isEmpty()) {
                result.add(s);
            }
        }
        return result.isEmpty() ? Collections.emptyList() : List.copyOf(result);
    }

    public static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= maxChars) {
            return trimmed;
        }
        if (maxChars <= 1) {
            return trimmed.substring(0, Math.max(0, maxChars));
        }
        return trimmed.substring(0, maxChars - 1) + "…";
    }
}
