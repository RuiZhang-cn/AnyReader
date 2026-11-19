package dev.local.docinspector.ui;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import dev.local.docinspector.action.BossKeyAction;
import dev.local.docinspector.model.BookRecord;
import dev.local.docinspector.model.ReaderSettings;
import dev.local.docinspector.model.ThemePreset;
import dev.local.docinspector.search.FullTextSearchService;
import dev.local.docinspector.search.SearchHit;
import dev.local.docinspector.state.DocInspectorStateService;
import dev.local.docinspector.statusbar.MicroReadBroadcaster;
import dev.local.docinspector.toolwindow.MainWorkspacePanel;
import dev.local.docinspector.viewer.PlainMarkdownViewerComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceAndPrivacyUiTest {

    @Test
    void testReaderThemePaletteAllFivePresetsAndCustomOverride() {
        for (ThemePreset preset : ThemePreset.values()) {
            ReaderSettings settings = new ReaderSettings();
            settings.setThemePreset(preset);
            ReaderThemePalette palette = ReaderThemePalette.resolve(settings);

            assertNotNull(palette.getBgColor());
            assertNotNull(palette.getFgColor());
            assertNotNull(palette.getMutedColor());
            assertNotNull(palette.getBorderColor());
            assertNotNull(palette.getAccentColor());
            assertTrue(palette.getBgHex().matches("^#[0-9A-F]{6}$"));
            assertTrue(palette.getFgHex().matches("^#[0-9A-F]{6}$"));
        }

        ReaderSettings custom = new ReaderSettings();
        custom.setThemePreset(ThemePreset.FOLLOW_IDE);
        custom.setCustomBgHex("#123456");
        custom.setCustomFgHex("abcdef");
        ReaderThemePalette customPalette = ReaderThemePalette.resolve(custom);
        assertEquals("#123456", customPalette.getBgHex());
        assertEquals("#ABCDEF", customPalette.getFgHex());
        assertTrue(customPalette.isDark());
    }

    @Test
    void testPlainAndMarkdownViewerOutlineAndSearch(@TempDir Path tempDir) throws Exception {
        File txtFile = tempDir.resolve("sample.txt").toFile();
        String txtContent = "序言部分说明\n\n"
                + "第一章 起始之路\n这是第一章的正文内容，包含关键词 AlphaTest。\n\n"
                + "第二章 深入探索\n这是第二章的正文内容，再次出现 AlphaTest 与 BetaCheck。\n";
        Files.write(txtFile.toPath(), txtContent.getBytes(Charset.forName("GB18030")));

        PlainMarkdownViewerComponent viewer = new PlainMarkdownViewerComponent();
        try {
            viewer.openFile(txtFile, 1, 0.35);
            assertEquals(3, viewer.getUnitCount());
            assertEquals("前言", viewer.getUnitLabel(0));
            assertEquals("第一章 起始之路", viewer.getUnitLabel(1));
            assertEquals("第二章 深入探索", viewer.getUnitLabel(2));
            assertEquals(1, viewer.getCurrentUnitIndex());
            assertEquals(0.35, viewer.getCurrentOffsetRatio(), 0.001);
            assertEquals(3, viewer.getOutlineEntries().size());

            try (FullTextSearchService searchService = new FullTextSearchService()) {
                List<SearchHit> hits = searchService.searchSync(viewer, "AlphaTest", true, new AtomicBoolean(false));
                assertEquals(2, hits.size());
                assertEquals(1, hits.get(0).unitIndex());
                assertEquals(2, hits.get(1).unitIndex());
            }

            int unitMatches = viewer.highlightKeyword("AlphaTest", true, 0);
            assertEquals(1, unitMatches);

            File mdFile = tempDir.resolve("guide.md").toFile();
            String mdContent = "# Architecture\nIntro **bold** and `inline`.\n\n## Storage\nDetails here.\n";
            Files.writeString(mdFile.toPath(), mdContent, StandardCharsets.UTF_8);
            viewer.openFile(mdFile, 0, 0.0);
            assertEquals(2, viewer.getUnitCount());
            assertEquals("Architecture", viewer.getUnitLabel(0));
            assertEquals("Storage", viewer.getUnitLabel(1));
        } finally {
            viewer.dispose();
        }
    }

    @Test
    void testBossMaskPanelTogglePreservesReadingPosition(@TempDir Path tempDir) throws Exception {
        File txtFile = tempDir.resolve("notes.txt").toFile();
        Files.writeString(txtFile.toPath(), "第1章 开始\n正文内容 A\n第2章 继续\n正文内容 B\n", StandardCharsets.UTF_8);

        DocInspectorStateService stateService = DocInspectorStateService.createForTest();
        BookRecord record = stateService.addOrGetBook(txtFile);
        stateService.updateReadingProgress(record.getBookId(), 1, 0.42, 2, 0.71);

        MainWorkspacePanel workspace = new MainWorkspacePanel(null, stateService);
        try {
            SwingUtilities.invokeAndWait(() -> {
                workspace.openBook(record);
                assertNotNull(workspace.getActiveViewer());
                assertEquals(1, workspace.getActiveViewer().getCurrentUnitIndex());
                assertEquals(0.42, workspace.getActiveViewer().getCurrentOffsetRatio(), 0.001);

                assertEquals(3, workspace.getBossMaskPanel().getTabCount());
                assertEquals("HTTP Status & Headers", workspace.getBossMaskPanel().getTabTitle(0));
                assertEquals("JVM GC & Flags", workspace.getBossMaskPanel().getTabTitle(1));
                assertEquals("Java Regex & Time", workspace.getBossMaskPanel().getTabTitle(2));

                workspace.toggleBossMask();
                assertTrue(workspace.isBossMasked());

                workspace.toggleBossMask();
                assertFalse(workspace.isBossMasked());
                assertEquals(1, workspace.getActiveViewer().getCurrentUnitIndex());
                assertEquals(0.42, workspace.getActiveViewer().getCurrentOffsetRatio(), 0.001);
            });
        } finally {
            workspace.dispose();
        }
    }

    @Test
    void testBookshelfPanelFilteringAndAliasMasking(@TempDir Path tempDir) throws Exception {
        File secretBook = tempDir.resolve("MySecretNovelTitle.txt").toFile();
        File normalBook = tempDir.resolve("EffectiveJavaNotes.md").toFile();
        Files.writeString(secretBook.toPath(), "Secret content", StandardCharsets.UTF_8);
        Files.writeString(normalBook.toPath(), "# Notes", StandardCharsets.UTF_8);

        DocInspectorStateService stateService = DocInspectorStateService.createForTest();
        BookRecord r1 = stateService.addOrGetBook(secretBook);
        BookRecord r2 = stateService.addOrGetBook(normalBook);

        stateService.setBookAlias(r1.getBookId(), "JDK 21 GC Spec");
        stateService.setBookGroup(r1.getBookId(), "JVM");
        stateService.setBookGroup(r2.getBookId(), "Design");

        BookshelfPanel panel = new BookshelfPanel(stateService);
        assertEquals(2, panel.getRowCount());

        panel.setGroupFilter("JVM");
        assertEquals(1, panel.getRowCount());
        String displayedName = String.valueOf(panel.getTableValueAt(0, 0));
        assertTrue(displayedName.contains("JDK 21 GC Spec"));
        assertFalse(displayedName.contains("MySecretNovelTitle"));

        panel.setGroupFilter("全部");
        panel.setQueryFilter("Effective");
        assertEquals(1, panel.getRowCount());
        assertTrue(String.valueOf(panel.getTableValueAt(0, 0)).contains("EffectiveJavaNotes"));
    }

    @Test
    void testMicroReadBroadcasterSentenceAdvanceAndClear() {
        MicroReadBroadcaster broadcaster = MicroReadBroadcaster.createForTest();
        broadcaster.updateFromSnippet("First short sentence. Second sentence is definitely much longer than thirty six characters in total! Third.");

        // Default hidden when disabled
        assertFalse(broadcaster.isEnabled());
        assertEquals("", broadcaster.getCurrentDisplayText());

        broadcaster.setEnabled(true);
        assertEquals("First short sentence.", broadcaster.getCurrentDisplayText());

        assertTrue(broadcaster.nextSentence());
        String second = broadcaster.getCurrentDisplayText();
        assertTrue(second.length() <= MicroReadBroadcaster.MAX_DISPLAY_CHARS);
        assertTrue(second.endsWith("…"));

        assertTrue(broadcaster.nextSentence());
        assertEquals("Third.", broadcaster.getCurrentDisplayText());

        assertTrue(broadcaster.prevSentence());
        assertTrue(broadcaster.getCurrentDisplayText().endsWith("…"));

        broadcaster.hideImmediately();
        assertFalse(broadcaster.isEnabled());
        assertEquals("", broadcaster.getCurrentDisplayText());
        assertEquals(0, broadcaster.getSentenceCount());
    }

    @Test
    void testBossKeyActionBgtAndToolbarUnitNavigationAndCaseInsensitiveSearchJump(@TempDir Path tempDir) throws Exception {
        assertEquals(ActionUpdateThread.BGT, new BossKeyAction().getActionUpdateThread());

        File txtFile = tempDir.resolve("multi-unit.txt").toFile();
        Files.writeString(txtFile.toPath(),
                "第1章 起步\n这里是 MixedCaseWord 内容。\n"
                        + "第2章 进阶\n这里是 mixedcaseword 第二章内容。\n"
                        + "第3章 收尾\n这里是 MIXEDCASEWORD 第三章内容。\n",
                StandardCharsets.UTF_8);

        DocInspectorStateService stateService = DocInspectorStateService.createForTest();
        BookRecord record = stateService.addOrGetBook(txtFile);

        MainWorkspacePanel workspace = new MainWorkspacePanel(null, stateService);
        try {
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(workspace.getSideDrawerPanel().isCollapsed(), "侧边抽屉默认应折叠避免左侧出现分割线");
                assertFalse(workspace.getPrevUnitButton().isEnabled());
                assertFalse(workspace.getNextUnitButton().isEnabled());

                AtomicBoolean popupClosed = new AtomicBoolean(false);
                ThemeTypographyPopup popup = new ThemeTypographyPopup(stateService, s -> {}, () -> popupClosed.set(true));
                popup.getCloseButton().doClick();
                assertTrue(popupClosed.get(), "配色面板底部关闭按钮应触发关闭回调");

                workspace.openBook(record);
                assertTrue(workspace.getPrevUnitButton().isEnabled());
                assertTrue(workspace.getNextUnitButton().isEnabled());
                assertEquals(0, workspace.getActiveViewer().getCurrentUnitIndex());

                workspace.getNextUnitButton().doClick();
                assertEquals(1, workspace.getActiveViewer().getCurrentUnitIndex());
                assertEquals(1, stateService.getBook(record.getBookId()).getLastUnitIndex());
                assertTrue(workspace.getProgressLabelText().startsWith("2/3"));

                workspace.getPrevUnitButton().doClick();
                assertEquals(0, workspace.getActiveViewer().getCurrentUnitIndex());
                assertEquals(0, stateService.getBook(record.getBookId()).getLastUnitIndex());
                assertTrue(workspace.getProgressLabelText().startsWith("1/3"));

                // SideDrawerPanel 默认不区分大小写，点击混合大小写命中应正确跳转
                assertFalse(workspace.getSideDrawerPanel().isSearchCaseSensitive());
                int matches = workspace.getActiveViewer().jumpToSearchHit(
                        1, "mixedcaseword", workspace.getSideDrawerPanel().isSearchCaseSensitive(), 0);
                assertEquals(1, workspace.getActiveViewer().getCurrentUnitIndex());
                assertTrue(matches >= 1);
            });
        } finally {
            workspace.dispose();
        }
    }
}
