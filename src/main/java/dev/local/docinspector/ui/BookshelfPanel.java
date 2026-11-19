package dev.local.docinspector.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.table.JBTable;
import dev.local.docinspector.model.BookFormat;
import dev.local.docinspector.model.BookRecord;
import dev.local.docinspector.state.DocInspectorStateService;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumnModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 紧凑书架管理面板（基于 {@link JBTable}）：
 * <ul>
 *     <li>显示伪装别名/分组/格式/阅读进度/末次阅读时间，有别名时严格隐藏原书名；</li>
 *     <li>支持单文件导入与目录扫描，完全绕过 IDEA 最近文件历史；</li>
 *     <li>支持过滤、分组、右键设别名/置顶/改分组/缺失文件重定位（保留进度与书签）/移除/一键清痕。</li>
 * </ul>
 */
public final class BookshelfPanel extends JPanel {

    public interface BookshelfListener {
        default void onOpenBookRequested(BookRecord record) {
        }

        default void onTracesCleared() {
        }

        default void onBookRemoved(String bookId) {
        }
    }

    private static final String ALL_GROUPS = "全部";

    private final DocInspectorStateService stateService;
    private final BookshelfTableModel tableModel = new BookshelfTableModel();
    private final JTable table = createTable(tableModel);

    private final JButton importFileBtn = new JButton("+ 文件");
    private final JButton scanDirBtn = new JButton("+ 目录");
    private final JComboBox<String> groupCombo = new JComboBox<>(new String[]{ALL_GROUPS});
    private final JTextField filterField = new JTextField(12);
    private final JLabel statusLabel = new JLabel(" ");

    private volatile BookshelfListener listener;
    private boolean updatingGroups = false;

    public BookshelfPanel() {
        this(DocInspectorStateService.getInstance());
    }

    public BookshelfPanel(DocInspectorStateService stateService) {
        super(new BorderLayout(0, 4));
        this.stateService = stateService != null ? stateService : DocInspectorStateService.getInstance();
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        add(buildToolbar(), BorderLayout.NORTH);
        configureTable();
        add(UiChrome.wrapScrollPane(table), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        wireEvents();
        refreshTable();
    }

    private static JTable createTable(BookshelfTableModel model) {
        if (ApplicationManager.getApplication() != null) {
            try {
                return new JBTable(model);
            } catch (Throwable ignored) {
            }
        }
        return new JTable(model);
    }

    private JPanel buildToolbar() {
        JPanel top = new JPanel(new BorderLayout(4, 4));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for (JButton b : new JButton[]{importFileBtn, scanDirBtn}) {
            b.setMargin(new Insets(2, 6, 2, 6));
            b.setFocusable(false);
        }
        importFileBtn.setToolTipText("导入本地文档或图片集（不写入 IDEA 最近文件历史）");
        scanDirBtn.setToolTipText("扫描本地目录批量导入支持格式");
        left.add(importFileBtn);
        left.add(scanDirBtn);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        filterField.putClientProperty("JTextField.placeholderText", "过滤书名/别名/分组...");
        right.add(groupCombo);
        right.add(filterField);

        top.add(left, BorderLayout.WEST);
        top.add(right, BorderLayout.CENTER);
        return top;
    }

    private void configureTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(24);
        table.setShowGrid(false);
        table.setAutoCreateRowSorter(false);
        table.getTableHeader().setReorderingAllowed(false);

        TableColumnModel cm = table.getColumnModel();
        if (cm.getColumnCount() >= 5) {
            cm.getColumn(0).setPreferredWidth(180);
            cm.getColumn(1).setPreferredWidth(70);
            cm.getColumn(2).setPreferredWidth(60);
            cm.getColumn(3).setPreferredWidth(105);
            cm.getColumn(4).setPreferredWidth(95);
        }
    }

    private void wireEvents() {
        importFileBtn.addActionListener(e -> chooseAndImportFile());
        scanDirBtn.addActionListener(e -> chooseAndScanDirectory());

        groupCombo.addActionListener(e -> {
            if (!updatingGroups) {
                refreshTable();
            }
        });

        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refreshTable();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refreshTable();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refreshTable();
            }
        });

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        openBookAtRow(row);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }
        });
    }

    private void maybeShowPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(e.getPoint());
        if (row >= 0) {
            table.setRowSelectionInterval(row, row);
        }
        BookRecord selected = row >= 0 ? tableModel.getBookAt(row) : null;
        JPopupMenu menu = buildContextMenu(selected);
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    private JPopupMenu buildContextMenu(BookRecord record) {
        JPopupMenu menu = new JPopupMenu();
        if (record != null) {
            JMenuItem openItem = new JMenuItem("打开阅读");
            openItem.addActionListener(e -> openBook(record));
            menu.add(openItem);

            JMenuItem aliasItem = new JMenuItem("设置伪装别名...");
            aliasItem.addActionListener(e -> promptSetAlias(record));
            menu.add(aliasItem);

            JMenuItem groupItem = new JMenuItem("修改分组...");
            groupItem.addActionListener(e -> promptSetGroup(record));
            menu.add(groupItem);

            JMenuItem pinItem = new JMenuItem(record.isPinned() ? "取消置顶" : "置顶");
            pinItem.addActionListener(e -> {
                stateService.setBookPinned(record.getBookId(), !record.isPinned());
                refreshTable();
            });
            menu.add(pinItem);

            JMenuItem relinkItem = new JMenuItem("重定位缺失文件...");
            relinkItem.addActionListener(e -> promptRelinkFile(record));
            menu.add(relinkItem);

            JMenuItem removeItem = new JMenuItem("移除书架");
            removeItem.addActionListener(e -> {
                stateService.removeBook(record.getBookId(), true);
                refreshTable();
                if (listener != null) {
                    listener.onBookRemoved(record.getBookId());
                }
            });
            menu.add(removeItem);
            menu.addSeparator();
        }

        JMenuItem clearItem = new JMenuItem("一键清除全部阅读痕迹");
        clearItem.addActionListener(e -> promptClearAllTraces());
        menu.add(clearItem);
        return menu;
    }

    private void chooseAndImportFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Documents (EPUB, PDF, TXT, MD, Images)",
                "epub", "pdf", "txt", "md", "markdown", "png", "jpg", "jpeg", "gif", "webp", "bmp"
        ));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File[] files = chooser.getSelectedFiles();
        if (files == null || files.length == 0) {
            File single = chooser.getSelectedFile();
            if (single != null) {
                files = new File[]{single};
            }
        }
        int imported = 0;
        if (files != null) {
            for (File f : files) {
                if (importFile(f) != null) {
                    imported++;
                }
            }
        }
        refreshTable();
        statusLabel.setText("已导入 " + imported + " 项");
    }

    private void chooseAndScanDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File dir = chooser.getSelectedFile();
        int count = importDirectory(dir);
        refreshTable();
        statusLabel.setText("目录扫描完成，新增/更新 " + count + " 项");
    }

    public BookRecord importFile(File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        BookFormat format = BookFormat.fromFile(file);
        if (format == null) {
            return null;
        }
        BookRecord rec = stateService.addOrGetBook(file);
        refreshTable();
        return rec;
    }

    public int importDirectory(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return 0;
        }
        int count = scanRecursive(dir, 0);
        refreshTable();
        return count;
    }

    private int scanRecursive(File dir, int depth) {
        if (depth > 8) {
            return 0;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        int count = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                if (child.getName().startsWith(".")) {
                    continue;
                }
                count += scanRecursive(child, depth + 1);
            } else {
                String name = child.getName().toLowerCase();
                if (name.endsWith(".epub") || name.endsWith(".pdf")
                        || name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".markdown")) {
                    if (BookFormat.fromFile(child) != null) {
                        stateService.addOrGetBook(child);
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private void openBookAtRow(int row) {
        BookRecord record = tableModel.getBookAt(row);
        if (record != null) {
            openBook(record);
        }
    }

    private void openBook(BookRecord record) {
        File file = new File(record.decodedPath());
        if (!file.exists()) {
            int opt = JOptionPane.showConfirmDialog(
                    this,
                    "原路径文件不存在，是否立即重新关联文件路径？（保留历史进度与书签）",
                    "文件缺失",
                    JOptionPane.YES_NO_OPTION
            );
            if (opt == JOptionPane.YES_OPTION) {
                if (!promptRelinkFile(record)) {
                    return;
                }
            } else {
                return;
            }
        }
        if (listener != null) {
            listener.onOpenBookRequested(record);
        }
    }

    private void promptSetAlias(BookRecord record) {
        String current = record.decodedAlias();
        String input = JOptionPane.showInputDialog(
                this,
                "输入伪装别名（留空恢复原名，仅显示别名）：",
                current
        );
        if (input != null) {
            stateService.setBookAlias(record.getBookId(), input);
            refreshTable();
        }
    }

    private void promptSetGroup(BookRecord record) {
        String input = JOptionPane.showInputDialog(
                this,
                "输入分组名称：",
                record.getGroupTag()
        );
        if (input != null) {
            stateService.setBookGroup(record.getBookId(), input);
            refreshTable();
        }
    }

    private boolean promptRelinkFile(BookRecord record) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return false;
        }
        File target = chooser.getSelectedFile();
        if (target == null || !target.exists()) {
            return false;
        }
        boolean ok = stateService.relinkBookPath(record.getBookId(), target);
        if (ok) {
            refreshTable();
            statusLabel.setText("已重定位文件并保留进度与书签");
        }
        return ok;
    }

    private void promptClearAllTraces() {
        int opt = JOptionPane.showConfirmDialog(
                this,
                "确定清除全部书架记录、书签与阅读痕迹？此操作不可撤销。",
                "一键清除阅读痕迹",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (opt == JOptionPane.YES_OPTION) {
            stateService.clearAllTraces();
            refreshTable();
            statusLabel.setText("已清除全部痕迹");
            if (listener != null) {
                listener.onTracesCleared();
            }
        }
    }

    public void setBookshelfListener(BookshelfListener listener) {
        this.listener = listener;
    }

    public void setGroupFilter(String group) {
        refreshGroupChoices();
        if (group == null || group.isBlank()) {
            groupCombo.setSelectedItem(ALL_GROUPS);
        } else {
            groupCombo.setSelectedItem(group);
        }
        refreshTable();
    }

    public void setQueryFilter(String query) {
        filterField.setText(query != null ? query : "");
        refreshTable();
    }

    public void refreshTable() {
        refreshGroupChoices();
        String selectedGroup = Objects.toString(groupCombo.getSelectedItem(), ALL_GROUPS);
        String query = filterField.getText();
        List<BookRecord> books = stateService.listBooks(selectedGroup, query);
        tableModel.setRows(books);
        statusLabel.setText("共 " + books.size() + " 项");
    }

    private void refreshGroupChoices() {
        updatingGroups = true;
        try {
            Object current = groupCombo.getSelectedItem();
            DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
            model.addElement(ALL_GROUPS);
            for (String tag : stateService.listGroupTags()) {
                model.addElement(tag);
            }
            groupCombo.setModel(model);
            if (current != null && model.getIndexOf(current) >= 0) {
                groupCombo.setSelectedItem(current);
            } else {
                groupCombo.setSelectedIndex(0);
            }
        } finally {
            updatingGroups = false;
        }
    }

    public int getRowCount() {
        return tableModel.getRowCount();
    }

    public BookRecord getBookAtRow(int row) {
        return tableModel.getBookAt(row);
    }

    public Object getTableValueAt(int row, int col) {
        return tableModel.getValueAt(row, col);
    }

    public BookshelfTableModel getTableModel() {
        return tableModel;
    }

    public static String formatProgress(BookRecord record) {
        if (record == null) {
            return "0%";
        }
        int pct = (int) Math.round(record.getOverallProgress() * 100.0);
        int total = record.getTotalUnits();
        if (total <= 0) {
            return pct + "%";
        }
        int current = Math.min(total, record.getLastUnitIndex() + 1);
        return switch (record.getFormat()) {
            case PDF -> "P." + current + "/" + total + " · " + pct + "%";
            case IMAGE_DIR -> current + "/" + total + " · " + pct + "%";
            case EPUB, TXT, MARKDOWN -> "Ch." + current + "/" + total + " · " + pct + "%";
        };
    }

    public static String formatLastRead(long epochMs) {
        if (epochMs <= 0L) {
            return "-";
        }
        return new SimpleDateFormat("MM-dd HH:mm").format(new Date(epochMs));
    }

    public static final class BookshelfTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"名称", "分组", "格式", "进度", "末次阅读"};
        private List<BookRecord> rows = Collections.emptyList();

        public void setRows(List<BookRecord> rows) {
            this.rows = rows != null ? new ArrayList<>(rows) : Collections.emptyList();
            fireTableDataChanged();
        }

        public BookRecord getBookAt(int row) {
            if (row < 0 || row >= rows.size()) {
                return null;
            }
            return rows.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            BookRecord r = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> (r.isPinned() ? "★ " : "") + r.displayTitle();
                case 1 -> r.getGroupTag();
                case 2 -> r.getFormat().name();
                case 3 -> formatProgress(r);
                case 4 -> formatLastRead(r.getLastReadEpochMs());
                default -> "";
            };
        }
    }
}
