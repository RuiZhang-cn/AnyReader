package dev.local.docinspector.pdf;

import dev.local.docinspector.model.PdfColorFilter;
import dev.local.docinspector.model.ReaderSettings;
import dev.local.docinspector.viewer.ViewerViewportListener;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDestinationNameTreeNode;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PdfEngineAndFilterTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsPdfParsesOutlinesAndReleasesFileHandleOnClose() throws Exception {
        File pdfFile = createSamplePdf(tempDir.resolve("sample.pdf").toFile(), 4);

        try (PdfDocumentSession session = PdfDocumentSession.open(pdfFile)) {
            assertEquals(4, session.getPageCount());
            assertFalse(session.isClosed());

            List<PdfOutlineItem> outlines = session.getOutlines();
            assertEquals(2, outlines.size());

            PdfOutlineItem ch1 = outlines.get(0);
            assertEquals("Chapter 1 概览", ch1.getTitle());
            assertEquals(0, ch1.getPageIndex());
            assertEquals(1, ch1.getChildren().size());
            assertEquals("Section 1.1 Named", ch1.getChildren().get(0).getTitle());
            assertEquals(2, ch1.getChildren().get(0).getPageIndex());

            PdfOutlineItem ch2 = outlines.get(1);
            assertEquals("Chapter 2 深入", ch2.getTitle());
            assertEquals(3, ch2.getPageIndex());

            String p0Text = session.getPageText(0);
            assertTrue(p0Text.contains("DocInspector"), "Page 0 text should contain English keyword");
            assertTrue(p0Text.contains("测试文档"), "Page 0 text should contain Chinese keyword");
        }

        // 关闭后必须释放 PDDocument 句柄，Windows 下可直接删除文件
        assertTrue(Files.deleteIfExists(pdfFile.toPath()), "PDF file should be deletable after close()");
    }

    @Test
    void lruRenderCacheHitsAndEvictsBeyondTenFrames() throws Exception {
        File pdfFile = createSamplePdf(tempDir.resolve("cache.pdf").toFile(), 14);

        try (PdfDocumentSession session = PdfDocumentSession.open(pdfFile)) {
            BufferedImage first = session.renderPage(0, 1.0f, 0, PdfColorFilter.ORIGINAL);
            assertNotNull(first);
            assertTrue(session.isCached(0, 1.0f, 0, PdfColorFilter.ORIGINAL));

            BufferedImage second = session.renderPage(0, 1.0f, 0, PdfColorFilter.ORIGINAL);
            assertSame(first, second, "LRU cache hit should return identical BufferedImage instance");

            // 连续渲染超过 10 个不同页，LRU 容量应严格限制在 10 帧以内
            for (int p = 1; p <= 12; p++) {
                session.renderPage(p, 1.0f, 0, PdfColorFilter.ORIGINAL);
            }
            assertTrue(session.getCacheSize() <= PdfDocumentSession.RENDER_CACHE_CAPACITY,
                    "Cache size should not exceed 10, actual=" + session.getCacheSize());
        }
    }

    @Test
    void colorFiltersTransformWhiteAndBlackPixelsAsExpected() {
        BufferedImage src = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        src.setRGB(0, 0, Color.WHITE.getRGB());
        src.setRGB(1, 0, Color.BLACK.getRGB());

        // ORIGINAL 直接返回原图
        assertSame(src, PdfColorFilterProcessor.apply(src, PdfColorFilter.ORIGINAL));

        // DARK_INVERT: 白底 -> #1E1F22 (30,31,34)，黑字 -> #CDD3DE (205,211,222)
        BufferedImage dark = PdfColorFilterProcessor.apply(src, PdfColorFilter.DARK_INVERT);
        Color darkBg = new Color(dark.getRGB(0, 0));
        Color darkFg = new Color(dark.getRGB(1, 0));
        assertEquals(30, darkBg.getRed());
        assertEquals(31, darkBg.getGreen());
        assertEquals(34, darkBg.getBlue());
        assertEquals(205, darkFg.getRed());
        assertEquals(211, darkFg.getGreen());
        assertEquals(222, darkFg.getBlue());

        // WARM_SEPIA: 白底 -> #F4ECD8 (244,236,216)，黑字 -> #3B3124 (59,49,36)
        BufferedImage sepia = PdfColorFilterProcessor.apply(src, PdfColorFilter.WARM_SEPIA);
        Color sepiaBg = new Color(sepia.getRGB(0, 0));
        Color sepiaFg = new Color(sepia.getRGB(1, 0));
        assertEquals(244, sepiaBg.getRed());
        assertEquals(236, sepiaBg.getGreen());
        assertEquals(216, sepiaBg.getBlue());
        assertEquals(59, sepiaFg.getRed());
        assertEquals(49, sepiaFg.getGreen());
        assertEquals(36, sepiaFg.getBlue());

        // GRAYSCALE_STEALTH: R==G==B 且对比度收窄
        BufferedImage stealth = PdfColorFilterProcessor.apply(src, PdfColorFilter.GRAYSCALE_STEALTH);
        Color stealthBg = new Color(stealth.getRGB(0, 0));
        Color stealthFg = new Color(stealth.getRGB(1, 0));
        assertEquals(stealthBg.getRed(), stealthBg.getGreen());
        assertEquals(stealthBg.getGreen(), stealthBg.getBlue());
        assertEquals(stealthFg.getRed(), stealthFg.getGreen());
        assertEquals(stealthFg.getGreen(), stealthFg.getBlue());
        assertTrue(stealthBg.getRed() < 210 && stealthFg.getRed() >= 40);
    }

    @Test
    void findHighlightRectsLocatesChineseAndEnglishGlyphBounds() throws Exception {
        File pdfFile = createSamplePdf(tempDir.resolve("highlight.pdf").toFile(), 2);

        try (PdfDocumentSession session = PdfDocumentSession.open(pdfFile)) {
            List<Rectangle2D> enCaseInsensitive = session.findHighlightRects(0, "docinspector", false);
            assertFalse(enCaseInsensitive.isEmpty(), "Case-insensitive English search should match");
            Rectangle2D rect = enCaseInsensitive.get(0);
            assertTrue(rect.getWidth() > 10 && rect.getHeight() > 5);

            List<Rectangle2D> enCaseSensitiveMiss = session.findHighlightRects(0, "docinspector", true);
            assertTrue(enCaseSensitiveMiss.isEmpty(), "Case-sensitive search should not match wrong case");

            List<Rectangle2D> zhRects = session.findHighlightRects(0, "测试文档", false);
            assertFalse(zhRects.isEmpty(), "Chinese keyword glyph rects should be located");
        }
    }

    @Test
    void pdfViewerComponentHandlesSettingsHighlightAndDispose() throws Exception {
        File pdfFile = createSamplePdf(tempDir.resolve("viewer.pdf").toFile(), 3);
        PdfViewerComponent viewer = new PdfViewerComponent();
        AtomicInteger unitCallbacks = new AtomicInteger(0);

        viewer.setViewportListener(new ViewerViewportListener() {
            @Override
            public void onViewportChanged(int unitIndex, double offsetRatio, int totalUnits, double overallProgress, String viewportSnippet) {
                unitCallbacks.incrementAndGet();
            }
        });

        viewer.openFile(pdfFile, 1, 0.25);
        assertEquals(3, viewer.getUnitCount());
        assertEquals(1, viewer.getCurrentUnitIndex());
        assertTrue(unitCallbacks.get() >= 1);

        ReaderSettings settings = new ReaderSettings();
        settings.setPdfColorFilter(PdfColorFilter.WARM_SEPIA);
        settings.setPdfOpacity(0.55f);
        viewer.applySettings(settings);
        assertEquals(PdfColorFilter.WARM_SEPIA, viewer.getColorFilter());
        assertEquals(0.55f, viewer.getOpacity(), 0.001f);

        viewer.goToUnit(0, 0.0, null);
        int hits = viewer.highlightKeyword("DocInspector", false, 0);
        assertTrue(hits >= 1);
        viewer.clearHighlights();

        viewer.close();
        assertTrue(Files.deleteIfExists(pdfFile.toPath()), "PDF file should be deletable after PdfViewerComponent.close()");
    }

    @Test
    void imageFolderViewerSortsNaturallyAndTracksProgress() throws Exception {
        Path imgDir = tempDir.resolve("images");
        Files.createDirectories(imgDir);
        createPng(imgDir.resolve("page10.png").toFile(), Color.BLUE);
        createPng(imgDir.resolve("page2.png").toFile(), Color.GREEN);
        createPng(imgDir.resolve("page1.png").toFile(), Color.RED);

        ImageFolderViewerComponent viewer = new ImageFolderViewerComponent();
        AtomicReference<String> lastSnippet = new AtomicReference<>("");
        AtomicInteger lastUnit = new AtomicInteger(-1);
        viewer.setViewportListener(new ViewerViewportListener() {
            @Override
            public void onViewportChanged(int unitIndex, double offsetRatio, int totalUnits, double overallProgress, String viewportSnippet) {
                lastUnit.set(unitIndex);
                lastSnippet.set(viewportSnippet);
            }
        });

        viewer.openPath(imgDir.toFile(), 0, 0.0);
        assertEquals(3, viewer.getUnitCount());
        List<File> files = viewer.getImageFiles();
        assertEquals("page1.png", files.get(0).getName());
        assertEquals("page2.png", files.get(1).getName());
        assertEquals("page10.png", files.get(2).getName());
        assertEquals("page1.png", lastSnippet.get());

        viewer.nextUnit();
        assertEquals(1, viewer.getCurrentUnitIndex());
        assertEquals(1, lastUnit.get());
        assertEquals("page2.png", viewer.getCurrentViewportSnippet());

        ReaderSettings settings = new ReaderSettings();
        settings.setPdfColorFilter(PdfColorFilter.GRAYSCALE_STEALTH);
        settings.setPdfOpacity(0.6f);
        viewer.applySettings(settings);
        assertEquals(PdfColorFilter.GRAYSCALE_STEALTH, viewer.getColorFilter());
        assertEquals(0.6f, viewer.getOpacity(), 0.001f);

        viewer.close();
    }

    private static File createSamplePdf(File target, int pageCount) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            File simhei = new File("C:/Windows/Fonts/simhei.ttf");
            PDFont font = simhei.isFile()
                    ? PDType0Font.load(doc, simhei)
                    : new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            List<PDPage> pages = new ArrayList<>();
            for (int i = 0; i < pageCount; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                pages.add(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 14);
                    cs.newLineAtOffset(50, 760);
                    cs.showText("DocInspector Page " + (i + 1) + " 测试文档内容");
                    cs.endText();
                }
            }

            if (pageCount >= 4) {
                PDDocumentOutline outline = new PDDocumentOutline();
                doc.getDocumentCatalog().setDocumentOutline(outline);

                PDOutlineItem ch1 = new PDOutlineItem();
                ch1.setTitle("Chapter 1 概览");
                PDPageXYZDestination dest0 = new PDPageXYZDestination();
                dest0.setPage(pages.get(0));
                ch1.setDestination(dest0);
                outline.addLast(ch1);

                // 命名目的地测试
                PDPageXYZDestination dest2 = new PDPageXYZDestination();
                dest2.setPage(pages.get(2));
                PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
                Map<String, PDPageDestination> destMap = new HashMap<>();
                destMap.put("sec_1_1", dest2);
                PDDestinationNameTreeNode destTree = new PDDestinationNameTreeNode();
                destTree.setNames(destMap);
                names.setDests(destTree);
                doc.getDocumentCatalog().setNames(names);

                PDOutlineItem sec11 = new PDOutlineItem();
                sec11.setTitle("Section 1.1 Named");
                PDNamedDestination namedDest = new PDNamedDestination();
                namedDest.setNamedDestination("sec_1_1");
                sec11.setDestination(namedDest);
                ch1.addLast(sec11);

                PDOutlineItem ch2 = new PDOutlineItem();
                ch2.setTitle("Chapter 2 深入");
                PDPageXYZDestination dest3 = new PDPageXYZDestination();
                dest3.setPage(pages.get(3));
                ch2.setDestination(dest3);
                outline.addLast(ch2);
            }

            doc.save(target);
        }
        return target;
    }

    private static void createPng(File target, Color color) throws IOException {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setColor(color);
            g2.fillRect(0, 0, 32, 32);
        } finally {
            g2.dispose();
        }
        ImageIO.write(img, "png", target);
    }
}
