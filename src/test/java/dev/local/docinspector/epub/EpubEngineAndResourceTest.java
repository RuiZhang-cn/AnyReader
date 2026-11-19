package dev.local.docinspector.epub;

import dev.local.docinspector.model.ImagePrivacyMode;
import dev.local.docinspector.model.ReaderSettings;
import dev.local.docinspector.model.ThemePreset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class EpubEngineAndResourceTest {

    private static final byte[] SAMPLE_PNG_BYTES = new byte[]{
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
    void charsetSnifferHandlesBomDeclarationAndGb18030Fallback() {
        Charset gb18030 = Charset.forName("GB18030");
        byte[] rawGb = "<html><body><p>中文GB18030无声明自动回退测试</p></body></html>".getBytes(gb18030);
        assertEquals(gb18030, CharsetSniffer.detect(rawGb));
        assertTrue(CharsetSniffer.decode(rawGb).contains("中文GB18030无声明自动回退测试"));

        byte[] utf8Bom = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                "<p>UTF8 BOM 文本</p>".getBytes(StandardCharsets.UTF_8));
        assertEquals(StandardCharsets.UTF_8, CharsetSniffer.detect(utf8Bom));
        assertEquals("<p>UTF8 BOM 文本</p>", CharsetSniffer.decode(utf8Bom));

        byte[] xmlDeclared = "<?xml version=\"1.0\" encoding=\"GBK\"?><p>测试</p>".getBytes(gb18030);
        assertEquals(gb18030, CharsetSniffer.detect(xmlDeclared));
    }

    @Test
    void parsesEpub3WithGb18030ChapterPngAndRejectsPathTraversal() throws Exception {
        File epubFile = buildSampleEpub3(tempDir.resolve("sample-v3.epub"));

        try (EpubBook book = EpubBookParser.parse(epubFile, "book_abc123")) {
            assertEquals("Doc Inspector Spec Book", book.getTitle());
            assertEquals("Local Author", book.getCreator());
            assertEquals(2, book.getChapterCount());

            // EPUB3 Nav 解析与锚点映射
            assertEquals(2, book.getTocItems().size());
            TocItem firstToc = book.getTocItems().get(0);
            assertEquals("Chapter 1: Architecture", firstToc.getTitle());
            assertEquals(0, firstToc.getSpineIndex());
            assertEquals("sec1", firstToc.getFragmentId());
            assertEquals(1, firstToc.getChildren().size());
            assertEquals("Sub Section 1.1", firstToc.getChildren().get(0).getTitle());

            // GB18030 章节纯文本提取（剥离 script/style）
            String ch2Text = book.extractChapterPlainText(1);
            assertTrue(ch2Text.contains("中文编码章节正文"));
            assertFalse(ch2Text.contains("console.log"));

            // 按需读取 PNG 图片与 CSS
            EpubBook.ResourceData png = book.readResource("OEBPS/Images/sample.png");
            assertEquals("image/png", png.getMimeType());
            assertArrayEquals(SAMPLE_PNG_BYTES, png.getBytes());

            EpubBook.ResourceData css = book.readResource("OEBPS/Styles/main.css");
            assertEquals("text/css", css.getMimeType());
            assertTrue(new String(css.getBytes(), StandardCharsets.UTF_8).contains("font-weight"));

            EpubBook.ResourceData xhtml = book.readResource(book.getSpineChapters().get(0).getNormalizedEntryPath());
            assertEquals("text/html", xhtml.getMimeType());
            assertEquals("application/xml", EpubBook.guessMimeType("META-INF/container.xml"));
            assertEquals("text/html", EpubCefResourceHandler.sanitizeBareMimeType("text/html; charset=utf-8"));
            assertEquals("text/css", EpubCefResourceHandler.sanitizeBareMimeType("TEXT/CSS; charset=UTF-8"));
            assertEquals("application/octet-stream", EpubCefResourceHandler.sanitizeBareMimeType(""));

            // 路径穿越严厉拒绝
            assertThrows(SecurityException.class, () -> book.readResource("../secret.txt"));
            assertThrows(SecurityException.class, () -> book.readResource("OEBPS/../../etc/passwd"));
            assertThrows(SecurityException.class, () -> book.readResource("/OEBPS/Images/sample.png"));
            assertThrows(SecurityException.class, () -> EpubBook.resolveRelativeEntryPath("OEBPS/Text", "../../../etc/passwd"));
        }

        // 关闭后可正常删除（无文件句柄泄漏）
        assertTrue(Files.deleteIfExists(epubFile.toPath()));
    }

    @Test
    void parsesEpub2NcxFallbackWhenNoEpub3Nav() throws Exception {
        File epub2 = buildSampleEpub2Ncx(tempDir.resolve("sample-v2.epub"));
        try (EpubBook book = EpubBookParser.parse(epub2, "book_v2")) {
            assertEquals(1, book.getChapterCount());
            assertEquals(1, book.getTocItems().size());
            assertEquals("NCX Chapter One", book.getTocItems().get(0).getTitle());
            assertEquals(0, book.getTocItems().get(0).getSpineIndex());
            assertEquals("p1", book.getTocItems().get(0).getFragmentId());
        }
    }

    @Test
    void virtualResourceResolverBlocksExternalAndTraversalUrls() throws Exception {
        File epubFile = buildSampleEpub3(tempDir.resolve("route-test.epub"));
        try (EpubBook book = EpubBookParser.parse(epubFile, "bk01")) {
            // 合法虚拟章节与静态资源
            var chRes = EpubVirtualResourceResolver.resolve(
                    "https://doclens.local/bk01/__chapter__/0.html", "bk01", book, 0);
            assertTrue(chRes.isAllowed());
            assertEquals(EpubVirtualResourceResolver.RouteKind.ALLOWED_VIRTUAL_CHAPTER, chRes.kind());
            assertEquals(0, chRes.spineIndex());

            var imgRes = EpubVirtualResourceResolver.resolve(
                    "https://doclens.local/bk01/OEBPS/Images/sample.png", "bk01", book, 0);
            assertTrue(imgRes.isAllowed());
            assertEquals(EpubVirtualResourceResolver.RouteKind.ALLOWED_STATIC_RESOURCE, imgRes.kind());
            assertEquals("OEBPS/Images/sample.png", imgRes.normalizedEntryPath());

            // 严厉阻断外网与伪造域名
            assertEquals(EpubVirtualResourceResolver.RouteKind.BLOCKED_EXTERNAL,
                    EpubVirtualResourceResolver.resolve("https://evil.example.com/track.js", "bk01", book, 0).kind());
            assertEquals(EpubVirtualResourceResolver.RouteKind.BLOCKED_EXTERNAL,
                    EpubVirtualResourceResolver.resolve("https://doclens.local.evil.com/bk01/a.png", "bk01", book, 0).kind());
            assertEquals(EpubVirtualResourceResolver.RouteKind.BLOCKED_EXTERNAL,
                    EpubVirtualResourceResolver.resolve("http://doclens.local/bk01/a.png", "bk01", book, 0).kind());
            assertFalse(EpubVirtualResourceResolver.isAllowedNavigationUrl("https://www.baidu.com"));
            assertTrue(EpubVirtualResourceResolver.isAllowedNavigationUrl("about:blank"));

            // 阻断路径穿越与 bookId 不匹配
            assertEquals(EpubVirtualResourceResolver.RouteKind.BLOCKED_INVALID_PATH,
                    EpubVirtualResourceResolver.resolve("https://doclens.local/bk01/OEBPS/%2e%2e/%2e%2e/secret", "bk01", book, 0).kind());
            assertEquals(EpubVirtualResourceResolver.RouteKind.BLOCKED_BOOK_MISMATCH,
                    EpubVirtualResourceResolver.resolve("https://doclens.local/otherBook/OEBPS/Images/sample.png", "bk01", book, 0).kind());
        }
    }

    @Test
    void htmlDecoratorInjectsCssVariablesCollapsedPillAndLightbox() throws Exception {
        File epubFile = buildSampleEpub3(tempDir.resolve("decorate-test.epub"));
        try (EpubBook book = EpubBookParser.parse(epubFile, "bk99")) {
            String rawHtml = book.readTextResource(book.getSpineChapters().get(0).getNormalizedEntryPath());
            ReaderSettings settings = new ReaderSettings();
            settings.setThemePreset(ThemePreset.CODE_CAMOUFLAGE);
            settings.setImagePrivacyMode(ImagePrivacyMode.COLLAPSED_PILL);
            settings.setFontSize(17);

            EpubHtmlDecorator.StylePalette palette =
                    EpubHtmlDecorator.StylePalette.fromSettings(settings, "#2B2D30", "#DFE1E5");

            String decorated = EpubHtmlDecorator.decorateChapterHtml(
                    rawHtml,
                    "bk99",
                    book.getSpineChapters().get(0).getNormalizedEntryPath(),
                    palette,
                    0.35,
                    "sec1",
                    "window.cefQuery({request: payload});"
            );

            assertTrue(decorated.contains("<base href=\"https://doclens.local/bk99/OEBPS/Text/\">"));
            assertTrue(decorated.contains("<link rel=\"stylesheet\" href=\"../Styles/main.css\">"));
            assertTrue(decorated.contains("--dl-font-size: 17px;"));
            assertTrue(decorated.contains("color-scheme: dark;"));
            assertTrue(decorated.contains("::-webkit-scrollbar"));
            assertTrue(decorated.contains("class=\"dl-img-collapsed\""));
            assertTrue(decorated.contains("[Figure · 点击查看]"));
            assertTrue(decorated.contains("id=\"dl-lightbox\""));
            assertTrue(decorated.contains("window.__dlApplyStyle"));
            assertTrue(decorated.contains("window.__dlHighlight"));
            assertTrue(decorated.contains("window.__dlAutoScroll"));
            assertTrue(decorated.contains("Content-Security-Policy"));

            // 验证内嵌 SVG 封面图像也能正确挂载隐私类与折叠胶囊
            String svgRaw = "<html><body><div><svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"><image width=\"600\" height=\"800\" xlink:href=\"../Images/sample.png\"/></svg></div></body></html>";
            String decoratedSvg = EpubHtmlDecorator.decorateChapterHtml(
                    svgRaw,
                    "bk99",
                    "OEBPS/Text/cover.xhtml",
                    palette,
                    0.0,
                    "",
                    ""
            );
            assertTrue(decoratedSvg.contains("class=\"dl-content-svg\""));
            assertTrue(decoratedSvg.contains("[Figure · 点击查看]"));
            assertTrue(decoratedSvg.contains("svg.dl-content-svg"));
        }
    }

    @Test
    void viewerComponentSafelyOpensAndHighlightsInHeadlessEnv() throws Exception {
        File epubFile = buildSampleEpub3(tempDir.resolve("viewer-test.epub"));
        try (EpubBook book = EpubBookParser.parse(epubFile, "bk_headless")) {
            EpubViewerComponent viewer = new EpubViewerComponent();
            assertNotNull(viewer.getComponent());
            viewer.openBook(book, 0, 0.25, "sec1");
            assertEquals(0, viewer.getCurrentUnitIndex());
            assertEquals(2, viewer.getUnitCount());

            ReaderSettings settings = new ReaderSettings();
            settings.setThemePreset(ThemePreset.WARM_PARCHMENT);
            viewer.applySettings(settings);

            int hits = viewer.highlightKeyword("Architecture", false, 0);
            assertTrue(hits >= 1);
            viewer.goToUnit(1, 0.0, null);
            assertEquals(1, viewer.getCurrentUnitIndex());
            viewer.dispose();
            assertTrue(book.isClosed());
        }
    }

    private static File buildSampleEpub3(Path target) throws IOException {
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
                        <dc:title>Doc Inspector Spec Book</dc:title>
                        <dc:creator>Local Author</dc:creator>
                      </metadata>
                      <manifest>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="css" href="Styles/main.css" media-type="text/css"/>
                        <item id="img1" href="Images/sample.png" media-type="image/png"/>
                        <item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="ch2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine>
                        <itemref idref="ch1"/>
                        <itemref idref="ch2"/>
                      </spine>
                    </package>
                    """.getBytes(StandardCharsets.UTF_8));

            writeZipEntry(zos, "OEBPS/nav.xhtml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                      <body>
                        <nav epub:type="toc">
                          <ol>
                            <li>
                              <a href="Text/ch1.xhtml#sec1">Chapter 1: Architecture</a>
                              <ol>
                                <li><a href="Text/ch1.xhtml#sub1">Sub Section 1.1</a></li>
                              </ol>
                            </li>
                            <li><a href="Text/ch2.xhtml">Chapter 2: GB18030</a></li>
                          </ol>
                        </nav>
                      </body>
                    </html>
                    """.getBytes(StandardCharsets.UTF_8));

            writeZipEntry(zos, "OEBPS/Styles/main.css",
                    "h1 { font-weight: 600; }".getBytes(StandardCharsets.UTF_8));
            writeZipEntry(zos, "OEBPS/Images/sample.png", SAMPLE_PNG_BYTES);

            writeZipEntry(zos, "OEBPS/Text/ch1.xhtml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <head>
                        <link rel="stylesheet" href="../Styles/main.css"/>
                      </head>
                      <body>
                        <h1 id="sec1">Architecture Overview</h1>
                        <p id="sub1">Detailed system architecture and inline figure:</p>
                        <img src="../Images/sample.png" alt="Diagram"/>
                      </body>
                    </html>
                    """.getBytes(StandardCharsets.UTF_8));

            writeZipEntry(zos, "OEBPS/Text/ch2.xhtml", """
                    <html>
                      <head><script>console.log("ignore me");</script></head>
                      <body>
                        <h1>第二章</h1>
                        <p>这是中文编码章节正文，验证无声明 GB18030 嗅探。</p>
                      </body>
                    </html>
                    """.getBytes(gb18030));
        }
        return target.toFile();
    }

    private static File buildSampleEpub2Ncx(Path target) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(target.toFile()))) {
            writeZipEntry(zos, "mimetype", "application/epub+zip".getBytes(StandardCharsets.US_ASCII));
            writeZipEntry(zos, "META-INF/container.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                    """.getBytes(StandardCharsets.UTF_8));
            writeZipEntry(zos, "content.opf", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>EPUB2 Book</dc:title>
                      </metadata>
                      <manifest>
                        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                        <item id="ch1" href="ch1.html" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine toc="ncx">
                        <itemref idref="ch1"/>
                      </spine>
                    </package>
                    """.getBytes(StandardCharsets.UTF_8));
            writeZipEntry(zos, "toc.ncx", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                      <navMap>
                        <navPoint id="np1" playOrder="1">
                          <navLabel><text>NCX Chapter One</text></navLabel>
                          <content src="ch1.html#p1"/>
                        </navPoint>
                      </navMap>
                    </ncx>
                    """.getBytes(StandardCharsets.UTF_8));
            writeZipEntry(zos, "ch1.html",
                    "<html><body><p id=\"p1\">EPUB2 content</p></body></html>".getBytes(StandardCharsets.UTF_8));
        }
        return target.toFile();
    }

    private static void writeZipEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(a.length + b.length);
        out.writeBytes(a);
        out.writeBytes(b);
        return out.toByteArray();
    }
}
