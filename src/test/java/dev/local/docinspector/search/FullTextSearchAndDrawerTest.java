package dev.local.docinspector.search;

import dev.local.docinspector.epub.EpubBook;
import dev.local.docinspector.epub.EpubBookParser;
import dev.local.docinspector.epub.EpubViewerComponent;
import dev.local.docinspector.model.BookRecord;
import dev.local.docinspector.model.BookmarkRecord;
import dev.local.docinspector.pdf.PdfDocumentSession;
import dev.local.docinspector.pdf.PdfViewerComponent;
import dev.local.docinspector.state.DocInspectorStateService;
import dev.local.docinspector.ui.InlineFindBar;
import dev.local.docinspector.ui.SideDrawerPanel;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullTextSearchAndDrawerTest {

    @TempDir
    Path tempDir;

    @Test
    void epubCrossChapterSearchAndContextTruncation() throws Exception {
        File epubFile = createMultiChapterEpub(tempDir.resolve("multi-chapter.epub").toFile());
        try (EpubBook book = EpubBookParser.parse(epubFile);
             FullTextSearchService searchService = new FullTextSearchService()) {

            // Case-insensitive search for "Target":
            // Chapter 1: "Target" (1) + "target" (1) = 2
            // Chapter 2: "TARGET" (1) = 1
            // Chapter 3: "Target" (1) = 1
            List<SearchHit> insensitiveHits = searchService.searchSync(book, "Target", false, new AtomicBoolean(false));
            assertEquals(4, insensitiveHits.size());

            assertEquals(0, insensitiveHits.get(0).unitIndex());
            assertEquals(0, insensitiveHits.get(0).matchOrdinalInUnit());
            assertEquals(0, insensitiveHits.get(1).unitIndex());
            assertEquals(1, insensitiveHits.get(1).matchOrdinalInUnit());
            assertEquals(1, insensitiveHits.get(2).unitIndex());
            assertEquals(0, insensitiveHits.get(2).matchOrdinalInUnit());
            assertEquals(2, insensitiveHits.get(3).unitIndex());
            assertEquals(0, insensitiveHits.get(3).matchOrdinalInUnit());

            // Verify context truncation (~30 chars before & after)
            SearchHit firstHit = insensitiveHits.get(0);
            assertTrue(firstHit.beforeText().length() <= FullTextSearchService.CONTEXT_CHARS);
            assertTrue(firstHit.afterText().length() <= FullTextSearchService.CONTEXT_CHARS);
            assertEquals("Target", firstHit.matchedText());

            // Case-sensitive search for "Target" -> only Chapter 1 #0 and Chapter 3 #0
            List<SearchHit> sensitiveHits = searchService.searchSync(book, "Target", true, new AtomicBoolean(false));
            assertEquals(2, sensitiveHits.size());
            assertEquals(0, sensitiveHits.get(0).unitIndex());
            assertEquals(2, sensitiveHits.get(1).unitIndex());
        }
    }

    @Test
    void pdfCrossPageSearchAndJumpHighlight() throws Exception {
        File pdfFile = createMultiPagePdf(tempDir.resolve("multi-page.pdf").toFile());
        try (PdfDocumentSession session = PdfDocumentSession.open(pdfFile);
             FullTextSearchService searchService = new FullTextSearchService()) {

            List<SearchHit> hits = searchService.searchSync(session, "Protocol", false, new AtomicBoolean(false));
            // Page 0: "Protocol" x2, Page 1: "protocol" x1, Page 2: "Protocol" x1
            assertEquals(4, hits.size());
            assertEquals("P.1", hits.get(0).locationLabel());
            assertEquals(0, hits.get(0).unitIndex());
            assertEquals(0, hits.get(0).matchOrdinalInUnit());
            assertEquals(0, hits.get(1).unitIndex());
            assertEquals(1, hits.get(1).matchOrdinalInUnit());
            assertEquals("P.2", hits.get(2).locationLabel());
            assertEquals(1, hits.get(2).unitIndex());
            assertEquals("P.3", hits.get(3).locationLabel());
            assertEquals(2, hits.get(3).unitIndex());

            // Verify PdfViewerComponent jumpToSearchHit
            PdfViewerComponent viewer = new PdfViewerComponent();
            try {
                viewer.openSession(session, 0, 0.0);
                SearchHit targetHit = hits.get(3); // Page 2
                int matchesInTarget = viewer.jumpToSearchHit(
                        targetHit.unitIndex(), "Protocol", false, targetHit.matchOrdinalInUnit());
                assertEquals(2, viewer.getCurrentUnitIndex());
                assertEquals(1, matchesInTarget);
            } finally {
                viewer.dispose();
            }
        }
    }

    @Test
    void searchCancellationAndMax500Cap() throws Exception {
        try (FullTextSearchService searchService = new FullTextSearchService()) {
            // 1. Verify max 500 hits cap
            StringBuilder many = new StringBuilder();
            for (int i = 0; i < 650; i++) {
                many.append("token ").append(i).append(' ');
            }
            BookSearchTarget largeTarget = new BookSearchTarget() {
                @Override
                public int getUnitCount() {
                    return 2;
                }

                @Override
                public String getUnitLabel(int unitIndex) {
                    return "Unit-" + (unitIndex + 1);
                }

                @Override
                public String getUnitPlainText(int unitIndex) {
                    return many.toString();
                }
            };

            CountDownLatch capLatch = new CountDownLatch(1);
            AtomicInteger capTotal = new AtomicInteger();
            AtomicBoolean capTruncated = new AtomicBoolean();
            searchService.searchAsync(largeTarget, "token", true, new AtomicBoolean(false),
                    new FullTextSearchService.SearchProgressListener() {
                        @Override
                        public void onHitsBatch(List<SearchHit> batch, int totalFoundSoFar, int scannedUnits, int totalUnits) {
                        }

                        @Override
                        public void onCompleted(int totalFound, int totalUnits, boolean truncated, boolean cancelled) {
                            capTotal.set(totalFound);
                            capTruncated.set(truncated);
                            capLatch.countDown();
                        }
                    });
            assertTrue(capLatch.await(5, TimeUnit.SECONDS));
            assertEquals(FullTextSearchService.MAX_HITS, capTotal.get());
            assertTrue(capTruncated.get());

            // 2. Verify cancellation via AtomicBoolean
            AtomicBoolean cancelFlag = new AtomicBoolean(false);
            BookSearchTarget slowTarget = new BookSearchTarget() {
                @Override
                public int getUnitCount() {
                    return 50;
                }

                @Override
                public String getUnitLabel(int unitIndex) {
                    return "Slow-" + unitIndex;
                }

                @Override
                public String getUnitPlainText(int unitIndex) {
                    if (unitIndex == 1) {
                        cancelFlag.set(true);
                    }
                    return "needle inside slow unit";
                }
            };

            CountDownLatch cancelLatch = new CountDownLatch(1);
            AtomicBoolean wasCancelled = new AtomicBoolean(false);
            AtomicInteger foundBeforeCancel = new AtomicInteger(-1);
            searchService.searchAsync(slowTarget, "needle", false, cancelFlag,
                    new FullTextSearchService.SearchProgressListener() {
                        @Override
                        public void onHitsBatch(List<SearchHit> batch, int totalFoundSoFar, int scannedUnits, int totalUnits) {
                        }

                        @Override
                        public void onCompleted(int totalFound, int totalUnits, boolean truncated, boolean cancelled) {
                            foundBeforeCancel.set(totalFound);
                            wasCancelled.set(cancelled);
                            cancelLatch.countDown();
                        }
                    });
            assertTrue(cancelLatch.await(5, TimeUnit.SECONDS));
            assertTrue(wasCancelled.get());
            assertTrue(foundBeforeCancel.get() <= 2);
        }
    }

    @Test
    void sideDrawerOutlineMarksSearchAndInlineFindBarIntegration() throws Exception {
        File epubFile = createMultiChapterEpub(tempDir.resolve("drawer-epub.epub").toFile());
        File pdfFile = createMultiPagePdf(tempDir.resolve("drawer-pdf.pdf").toFile());

        DocInspectorStateService stateService = DocInspectorStateService.createForTest();
        BookRecord epubRecord = stateService.addOrGetBook(epubFile);

        try (EpubBook epubBook = EpubBookParser.parse(epubFile);
             PdfDocumentSession pdfSession = PdfDocumentSession.open(pdfFile);
             FullTextSearchService searchService = new FullTextSearchService();
             SideDrawerPanel drawer = new SideDrawerPanel(stateService, searchService)) {

            AtomicInteger lastOutlineUnit = new AtomicInteger(-1);
            AtomicReference<String> lastOutlineFragment = new AtomicReference<>("");
            AtomicReference<SearchHit> lastClickedHit = new AtomicReference<>();

            drawer.setDrawerListener(new SideDrawerPanel.DrawerListener() {
                @Override
                public void onOutlineSelected(int unitIndex, String fragmentId) {
                    lastOutlineUnit.set(unitIndex);
                    lastOutlineFragment.set(fragmentId);
                }

                @Override
                public void onSearchHitSelected(SearchHit hit) {
                    lastClickedHit.set(hit);
                }
            });

            // 1. Outline: EPUB TOC + filter + syncCurrentUnit + #anchor support
            SwingUtilities.invokeAndWait(() -> {
                drawer.setEpubOutline(epubBook.getTocItems());
                assertTrue(drawer.getVisibleOutlineNodeCount() >= 3);

                drawer.filterOutline("Section 2A");
                assertEquals(2, drawer.getVisibleOutlineNodeCount()); // parent Ch.2 + child Section 2A

                drawer.filterOutline("");
                drawer.syncCurrentUnit(1);
                assertNotNull(drawer.getSelectedOutlineEntry());
                assertEquals(1, drawer.getSelectedOutlineEntry().unitIndex());
                assertEquals(-1, lastOutlineUnit.get()); // syncCurrentUnit must not fire jump callback
            });

            // Verify EPUB TocItem fragmentId is preserved in OutlineEntry
            boolean hasAnchorSec2A = epubBook.getTocItems().stream()
                    .flatMap(t -> t.getChildren().stream())
                    .anyMatch(c -> "sec-2a".equals(c.getFragmentId()));
            assertTrue(hasAnchorSec2A);

            // 2. Marks: CRUD & Export Markdown
            SwingUtilities.invokeAndWait(() -> {
                drawer.selectTab(SideDrawerPanel.Tab.MARKS);
                drawer.bindBook(epubRecord.getBookId());
                BookmarkRecord m1 = drawer.addBookmarkFromCurrent(
                        1, 0.35, "Ch.2 · Second Chapter", "Important snippet", "First note");
                assertNotNull(m1);
                assertEquals(1, drawer.getBookmarks().size());

                drawer.updateBookmarkNote(m1.getBookmarkId(), "Updated note");
                assertEquals("Updated note", drawer.getBookmarks().get(0).decodedNote());

                String md = drawer.exportBookmarksMarkdown();
                assertTrue(md.contains("Ch.2 · Second Chapter"));
                assertTrue(md.contains("Important snippet"));
                assertTrue(md.contains("Updated note"));

                drawer.removeBookmark(m1.getBookmarkId());
                assertTrue(drawer.getBookmarks().isEmpty());
            });

            // 3. Search tab async UI update
            SwingUtilities.invokeAndWait(() -> {
                drawer.selectTab(SideDrawerPanel.Tab.SEARCH);
                drawer.setSearchTarget(epubBook);
                drawer.triggerSearch("Target", false);
            });
            // Wait for EDT batches
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                AtomicInteger count = new AtomicInteger();
                SwingUtilities.invokeAndWait(() -> count.set(drawer.getSearchHitCount()));
                if (count.get() == 4) {
                    break;
                }
                Thread.sleep(50);
            }
            SwingUtilities.invokeAndWait(() -> assertEquals(4, drawer.getSearchHitCount()));

            // 4. InlineFindBar linked with PdfViewerComponent & EpubViewerComponent
            PdfViewerComponent pdfViewer = new PdfViewerComponent();
            EpubViewerComponent epubViewer = new EpubViewerComponent();
            try {
                pdfViewer.openSession(pdfSession, 0, 0.0);
                InlineFindBar findBar = new InlineFindBar();
                SwingUtilities.invokeAndWait(() -> {
                    findBar.attachViewer(pdfViewer);
                    findBar.openWithQuery("Protocol");
                    assertTrue(findBar.isVisible());
                    assertEquals(2, findBar.getTotalMatches()); // Page 0 has 2 matches
                    assertEquals("1 / 2", findBar.getCountText());

                    findBar.findNext();
                    assertEquals("2 / 2", findBar.getCountText());

                    findBar.findNext(); // wraps around
                    assertEquals("1 / 2", findBar.getCountText());

                    findBar.findPrevious(); // wraps back
                    assertEquals("2 / 2", findBar.getCountText());

                    findBar.closeBar();
                    assertFalse(findBar.isVisible());
                    assertEquals("0 / 0", findBar.getCountText());
                });

                // Test EpubViewerComponent fallback jumpToSearchHit without crashing
                epubViewer.openBook(epubBook, 0, 0.0, null);
                int epubChapterMatches = epubViewer.jumpToSearchHit(2, "Target", true, 0);
                assertEquals(2, epubViewer.getCurrentUnitIndex());
                assertEquals(1, epubChapterMatches);
            } finally {
                pdfViewer.dispose();
                epubViewer.dispose();
            }
        }
    }

    private File createMultiChapterEpub(File outFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outFile), StandardCharsets.UTF_8)) {
            writeZipEntry(zos, "mimetype", "application/epub+zip");
            writeZipEntry(zos, "META-INF/container.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                    """);
            writeZipEntry(zos, "OEBPS/content.opf", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Search Test Book</dc:title>
                        <dc:creator>Test Author</dc:creator>
                      </metadata>
                      <manifest>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                        <item id="ch3" href="ch3.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine>
                        <itemref idref="ch1"/>
                        <itemref idref="ch2"/>
                        <itemref idref="ch3"/>
                      </spine>
                    </package>
                    """);
            writeZipEntry(zos, "OEBPS/nav.xhtml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                      <body>
                        <nav epub:type="toc">
                          <ol>
                            <li><a href="ch1.xhtml">First Chapter</a></li>
                            <li>
                              <a href="ch2.xhtml">Second Chapter</a>
                              <ol>
                                <li><a href="ch2.xhtml#sec-2a">Section 2A</a></li>
                              </ol>
                            </li>
                            <li><a href="ch3.xhtml">Third Chapter</a></li>
                          </ol>
                        </nav>
                      </body>
                    </html>
                    """);
            writeZipEntry(zos, "OEBPS/ch1.xhtml", """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        <p>Prefix text that is definitely longer than thirty characters before Target appears here, followed by suffix text longer than thirty characters and another target word.</p>
                      </body>
                    </html>
                    """);
            writeZipEntry(zos, "OEBPS/ch2.xhtml", """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        <h2 id="sec-2a">Section 2A</h2>
                        <p>Chapter two mentions TARGET in uppercase.</p>
                      </body>
                    </html>
                    """);
            writeZipEntry(zos, "OEBPS/ch3.xhtml", """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        <p>Chapter three has Target again at the end.</p>
                      </body>
                    </html>
                    """);
        }
        return outFile;
    }

    private File createMultiPagePdf(File outFile) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            List<PDPage> pages = new ArrayList<>();
            String[] texts = {
                    "Page one introduces the Protocol design and Protocol lifecycle.",
                    "Page two covers network protocol framing details.",
                    "Page three summarizes the Protocol verification checklist."
            };
            for (String line : texts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                pages.add(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(line);
                    cs.endText();
                }
            }

            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            for (int i = 0; i < pages.size(); i++) {
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle("PDF Section " + (i + 1));
                PDPageFitDestination dest = new PDPageFitDestination();
                dest.setPage(pages.get(i));
                item.setDestination(dest);
                outline.addLast(item);
            }
            doc.save(outFile);
        }
        return outFile;
    }

    private static void writeZipEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
