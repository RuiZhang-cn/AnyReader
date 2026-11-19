package dev.local.docinspector.toolwindow;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.IconButton;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.ui.OnePixelSplitter;
import dev.local.docinspector.action.BossKeyAction;
import dev.local.docinspector.epub.EpubBook;
import dev.local.docinspector.epub.EpubBookParser;
import dev.local.docinspector.epub.EpubViewerComponent;
import dev.local.docinspector.model.BookFormat;
import dev.local.docinspector.model.BookRecord;
import dev.local.docinspector.model.BookmarkRecord;
import dev.local.docinspector.model.ReaderSettings;
import dev.local.docinspector.pdf.ImageFolderViewerComponent;
import dev.local.docinspector.pdf.PdfViewerComponent;
import dev.local.docinspector.search.BookSearchTarget;
import dev.local.docinspector.search.FullTextSearchService;
import dev.local.docinspector.search.SearchHit;
import dev.local.docinspector.state.DocInspectorStateService;
import dev.local.docinspector.statusbar.MicroReadBroadcaster;
import dev.local.docinspector.ui.BookshelfPanel;
import dev.local.docinspector.ui.BossMaskPanel;
import dev.local.docinspector.ui.InlineFindBar;
import dev.local.docinspector.ui.ReaderThemePalette;
import dev.local.docinspector.ui.SideDrawerPanel;
import dev.local.docinspector.ui.ThemeTypographyPopup;
import dev.local.docinspector.viewer.BookViewer;
import dev.local.docinspector.viewer.PlainMarkdownViewerComponent;
import dev.local.docinspector.viewer.ViewerViewportListener;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.Objects;

/**
 * Doc Inspector 主工作区面板：
 * 完整装配书架、极简无字工具栏、三合一侧边抽屉、页内查找条、EPUB/PDF/图片集/TXT/MD 阅读器、
 * 老板键遮罩层（Alt+Q / 双击 Esc / 失焦超时）与关闭落盘/资源释放。
 */
public final class MainWorkspacePanel extends JPanel implements Disposable, BossKeyAction.BossMaskHost {

    private static final String CARD_BOOKSHELF = "BOOKSHELF";
    private static final String CARD_READER = "READER";
    private static final String CARD_MASK = "MASK";
    private static final long DOUBLE_ESC_WINDOW_MS = 400L;

    private final Project project;
    private final DocInspectorStateService stateService;
    private final FullTextSearchService searchService = new FullTextSearchService();

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel centerCardPanel = new JPanel(cardLayout);

    private static final Color SUBTLE_CHROME_FG = new Color(122, 126, 133);

    private final BookshelfPanel bookshelfPanel;
    private final SideDrawerPanel sideDrawerPanel;
    private final InlineFindBar inlineFindBar = new InlineFindBar();
    private final JPanel viewerHolder = new JPanel(new BorderLayout());
    private final JPanel readerRightPanel = new JPanel(new BorderLayout());
    private final JComponent readerSplitPane;
    private final BossMaskPanel bossMaskPanel = new BossMaskPanel();

    private final JButton bookshelfBtn;
    private final JButton drawerBtn;
    private final JButton bookmarkBtn;
    private final JButton findBtn;
    private final JButton themeBtn;
    private final JButton bossMaskBtn;
    private final JButton prevUnitBtn;
    private final JButton nextUnitBtn;
    private final JLabel progressLabel = new JLabel("Docs");

    private volatile BookRecord currentBook;
    private volatile BookViewer activeViewer;
    private volatile String activeCard = CARD_BOOKSHELF;
    private volatile String cardBeforeMask = CARD_BOOKSHELF;
    private volatile boolean bossMasked = false;
    private volatile boolean disposed = false;
    private volatile JBPopup activeThemePopup;
    private volatile JPopupMenu activeFallbackPopupMenu;

    private volatile long lastInteractionEpochMs = System.currentTimeMillis();
    private volatile long lastEscPressEpochMs = 0L;
    private final Timer autoMaskTimer;
    private final AWTEventListener escEventListener;

    public MainWorkspacePanel(Project project) {
        this(project, DocInspectorStateService.getInstance());
    }

    public MainWorkspacePanel(Project project, DocInspectorStateService stateService) {
        super(new BorderLayout());
        this.project = project;
        this.stateService = stateService != null ? stateService : DocInspectorStateService.getInstance();

        this.bookshelfPanel = new BookshelfPanel(this.stateService);
        this.sideDrawerPanel = new SideDrawerPanel(this.stateService, this.searchService);

        this.bookshelfBtn = createToolbarButton("书架", loadIcon(() -> AllIcons.Nodes.Folder), "▦");
        this.drawerBtn = createToolbarButton("目录 / 书签 / 全文检索", loadIcon(() -> AllIcons.Actions.Preview), "☰");
        this.bookmarkBtn = createToolbarButton("添加当前位置书签", loadIcon(() -> AllIcons.Actions.AddToDictionary), "★");
        this.findBtn = createToolbarButton("页内查找 (Ctrl+F)", loadIcon(() -> AllIcons.Actions.Find), "⌕");
        this.themeBtn = createToolbarButton("配色、排版与隐私设置", loadIcon(() -> AllIcons.Actions.Colors), "Aa");
        this.bossMaskBtn = createToolbarButton("切换速查伪装层 (Alt+Q / 双击 Esc)", loadIcon(() -> AllIcons.General.InspectionsEye), "🛡");
        this.prevUnitBtn = createToolbarButton("上一章/页", null, "‹");
        this.nextUnitBtn = createToolbarButton("下一章/页", null, "›");
        this.prevUnitBtn.setForeground(SUBTLE_CHROME_FG);
        this.nextUnitBtn.setForeground(SUBTLE_CHROME_FG);
        this.progressLabel.setForeground(SUBTLE_CHROME_FG);
        this.progressLabel.setFont(this.progressLabel.getFont().deriveFont(11f));

        this.readerRightPanel.add(inlineFindBar, BorderLayout.NORTH);
        this.readerRightPanel.add(viewerHolder, BorderLayout.CENTER);
        inlineFindBar.installFindShortcut(readerRightPanel);

        this.readerSplitPane = createReaderSplitter(sideDrawerPanel, readerRightPanel);

        centerCardPanel.add(bookshelfPanel, CARD_BOOKSHELF);
        centerCardPanel.add(readerSplitPane, CARD_READER);
        centerCardPanel.add(bossMaskPanel, CARD_MASK);

        add(buildToolbar(), BorderLayout.NORTH);
        add(centerCardPanel, BorderLayout.CENTER);

        wireBookshelf();
        wireDrawer();
        wireToolbar();

        this.sideDrawerPanel.setCollapsed(true);
        updateSplitLayout(true);

        this.autoMaskTimer = new Timer(1000, e -> checkAutoMaskTimeout());
        this.autoMaskTimer.setRepeats(true);
        this.autoMaskTimer.start();

        this.escEventListener = this::handleGlobalKeyEvent;
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(this.escEventListener, AWTEvent.KEY_EVENT_MASK);
        } catch (Throwable ignored) {
        }

        BossKeyAction.registerHost(project, this);
        MicroReadBroadcaster.getInstance().setEnabled(this.stateService.getSettings().isMicroBarEnabled());
        applyPaletteToChrome(ReaderThemePalette.resolve(this.stateService.getSettings()));
        showCard(CARD_BOOKSHELF);
        updateToolbarState();
    }

    private static JComponent createReaderSplitter(JComponent first, JComponent second) {
        if (ApplicationManager.getApplication() != null) {
            try {
                OnePixelSplitter splitter = new OnePixelSplitter(false, 0.28f);
                splitter.setFirstComponent(first);
                splitter.setSecondComponent(second);
                splitter.setBorder(BorderFactory.createEmptyBorder());
                return splitter;
            } catch (Throwable ignored) {
            }
        }
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, first, second);
        split.setDividerLocation(240);
        split.setDividerSize(0);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setUI(new BasicSplitPaneUI() {
            @Override
            public BasicSplitPaneDivider createDefaultDivider() {
                BasicSplitPaneDivider divider = new BasicSplitPaneDivider(this) {
                    @Override
                    public void paint(Graphics g) {
                        // 极简扁平无边框分割条，消除默认亮白 3D 高光线
                    }
                };
                divider.setBorder(BorderFactory.createEmptyBorder());
                return divider;
            }
        });
        return split;
    }

    private void updateSplitLayout(boolean collapsed) {
        if (readerSplitPane instanceof JSplitPane js) {
            js.setDividerSize(collapsed ? 0 : 2);
            if (!collapsed) {
                js.setDividerLocation(240);
            }
        }
        readerSplitPane.revalidate();
        readerSplitPane.repaint();
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(4, 0));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new java.awt.Color(128, 128, 128, 60)),
                BorderFactory.createEmptyBorder(2, 4, 2, 6)
        ));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        left.add(bookshelfBtn);
        left.add(drawerBtn);
        left.add(bookmarkBtn);
        left.add(findBtn);
        left.add(themeBtn);
        left.add(bossMaskBtn);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        right.add(prevUnitBtn);
        right.add(nextUnitBtn);
        progressLabel.setHorizontalAlignment(JLabel.RIGHT);
        right.add(progressLabel);
        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private static Icon loadIcon(java.util.function.Supplier<Icon> supplier) {
        if (ApplicationManager.getApplication() == null) {
            return null;
        }
        try {
            return supplier.get();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JButton createToolbarButton(String tooltip, Icon icon, String fallbackText) {
        JButton btn = icon != null ? new JButton(icon) : new JButton(fallbackText);
        btn.setToolTipText(tooltip);
        btn.setFocusable(false);
        btn.setMargin(new Insets(2, 5, 2, 5));
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        return btn;
    }

    private void wireToolbar() {
        bookshelfBtn.addActionListener(e -> {
            recordInteraction();
            if (bossMasked) {
                toggleBossMask();
            }
            if (CARD_BOOKSHELF.equals(activeCard) && activeViewer != null) {
                showCard(CARD_READER);
            } else {
                flushCurrentProgress();
                bookshelfPanel.refreshTable();
                showCard(CARD_BOOKSHELF);
            }
        });

        drawerBtn.addActionListener(e -> {
            recordInteraction();
            if (activeViewer == null) {
                return;
            }
            boolean nextCollapsed = !sideDrawerPanel.isCollapsed();
            sideDrawerPanel.setCollapsed(nextCollapsed);
            updateSplitLayout(nextCollapsed);
        });

        bookmarkBtn.addActionListener(e -> {
            recordInteraction();
            addBookmarkAtCurrentPosition();
        });

        findBtn.addActionListener(e -> {
            recordInteraction();
            if (activeViewer != null && !bossMasked) {
                showCard(CARD_READER);
                inlineFindBar.openAndFocus();
            }
        });

        themeBtn.addActionListener(e -> {
            recordInteraction();
            showThemePopup();
        });

        bossMaskBtn.addActionListener(e -> toggleBossMask());

        prevUnitBtn.addActionListener(e -> {
            recordInteraction();
            BookViewer v = activeViewer;
            if (v != null && !bossMasked) {
                v.prevUnit();
            }
        });

        nextUnitBtn.addActionListener(e -> {
            recordInteraction();
            BookViewer v = activeViewer;
            if (v != null && !bossMasked) {
                v.nextUnit();
            }
        });
    }

    private void wireBookshelf() {
        bookshelfPanel.setBookshelfListener(new BookshelfPanel.BookshelfListener() {
            @Override
            public void onOpenBookRequested(BookRecord record) {
                openBook(record);
            }

            @Override
            public void onTracesCleared() {
                closeActiveViewer();
                currentBook = null;
                showCard(CARD_BOOKSHELF);
                updateToolbarState();
            }

            @Override
            public void onBookRemoved(String bookId) {
                BookRecord cur = currentBook;
                if (cur != null && Objects.equals(cur.getBookId(), bookId)) {
                    closeActiveViewer();
                    currentBook = null;
                    showCard(CARD_BOOKSHELF);
                    updateToolbarState();
                }
            }
        });
    }

    private void wireDrawer() {
        sideDrawerPanel.setDrawerListener(new SideDrawerPanel.DrawerListener() {
            @Override
            public void onOutlineSelected(int unitIndex, String fragmentId) {
                recordInteraction();
                BookViewer v = activeViewer;
                if (v != null) {
                    v.goToUnit(unitIndex, 0.0, fragmentId);
                }
            }

            @Override
            public void onAddBookmarkRequested() {
                recordInteraction();
                addBookmarkAtCurrentPosition();
            }

            @Override
            public void onBookmarkSelected(BookmarkRecord bookmark) {
                recordInteraction();
                BookViewer v = activeViewer;
                if (v != null && bookmark != null) {
                    v.goToUnit(bookmark.getUnitIndex(), bookmark.getOffsetRatio(), null);
                }
            }

            @Override
            public void onSearchHitSelected(SearchHit hit) {
                recordInteraction();
                BookViewer v = activeViewer;
                if (v != null && hit != null) {
                    v.jumpToSearchHit(hit.unitIndex(), hit.matchedText(), sideDrawerPanel.isSearchCaseSensitive(), hit.matchOrdinalInUnit());
                }
            }

            @Override
            public void onCollapsedChanged(boolean collapsed) {
                updateSplitLayout(collapsed);
            }
        });
    }

    public void openBook(BookRecord record) {
        if (disposed || record == null) {
            return;
        }
        recordInteraction();
        closeActiveViewer();

        File file = new File(record.decodedPath());
        if (!file.exists()) {
            JOptionPane.showMessageDialog(this, "文件不存在：" + record.decodedPath(), "打开失败", JOptionPane.WARNING_MESSAGE);
            return;
        }

        ReaderSettings settings = stateService.getSettings();
        BookViewer createdViewer = null;
        try {
            BookFormat format = record.getFormat();
            switch (format) {
                case EPUB -> {
                    EpubBook epubBook = EpubBookParser.parse(file, record.getBookId());
                    EpubViewerComponent epubViewer = new EpubViewerComponent();
                    epubViewer.applySettings(settings);
                    epubViewer.openBook(epubBook, record.getLastUnitIndex(), record.getLastOffsetRatio(), null);
                    sideDrawerPanel.setEpubOutline(epubBook.getTocItems());
                    sideDrawerPanel.setSearchTarget(epubBook);
                    createdViewer = epubViewer;
                }
                case PDF -> {
                    PdfViewerComponent pdfViewer = new PdfViewerComponent();
                    pdfViewer.applySettings(settings);
                    pdfViewer.applyViewState(record.getPdfZoom(), record.isPdfFitWidth(), record.getPdfRotation());
                    pdfViewer.openFile(file, record.getLastUnitIndex(), record.getLastOffsetRatio());
                    if (pdfViewer.getSession() != null) {
                        sideDrawerPanel.setPdfOutline(pdfViewer.getSession().getOutlines());
                        sideDrawerPanel.setSearchTarget(pdfViewer.getSession());
                    } else {
                        sideDrawerPanel.clearOutline();
                        sideDrawerPanel.setSearchTarget(null);
                    }
                    createdViewer = pdfViewer;
                }
                case IMAGE_DIR -> {
                    ImageFolderViewerComponent imgViewer = new ImageFolderViewerComponent();
                    imgViewer.applySettings(settings);
                    imgViewer.openPath(file, record.getLastUnitIndex(), record.getLastOffsetRatio());
                    sideDrawerPanel.clearOutline();
                    sideDrawerPanel.setSearchTarget(null);
                    createdViewer = imgViewer;
                }
                case TXT, MARKDOWN -> {
                    PlainMarkdownViewerComponent textViewer = new PlainMarkdownViewerComponent();
                    textViewer.applySettings(settings);
                    textViewer.openFile(file, record.getLastUnitIndex(), record.getLastOffsetRatio());
                    sideDrawerPanel.setGenericOutline(textViewer.getGenericOutlineEntries());
                    sideDrawerPanel.setSearchTarget((BookSearchTarget) textViewer);
                    createdViewer = textViewer;
                }
            }
        } catch (Throwable ex) {
            if (createdViewer != null) {
                try {
                    createdViewer.dispose();
                } catch (Throwable ignored) {
                }
            }
            JOptionPane.showMessageDialog(this, "无法打开文档：" + ex.getMessage(), "打开失败", JOptionPane.ERROR_MESSAGE);
            return;
        }

        this.currentBook = record;
        this.activeViewer = createdViewer;

        createdViewer.setViewportListener(new ViewerViewportListener() {
            @Override
            public void onViewportChanged(int unitIndex, double offsetRatio, int totalUnits, double overallProgress, String viewportSnippet) {
                recordInteraction();
                BookRecord book = currentBook;
                if (book != null) {
                    stateService.updateReadingProgress(book.getBookId(), unitIndex, offsetRatio, totalUnits, overallProgress);
                }
                sideDrawerPanel.syncCurrentUnit(unitIndex);
                MicroReadBroadcaster.getInstance().updateFromSnippet(viewportSnippet);
                updateProgressLabel(unitIndex, totalUnits, overallProgress);
            }

            @Override
            public void onUnitChanged(int unitIndex, int totalUnits, String unitTitle) {
                recordInteraction();
                sideDrawerPanel.syncCurrentUnit(unitIndex);
                BookViewer viewer = activeViewer;
                BookRecord book = currentBook;
                int safeTotal = Math.max(1, totalUnits);
                double ratio = viewer != null ? viewer.getCurrentOffsetRatio() : 0.0;
                double overall = Math.max(0.0, Math.min(1.0, (unitIndex + ratio) / safeTotal));
                if (book != null) {
                    stateService.updateReadingProgress(book.getBookId(), unitIndex, ratio, safeTotal, overall);
                }
                updateProgressLabel(unitIndex, safeTotal, overall);
                inlineFindBar.refreshForCurrentUnit();
            }

            @Override
            public void onUserInteraction() {
                recordInteraction();
            }
        });

        sideDrawerPanel.bindBook(record.getBookId());
        inlineFindBar.attachViewer(createdViewer);

        viewerHolder.removeAll();
        viewerHolder.add(createdViewer.getComponent(), BorderLayout.CENTER);
        viewerHolder.revalidate();
        viewerHolder.repaint();

        MicroReadBroadcaster.getInstance().updateFromSnippet(createdViewer.getCurrentViewportSnippet());
        updateProgressLabel(
                createdViewer.getCurrentUnitIndex(),
                createdViewer.getUnitCount(),
                record.getOverallProgress()
        );
        showCard(CARD_READER);
        updateToolbarState();
    }

    private void addBookmarkAtCurrentPosition() {
        BookRecord book = currentBook;
        BookViewer viewer = activeViewer;
        if (book == null || viewer == null) {
            return;
        }
        int unit = viewer.getCurrentUnitIndex();
        double ratio = viewer.getCurrentOffsetRatio();
        String label = formatUnitLabel(book.getFormat(), unit, viewer.getUnitCount());
        String snippet = viewer.getCurrentViewportSnippet();
        sideDrawerPanel.addBookmarkFromCurrent(unit, ratio, label, snippet, "");
        sideDrawerPanel.setCollapsed(false);
        sideDrawerPanel.selectTab(SideDrawerPanel.Tab.MARKS);
    }

    private static String formatUnitLabel(BookFormat format, int unitIndex, int totalUnits) {
        int current = unitIndex + 1;
        if (format == BookFormat.PDF) {
            return "P." + current + "/" + Math.max(1, totalUnits);
        }
        return "Ch." + current + "/" + Math.max(1, totalUnits);
    }

    private void showThemePopup() {
        if (activeThemePopup != null && !activeThemePopup.isDisposed()) {
            closeThemePopup();
            return;
        }
        if (activeFallbackPopupMenu != null && activeFallbackPopupMenu.isVisible()) {
            closeThemePopup();
            return;
        }
        ThemeTypographyPopup content = new ThemeTypographyPopup(
                stateService,
                this::applySettingsToWorkspace,
                this::closeThemePopup
        );
        try {
            JBPopup popup = JBPopupFactory.getInstance()
                    .createComponentPopupBuilder(content, content)
                    .setTitle("配色与排版")
                    .setMovable(true)
                    .setResizable(true)
                    .setRequestFocus(true)
                    .setCancelOnClickOutside(true)
                    .setCancelKeyEnabled(true)
                    .setLocateWithinScreenBounds(true)
                    .setCancelButton(new IconButton("关闭", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered))
                    .addListener(new JBPopupListener() {
                        @Override
                        public void onClosed(LightweightWindowEvent event) {
                            activeThemePopup = null;
                        }
                    })
                    .createPopup();
            this.activeThemePopup = popup;
            if (getWidth() > 0 && getHeight() > 0) {
                popup.showInCenterOf(this);
            } else {
                popup.showUnderneathOf(themeBtn);
            }
            return;
        } catch (Throwable ignored) {
        }
        JPopupMenu fallbackMenu = new JPopupMenu();
        fallbackMenu.add(content);
        this.activeFallbackPopupMenu = fallbackMenu;
        fallbackMenu.show(themeBtn, 0, themeBtn.getHeight());
    }

    private void closeThemePopup() {
        JBPopup popup = activeThemePopup;
        activeThemePopup = null;
        if (popup != null && !popup.isDisposed()) {
            try {
                popup.cancel();
            } catch (Throwable ignored) {
            }
        }
        JPopupMenu menu = activeFallbackPopupMenu;
        activeFallbackPopupMenu = null;
        if (menu != null && menu.isVisible()) {
            menu.setVisible(false);
        }
    }

    public void applySettingsToWorkspace(ReaderSettings settings) {
        if (settings == null) {
            return;
        }
        ReaderThemePalette palette = ReaderThemePalette.resolve(settings);
        applyPaletteToChrome(palette);
        BookViewer v = activeViewer;
        if (v != null) {
            v.applySettings(settings);
        }
        bossMaskPanel.applyPalette(palette);
        MicroReadBroadcaster.getInstance().setEnabled(settings.isMicroBarEnabled());
    }

    private void applyPaletteToChrome(ReaderThemePalette palette) {
        if (palette == null) {
            return;
        }
        Color bg = palette.getBgColor();
        centerCardPanel.setBackground(bg);
        readerSplitPane.setBackground(bg);
        readerRightPanel.setBackground(bg);
        viewerHolder.setBackground(bg);
    }

    @Override
    public void toggleBossMask() {
        if (disposed) {
            return;
        }
        if (!bossMasked) {
            cardBeforeMask = activeCard;
            bossMasked = true;
            bossMaskPanel.applyPalette(ReaderThemePalette.resolve(stateService.getSettings()));
            MicroReadBroadcaster.getInstance().setMasked(true);
            showCard(CARD_MASK);
        } else {
            bossMasked = false;
            MicroReadBroadcaster.getInstance().setMasked(false);
            recordInteraction();
            showCard(cardBeforeMask != null ? cardBeforeMask : CARD_BOOKSHELF);
        }
        updateToolbarState();
    }

    public boolean isBossMasked() {
        return bossMasked;
    }

    private void showCard(String card) {
        this.activeCard = card;
        cardLayout.show(centerCardPanel, card);
    }

    private void updateToolbarState() {
        boolean hasViewer = activeViewer != null;
        drawerBtn.setEnabled(hasViewer && !bossMasked);
        bookmarkBtn.setEnabled(hasViewer && !bossMasked);
        findBtn.setEnabled(hasViewer && !bossMasked);
        prevUnitBtn.setEnabled(hasViewer && !bossMasked);
        nextUnitBtn.setEnabled(hasViewer && !bossMasked);
        if (bossMasked) {
            progressLabel.setText("Ref");
        } else if (!hasViewer) {
            progressLabel.setText("Docs");
        }
    }

    private void updateProgressLabel(int unitIndex, int totalUnits, double overallProgress) {
        if (bossMasked) {
            progressLabel.setText("Ref");
            return;
        }
        if (totalUnits <= 0) {
            progressLabel.setText("Docs");
            return;
        }
        int pct = (int) Math.round(Math.max(0.0, Math.min(1.0, overallProgress)) * 100.0);
        progressLabel.setText((unitIndex + 1) + "/" + totalUnits + " · " + pct + "%");
    }

    private void recordInteraction() {
        lastInteractionEpochMs = System.currentTimeMillis();
    }

    private void checkAutoMaskTimeout() {
        if (disposed || bossMasked) {
            return;
        }
        int idleThresholdSec = stateService.getSettings().getAutoMaskIdleSeconds();
        if (idleThresholdSec <= 0 || !CARD_READER.equals(activeCard)) {
            return;
        }
        Window activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        Window myWindow = SwingUtilities.getWindowAncestor(this);
        boolean windowUnfocused = (myWindow != null && activeWindow != myWindow);
        long elapsedMs = System.currentTimeMillis() - lastInteractionEpochMs;
        if (windowUnfocused || elapsedMs >= idleThresholdSec * 1000L) {
            toggleBossMask();
        }
    }

    private void handleGlobalKeyEvent(AWTEvent event) {
        if (disposed || !(event instanceof KeyEvent ke)) {
            return;
        }
        if (ke.getID() != KeyEvent.KEY_PRESSED || ke.getKeyCode() != KeyEvent.VK_ESCAPE) {
            return;
        }
        Object src = ke.getSource();
        if (!(src instanceof Component comp) || !SwingUtilities.isDescendingFrom(comp, this)) {
            return;
        }
        if (inlineFindBar.isVisible()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastEscPressEpochMs <= DOUBLE_ESC_WINDOW_MS) {
            lastEscPressEpochMs = 0L;
            SwingUtilities.invokeLater(this::toggleBossMask);
        } else {
            lastEscPressEpochMs = now;
        }
    }

    public void flushCurrentProgress() {
        BookRecord book = currentBook;
        BookViewer viewer = activeViewer;
        if (book == null || viewer == null) {
            return;
        }
        int unit = viewer.getCurrentUnitIndex();
        double ratio = viewer.getCurrentOffsetRatio();
        int total = Math.max(1, viewer.getUnitCount());
        double overall = Math.max(0.0, Math.min(1.0, (unit + ratio) / total));
        stateService.updateReadingProgress(book.getBookId(), unit, ratio, total, overall);
        if (viewer instanceof PdfViewerComponent pdf) {
            stateService.updatePdfViewState(book.getBookId(), pdf.getZoom(), pdf.isFitWidth(), pdf.getRotation());
        }
    }

    private void closeActiveViewer() {
        flushCurrentProgress();
        BookViewer v = activeViewer;
        activeViewer = null;
        inlineFindBar.attachViewer(null);
        inlineFindBar.closeBar();
        sideDrawerPanel.setSearchTarget(null);
        sideDrawerPanel.clearOutline();
        sideDrawerPanel.bindBook("");
        MicroReadBroadcaster.getInstance().clear();
        viewerHolder.removeAll();
        viewerHolder.revalidate();
        viewerHolder.repaint();
        if (v != null) {
            try {
                v.dispose();
            } catch (Throwable ignored) {
            }
        }
    }

    public BookViewer getActiveViewer() {
        return activeViewer;
    }

    public BookshelfPanel getBookshelfPanel() {
        return bookshelfPanel;
    }

    public BossMaskPanel getBossMaskPanel() {
        return bossMaskPanel;
    }

    public SideDrawerPanel getSideDrawerPanel() {
        return sideDrawerPanel;
    }

    public InlineFindBar getInlineFindBar() {
        return inlineFindBar;
    }

    public JButton getPrevUnitButton() {
        return prevUnitBtn;
    }

    public JButton getNextUnitButton() {
        return nextUnitBtn;
    }

    public String getProgressLabelText() {
        return progressLabel.getText();
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        closeThemePopup();
        autoMaskTimer.stop();
        try {
            Toolkit.getDefaultToolkit().removeAWTEventListener(escEventListener);
        } catch (Throwable ignored) {
        }
        BossKeyAction.unregisterHost(project, this);
        closeActiveViewer();
        sideDrawerPanel.close();
        searchService.close();
    }
}
