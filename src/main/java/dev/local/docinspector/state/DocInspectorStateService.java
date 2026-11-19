package dev.local.docinspector.state;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import dev.local.docinspector.model.BookFormat;
import dev.local.docinspector.model.BookRecord;
import dev.local.docinspector.model.BookmarkRecord;
import dev.local.docinspector.model.ReaderSettings;
import dev.local.docinspector.privacy.ObfuscationCodec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Doc Inspector 应用级持久化状态服务。
 * 存储于 {@code options/ide-doc-lens.xml}，支持无 IDEA 容器的纯单元测试单例与 {@link #createForTest()}。
 */
@Service(Service.Level.APP)
@State(
        name = "DocInspectorStore",
        storages = @Storage("ide-doc-lens.xml")
)
public final class DocInspectorStateService implements PersistentStateComponent<DocInspectorStateService.State> {

    public static class State {
        public List<BookRecord> books = new ArrayList<>();
        public List<BookmarkRecord> bookmarks = new ArrayList<>();
        public ReaderSettings settings = new ReaderSettings();

        public State() {
        }
    }

    private static final class TestHolder {
        private static final DocInspectorStateService INSTANCE = new DocInspectorStateService();
    }

    private State state = new State();

    public DocInspectorStateService() {
    }

    public static DocInspectorStateService getInstance() {
        Application app = ApplicationManager.getApplication();
        if (app != null) {
            DocInspectorStateService service = app.getService(DocInspectorStateService.class);
            if (service != null) {
                return service;
            }
        }
        return TestHolder.INSTANCE;
    }

    public static DocInspectorStateService createForTest() {
        return new DocInspectorStateService();
    }

    @Override
    public synchronized @Nullable State getState() {
        return state;
    }

    @Override
    public synchronized void loadState(@NotNull State loaded) {
        if (loaded.books == null) {
            loaded.books = new ArrayList<>();
        }
        if (loaded.bookmarks == null) {
            loaded.bookmarks = new ArrayList<>();
        }
        if (loaded.settings == null) {
            loaded.settings = new ReaderSettings();
        }
        this.state = loaded;
    }

    public synchronized BookRecord addOrGetBook(File file) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        String normalizedPath = normalizePath(file);
        String computedId = BookRecord.computeBookId(normalizedPath);

        for (BookRecord existing : state.books) {
            if (Objects.equals(existing.getBookId(), computedId)
                    || existing.decodedPath().equalsIgnoreCase(normalizedPath)) {
                return existing;
            }
        }

        BookFormat format = BookFormat.fromFile(file);
        if (format == null) {
            throw new IllegalArgumentException("Unsupported document format: " + file.getName());
        }

        long now = System.currentTimeMillis();
        BookRecord record = new BookRecord();
        record.setBookId(computedId);
        record.setEncodedPath(ObfuscationCodec.encode(normalizedPath));
        record.setEncodedTitle(ObfuscationCodec.encode(extractBaseTitle(file, format)));
        record.setEncodedAlias("");
        record.setGroupTag("Default");
        record.setFormat(format);
        record.setAddedEpochMs(now);
        record.setLastReadEpochMs(now);

        state.books.add(record);
        return record;
    }

    public synchronized @Nullable BookRecord getBook(String bookId) {
        return findBookById(bookId);
    }

    public synchronized List<BookRecord> listBooks(@Nullable String groupFilter, @Nullable String queryFilter) {
        String normalizedGroup = groupFilter == null ? "" : groupFilter.trim();
        boolean filterByGroup = !normalizedGroup.isEmpty()
                && !"All".equalsIgnoreCase(normalizedGroup)
                && !"全部".equals(normalizedGroup);

        String normalizedQuery = queryFilter == null ? "" : queryFilter.trim().toLowerCase(Locale.ROOT);
        boolean filterByQuery = !normalizedQuery.isEmpty();

        List<BookRecord> result = new ArrayList<>();
        for (BookRecord record : state.books) {
            if (filterByGroup && !normalizedGroup.equals(record.getGroupTag())) {
                continue;
            }
            if (filterByQuery && !matchesQuery(record, normalizedQuery)) {
                continue;
            }
            result.add(record);
        }

        result.sort(Comparator
                .comparing(BookRecord::isPinned, Comparator.reverseOrder())
                .thenComparing(Comparator.comparingLong(BookRecord::getLastReadEpochMs).reversed())
                .thenComparing(Comparator.comparingLong(BookRecord::getAddedEpochMs).reversed()));
        return result;
    }

    public synchronized List<String> listGroupTags() {
        Set<String> tags = new LinkedHashSet<>();
        tags.add("Default");
        for (BookRecord record : state.books) {
            if (!record.getGroupTag().isBlank()) {
                tags.add(record.getGroupTag());
            }
        }
        return new ArrayList<>(tags);
    }

    public synchronized void updateReadingProgress(
            String bookId,
            int unitIndex,
            double offsetRatio,
            int totalUnits,
            double overallProgress) {
        BookRecord book = findBookById(bookId);
        if (book == null) {
            return;
        }
        book.setLastUnitIndex(unitIndex);
        book.setLastOffsetRatio(offsetRatio);
        if (totalUnits > 0) {
            book.setTotalUnits(totalUnits);
        }
        book.setOverallProgress(overallProgress);
        book.setLastReadEpochMs(System.currentTimeMillis());
    }

    public synchronized void updatePdfViewState(String bookId, float zoom, boolean fitWidth, int rotation) {
        BookRecord book = findBookById(bookId);
        if (book == null) {
            return;
        }
        book.setPdfZoom(zoom);
        book.setPdfFitWidth(fitWidth);
        book.setPdfRotation(rotation);
    }

    public synchronized void setBookAlias(String bookId, @Nullable String alias) {
        BookRecord book = findBookById(bookId);
        if (book == null) {
            return;
        }
        if (alias == null || alias.isBlank()) {
            book.setEncodedAlias("");
        } else {
            book.setEncodedAlias(ObfuscationCodec.encode(alias.trim()));
        }
    }

    public synchronized void setBookGroup(String bookId, @Nullable String groupTag) {
        BookRecord book = findBookById(bookId);
        if (book == null) {
            return;
        }
        book.setGroupTag(groupTag);
    }

    public synchronized void setBookPinned(String bookId, boolean pinned) {
        BookRecord book = findBookById(bookId);
        if (book == null) {
            return;
        }
        book.setPinned(pinned);
    }

    public synchronized boolean relinkBookPath(String bookId, File newFile) {
        BookRecord book = findBookById(bookId);
        if (book == null || newFile == null) {
            return false;
        }
        BookFormat format = BookFormat.fromFile(newFile);
        if (format == null) {
            return false;
        }
        book.updateEncodedPath(normalizePath(newFile));
        book.setFormat(format);
        return true;
    }

    public synchronized void removeBook(String bookId, boolean deleteBookmarks) {
        state.books.removeIf(b -> Objects.equals(b.getBookId(), bookId));
        if (deleteBookmarks) {
            state.bookmarks.removeIf(m -> Objects.equals(m.getBookId(), bookId));
        }
    }

    public synchronized BookmarkRecord addBookmark(
            String bookId,
            int unitIndex,
            double offsetRatio,
            @Nullable String locationLabel,
            @Nullable String snippet,
            @Nullable String note) {
        BookmarkRecord record = new BookmarkRecord();
        record.setBookmarkId(UUID.randomUUID().toString());
        record.setBookId(bookId);
        record.setUnitIndex(unitIndex);
        record.setOffsetRatio(offsetRatio);
        record.setEncodedLocationLabel(ObfuscationCodec.encode(locationLabel));
        record.setEncodedSnippet(ObfuscationCodec.encode(snippet));
        record.setEncodedNote(ObfuscationCodec.encode(note));
        record.setCreatedAtEpochMs(System.currentTimeMillis());
        state.bookmarks.add(record);
        return record;
    }

    public synchronized List<BookmarkRecord> listBookmarks(String bookId) {
        List<BookmarkRecord> result = new ArrayList<>();
        for (BookmarkRecord record : state.bookmarks) {
            if (Objects.equals(record.getBookId(), bookId)) {
                result.add(record);
            }
        }
        result.sort(Comparator
                .comparingInt(BookmarkRecord::getUnitIndex)
                .thenComparingDouble(BookmarkRecord::getOffsetRatio)
                .thenComparingLong(BookmarkRecord::getCreatedAtEpochMs));
        return result;
    }

    public synchronized void updateBookmarkNote(String bookmarkId, @Nullable String note) {
        for (BookmarkRecord record : state.bookmarks) {
            if (Objects.equals(record.getBookmarkId(), bookmarkId)) {
                record.setEncodedNote(ObfuscationCodec.encode(note));
                return;
            }
        }
    }

    public synchronized void removeBookmark(String bookmarkId) {
        state.bookmarks.removeIf(m -> Objects.equals(m.getBookmarkId(), bookmarkId));
    }

    public synchronized String exportBookmarksMarkdown(String bookId) {
        BookRecord book = findBookById(bookId);
        String title = book != null ? book.displayTitle() : (bookId == null ? "" : bookId);
        List<BookmarkRecord> bookmarks = listBookmarks(bookId);

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append(" - Bookmarks\n\n");
        if (bookmarks.isEmpty()) {
            sb.append("_No bookmarks._\n");
            return sb.toString();
        }
        for (BookmarkRecord mark : bookmarks) {
            String loc = mark.decodedLocationLabel();
            if (loc.isBlank()) {
                loc = "Unit " + (mark.getUnitIndex() + 1);
            }
            sb.append("- **").append(loc).append("**\n");
            String snippet = mark.decodedSnippet();
            if (!snippet.isBlank()) {
                sb.append("  > ").append(snippet.replace("\r\n", " ").replace('\n', ' ')).append('\n');
            }
            String note = mark.decodedNote();
            if (!note.isBlank()) {
                sb.append("  Note: ").append(note).append('\n');
            }
        }
        return sb.toString();
    }

    public synchronized ReaderSettings getSettings() {
        return state.settings;
    }

    public synchronized void updateSettings(Consumer<ReaderSettings> mutator) {
        if (mutator != null) {
            mutator.accept(state.settings);
        }
    }

    public synchronized void clearAllTraces() {
        state.books.clear();
        state.bookmarks.clear();
        state.settings = new ReaderSettings();
    }

    private BookRecord findBookById(String bookId) {
        if (bookId == null) {
            return null;
        }
        for (BookRecord record : state.books) {
            if (Objects.equals(record.getBookId(), bookId)) {
                return record;
            }
        }
        return null;
    }

    private static boolean matchesQuery(BookRecord record, String lowerQuery) {
        if (record.decodedTitle().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            return true;
        }
        if (record.decodedAlias().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            return true;
        }
        return record.getGroupTag().toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    private static String normalizePath(File file) {
        return file.toPath().toAbsolutePath().normalize().toString();
    }

    private static String extractBaseTitle(File file, BookFormat format) {
        String name = file.getName();
        if (format == BookFormat.IMAGE_DIR && file.isDirectory()) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            return name.substring(0, dot);
        }
        return name;
    }
}
