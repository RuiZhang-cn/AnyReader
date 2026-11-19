package dev.local.docinspector.state;

import com.intellij.openapi.util.JDOMUtil;
import com.intellij.util.xmlb.XmlSerializer;
import dev.local.docinspector.model.BookFormat;
import dev.local.docinspector.model.BookRecord;
import dev.local.docinspector.model.BookmarkRecord;
import dev.local.docinspector.model.ImagePrivacyMode;
import dev.local.docinspector.model.PdfColorFilter;
import dev.local.docinspector.model.ThemePreset;
import dev.local.docinspector.privacy.ObfuscationCodec;
import org.jdom.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateServiceAndCodecTest {

    @Test
    void codecRoundTripAndEdgeCases() {
        assertEquals("", ObfuscationCodec.encode(null));
        assertEquals("", ObfuscationCodec.encode(""));
        assertEquals("", ObfuscationCodec.decode(null));
        assertEquals("", ObfuscationCodec.decode(""));

        String sample = "C:/SecretBooks/凡人修仙传_第1卷.epub";
        String encoded = ObfuscationCodec.encode(sample);
        assertTrue(encoded.startsWith("v1:"));
        assertFalse(encoded.contains("SecretBooks"));
        assertFalse(encoded.contains("凡人修仙传"));
        assertEquals(sample, ObfuscationCodec.decode(encoded));

        // Legacy plaintext fallback
        assertEquals("legacy-plain-title", ObfuscationCodec.decode("legacy-plain-title"));

        // Corrupted v1 payload should not throw
        assertEquals("", ObfuscationCodec.decode("v1:!!!not-valid-base64!!!"));
        assertEquals("", ObfuscationCodec.decode("v1:"));
    }

    @Test
    void xmlSerializationHasZeroPlaintextAndRoundTrips(@TempDir Path tempDir) throws Exception {
        DocInspectorStateService service = DocInspectorStateService.createForTest();
        Path bookPath = tempDir.resolve("SecretNovel_Vol1.epub");
        Files.writeString(bookPath, "dummy");

        BookRecord book = service.addOrGetBook(bookPath.toFile());
        service.setBookAlias(book.getBookId(), "Spring Boot Reference");
        service.setBookGroup(book.getBookId(), "Backend");
        service.setBookPinned(book.getBookId(), true);
        service.updateReadingProgress(book.getBookId(), 12, 0.45, 40, 0.31);
        service.updateSettings(s -> {
            s.setThemePreset(ThemePreset.CODE_CAMOUFLAGE);
            s.setFontSize(17);
            s.setImagePrivacyMode(ImagePrivacyMode.COLLAPSED_PILL);
            s.setPdfColorFilter(PdfColorFilter.WARM_SEPIA);
            s.setMicroBarEnabled(true);
        });
        service.addBookmark(
                book.getBookId(),
                12,
                0.45,
                "第12章 秘密山谷",
                "这是一段绝密摘录内容",
                "个人隐私备注xyz");

        Element serialized = XmlSerializer.serialize(service.getState());
        String xml = JDOMUtil.write(serialized);

        // Zero plaintext sensitive strings in XML
        assertFalse(xml.contains("SecretNovel_Vol1"), "XML must not contain original book title");
        assertFalse(xml.contains(bookPath.toString()), "XML must not contain raw file path");
        assertFalse(xml.contains("Spring Boot Reference"), "XML must not contain alias in plaintext");
        assertFalse(xml.contains("秘密山谷"), "XML must not contain bookmark location in plaintext");
        assertFalse(xml.contains("绝密摘录"), "XML must not contain bookmark snippet in plaintext");
        assertFalse(xml.contains("个人隐私备注xyz"), "XML must not contain bookmark note in plaintext");

        // Deserialize into fresh service instance
        Element reparsed = JDOMUtil.load(xml);
        DocInspectorStateService.State restoredState =
                XmlSerializer.deserialize(reparsed, DocInspectorStateService.State.class);

        DocInspectorStateService restored = DocInspectorStateService.createForTest();
        restored.loadState(restoredState);

        List<BookRecord> books = restored.listBooks(null, null);
        assertEquals(1, books.size());
        BookRecord restoredBook = books.get(0);
        assertEquals(book.getBookId(), restoredBook.getBookId());
        assertEquals("Spring Boot Reference", restoredBook.displayTitle());
        assertEquals("SecretNovel_Vol1", restoredBook.decodedTitle());
        assertEquals(bookPath.toAbsolutePath().normalize().toString(), restoredBook.decodedPath());
        assertEquals(12, restoredBook.getLastUnitIndex());
        assertEquals(0.45, restoredBook.getLastOffsetRatio(), 1e-6);
        assertEquals(40, restoredBook.getTotalUnits());
        assertEquals(0.31, restoredBook.getOverallProgress(), 1e-6);
        assertTrue(restoredBook.isPinned());
        assertEquals("Backend", restoredBook.getGroupTag());

        List<BookmarkRecord> marks = restored.listBookmarks(book.getBookId());
        assertEquals(1, marks.size());
        assertEquals("第12章 秘密山谷", marks.get(0).decodedLocationLabel());
        assertEquals("这是一段绝密摘录内容", marks.get(0).decodedSnippet());
        assertEquals("个人隐私备注xyz", marks.get(0).decodedNote());

        assertEquals(ThemePreset.CODE_CAMOUFLAGE, restored.getSettings().getThemePreset());
        assertEquals(17, restored.getSettings().getFontSize());
        assertEquals(ImagePrivacyMode.COLLAPSED_PILL, restored.getSettings().getImagePrivacyMode());
        assertEquals(PdfColorFilter.WARM_SEPIA, restored.getSettings().getPdfColorFilter());
        assertTrue(restored.getSettings().isMicroBarEnabled());
    }

    @Test
    void bookshelfSortingSearchAndAlias(@TempDir Path tempDir) throws Exception {
        DocInspectorStateService service = DocInspectorStateService.createForTest();
        File f1 = tempDir.resolve("Alpha.epub").toFile();
        File f2 = tempDir.resolve("Beta.pdf").toFile();
        File f3 = tempDir.resolve("Gamma.md").toFile();

        BookRecord b1 = service.addOrGetBook(f1);
        Thread.sleep(5);
        BookRecord b2 = service.addOrGetBook(f2);
        Thread.sleep(5);
        BookRecord b3 = service.addOrGetBook(f3);

        // Make b1 pinned, b2 most recently read among unpinned
        Thread.sleep(20);
        service.setBookPinned(b1.getBookId(), true);
        service.updateReadingProgress(b2.getBookId(), 3, 0.2, 10, 0.3);
        service.setBookAlias(b3.getBookId(), "JDK 21 Spec");
        service.setBookGroup(b3.getBookId(), "Java");

        List<BookRecord> all = service.listBooks(null, null);
        assertEquals(3, all.size());
        assertEquals(b1.getBookId(), all.get(0).getBookId());
        assertEquals(b2.getBookId(), all.get(1).getBookId());
        assertEquals(b3.getBookId(), all.get(2).getBookId());

        // Search by alias
        List<BookRecord> byAlias = service.listBooks(null, "jdk 21");
        assertEquals(1, byAlias.size());
        assertEquals("JDK 21 Spec", byAlias.get(0).displayTitle());

        // Search by group
        List<BookRecord> byGroup = service.listBooks("Java", null);
        assertEquals(1, byGroup.size());
        assertEquals(b3.getBookId(), byGroup.get(0).getBookId());

        // Clear alias falls back to original title
        service.setBookAlias(b3.getBookId(), "");
        assertEquals("Gamma", service.getBook(b3.getBookId()).displayTitle());
    }

    @Test
    void relinkPreservesProgressAndBookmarks(@TempDir Path tempDir) {
        DocInspectorStateService service = DocInspectorStateService.createForTest();
        File oldFile = tempDir.resolve("old/Manual.pdf").toFile();
        File newFile = tempDir.resolve("moved/Manual_v2.pdf").toFile();

        BookRecord book = service.addOrGetBook(oldFile);
        String originalId = book.getBookId();
        service.updateReadingProgress(originalId, 8, 0.75, 100, 0.08);
        service.updatePdfViewState(originalId, 1.5f, false, 90);
        BookmarkRecord mark = service.addBookmark(originalId, 8, 0.75, "P.9", "Important snippet", "Check later");

        assertTrue(service.relinkBookPath(originalId, newFile));

        BookRecord relinked = service.getBook(originalId);
        assertNotNull(relinked);
        assertEquals(originalId, relinked.getBookId());
        assertEquals(newFile.toPath().toAbsolutePath().normalize().toString(), relinked.decodedPath());
        assertEquals(BookFormat.PDF, relinked.getFormat());
        assertEquals(8, relinked.getLastUnitIndex());
        assertEquals(0.75, relinked.getLastOffsetRatio(), 1e-6);
        assertEquals(1.5f, relinked.getPdfZoom(), 1e-6);
        assertFalse(relinked.isPdfFitWidth());
        assertEquals(90, relinked.getPdfRotation());

        // Re-adding moved path returns existing relinked record
        BookRecord readded = service.addOrGetBook(newFile);
        assertEquals(originalId, readded.getBookId());
        assertEquals(1, service.listBooks(null, null).size());

        List<BookmarkRecord> marks = service.listBookmarks(originalId);
        assertEquals(1, marks.size());
        assertEquals(mark.getBookmarkId(), marks.get(0).getBookmarkId());

        service.updateBookmarkNote(mark.getBookmarkId(), "Updated note");
        String md = service.exportBookmarksMarkdown(originalId);
        assertTrue(md.contains("P.9"));
        assertTrue(md.contains("Important snippet"));
        assertTrue(md.contains("Updated note"));

        service.removeBookmark(mark.getBookmarkId());
        assertTrue(service.listBookmarks(originalId).isEmpty());
    }

    @Test
    void clearAllTracesWipesBooksBookmarksAndResetsSettings(@TempDir Path tempDir) {
        DocInspectorStateService service = DocInspectorStateService.createForTest();
        BookRecord book = service.addOrGetBook(tempDir.resolve("Secret.txt").toFile());
        service.addBookmark(book.getBookId(), 0, 0.1, "L1", "S1", "N1");
        service.updateSettings(s -> s.setMicroBarEnabled(true));

        service.clearAllTraces();

        assertTrue(service.listBooks(null, null).isEmpty());
        assertTrue(service.listBookmarks(book.getBookId()).isEmpty());
        assertFalse(service.getSettings().isMicroBarEnabled());
        assertNotEquals(null, DocInspectorStateService.getInstance());
    }
}
