package dev.local.docinspector.ui;

import dev.local.docinspector.viewer.BookViewer;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * 紧凑内嵌页内查找条：
 * <ul>
 *     <li>{@code Ctrl+F} 呼出并聚焦输入框；</li>
 *     <li>{@code Esc} 关闭并清除高亮；</li>
 *     <li>{@code Enter} 跳转下一个匹配，{@code Shift+Enter} 跳转上一个匹配；</li>
 *     <li>实时显示 {@code 当前/总数}（如 {@code 3 / 14} 或 {@code 0 / 0}）。</li>
 * </ul>
 */
public final class InlineFindBar extends JPanel {

    private final JTextField findField = new JTextField(16);
    private final JToggleButton caseSensitiveButton = new JToggleButton("Aa");
    private final JLabel countLabel = new JLabel("0 / 0");
    private final JButton prevButton = new JButton("↑");
    private final JButton nextButton = new JButton("↓");
    private final JButton closeButton = new JButton("×");

    private volatile BookViewer viewer;
    private int currentMatchOrdinal = -1;
    private int totalMatches = 0;

    public InlineFindBar() {
        super(new BorderLayout(6, 0));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new java.awt.Color(128, 128, 128, 70)),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)
        ));
        setVisible(false);

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        findField.putClientProperty("JTextField.placeholderText", "页内查找...");
        findField.setPreferredSize(new Dimension(170, 24));

        caseSensitiveButton.setFocusable(false);
        caseSensitiveButton.setToolTipText("区分大小写");
        caseSensitiveButton.setMargin(new Insets(1, 5, 1, 5));

        countLabel.setPreferredSize(new Dimension(56, 22));
        countLabel.setHorizontalAlignment(JLabel.CENTER);

        leftPanel.add(findField);
        leftPanel.add(caseSensitiveButton);
        leftPanel.add(countLabel);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        for (JButton btn : new JButton[]{prevButton, nextButton, closeButton}) {
            btn.setFocusable(false);
            btn.setMargin(new Insets(1, 6, 1, 6));
            rightPanel.add(btn);
        }
        prevButton.setToolTipText("上一个 (Shift+Enter)");
        nextButton.setToolTipText("下一个 (Enter)");
        closeButton.setToolTipText("关闭 (Esc)");

        add(leftPanel, BorderLayout.CENTER);
        add(rightPanel, BorderLayout.EAST);

        wireEvents();
    }

    private void wireEvents() {
        findField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                executeNewQuery();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                executeNewQuery();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                executeNewQuery();
            }
        });

        caseSensitiveButton.addActionListener(e -> executeNewQuery());
        prevButton.addActionListener(e -> findPrevious());
        nextButton.addActionListener(e -> findNext());
        closeButton.addActionListener(e -> closeBar());

        // Enter -> next, Shift+Enter -> previous, Esc -> close
        findField.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "findNext");
        findField.getActionMap().put("findNext", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findNext();
            }
        });

        findField.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "findPrev");
        findField.getActionMap().put("findPrev", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findPrevious();
            }
        });

        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeFindBar");
        getActionMap().put("closeFindBar", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                closeBar();
            }
        });
    }

    /**
     * 为指定容器绑定 {@code Ctrl+F} 快捷键以呼出本查找条。
     */
    public void installFindShortcut(JComponent hostComponent) {
        if (hostComponent == null) {
            return;
        }
        hostComponent.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "openInlineFindBar");
        hostComponent.getActionMap().put("openInlineFindBar", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openAndFocus();
            }
        });
    }

    public void attachViewer(BookViewer viewer) {
        this.viewer = viewer;
        if (isVisible()) {
            refreshForCurrentUnit();
        }
    }

    public void openAndFocus() {
        setVisible(true);
        findField.requestFocusInWindow();
        findField.selectAll();
        if (!findField.getText().isEmpty()) {
            executeNewQuery();
        }
    }

    public void openWithQuery(String query) {
        setVisible(true);
        findField.setText(query != null ? query : "");
        findField.requestFocusInWindow();
    }

    public void closeBar() {
        setVisible(false);
        this.currentMatchOrdinal = -1;
        this.totalMatches = 0;
        countLabel.setText("0 / 0");
        BookViewer v = this.viewer;
        if (v != null) {
            v.clearHighlights();
        }
    }

    public void findNext() {
        if (totalMatches <= 0) {
            executeNewQuery();
            return;
        }
        currentMatchOrdinal = (currentMatchOrdinal + 1) % totalMatches;
        applyHighlightAtCurrentOrdinal();
    }

    public void findPrevious() {
        if (totalMatches <= 0) {
            executeNewQuery();
            return;
        }
        currentMatchOrdinal = (currentMatchOrdinal - 1 + totalMatches) % totalMatches;
        applyHighlightAtCurrentOrdinal();
    }

    public void refreshForCurrentUnit() {
        if (!isVisible()) {
            return;
        }
        executeNewQuery();
    }

    private void executeNewQuery() {
        String query = findField.getText();
        BookViewer v = this.viewer;
        if (v == null || query == null || query.isEmpty()) {
            this.totalMatches = 0;
            this.currentMatchOrdinal = -1;
            if (v != null) {
                v.clearHighlights();
            }
            updateCountLabel();
            return;
        }

        int count = v.highlightKeyword(query, caseSensitiveButton.isSelected(), 0);
        this.totalMatches = Math.max(0, count);
        this.currentMatchOrdinal = this.totalMatches > 0 ? 0 : -1;
        updateCountLabel();
    }

    private void applyHighlightAtCurrentOrdinal() {
        BookViewer v = this.viewer;
        String query = findField.getText();
        if (v == null || query == null || query.isEmpty()) {
            return;
        }
        int count = v.highlightKeyword(query, caseSensitiveButton.isSelected(), currentMatchOrdinal);
        this.totalMatches = Math.max(0, count);
        if (this.totalMatches == 0) {
            this.currentMatchOrdinal = -1;
        } else if (this.currentMatchOrdinal >= this.totalMatches) {
            this.currentMatchOrdinal = 0;
        }
        updateCountLabel();
    }

    private void updateCountLabel() {
        if (totalMatches <= 0 || currentMatchOrdinal < 0) {
            countLabel.setText("0 / 0");
        } else {
            countLabel.setText((currentMatchOrdinal + 1) + " / " + totalMatches);
        }
    }

    public String getCurrentQuery() {
        return findField.getText();
    }

    public boolean isCaseSensitive() {
        return caseSensitiveButton.isSelected();
    }

    public int getCurrentMatchOrdinal() {
        return currentMatchOrdinal;
    }

    public int getTotalMatches() {
        return totalMatches;
    }

    public String getCountText() {
        return countLabel.getText();
    }
}
