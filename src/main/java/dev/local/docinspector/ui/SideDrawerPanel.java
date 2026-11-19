package dev.local.docinspector.ui;

import dev.local.docinspector.epub.TocItem;
import dev.local.docinspector.model.BookmarkRecord;
import dev.local.docinspector.pdf.PdfOutlineItem;
import dev.local.docinspector.search.BookSearchTarget;
import dev.local.docinspector.search.FullTextSearchService;
import dev.local.docinspector.search.SearchHit;
import dev.local.docinspector.state.DocInspectorStateService;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 可折叠三合一左侧抽屉面板（Outline | Marks | Search）。
 */
public final class SideDrawerPanel extends JPanel implements AutoCloseable {

    public enum Tab {
        OUTLINE,
        MARKS,
        SEARCH
    }

    /**
     * 统一目录节点数据。
     */
    public record OutlineEntry(String title, int unitIndex, String fragmentId) {
        public OutlineEntry {
            title = title != null ? title.trim() : "";
            fragmentId = fragmentId != null ? fragmentId.trim() : "";
        }

        @Override
        public String toString() {
            return title.isEmpty() ? ("#" + (unitIndex + 1)) : title;
        }
    }

    /**
     * 可嵌套的通用目录构建节点。
     */
    public static final class OutlineTreeNodeData {
        private final OutlineEntry entry;
        private final List<OutlineTreeNodeData> children;

        public OutlineTreeNodeData(OutlineEntry entry, List<OutlineTreeNodeData> children) {
            this.entry = entry;
            this.children = children != null ? List.copyOf(children) : List.of();
        }

        public OutlineEntry getEntry() {
            return entry;
        }

        public List<OutlineTreeNodeData> getChildren() {
            return children;
        }
    }

    public interface DrawerListener {
        default void onOutlineSelected(int unitIndex, String fragmentId) {
        }

        default void onAddBookmarkRequested() {
        }

        default void onBookmarkSelected(BookmarkRecord bookmark) {
        }

        default void onSearchHitSelected(SearchHit hit) {
        }

        default void onCollapsedChanged(boolean collapsed) {
        }
    }

    private final DocInspectorStateService stateService;
    private FullTextSearchService searchService;
    private final boolean ownsSearchService;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardContainer = new JPanel(cardLayout);

    private final JToggleButton outlineTabBtn = new JToggleButton("Outline");
    private final JToggleButton marksTabBtn = new JToggleButton("Marks");
    private final JToggleButton searchTabBtn = new JToggleButton("Search");
    private final JButton collapseButton = new JButton("«");

    // Outline tab components
    private final JTextField outlineFilterField = new JTextField();
    private final DefaultMutableTreeNode outlineRoot = new DefaultMutableTreeNode("Root");
    private final DefaultTreeModel outlineTreeModel = new DefaultTreeModel(outlineRoot);
    private final JTree outlineTree = new JTree(outlineTreeModel);
    private List<OutlineTreeNodeData> allOutlineNodes = Collections.emptyList();
    private boolean suppressOutlineSelection = false;

    // Marks tab components
    private final DefaultListModel<BookmarkRecord> bookmarkListModel = new DefaultListModel<>();
    private final JList<BookmarkRecord> bookmarkList = new JList<>(bookmarkListModel);
    private final JButton addBookmarkBtn = new JButton("+ 当前位置");
    private final JButton exportMdBtn = new JButton("导出 MD");
    private final JButton editNoteBtn = new JButton("备注");
    private final JButton deleteBookmarkBtn = new JButton("删除");
    private final JLabel marksStatusLabel = new JLabel(" ");

    // Search tab components
    private final JTextField searchField = new JTextField();
    private final JToggleButton caseSensitiveBtn = new JToggleButton("Aa");
    private final JButton cancelSearchBtn = new JButton("×");
    private final JLabel searchStatusLabel = new JLabel("输入关键词全书检索");
    private final DefaultListModel<SearchHit> searchListModel = new DefaultListModel<>();
    private final JList<SearchHit> searchList = new JList<>(searchListModel);

    private volatile String currentBookId = "";
    private volatile BookSearchTarget searchTarget;
    private volatile DrawerListener drawerListener;
    private volatile boolean collapsed = false;
    private volatile Tab activeTab = Tab.OUTLINE;

    private AtomicBoolean activeSearchCancelFlag = new AtomicBoolean(false);
    private Future<?> activeSearchFuture;

    public SideDrawerPanel() {
        this(DocInspectorStateService.getInstance(), null);
    }

    public SideDrawerPanel(DocInspectorStateService stateService, FullTextSearchService externalSearchService) {
        super(new BorderLayout(0, 4));
        this.stateService = stateService != null ? stateService : DocInspectorStateService.getInstance();
        if (externalSearchService != null) {
            this.searchService = externalSearchService;
            this.ownsSearchService = false;
        } else {
            this.searchService = new FullTextSearchService();
            this.ownsSearchService = true;
        }

        setPreferredSize(new Dimension(260, 420));
        setMinimumSize(new Dimension(180, 200));
        setBorder(BorderFactory.createEmptyBorder());

        initHeader();
        initOutlineTab();
        initMarksTab();
        initSearchTab();

        add(cardContainer, BorderLayout.CENTER);
        selectTab(Tab.OUTLINE);
    }

    private void initHeader() {
        JPanel header = new JPanel(new BorderLayout(4, 0));
        header.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        JPanel tabsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        ButtonGroup group = new ButtonGroup();
        for (JToggleButton btn : List.of(outlineTabBtn, marksTabBtn, searchTabBtn)) {
            btn.setFocusable(false);
            btn.setMargin(new java.awt.Insets(2, 6, 2, 6));
            group.add(btn);
            tabsPanel.add(btn);
        }

        outlineTabBtn.addActionListener(e -> selectTab(Tab.OUTLINE));
        marksTabBtn.addActionListener(e -> selectTab(Tab.MARKS));
        searchTabBtn.addActionListener(e -> selectTab(Tab.SEARCH));

        collapseButton.setFocusable(false);
        collapseButton.setToolTipText("折叠侧边栏");
        collapseButton.setMargin(new java.awt.Insets(2, 6, 2, 6));
        collapseButton.addActionListener(e -> setCollapsed(true));

        header.add(tabsPanel, BorderLayout.CENTER);
        header.add(collapseButton, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
    }

    private void initOutlineTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 6, 6, 6));

        outlineFilterField.putClientProperty("JTextField.placeholderText", "过滤目录...");
        outlineFilterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                filterOutline(outlineFilterField.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                filterOutline(outlineFilterField.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                filterOutline(outlineFilterField.getText());
            }
        });

        outlineTree.setRootVisible(false);
        outlineTree.setShowsRootHandles(true);
        outlineTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        DefaultTreeCellRenderer renderer = new DefaultTreeCellRenderer();
        renderer.setLeafIcon(null);
        renderer.setOpenIcon(null);
        renderer.setClosedIcon(null);
        outlineTree.setCellRenderer(renderer);

        outlineTree.addTreeSelectionListener(e -> {
            if (suppressOutlineSelection) {
                return;
            }
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) outlineTree.getLastSelectedPathComponent();
            if (node != null && node.getUserObject() instanceof OutlineEntry entry) {
                DrawerListener listener = drawerListener;
                if (listener != null && entry.unitIndex() >= 0) {
                    listener.onOutlineSelected(entry.unitIndex(), entry.fragmentId());
                }
            }
        });

        panel.add(outlineFilterField, BorderLayout.NORTH);
        panel.add(UiChrome.wrapScrollPane(outlineTree), BorderLayout.CENTER);
        cardContainer.add(panel, Tab.OUTLINE.name());
    }

    private void initMarksTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 6, 6, 6));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        for (JButton btn : List.of(addBookmarkBtn, exportMdBtn, editNoteBtn, deleteBookmarkBtn)) {
            btn.setFocusable(false);
            btn.setMargin(new java.awt.Insets(2, 6, 2, 6));
            toolbar.add(btn);
        }

        addBookmarkBtn.addActionListener(e -> {
            DrawerListener listener = drawerListener;
            if (listener != null) {
                listener.onAddBookmarkRequested();
            }
        });

        exportMdBtn.addActionListener(e -> {
            String md = exportBookmarksMarkdown();
            if (!md.isBlank()) {
                try {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(md), null);
                    marksStatusLabel.setText("已导出 Markdown 并复制到剪贴板");
                } catch (Throwable ignored) {
                    marksStatusLabel.setText("已生成 Markdown");
                }
            }
        });

        editNoteBtn.addActionListener(e -> promptEditSelectedBookmarkNote());
        deleteBookmarkBtn.addActionListener(e -> removeSelectedBookmark());

        bookmarkList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        bookmarkList.setCellRenderer(new BookmarkCellRenderer());
        bookmarkList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    jumpToSelectedBookmark();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowBookmarkPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowBookmarkPopup(e);
            }
        });
        bookmarkList.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    jumpToSelectedBookmark();
                } else if (e.getKeyCode() == KeyEvent.VK_DELETE) {
                    removeSelectedBookmark();
                }
            }
        });

        marksStatusLabel.setFont(marksStatusLabel.getFont().deriveFont(11f));

        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(UiChrome.wrapScrollPane(bookmarkList), BorderLayout.CENTER);
        panel.add(marksStatusLabel, BorderLayout.SOUTH);
        cardContainer.add(panel, Tab.MARKS.name());
    }

    private void initSearchTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 6, 6, 6));

        JPanel topBox = new JPanel();
        topBox.setLayout(new BoxLayout(topBox, BoxLayout.Y_AXIS));

        JPanel searchRow = new JPanel(new BorderLayout(4, 0));
        searchField.putClientProperty("JTextField.placeholderText", "全书关键词检索...");
        searchField.addActionListener(e -> triggerSearch(searchField.getText(), caseSensitiveBtn.isSelected()));

        caseSensitiveBtn.setFocusable(false);
        caseSensitiveBtn.setToolTipText("区分大小写");
        caseSensitiveBtn.setMargin(new java.awt.Insets(2, 6, 2, 6));
        caseSensitiveBtn.addActionListener(e -> {
            if (!searchField.getText().isEmpty()) {
                triggerSearch(searchField.getText(), caseSensitiveBtn.isSelected());
            }
        });

        cancelSearchBtn.setFocusable(false);
        cancelSearchBtn.setToolTipText("停止/清空检索");
        cancelSearchBtn.setMargin(new java.awt.Insets(2, 6, 2, 6));
        cancelSearchBtn.addActionListener(e -> {
            cancelSearch();
            searchField.setText("");
            searchListModel.clear();
            searchStatusLabel.setText("输入关键词全书检索");
        });

        JPanel rightBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        rightBtns.add(caseSensitiveBtn);
        rightBtns.add(cancelSearchBtn);

        searchRow.add(searchField, BorderLayout.CENTER);
        searchRow.add(rightBtns, BorderLayout.EAST);

        searchStatusLabel.setFont(searchStatusLabel.getFont().deriveFont(11f));
        searchStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        topBox.add(searchRow);
        topBox.add(Box.createVerticalStrut(4));
        topBox.add(searchStatusLabel);

        searchList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        searchList.setCellRenderer(new SearchHitCellRenderer());
        searchList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() >= 1) {
                    notifySelectedSearchHit();
                }
            }
        });
        searchList.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    notifySelectedSearchHit();
                }
            }
        });

        panel.add(topBox, BorderLayout.NORTH);
        panel.add(UiChrome.wrapScrollPane(searchList), BorderLayout.CENTER);
        cardContainer.add(panel, Tab.SEARCH.name());
    }

    public void setDrawerListener(DrawerListener listener) {
        this.drawerListener = listener;
    }

    public void selectTab(Tab tab) {
        if (tab == null) {
            return;
        }
        this.activeTab = tab;
        switch (tab) {
            case OUTLINE -> outlineTabBtn.setSelected(true);
            case MARKS -> {
                marksTabBtn.setSelected(true);
                refreshBookmarks();
            }
            case SEARCH -> {
                searchTabBtn.setSelected(true);
                searchField.requestFocusInWindow();
            }
        }
        cardLayout.show(cardContainer, tab.name());
    }

    public Tab getActiveTab() {
        return activeTab;
    }

    public boolean isSearchCaseSensitive() {
        return caseSensitiveBtn.isSelected();
    }

    public void setCollapsed(boolean collapsed) {
        boolean changed = (this.collapsed != collapsed);
        this.collapsed = collapsed;
        setVisible(!collapsed);
        if (changed) {
            DrawerListener listener = drawerListener;
            if (listener != null) {
                listener.onCollapsedChanged(collapsed);
            }
        }
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    // ==================== Outline APIs ====================

    public void setEpubOutline(List<TocItem> tocItems) {
        List<OutlineTreeNodeData> converted = convertEpubToc(tocItems);
        setOutlineTreeData(converted);
    }

    public void setPdfOutline(List<PdfOutlineItem> outlineItems) {
        List<OutlineTreeNodeData> converted = convertPdfOutline(outlineItems);
        setOutlineTreeData(converted);
    }

    public void setGenericOutline(List<OutlineEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            setOutlineTreeData(Collections.emptyList());
            return;
        }
        List<OutlineTreeNodeData> converted = new ArrayList<>(entries.size());
        for (OutlineEntry entry : entries) {
            converted.add(new OutlineTreeNodeData(entry, List.of()));
        }
        setOutlineTreeData(converted);
    }

    public void clearOutline() {
        setOutlineTreeData(Collections.emptyList());
    }

    private void setOutlineTreeData(List<OutlineTreeNodeData> nodes) {
        this.allOutlineNodes = nodes != null ? List.copyOf(nodes) : Collections.emptyList();
        filterOutline(outlineFilterField.getText());
    }

    public void filterOutline(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        suppressOutlineSelection = true;
        try {
            outlineRoot.removeAllChildren();
            for (OutlineTreeNodeData nodeData : allOutlineNodes) {
                DefaultMutableTreeNode built = buildFilteredTreeNode(nodeData, normalized);
                if (built != null) {
                    outlineRoot.add(built);
                }
            }
            outlineTreeModel.reload();
            expandAllOutlineNodes();
        } finally {
            suppressOutlineSelection = false;
        }
    }

    public int getVisibleOutlineNodeCount() {
        return countTreeNodes(outlineRoot) - 1;
    }

    /**
     * 阅读切章/翻页时自动高亮最匹配的目录节点，不触发跳转回调。
     */
    public void syncCurrentUnit(int unitIndex) {
        DefaultMutableTreeNode best = findBestOutlineNodeForUnit(outlineRoot, unitIndex);
        if (best == null) {
            return;
        }
        suppressOutlineSelection = true;
        try {
            TreePath path = new TreePath(best.getPath());
            outlineTree.setSelectionPath(path);
            outlineTree.scrollPathToVisible(path);
        } finally {
            suppressOutlineSelection = false;
        }
    }

    public OutlineEntry getSelectedOutlineEntry() {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) outlineTree.getLastSelectedPathComponent();
        if (node != null && node.getUserObject() instanceof OutlineEntry entry) {
            return entry;
        }
        return null;
    }

    private static List<OutlineTreeNodeData> convertEpubToc(List<TocItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<OutlineTreeNodeData> result = new ArrayList<>(items.size());
        for (TocItem item : items) {
            OutlineEntry entry = new OutlineEntry(item.getTitle(), item.getSpineIndex(), item.getFragmentId());
            result.add(new OutlineTreeNodeData(entry, convertEpubToc(item.getChildren())));
        }
        return result;
    }

    private static List<OutlineTreeNodeData> convertPdfOutline(List<PdfOutlineItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<OutlineTreeNodeData> result = new ArrayList<>(items.size());
        for (PdfOutlineItem item : items) {
            OutlineEntry entry = new OutlineEntry(item.getTitle(), item.getPageIndex(), "");
            result.add(new OutlineTreeNodeData(entry, convertPdfOutline(item.getChildren())));
        }
        return result;
    }

    private static DefaultMutableTreeNode buildFilteredTreeNode(OutlineTreeNodeData data, String lowerQuery) {
        List<DefaultMutableTreeNode> matchedChildren = new ArrayList<>();
        for (OutlineTreeNodeData child : data.getChildren()) {
            DefaultMutableTreeNode childNode = buildFilteredTreeNode(child, lowerQuery);
            if (childNode != null) {
                matchedChildren.add(childNode);
            }
        }
        boolean selfMatches = lowerQuery.isEmpty()
                || data.getEntry().title().toLowerCase(Locale.ROOT).contains(lowerQuery);
        if (!selfMatches && matchedChildren.isEmpty()) {
            return null;
        }
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(data.getEntry());
        for (DefaultMutableTreeNode childNode : matchedChildren) {
            node.add(childNode);
        }
        return node;
    }

    private void expandAllOutlineNodes() {
        for (int i = 0; i < outlineTree.getRowCount(); i++) {
            outlineTree.expandRow(i);
        }
    }

    private static int countTreeNodes(DefaultMutableTreeNode node) {
        int count = 1;
        Enumeration<?> children = node.children();
        while (children.hasMoreElements()) {
            count += countTreeNodes((DefaultMutableTreeNode) children.nextElement());
        }
        return count;
    }

    private static DefaultMutableTreeNode findBestOutlineNodeForUnit(DefaultMutableTreeNode root, int unitIndex) {
        DefaultMutableTreeNode exact = null;
        DefaultMutableTreeNode nearestBefore = null;
        int nearestUnit = -1;

        Enumeration<?> depthFirst = root.depthFirstEnumeration();
        while (depthFirst.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) depthFirst.nextElement();
            if (node == root || !(node.getUserObject() instanceof OutlineEntry entry)) {
                continue;
            }
            int u = entry.unitIndex();
            if (u == unitIndex) {
                exact = node;
                break;
            }
            if (u >= 0 && u < unitIndex && u >= nearestUnit) {
                nearestUnit = u;
                nearestBefore = node;
            }
        }
        return exact != null ? exact : nearestBefore;
    }

    // ==================== Marks APIs ====================

    public void bindBook(String bookId) {
        this.currentBookId = bookId != null ? bookId : "";
        refreshBookmarks();
    }

    public void refreshBookmarks() {
        bookmarkListModel.clear();
        if (currentBookId.isEmpty()) {
            marksStatusLabel.setText("未打开书籍");
            return;
        }
        List<BookmarkRecord> records = stateService.listBookmarks(currentBookId);
        for (BookmarkRecord record : records) {
            bookmarkListModel.addElement(record);
        }
        marksStatusLabel.setText("共 " + records.size() + " 条书签");
    }

    public BookmarkRecord addBookmarkFromCurrent(int unitIndex,
                                                 double offsetRatio,
                                                 String locationLabel,
                                                 String snippet,
                                                 String note) {
        if (currentBookId.isEmpty()) {
            return null;
        }
        BookmarkRecord record = stateService.addBookmark(
                currentBookId, unitIndex, offsetRatio, locationLabel, snippet, note);
        refreshBookmarks();
        return record;
    }

    public void updateBookmarkNote(String bookmarkId, String note) {
        stateService.updateBookmarkNote(bookmarkId, note);
        refreshBookmarks();
    }

    public void removeBookmark(String bookmarkId) {
        stateService.removeBookmark(bookmarkId);
        refreshBookmarks();
    }

    public List<BookmarkRecord> getBookmarks() {
        List<BookmarkRecord> result = new ArrayList<>(bookmarkListModel.getSize());
        for (int i = 0; i < bookmarkListModel.getSize(); i++) {
            result.add(bookmarkListModel.getElementAt(i));
        }
        return result;
    }

    public String exportBookmarksMarkdown() {
        if (currentBookId.isEmpty()) {
            return "";
        }
        return stateService.exportBookmarksMarkdown(currentBookId);
    }

    private void jumpToSelectedBookmark() {
        BookmarkRecord selected = bookmarkList.getSelectedValue();
        if (selected != null && drawerListener != null) {
            drawerListener.onBookmarkSelected(selected);
        }
    }

    private void promptEditSelectedBookmarkNote() {
        BookmarkRecord selected = bookmarkList.getSelectedValue();
        if (selected == null) {
            return;
        }
        String updated = JOptionPane.showInputDialog(
                this,
                "编辑书签备注：",
                selected.decodedNote()
        );
        if (updated != null) {
            updateBookmarkNote(selected.getBookmarkId(), updated);
        }
    }

    private void removeSelectedBookmark() {
        BookmarkRecord selected = bookmarkList.getSelectedValue();
        if (selected != null) {
            removeBookmark(selected.getBookmarkId());
        }
    }

    private void maybeShowBookmarkPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int idx = bookmarkList.locationToIndex(e.getPoint());
        if (idx >= 0) {
            bookmarkList.setSelectedIndex(idx);
        }
        BookmarkRecord selected = bookmarkList.getSelectedValue();
        if (selected == null) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem jumpItem = new JMenuItem("跳转至该位置");
        jumpItem.addActionListener(ev -> jumpToSelectedBookmark());
        JMenuItem editItem = new JMenuItem("编辑备注");
        editItem.addActionListener(ev -> promptEditSelectedBookmarkNote());
        JMenuItem delItem = new JMenuItem("删除书签");
        delItem.addActionListener(ev -> removeBookmark(selected.getBookmarkId()));
        menu.add(jumpItem);
        menu.add(editItem);
        menu.addSeparator();
        menu.add(delItem);
        menu.show(bookmarkList, e.getX(), e.getY());
    }

    // ==================== Search APIs ====================

    public void setSearchTarget(BookSearchTarget target) {
        cancelSearch();
        this.searchTarget = target;
        searchListModel.clear();
        searchStatusLabel.setText(target != null ? "输入关键词全书检索" : "当前文档不支持全文检索");
    }

    public void setSearchService(FullTextSearchService service) {
        if (service != null) {
            this.searchService = service;
        }
    }

    public void triggerSearch(String keyword, boolean caseSensitive) {
        cancelSearch();
        searchListModel.clear();

        String trimmed = keyword != null ? keyword : "";
        if (!Objects.equals(searchField.getText(), trimmed)) {
            searchField.setText(trimmed);
        }
        caseSensitiveBtn.setSelected(caseSensitive);

        if (trimmed.isEmpty()) {
            searchStatusLabel.setText("输入关键词全书检索");
            return;
        }
        BookSearchTarget target = this.searchTarget;
        if (target == null) {
            searchStatusLabel.setText("未打开可检索文档");
            return;
        }

        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        this.activeSearchCancelFlag = cancelFlag;
        int totalUnits = target.getUnitCount();
        searchStatusLabel.setText("检索中 0/" + totalUnits + "…");

        this.activeSearchFuture = searchService.searchAsync(
                target,
                trimmed,
                caseSensitive,
                cancelFlag,
                new FullTextSearchService.SearchProgressListener() {
                    @Override
                    public void onHitsBatch(List<SearchHit> batch, int totalFoundSoFar, int scannedUnits, int total) {
                        if (cancelFlag.get()) {
                            return;
                        }
                        for (SearchHit hit : batch) {
                            searchListModel.addElement(hit);
                        }
                        searchStatusLabel.setText("检索中 " + scannedUnits + "/" + total + "（" + totalFoundSoFar + " 处）…");
                    }

                    @Override
                    public void onCompleted(int totalFound, int total, boolean truncated, boolean cancelled) {
                        if (cancelled) {
                            searchStatusLabel.setText("已取消（" + totalFound + " 处匹配）");
                        } else if (truncated) {
                            searchStatusLabel.setText("共 " + totalFound + " 处匹配（已达上限）");
                        } else {
                            searchStatusLabel.setText("共 " + totalFound + " 处匹配");
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        searchStatusLabel.setText("检索出错: " + error.getMessage());
                    }
                }
        );
    }

    public void cancelSearch() {
        activeSearchCancelFlag.set(true);
        if (activeSearchFuture != null) {
            activeSearchFuture.cancel(true);
            activeSearchFuture = null;
        }
    }

    public List<SearchHit> getSearchHits() {
        List<SearchHit> hits = new ArrayList<>(searchListModel.getSize());
        for (int i = 0; i < searchListModel.getSize(); i++) {
            hits.add(searchListModel.getElementAt(i));
        }
        return hits;
    }

    public int getSearchHitCount() {
        return searchListModel.getSize();
    }

    public String getSearchStatusText() {
        return searchStatusLabel.getText();
    }

    private void notifySelectedSearchHit() {
        SearchHit selected = searchList.getSelectedValue();
        if (selected != null && drawerListener != null) {
            drawerListener.onSearchHitSelected(selected);
        }
    }

    @Override
    public void close() {
        cancelSearch();
        if (ownsSearchService && searchService != null) {
            searchService.close();
        }
    }

    // ==================== Cell Renderers ====================

    private static final class BookmarkCellRenderer extends JPanel implements ListCellRenderer<BookmarkRecord> {
        private final JLabel titleLabel = new JLabel();
        private final JLabel snippetLabel = new JLabel();
        private final JLabel noteLabel = new JLabel();

        BookmarkCellRenderer() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 12f));
            snippetLabel.setFont(snippetLabel.getFont().deriveFont(11f));
            noteLabel.setFont(noteLabel.getFont().deriveFont(Font.ITALIC, 11f));
            add(titleLabel);
            add(snippetLabel);
            add(noteLabel);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends BookmarkRecord> list,
                                                      BookmarkRecord value,
                                                      int index,
                                                      boolean isSelected,
                                                      boolean cellHasFocus) {
            if (value == null) {
                return this;
            }
            String loc = value.decodedLocationLabel();
            titleLabel.setText(loc.isEmpty() ? ("#" + (value.getUnitIndex() + 1)) : loc);

            String snippet = value.decodedSnippet();
            snippetLabel.setText(snippet.isEmpty() ? "(无摘录)" : truncate(snippet, 64));

            String note = value.decodedNote();
            noteLabel.setVisible(!note.isEmpty());
            noteLabel.setText(note.isEmpty() ? "" : ("备注: " + truncate(note, 64)));

            if (isSelected) {
                setBackground(list.getSelectionBackground());
                titleLabel.setForeground(list.getSelectionForeground());
                snippetLabel.setForeground(list.getSelectionForeground());
                noteLabel.setForeground(list.getSelectionForeground());
            } else {
                setBackground(list.getBackground());
                titleLabel.setForeground(list.getForeground());
                snippetLabel.setForeground(list.getForeground());
                noteLabel.setForeground(list.getForeground());
            }
            setOpaque(true);
            return this;
        }

        private static String truncate(String s, int max) {
            return s.length() <= max ? s : s.substring(0, max) + "...";
        }
    }

    private static final class SearchHitCellRenderer extends JLabel implements ListCellRenderer<SearchHit> {

        SearchHitCellRenderer() {
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends SearchHit> list,
                                                      SearchHit value,
                                                      int index,
                                                      boolean isSelected,
                                                      boolean cellHasFocus) {
            if (value == null) {
                setText("");
                return this;
            }
            String html = "<html><div style='width:210px;'>"
                    + "<b>" + escapeHtml(value.locationLabel()) + "</b><br/>"
                    + "<span style='opacity:0.85;'>"
                    + escapeHtml(value.beforeText())
                    + "<b style='text-decoration:underline;'>" + escapeHtml(value.matchedText()) + "</b>"
                    + escapeHtml(value.afterText())
                    + "</span></div></html>";
            setText(html);
            setFont(list.getFont().deriveFont(11.5f));
            if (isSelected) {
                setBackground(list.getSelectionBackground());
                setForeground(list.getSelectionForeground());
            } else {
                setBackground(list.getBackground());
                setForeground(list.getForeground());
            }
            return this;
        }

        private static String escapeHtml(String s) {
            if (s == null) {
                return "";
            }
            return s.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
        }
    }
}
