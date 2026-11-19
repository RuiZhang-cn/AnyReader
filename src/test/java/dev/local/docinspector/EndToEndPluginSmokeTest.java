package dev.local.docinspector;

import com.intellij.openapi.util.JDOMUtil;
import com.intellij.util.xmlb.XmlSerializer;
import dev.local.docinspector.epub.EpubBook;
import dev.local.docinspector.epub.EpubBookParser;
import dev.local.docinspector.epub.EpubHtmlDecorator;
import dev.local.docinspector.epub.EpubViewerComponent;
import dev.local.docinspector.epub.EpubVirtualResourceResolver;
import dev.local.docinspector.epub.TocItem;
import dev.local.docinspector.model.BookFormat;
import dev.local.docinspector.model.BookRecord;
import dev.local.docinspector.model.BookmarkRecord;
import dev.local.docinspector.model.PdfColorFilter;
import dev.local.docinspector.model.ReaderSettings;
import dev.local.docinspector.pdf.PdfColorFilterProcessor;
import dev.local.docinspector.pdf.PdfDocumentSession;
import dev.local.docinspector.pdf.PdfOutlineItem;
import dev.local.docinspector.pdf.PdfViewerComponent;
import dev.local.docinspector.search.FullTextSearchService;
import dev.local.docinspector.search.SearchHit;
import dev.local.docinspector.state.DocInspectorStateService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.jdom.Element;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T6 端到端隔离 Smoke 集成测试：
 * 在 @TempDir 中构造真实 EPUB3（含 PNG/CSS/Nav/GB18030）与真实多页 PDF（含目录与中文正文），
 * 串联验证 EPUB 资源路由与外网阻断、PDF 渲染与四滤镜及句柄释放、跨章/跨页全文检索跳转、
 * 以及状态服务重定位与零明文 XML 持久化往返。
 */
class EndToEndPluginSmokeTest {

    private static final byte[] PNG_1X1_BYTES = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89,
            0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
            0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00, 0x05,
            0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00, 0x00,
            0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82
    };

    @TempDir
    Path tempDir;

    @Test
    void fullEndToEndEpubPdfSearchAndStateSmoke() throws Exception {
        File epubFile = buildRealisticEpub3(tempDir.resolve("SecretArchitectureGuide.epub"));
        File pdfFile = buildRealisticPdf(tempDir.resolve("InternalSpecManual.pdf"));

        // 1) EPUB 解析 + EpubVirtualResourceResolver 加载 PNG 字节流 + 拦截外网 URL
        String epubBookId = "smoke_epub_01";
        try (EpubBook book = EpubBookParser.parse(epubFile, epubBookId)) {
            assertEquals("Secret Architecture Guide", book.getTitle());
            assertEquals(3, book.getChapterCount());

            List<TocItem> toc = book.getTocItems();
            assertEquals(3, toc.size());
            assertEquals("Chapter 1: Core Engine", toc.get(0).getTitle());
            assertEquals(0, toc.get(0).getSpineIndex());
            assertEquals("intro", toc.get(0).getFragmentId());
            assertEquals(2, toc.get(2).getSpineIndex());

            // 验证 GB18030 编码章节纯文本正确提取
            String gbChapterText = book.extractChapterPlainText(1);
            assertTrue(gbChapterText.contains("核心网关路由"), "GB18030 chapter text must decode cleanly");
            assertTrue(gbChapterText.contains("smokekeyword"), "Chapter 2 must contain search keyword");

            // 经 EpubVirtualResourceResolver 路由并从 EpubBook 读取 PNG 字节流与 CSS
            EpubVirtualResourceResolver.ResolutionResult pngRoute = EpubVirtualResourceResolver.resolve(
                    "https://doclens.local/" + epubBookId + "/OEBPS/Images/diagram.png",
                    epubBookId, book, 0);
            assertTrue(pngRoute.isAllowed());
            assertEquals(EpubVirtualResourceResolver.RouteKind.ALLOWED_STATIC_RESOURCE, pngRoute.kind());
            EpubBook.ResourceData pngData = book.readResource(pngRoute.normalizedEntryPath());
            assertEquals("image/png", pngData.getMimeType());
            assertArrayEquals(PNG_1X1_BYTES, pngData.getBytes());

            EpubVirtualResourceResolver.ResolutionResult cssRoute = EpubVirtualResourceResolver.resolve(
                    "https://doclens.local/" + epubBookId + "/OEBPS/Styles/reader.css",
                    epubBookId, book, 0);
            assertTrue(cssRoute.isAllowed());
            EpubBook.ResourceData cssData = book.readResource(cssRoute.normalizedEntryPath());
            assertEquals("text/css", cssData.getMimeType());
            assertTrue(new String(cssData.getBytes(), StandardCharsets.UTF_8).contains("line-height"));

            EpubBook.ResourceData chData = book.readResource(book.getSpineChapters().get(0).getNormalizedEntryPath());
            assertEquals("text/html", chData.getMimeType());
            assertFalse(chData.getMimeType().contains(";"));

            // 严格拦截外网 URL 与路径穿越
            assertEquals(EpubVirtualResourceResolver.RouteKind.BLOCKED_EXTERNAL,
                    EpubVirtualResourceResolver.resolve("https://evil.example.com/beacon.png", epubBookId, book, 0).kind());
            assertEquals(EpubVirtualResourceResolver.RouteKind.BLOCKED_EXTERNAL,
                    EpubVirtualResourceResolver.resolve("http://doclens.local/" + epubBookId + "/OEBPS/Images/diagram.png", epubBookId, book, 0).kind());
            assertEquals(EpubVirtualResourceResolver.RouteKind.BLOCKED_INVALID_PATH,
                    EpubVirtualResourceResolver.resolve("https://doclens.local/" + epubBookId + "/OEBPS/../META-INF/container.xml", epubBookId, book, 0).kind());
            assertFalse(EpubVirtualResourceResolver.isAllowedNavigationUrl("https://evil.example.com/phish"));
            assertTrue(EpubVirtualResourceResolver.isAllowedNavigationUrl("about:blank"));

            // 3a) FullTextSearchService 跨章检索 + EpubViewerComponent 命中跳转
            try (FullTextSearchService searchService = new FullTextSearchService()) {
                List<SearchHit> epubHits = searchService.searchSync(book, "SmokeKeyword", false, new AtomicBoolean(false));
                assertEquals(3, epubHits.size(), "SmokeKeyword should match across Chapter 1, 2, 3");
                assertEquals(0, epubHits.get(0).unitIndex());
                assertEquals(1, epubHits.get(1).unitIndex());
                assertEquals(2, epubHits.get(2).unitIndex());

                EpubViewerComponent epubViewer = new EpubViewerComponent();
                try {
                    epubViewer.openBook(book, 0, 0.0, null);
                    SearchHit ch3Hit = epubHits.get(2);
                    int matched = epubViewer.jumpToSearchHit(ch3Hit.unitIndex(), "SmokeKeyword", false, ch3Hit.matchOrdinalInUnit());
                    assertEquals(2, epubViewer.getCurrentUnitIndex());
                    assertTrue(matched >= 1);
                } finally {
                    epubViewer.dispose();
                }
            }
        }
        assertTrue(Files.deleteIfExists(epubFile.toPath()), "EPUB file handle must be released after close");

        // 2) PDF 加载渲染 + 4 种护眼滤镜 + 字形检索定位 + 关闭不锁死文件
        try (PdfDocumentSession session = PdfDocumentSession.open(pdfFile);
             FullTextSearchService searchService = new FullTextSearchService()) {
            assertEquals(3, session.getPageCount());
            List<PdfOutlineItem> outlines = session.getOutlines();
            assertEquals(2, outlines.size());
            assertEquals("Part 1 中文概览", outlines.get(0).getTitle());
            assertEquals(0, outlines.get(0).getPageIndex());
            assertEquals("Part 2 深入实现", outlines.get(1).getTitle());
            assertEquals(2, outlines.get(1).getPageIndex());

            BufferedImage rendered = session.renderPage(0, 1.0f, 0, PdfColorFilter.ORIGINAL);
            assertNotNull(rendered);
            assertTrue(rendered.getWidth() > 0 && rendered.getHeight() > 0);

            // 验证 4 种护眼滤镜均可正常处理渲染图
            for (PdfColorFilter filter : PdfColorFilter.values()) {
                BufferedImage filtered = PdfColorFilterProcessor.apply(rendered, filter);
                assertNotNull(filtered);
                assertEquals(rendered.getWidth(), filtered.getWidth());
                assertEquals(rendered.getHeight(), filtered.getHeight());
                if (filter == PdfColorFilter.ORIGINAL) {
                    assertSame(rendered, filtered);
                }
            }

            // 验证中英文字形坐标定位
            List<Rectangle2D> enRects = session.findHighlightRects(0, "SmokeKeyword", false);
            assertFalse(enRects.isEmpty(), "PDF glyph rects should match SmokeKeyword");
            if (new File("C:/Windows/Fonts/simhei.ttf").isFile()) {
                List<Rectangle2D> zhRects = session.findHighlightRects(1, "中文检索词", false);
                assertFalse(zhRects.isEmpty(), "PDF glyph rects should match Chinese text");
            }

            // 3b) FullTextSearchService 跨页检索 + PdfViewerComponent 命中跳转
            List<SearchHit> pdfHits = searchService.searchSync(session, "SmokeKeyword", false, new AtomicBoolean(false));
            assertEquals(3, pdfHits.size(), "SmokeKeyword should match across all 3 PDF pages");
            assertEquals("P.1", pdfHits.get(0).locationLabel());
            assertEquals("P.3", pdfHits.get(2).locationLabel());

            PdfViewerComponent pdfViewer = new PdfViewerComponent();
            try {
                pdfViewer.openSession(session, 0, 0.0);
                SearchHit target = pdfHits.get(2);
                int count = pdfViewer.jumpToSearchHit(target.unitIndex(), "SmokeKeyword", false, target.matchOrdinalInUnit());
                assertEquals(2, pdfViewer.getCurrentUnitIndex());
                assertTrue(count >= 1);
            } finally {
                pdfViewer.dispose();
            }
        }
        assertTrue(Files.deleteIfExists(pdfFile.toPath()), "PDF file handle must be released after close");

        // 4) DocInspectorStateService 书架、别名、位置、书签、重定位与零明文 XML 落盘往返
        DocInspectorStateService stateService = DocInspectorStateService.createForTest();
        Path originalBookPath = tempDir.resolve("HiddenFolder/绝密武侠小说_第一卷.pdf");
        Path relocatedBookPath = tempDir.resolve("ArchiveFolder/绝密武侠小说_修订版.pdf");
        Files.createDirectories(originalBookPath.getParent());
        Files.createDirectories(relocatedBookPath.getParent());
        Files.writeString(originalBookPath, "placeholder-v1");
        Files.writeString(relocatedBookPath, "placeholder-v2");

        BookRecord added = stateService.addOrGetBook(originalBookPath.toFile());
        String stableBookId = added.getBookId();
        stateService.setBookAlias(stableBookId, "JVM GC Tuning Spec");
        stateService.setBookGroup(stableBookId, "Runtime");
        stateService.setBookPinned(stableBookId, true);
        stateService.updateReadingProgress(stableBookId, 2, 0.68, 3, 0.89);

        BookmarkRecord mark = stateService.addBookmark(
                stableBookId,
                2,
                0.68,
                "第3页 · 核心段落",
                "绝密摘录片段SecretSnippet",
                "私人笔记PrivateNote999"
        );

        assertTrue(stateService.relinkBookPath(stableBookId, relocatedBookPath.toFile()));
        BookRecord afterRelink = stateService.getBook(stableBookId);
        assertNotNull(afterRelink);
        assertEquals(stableBookId, afterRelink.getBookId());
        assertEquals(BookFormat.PDF, afterRelink.getFormat());
        assertEquals(2, afterRelink.getLastUnitIndex());
        assertEquals(0.68, afterRelink.getLastOffsetRatio(), 1e-6);
        assertEquals(1, stateService.listBookmarks(stableBookId).size());

        // 模拟 XML 落盘并校验零明文
        Element serialized = XmlSerializer.serialize(stateService.getState());
        String xmlText = JDOMUtil.write(serialized);
        assertFalse(xmlText.contains("绝密武侠小说"), "Persisted XML must not leak original book title");
        assertFalse(xmlText.contains("HiddenFolder"), "Persisted XML must not leak original folder");
        assertFalse(xmlText.contains("ArchiveFolder"), "Persisted XML must not leak relocated folder");
        assertFalse(xmlText.contains("JVM GC Tuning Spec"), "Persisted XML must not leak alias in plaintext");
        assertFalse(xmlText.contains("核心段落"), "Persisted XML must not leak bookmark location");
        assertFalse(xmlText.contains("SecretSnippet"), "Persisted XML must not leak bookmark snippet");
        assertFalse(xmlText.contains("PrivateNote999"), "Persisted XML must not leak bookmark note");

        // 从 XML 重加载到全新服务实例并校验完全还原
        DocInspectorStateService reloaded = DocInspectorStateService.createForTest();
        Element reparsed = JDOMUtil.load(xmlText);
        reloaded.loadState(XmlSerializer.deserialize(reparsed, DocInspectorStateService.State.class));

        List<BookRecord> restoredBooks = reloaded.listBooks(null, null);
        assertEquals(1, restoredBooks.size());
        BookRecord restoredBook = restoredBooks.get(0);
        assertEquals(stableBookId, restoredBook.getBookId());
        assertEquals("JVM GC Tuning Spec", restoredBook.displayTitle());
        assertEquals(relocatedBookPath.toAbsolutePath().normalize().toString(), restoredBook.decodedPath());
        assertEquals(2, restoredBook.getLastUnitIndex());
        assertEquals(0.68, restoredBook.getLastOffsetRatio(), 1e-6);
        assertEquals(0.89, restoredBook.getOverallProgress(), 1e-6);
        assertTrue(restoredBook.isPinned());
        assertEquals("Runtime", restoredBook.getGroupTag());

        List<BookmarkRecord> restoredMarks = reloaded.listBookmarks(stableBookId);
        assertEquals(1, restoredMarks.size());
        assertEquals(mark.getBookmarkId(), restoredMarks.get(0).getBookmarkId());
        assertEquals("第3页 · 核心段落", restoredMarks.get(0).decodedLocationLabel());
        assertEquals("绝密摘录片段SecretSnippet", restoredMarks.get(0).decodedSnippet());
        assertEquals("私人笔记PrivateNote999", restoredMarks.get(0).decodedNote());
    }

    @Test
    void epubFooterNavShortcutsAndRelinkedEpubBookIdConsistency() throws Exception {
        Files.createDirectories(tempDir.resolve("orig"));
        File originalEpub = buildRealisticEpub3(tempDir.resolve("orig/Guide.epub"));
        Files.createDirectories(tempDir.resolve("moved"));
        File movedEpub = tempDir.resolve("moved/Guide_Renamed.epub").toFile();
        Files.move(originalEpub.toPath(), movedEpub.toPath());

        DocInspectorStateService stateService = DocInspectorStateService.createForTest();
        BookRecord record = stateService.addOrGetBook(originalEpub);
        String originalBookId = record.getBookId();
        assertTrue(stateService.relinkBookPath(originalBookId, movedEpub));

        try (EpubBook relinkedBook = EpubBookParser.parse(movedEpub, record.getBookId())) {
            assertEquals(originalBookId, relinkedBook.getBookId());

            String rawHtml = relinkedBook.readTextResource(
                    relinkedBook.getSpineChapters().get(0).getNormalizedEntryPath());
            EpubHtmlDecorator.StylePalette palette = EpubHtmlDecorator.StylePalette.fromSettings(
                    new ReaderSettings(), "#2B2D30", "#DFE1E5");
            String decorated = EpubHtmlDecorator.decorateChapterHtml(
                    rawHtml,
                    relinkedBook.getBookId(),
                    relinkedBook.getSpineChapters().get(0).getNormalizedEntryPath(),
                    palette,
                    0.0,
                    null,
                    "window.cefQuery({request: payload});"
            );

            assertTrue(decorated.contains("class=\"dl-chapter-nav\""));
            assertTrue(decorated.contains("data-dl-nav=\"prev\""));
            assertTrue(decorated.contains("data-dl-nav=\"next\""));
            assertTrue(decorated.contains("e.key === '['"));
            assertTrue(decorated.contains("e.key === ']'"));
            assertTrue(decorated.contains("e.key === 'j'"));
            assertTrue(decorated.contains("e.key === 'k'"));
            assertTrue(decorated.contains("JSON.stringify({ nav:"));

            EpubViewerComponent viewer = new EpubViewerComponent();
            try {
                viewer.openBook(relinkedBook, 0, 0.0, null);
                assertEquals(0, viewer.getCurrentUnitIndex());

                viewer.handleJsBridgeMessage("{\"nav\":\"next\"}");
                SwingUtilities.invokeAndWait(() -> {});
                assertEquals(1, viewer.getCurrentUnitIndex());

                viewer.handleJsBridgeMessage("{\"nav\":\"prev\"}");
                SwingUtilities.invokeAndWait(() -> {});
                assertEquals(0, viewer.getCurrentUnitIndex());
            } finally {
                viewer.dispose();
            }
        }
    }

    private static File buildRealisticEpub3(Path target) throws IOException {
        Charset gb18030 = Charset.forName("GB18030");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(target.toFile()))) {
            writeZipEntry(zos, "mimetype", "application/epub+zip".getBytes(StandardCharsets.US_ASCII));
            writeZipEntry(zos, "META-INF/container.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                    """.getBytes(StandardCharsets.UTF_8));
            writeZipEntry(zos, "OEBPS/content.opf", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>Secret Architecture Guide</dc:title>
                        <dc:creator>Doc Inspector</dc:creator>
                      </metadata>
                      <manifest>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="css" href="Styles/reader.css" media-type="text/css"/>
                        <item id="img1" href="Images/diagram.png" media-type="image/png"/>
                        <item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="ch2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
                        <item id="ch3" href="Text/ch3.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine>
                        <itemref idref="ch1"/>
                        <itemref idref="ch2"/>
                        <itemref idref="ch3"/>
                      </spine>
                    </package>
                    """.getBytes(StandardCharsets.UTF_8));
            writeZipEntry(zos, "OEBPS/nav.xhtml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                      <body>
                        <nav epub:type="toc">
                          <ol>
                            <li><a href="Text/ch1.xhtml#intro">Chapter 1: Core Engine</a></li>
                            <li><a href="Text/ch2.xhtml">Chapter 2: GB18030 Encoding</a></li>
                            <li><a href="Text/ch3.xhtml">Chapter 3: Summary</a></li>
                          </ol>
                        </nav>
                      </body>
                    </html>
                    """.getBytes(StandardCharsets.UTF_8));
            writeZipEntry(zos, "OEBPS/Styles/reader.css",
                    "body { line-height: 1.7; font-size: 15px; }".getBytes(StandardCharsets.UTF_8));
            writeZipEntry(zos, "OEBPS/Images/diagram.png", PNG_1X1_BYTES);

            writeZipEntry(zos, "OEBPS/Text/ch1.xhtml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <head><link rel="stylesheet" href="../Styles/reader.css"/></head>
                      <body>
                        <h1 id="intro">Core Engine</h1>
                        <p>Chapter one introduces SmokeKeyword and virtual image routing.</p>
                        <img src="../Images/diagram.png" alt="Architecture"/>
                      </body>
                    </html>
                    """.getBytes(StandardCharsets.UTF_8));
            writeZipEntry(zos, "OEBPS/Text/ch2.xhtml", """
                    <html>
                      <body>
                        <h1>第二章 编码测试</h1>
                        <p>这里是无声明 GB18030 的核心网关路由说明，包含 smokekeyword 检索项。</p>
                      </body>
                    </html>
                    """.getBytes(gb18030));
            writeZipEntry(zos, "OEBPS/Text/ch3.xhtml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <body>
                        <h1>Summary</h1>
                        <p>Final chapter verifies SmokeKeyword cross-chapter jump.</p>
                      </body>
                    </html>
                    """.getBytes(StandardCharsets.UTF_8));
        }
        return target.toFile();
    }

    private static File buildRealisticPdf(Path target) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            File simhei = new File("C:/Windows/Fonts/simhei.ttf");
            PDFont font = simhei.isFile()
                    ? PDType0Font.load(doc, simhei)
                    : new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            boolean hasCjk = simhei.isFile();

            List<PDPage> pages = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                pages.add(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 14);
                    cs.newLineAtOffset(50, 760);
                    String line = hasCjk
                            ? "Page " + (i + 1) + " SmokeKeyword 中文检索词与护眼滤镜测试"
                            : "Page " + (i + 1) + " SmokeKeyword PDF rendering test";
                    cs.showText(line);
                    cs.endText();
                }
            }

            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);

            PDOutlineItem part1 = new PDOutlineItem();
            part1.setTitle("Part 1 中文概览");
            PDPageXYZDestination dest0 = new PDPageXYZDestination();
            dest0.setPage(pages.get(0));
            part1.setDestination(dest0);
            outline.addLast(part1);

            PDOutlineItem part2 = new PDOutlineItem();
            part2.setTitle("Part 2 深入实现");
            PDPageXYZDestination dest2 = new PDPageXYZDestination();
            dest2.setPage(pages.get(2));
            part2.setDestination(dest2);
            outline.addLast(part2);

            doc.save(target.toFile());
        }
        return target.toFile();
    }

    private static void writeZipEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }
}
