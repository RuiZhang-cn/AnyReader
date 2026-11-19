package dev.local.docinspector.pdf;

import com.intellij.ui.scale.JBUIScale;
import dev.local.docinspector.model.PdfColorFilter;
import dev.local.docinspector.model.ReaderSettings;
import dev.local.docinspector.ui.UiChrome;
import dev.local.docinspector.viewer.BookViewer;
import dev.local.docinspector.viewer.ViewerViewportListener;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Dimension2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 PDFBox 3.0.5 与 Swing 高 DPI 画布的 PDF 阅读组件。
 * 支持 {@link JBUIScale#sysScale} 缩放、透明度调节、护眼滤镜、90° 旋转、拖拽平移、滚轮/键盘翻页与字形坐标高亮。
 */
public final class PdfViewerComponent implements BookViewer {

    public static final float MIN_ZOOM = 0.25f;
    public static final float MAX_ZOOM = 4.0f;
    public static final float MIN_OPACITY = 0.15f;
    public static final float MAX_OPACITY = 1.0f;

    private final JPanel rootPanel;
    private final PdfCanvas canvas;
    private final JScrollPane scrollPane;
    private final JButton prevButton;
    private final JButton nextButton;
    private final JTextField pageInputField;
    private final JLabel totalPagesLabel;
    private final JButton zoomOutButton;
    private final JLabel zoomLabel;
    private final JButton zoomInButton;
    private final JToggleButton fitWidthButton;
    private final JButton rotateCcwButton;
    private final JButton rotateCwButton;
    private final JLabel statusLabel;

    private volatile PdfDocumentSession session;
    private volatile ViewerViewportListener viewportListener;
    private volatile ReaderSettings currentSettings = new ReaderSettings();

    private volatile int currentPageIndex = 0;
    private volatile double currentOffsetRatio = 0.0;
    private volatile float zoom = 1.0f;
    private volatile boolean fitWidth = true;
    private volatile int rotationDeg = 0;
    private volatile float opacity = 1.0f;
    private volatile PdfColorFilter colorFilter = PdfColorFilter.ORIGINAL;

    private volatile BufferedImage currentPageImage;
    private volatile int currentImageWidth = 595;
    private volatile int currentImageHeight = 842;
    private volatile List<Rectangle2D> highlightRects = Collections.emptyList();
    private volatile int activeHighlightIndex = -1;
    private volatile String errorMessage = "";
    private volatile boolean disposed = false;
    private volatile boolean suppressScrollEvents = false;
    private final AtomicLong renderGeneration = new AtomicLong(0);

    public PdfViewerComponent() {
        this.rootPanel = new JPanel(new BorderLayout());
        this.canvas = new PdfCanvas();
        this.scrollPane = UiChrome.wrapScrollPane(this.canvas);
        this.scrollPane.getVerticalScrollBar().setUnitIncrement(24);
        this.scrollPane.getHorizontalScrollBar().setUnitIncrement(24);

        this.prevButton = new JButton("<");
        this.nextButton = new JButton(">");
        this.pageInputField = new JTextField("0", 3);
        this.pageInputField.setHorizontalAlignment(SwingConstants.CENTER);
        this.totalPagesLabel = new JLabel("/ 0");
        this.zoomOutButton = new JButton("-");
        this.zoomLabel = new JLabel("100%");
        this.zoomInButton = new JButton("+");
        this.fitWidthButton = new JToggleButton("Fit", true);
        this.rotateCcwButton = new JButton("↺");
        this.rotateCwButton = new JButton("↻");
        this.statusLabel = new JLabel("");

        JPanel controlBar = buildControlBar();
        this.rootPanel.add(this.scrollPane, BorderLayout.CENTER);
        this.rootPanel.add(controlBar, BorderLayout.SOUTH);

        installInteractions();
        updateToolbarState();
    }

    private JPanel buildControlBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 3));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(128, 128, 128, 45)));

        for (JButton btn : new JButton[]{prevButton, nextButton, zoomOutButton, zoomInButton, rotateCcwButton, rotateCwButton}) {
            btn.setFocusable(false);
            btn.setMargin(new java.awt.Insets(2, 6, 2, 6));
        }
        fitWidthButton.setFocusable(false);
        fitWidthButton.setMargin(new java.awt.Insets(2, 6, 2, 6));

        prevButton.addActionListener(e -> {
            notifyUserInteraction();
            prevUnit();
        });
        nextButton.addActionListener(e -> {
            notifyUserInteraction();
            nextUnit();
        });
        pageInputField.addActionListener(e -> {
            notifyUserInteraction();
            jumpToTypedPage();
        });
        zoomOutButton.addActionListener(e -> {
            notifyUserInteraction();
            zoomOut();
        });
        zoomInButton.addActionListener(e -> {
            notifyUserInteraction();
            zoomIn();
        });
        fitWidthButton.addActionListener(e -> {
            notifyUserInteraction();
            setFitWidth(fitWidthButton.isSelected());
        });
        rotateCcwButton.addActionListener(e -> {
            notifyUserInteraction();
            rotateCounterClockwise();
        });
        rotateCwButton.addActionListener(e -> {
            notifyUserInteraction();
            rotateClockwise();
        });

        bar.add(prevButton);
        bar.add(pageInputField);
        bar.add(totalPagesLabel);
        bar.add(nextButton);
        bar.add(zoomOutButton);
        bar.add(zoomLabel);
        bar.add(zoomInButton);
        bar.add(fitWidthButton);
        bar.add(rotateCcwButton);
        bar.add(rotateCwButton);
        bar.add(statusLabel);
        return bar;
    }

    private void installInteractions() {
        scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (fitWidth && session != null && !disposed) {
                    requestRenderCurrentPage(currentOffsetRatio);
                }
            }
        });

        scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (suppressScrollEvents || disposed || session == null) {
                return;
            }
            if (!e.getValueIsAdjusting()) {
                updateCurrentOffsetFromScroll();
                notifyViewportChanged();
            }
        });

        MouseAdapter dragPanAdapter = new MouseAdapter() {
            private Point lastPoint;

            @Override
            public void mousePressed(MouseEvent e) {
                notifyUserInteraction();
                canvas.requestFocusInWindow();
                if (SwingUtilities.isLeftMouseButton(e)) {
                    lastPoint = e.getLocationOnScreen();
                    canvas.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                lastPoint = null;
                canvas.setCursor(Cursor.getDefaultCursor());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (lastPoint == null) {
                    return;
                }
                Point now = e.getLocationOnScreen();
                int dx = lastPoint.x - now.x;
                int dy = lastPoint.y - now.y;
                lastPoint = now;

                Point pos = scrollPane.getViewport().getViewPosition();
                Dimension viewSize = scrollPane.getViewport().getViewSize();
                Dimension extent = scrollPane.getViewport().getExtentSize();
                int maxX = Math.max(0, viewSize.width - extent.width);
                int maxY = Math.max(0, viewSize.height - extent.height);
                int nextX = Math.max(0, Math.min(maxX, pos.x + dx));
                int nextY = Math.max(0, Math.min(maxY, pos.y + dy));
                scrollPane.getViewport().setViewPosition(new Point(nextX, nextY));
                updateCurrentOffsetFromScroll();
            }
        };
        canvas.addMouseListener(dragPanAdapter);
        canvas.addMouseMotionListener(dragPanAdapter);

        canvas.addMouseWheelListener(this::handleMouseWheel);

        canvas.setFocusable(true);
        bindKeyAction(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0), "pdfNextPage", this::nextUnit);
        bindKeyAction(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "pdfNextPageRight", this::nextUnit);
        bindKeyAction(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0), "pdfPrevPage", this::prevUnit);
        bindKeyAction(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "pdfPrevPageLeft", this::prevUnit);
    }

    private void bindKeyAction(KeyStroke keyStroke, String name, Runnable action) {
        canvas.getInputMap(JComponent.WHEN_FOCUSED).put(keyStroke, name);
        canvas.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                notifyUserInteraction();
                action.run();
            }
        });
    }

    private void handleMouseWheel(MouseWheelEvent e) {
        notifyUserInteraction();
        if ((e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0) {
            if (e.getWheelRotation() < 0) {
                zoomIn();
            } else if (e.getWheelRotation() > 0) {
                zoomOut();
            }
            e.consume();
            return;
        }

        JScrollBar vBar = scrollPane.getVerticalScrollBar();
        int min = vBar.getMinimum();
        int max = vBar.getMaximum() - vBar.getVisibleAmount();
        int val = vBar.getValue();
        int rot = e.getWheelRotation();
        if (rot > 0 && (max <= min || val >= max - 2)) {
            if (currentPageIndex + 1 < getUnitCount()) {
                goToUnit(currentPageIndex + 1, 0.0, null);
                e.consume();
            }
        } else if (rot < 0 && (max <= min || val <= min + 2)) {
            if (currentPageIndex - 1 >= 0) {
                goToUnit(currentPageIndex - 1, 0.0, null);
                e.consume();
            }
        }
    }

    /**
     * 打开本地 PDF 文件并定位到指定页码与滚动比例。
     */
    public void openFile(File pdfFile, int pageIndex, double offsetRatio) {
        if (disposed) {
            return;
        }
        try {
            PdfDocumentSession newSession = PdfDocumentSession.open(pdfFile);
            openSession(newSession, pageIndex, offsetRatio);
        } catch (IOException e) {
            closeCurrentSession();
            this.errorMessage = "无法打开 PDF: " + e.getMessage();
            this.statusLabel.setText(this.errorMessage);
            this.currentPageImage = null;
            updateToolbarState();
            canvas.repaint();
        }
    }

    /**
     * 绑定已打开的 {@link PdfDocumentSession} 并渲染指定页。
     */
    public void openSession(PdfDocumentSession newSession, int pageIndex, double offsetRatio) {
        if (disposed) {
            return;
        }
        PdfDocumentSession old = this.session;
        this.session = newSession;
        if (old != null && old != newSession) {
            try {
                old.close();
            } catch (IOException ignored) {
            }
        }
        this.errorMessage = "";
        this.statusLabel.setText("");
        this.highlightRects = Collections.emptyList();
        this.activeHighlightIndex = -1;

        if (newSession == null || newSession.getPageCount() == 0) {
            this.currentPageIndex = 0;
            this.currentOffsetRatio = 0.0;
            this.currentPageImage = null;
            updateToolbarState();
            canvas.repaint();
            return;
        }
        goToUnit(pageIndex, offsetRatio, null);
    }

    public PdfDocumentSession getSession() {
        return session;
    }

    public void applyViewState(float zoom, boolean fitWidth, int rotationDeg) {
        this.zoom = clampZoom(zoom);
        this.fitWidth = fitWidth;
        this.rotationDeg = PdfDocumentSession.normalizeRotation(rotationDeg);
        this.fitWidthButton.setSelected(this.fitWidth);
        updateToolbarState();
        if (session != null && !disposed) {
            requestRenderCurrentPage(currentOffsetRatio);
        }
    }

    public void setZoom(float zoom) {
        this.fitWidth = false;
        this.fitWidthButton.setSelected(false);
        this.zoom = clampZoom(zoom);
        updateToolbarState();
        if (session != null && !disposed) {
            requestRenderCurrentPage(currentOffsetRatio);
        }
    }

    public float getZoom() {
        return zoom;
    }

    public void zoomIn() {
        setZoom(computeEffectiveLogicalZoom() * 1.2f);
    }

    public void zoomOut() {
        setZoom(computeEffectiveLogicalZoom() / 1.2f);
    }

    public void setFitWidth(boolean fitWidth) {
        this.fitWidth = fitWidth;
        this.fitWidthButton.setSelected(fitWidth);
        updateToolbarState();
        if (session != null && !disposed) {
            requestRenderCurrentPage(currentOffsetRatio);
        }
    }

    public boolean isFitWidth() {
        return fitWidth;
    }

    public void rotateClockwise() {
        setRotation(rotationDeg + 90);
    }

    public void rotateCounterClockwise() {
        setRotation(rotationDeg - 90);
    }

    public void setRotation(int degrees) {
        this.rotationDeg = PdfDocumentSession.normalizeRotation(degrees);
        updateToolbarState();
        if (session != null && !disposed) {
            requestRenderCurrentPage(0.0);
        }
    }

    public int getRotation() {
        return rotationDeg;
    }

    public void setOpacity(float opacity) {
        if (Float.isNaN(opacity)) {
            this.opacity = 1.0f;
        } else {
            this.opacity = Math.max(MIN_OPACITY, Math.min(MAX_OPACITY, opacity));
        }
        canvas.repaint();
    }

    public float getOpacity() {
        return opacity;
    }

    public PdfColorFilter getColorFilter() {
        return colorFilter;
    }

    @Override
    public JComponent getComponent() {
        return rootPanel;
    }

    @Override
    public int getCurrentUnitIndex() {
        return currentPageIndex;
    }

    @Override
    public int getUnitCount() {
        PdfDocumentSession s = this.session;
        return s != null ? s.getPageCount() : 0;
    }

    @Override
    public double getCurrentOffsetRatio() {
        return currentOffsetRatio;
    }

    @Override
    public String getCurrentViewportSnippet() {
        PdfDocumentSession s = this.session;
        if (s == null || s.isClosed() || getUnitCount() == 0) {
            return "";
        }
        String text = s.getPageText(currentPageIndex).replaceAll("\\s+", " ").trim();
        if (text.length() > 140) {
            return text.substring(0, 140) + "...";
        }
        return text;
    }

    @Override
    public void goToUnit(int unitIndex, double offsetRatio, String anchorId) {
        PdfDocumentSession s = this.session;
        if (disposed || s == null || s.getPageCount() == 0) {
            return;
        }
        int clampedIndex = Math.max(0, Math.min(s.getPageCount() - 1, unitIndex));
        double clampedRatio = clampRatio(offsetRatio);
        boolean pageChanged = (clampedIndex != this.currentPageIndex);

        this.currentPageIndex = clampedIndex;
        this.currentOffsetRatio = clampedRatio;
        if (pageChanged) {
            this.highlightRects = Collections.emptyList();
            this.activeHighlightIndex = -1;
        }
        updateToolbarState();
        requestRenderCurrentPage(clampedRatio);

        ViewerViewportListener listener = this.viewportListener;
        if (listener != null) {
            listener.onUnitChanged(clampedIndex, s.getPageCount(), "P." + (clampedIndex + 1));
            notifyViewportChanged();
        }
    }

    @Override
    public void applySettings(ReaderSettings settings) {
        if (settings == null) {
            return;
        }
        this.currentSettings = settings;
        PdfColorFilter newFilter = settings.getPdfColorFilter();
        boolean filterChanged = (newFilter != this.colorFilter);
        this.colorFilter = newFilter;
        setOpacity(settings.getPdfOpacity());
        if (filterChanged && session != null && !disposed) {
            requestRenderCurrentPage(currentOffsetRatio);
        }
    }

    @Override
    public void setViewportListener(ViewerViewportListener listener) {
        this.viewportListener = listener;
    }

    @Override
    public int highlightKeyword(String keyword, boolean caseSensitive, int targetOrdinal) {
        PdfDocumentSession s = this.session;
        if (disposed || s == null || s.isClosed() || keyword == null || keyword.isEmpty()) {
            clearHighlights();
            return 0;
        }
        List<Rectangle2D> rects = s.findHighlightRects(currentPageIndex, keyword, caseSensitive);
        this.highlightRects = rects;
        if (rects.isEmpty()) {
            this.activeHighlightIndex = -1;
            canvas.repaint();
            return 0;
        }
        int safeOrdinal = Math.max(0, Math.min(rects.size() - 1, targetOrdinal));
        this.activeHighlightIndex = safeOrdinal;
        scrollToHighlightRect(rects.get(safeOrdinal));
        canvas.repaint();
        return rects.size();
    }

    @Override
    public void clearHighlights() {
        this.highlightRects = Collections.emptyList();
        this.activeHighlightIndex = -1;
        canvas.repaint();
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        renderGeneration.incrementAndGet();
        currentPageImage = null;
        highlightRects = Collections.emptyList();
        closeCurrentSession();
    }

    private void closeCurrentSession() {
        PdfDocumentSession s = this.session;
        this.session = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void jumpToTypedPage() {
        int total = getUnitCount();
        if (total <= 0) {
            return;
        }
        try {
            int typed = Integer.parseInt(pageInputField.getText().trim());
            int target = Math.max(0, Math.min(total - 1, typed - 1));
            goToUnit(target, 0.0, null);
        } catch (NumberFormatException ignored) {
            updateToolbarState();
        }
    }

    private float computeEffectiveLogicalZoom() {
        PdfDocumentSession s = this.session;
        if (!fitWidth || s == null || s.isClosed()) {
            return zoom;
        }
        int viewportWidth = scrollPane.getViewport().getWidth();
        if (viewportWidth <= 20) {
            return zoom;
        }
        Dimension2D pagePts = s.getPageSizePoints(currentPageIndex, rotationDeg);
        double usableWidth = Math.max(80.0, viewportWidth - 20.0);
        float fitZoom = (float) (usableWidth / Math.max(1.0, pagePts.getWidth()));
        return clampZoom(fitZoom);
    }

    private float getSysScaleSafe() {
        try {
            float s = JBUIScale.sysScale(canvas);
            return s > 0.1f ? s : 1.0f;
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }

    private void requestRenderCurrentPage(double targetOffsetRatio) {
        PdfDocumentSession s = this.session;
        if (disposed || s == null || s.isClosed() || s.getPageCount() == 0) {
            return;
        }
        int pageIdx = this.currentPageIndex;
        float logicalZoom = computeEffectiveLogicalZoom();
        float sysScale = getSysScaleSafe();
        float renderScale = logicalZoom * sysScale;
        int rot = this.rotationDeg;
        PdfColorFilter filter = this.colorFilter;

        Dimension2D pagePts = s.getPageSizePoints(pageIdx, rot);
        int logicalW = Math.max(1, (int) Math.round(pagePts.getWidth() * logicalZoom));
        int logicalH = Math.max(1, (int) Math.round(pagePts.getHeight() * logicalZoom));
        this.currentImageWidth = logicalW;
        this.currentImageHeight = logicalH;
        canvas.setPreferredSize(new Dimension(logicalW, logicalH));
        canvas.revalidate();

        long gen = renderGeneration.incrementAndGet();
        s.renderPageAsync(pageIdx, renderScale, rot, filter).whenComplete((img, err) -> {
            Runnable applyUi = () -> {
                if (disposed || gen != renderGeneration.get()) {
                    return;
                }
                if (err != null) {
                    this.errorMessage = "PDF 渲染失败: " + err.getMessage();
                    this.statusLabel.setText(this.errorMessage);
                    canvas.repaint();
                    return;
                }
                this.currentPageImage = img;
                this.errorMessage = "";
                this.statusLabel.setText("");
                updateToolbarState();
                canvas.repaint();
                if (activeHighlightIndex >= 0 && activeHighlightIndex < highlightRects.size()) {
                    scrollToHighlightRect(highlightRects.get(activeHighlightIndex));
                } else {
                    applyScrollRatio(targetOffsetRatio);
                }
            };
            if (SwingUtilities.isEventDispatchThread()) {
                applyUi.run();
            } else {
                SwingUtilities.invokeLater(applyUi);
            }
        });
    }

    private void applyScrollRatio(double ratio) {
        suppressScrollEvents = true;
        try {
            JScrollBar vBar = scrollPane.getVerticalScrollBar();
            int max = Math.max(0, vBar.getMaximum() - vBar.getVisibleAmount());
            vBar.setValue((int) Math.round(clampRatio(ratio) * max));
        } finally {
            suppressScrollEvents = false;
        }
    }

    private void updateCurrentOffsetFromScroll() {
        JScrollBar vBar = scrollPane.getVerticalScrollBar();
        int max = vBar.getMaximum() - vBar.getVisibleAmount();
        if (max <= 0) {
            this.currentOffsetRatio = 0.0;
        } else {
            this.currentOffsetRatio = clampRatio((double) vBar.getValue() / max);
        }
    }

    private void scrollToHighlightRect(Rectangle2D pageRect) {
        PdfDocumentSession s = this.session;
        if (s == null || pageRect == null) {
            return;
        }
        Dimension2D origPts = s.getPageSizePoints(currentPageIndex, 0);
        float logicalZoom = computeEffectiveLogicalZoom();
        Rectangle transformed = transformHighlightRect(
                pageRect, origPts.getWidth(), origPts.getHeight(), logicalZoom, rotationDeg);
        transformed.grow(16, 24);
        canvas.scrollRectToVisible(transformed);
        updateCurrentOffsetFromScroll();
    }

    private static Rectangle transformHighlightRect(Rectangle2D r,
                                                    double origW,
                                                    double origH,
                                                    double scale,
                                                    int rot) {
        double x = r.getX();
        double y = r.getY();
        double w = r.getWidth();
        double h = r.getHeight();
        double tx;
        double ty;
        double tw;
        double th;
        switch (rot) {
            case 90 -> {
                tx = (origH - y - h) * scale;
                ty = x * scale;
                tw = h * scale;
                th = w * scale;
            }
            case 180 -> {
                tx = (origW - x - w) * scale;
                ty = (origH - y - h) * scale;
                tw = w * scale;
                th = h * scale;
            }
            case 270 -> {
                tx = y * scale;
                ty = (origW - x - w) * scale;
                tw = h * scale;
                th = w * scale;
            }
            default -> {
                tx = x * scale;
                ty = y * scale;
                tw = w * scale;
                th = h * scale;
            }
        }
        return new Rectangle(
                (int) Math.floor(tx),
                (int) Math.floor(ty),
                Math.max(2, (int) Math.ceil(tw)),
                Math.max(2, (int) Math.ceil(th))
        );
    }

    private void updateToolbarState() {
        int total = getUnitCount();
        pageInputField.setText(total > 0 ? String.valueOf(currentPageIndex + 1) : "0");
        totalPagesLabel.setText("/ " + total);
        prevButton.setEnabled(total > 0 && currentPageIndex > 0);
        nextButton.setEnabled(total > 0 && currentPageIndex + 1 < total);
        int pct = Math.round(computeEffectiveLogicalZoom() * 100f);
        zoomLabel.setText(pct + "%");
    }

    private void notifyViewportChanged() {
        ViewerViewportListener listener = this.viewportListener;
        int total = getUnitCount();
        if (listener == null || total <= 0) {
            return;
        }
        double overall = (currentPageIndex + currentOffsetRatio) / total;
        listener.onViewportChanged(
                currentPageIndex,
                currentOffsetRatio,
                total,
                Math.max(0.0, Math.min(1.0, overall)),
                getCurrentViewportSnippet()
        );
    }

    private void notifyUserInteraction() {
        ViewerViewportListener listener = this.viewportListener;
        if (listener != null) {
            listener.onUserInteraction();
        }
    }

    private static float clampZoom(float z) {
        if (Float.isNaN(z) || Float.isInfinite(z)) {
            return 1.0f;
        }
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
    }

    private static double clampRatio(double ratio) {
        if (Double.isNaN(ratio) || Double.isInfinite(ratio)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, ratio));
    }

    private final class PdfCanvas extends JPanel {
        PdfCanvas() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                if (errorMessage != null && !errorMessage.isEmpty()) {
                    g2.setColor(new Color(200, 80, 80));
                    g2.drawString(errorMessage, 20, 32);
                    return;
                }

                BufferedImage img = currentPageImage;
                if (img == null) {
                    return;
                }

                Composite oldComposite = g2.getComposite();
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));

                int drawW = Math.max(1, currentImageWidth);
                int drawH = Math.max(1, currentImageHeight);
                int offsetX = Math.max(0, (getWidth() - drawW) / 2);
                g2.drawImage(img, offsetX, 0, drawW, drawH, null);

                List<Rectangle2D> rects = highlightRects;
                PdfDocumentSession s = session;
                if (rects != null && !rects.isEmpty() && s != null && !s.isClosed()) {
                    Dimension2D origPts = s.getPageSizePoints(currentPageIndex, 0);
                    float logicalZoom = computeEffectiveLogicalZoom();
                    for (int i = 0; i < rects.size(); i++) {
                        Rectangle box = transformHighlightRect(
                                rects.get(i), origPts.getWidth(), origPts.getHeight(), logicalZoom, rotationDeg);
                        box.translate(offsetX, 0);
                        boolean active = (i == activeHighlightIndex);
                        g2.setColor(active ? new Color(255, 140, 0, 145) : new Color(255, 225, 50, 95));
                        g2.fillRect(box.x, box.y, box.width, box.height);
                        if (active) {
                            g2.setColor(new Color(230, 100, 0, 210));
                            g2.drawRect(box.x, box.y, box.width, box.height);
                        }
                    }
                }
                g2.setComposite(oldComposite);
            } finally {
                g2.dispose();
            }
        }
    }
}
