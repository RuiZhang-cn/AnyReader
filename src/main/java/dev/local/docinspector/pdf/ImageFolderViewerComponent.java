package dev.local.docinspector.pdf;

import dev.local.docinspector.model.PdfColorFilter;
import dev.local.docinspector.model.ReaderSettings;
import dev.local.docinspector.ui.UiChrome;
import dev.local.docinspector.viewer.BookViewer;
import dev.local.docinspector.viewer.ViewerViewportListener;

import javax.imageio.ImageIO;
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
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 独立图片集与单图浏览组件（实现 {@link BookViewer}）：
 * 支持自然序排序本地图片目录、单张图片、缩放/自适应宽度/90°旋转、拖拽平移、
 * {@link PdfColorFilterProcessor} 隐私滤镜、透明度调节与阅读进度记忆。
 */
public final class ImageFolderViewerComponent implements BookViewer {

    public static final float MIN_ZOOM = 0.25f;
    public static final float MAX_ZOOM = 4.0f;
    public static final float MIN_OPACITY = 0.15f;
    public static final float MAX_OPACITY = 1.0f;

    private final JPanel rootPanel;
    private final ImageCanvas canvas;
    private final JScrollPane scrollPane;
    private final JButton prevButton;
    private final JButton nextButton;
    private final JTextField indexInputField;
    private final JLabel totalLabel;
    private final JButton zoomOutButton;
    private final JLabel zoomLabel;
    private final JButton zoomInButton;
    private final JToggleButton fitWidthButton;
    private final JButton rotateCcwButton;
    private final JButton rotateCwButton;
    private final JLabel nameLabel;

    private volatile List<File> imageFiles = Collections.emptyList();
    private volatile int currentIndex = 0;
    private volatile double currentOffsetRatio = 0.0;
    private volatile float zoom = 1.0f;
    private volatile boolean fitWidth = true;
    private volatile int rotationDeg = 0;
    private volatile float opacity = 1.0f;
    private volatile PdfColorFilter colorFilter = PdfColorFilter.GRAYSCALE_STEALTH;

    private volatile BufferedImage rawImage;
    private volatile BufferedImage displayImage;
    private volatile int drawWidth = 0;
    private volatile int drawHeight = 0;
    private volatile String errorMessage = "";
    private volatile ViewerViewportListener viewportListener;
    private volatile boolean suppressScrollEvents = false;
    private volatile boolean disposed = false;

    public ImageFolderViewerComponent() {
        this.rootPanel = new JPanel(new BorderLayout());
        this.canvas = new ImageCanvas();
        this.scrollPane = UiChrome.wrapScrollPane(this.canvas);
        this.scrollPane.getVerticalScrollBar().setUnitIncrement(24);
        this.scrollPane.getHorizontalScrollBar().setUnitIncrement(24);

        this.prevButton = new JButton("<");
        this.nextButton = new JButton(">");
        this.indexInputField = new JTextField("0", 3);
        this.indexInputField.setHorizontalAlignment(SwingConstants.CENTER);
        this.totalLabel = new JLabel("/ 0");
        this.zoomOutButton = new JButton("-");
        this.zoomLabel = new JLabel("100%");
        this.zoomInButton = new JButton("+");
        this.fitWidthButton = new JToggleButton("Fit", true);
        this.rotateCcwButton = new JButton("↺");
        this.rotateCwButton = new JButton("↻");
        this.nameLabel = new JLabel("");

        this.rootPanel.add(this.scrollPane, BorderLayout.CENTER);
        this.rootPanel.add(buildControlBar(), BorderLayout.SOUTH);

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
        indexInputField.addActionListener(e -> {
            notifyUserInteraction();
            jumpToTypedIndex();
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
        bar.add(indexInputField);
        bar.add(totalLabel);
        bar.add(nextButton);
        bar.add(zoomOutButton);
        bar.add(zoomLabel);
        bar.add(zoomInButton);
        bar.add(fitWidthButton);
        bar.add(rotateCcwButton);
        bar.add(rotateCwButton);
        bar.add(nameLabel);
        return bar;
    }

    private void installInteractions() {
        scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (fitWidth && displayImage != null && !disposed) {
                    relayoutCanvas(currentOffsetRatio);
                }
            }
        });

        scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (suppressScrollEvents || disposed || imageFiles.isEmpty()) {
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
                scrollPane.getViewport().setViewPosition(new Point(
                        Math.max(0, Math.min(maxX, pos.x + dx)),
                        Math.max(0, Math.min(maxY, pos.y + dy))
                ));
                updateCurrentOffsetFromScroll();
            }
        };
        canvas.addMouseListener(dragPanAdapter);
        canvas.addMouseMotionListener(dragPanAdapter);
        canvas.addMouseWheelListener(this::handleMouseWheel);

        canvas.setFocusable(true);
        bindKeyAction(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0), "imgNext", this::nextUnit);
        bindKeyAction(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "imgNextRight", this::nextUnit);
        bindKeyAction(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0), "imgPrev", this::prevUnit);
        bindKeyAction(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "imgPrevLeft", this::prevUnit);
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
            if (currentIndex + 1 < getUnitCount()) {
                goToUnit(currentIndex + 1, 0.0, null);
                e.consume();
            }
        } else if (rot < 0 && (max <= min || val <= min + 2)) {
            if (currentIndex - 1 >= 0) {
                goToUnit(currentIndex - 1, 0.0, null);
                e.consume();
            }
        }
    }

    /**
     * 打开单张图片或本地图片目录（自动按自然序排序目录内图片）。
     */
    public void openPath(File path, int unitIndex, double offsetRatio) {
        if (disposed) {
            return;
        }
        if (path == null || !path.exists()) {
            this.imageFiles = Collections.emptyList();
            this.rawImage = null;
            this.displayImage = null;
            this.errorMessage = "图片路径不存在";
            updateToolbarState();
            canvas.repaint();
            return;
        }

        List<File> collected = new ArrayList<>();
        if (path.isDirectory()) {
            File[] children = path.listFiles();
            if (children != null) {
                for (File f : children) {
                    if (f.isFile() && isSupportedImage(f.getName())) {
                        collected.add(f);
                    }
                }
            }
            collected.sort((a, b) -> compareNatural(a.getName(), b.getName()));
        } else if (path.isFile() && isSupportedImage(path.getName())) {
            collected.add(path);
        }

        this.imageFiles = Collections.unmodifiableList(collected);
        this.errorMessage = collected.isEmpty() ? "目录中未找到支持的图片" : "";
        if (collected.isEmpty()) {
            this.rawImage = null;
            this.displayImage = null;
            this.currentIndex = 0;
            this.currentOffsetRatio = 0.0;
            updateToolbarState();
            canvas.repaint();
            return;
        }

        goToUnit(unitIndex, offsetRatio, null);
    }

    public List<File> getImageFiles() {
        return imageFiles;
    }

    public static boolean isSupportedImage(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".gif")
                || lower.endsWith(".webp")
                || lower.endsWith(".bmp");
    }

    /**
     * 自然序文件名比较（例如 page2.png < page10.png）。
     */
    public static int compareNatural(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        int i = 0;
        int j = 0;
        int lenA = a.length();
        int lenB = b.length();
        while (i < lenA && j < lenB) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startA = i;
                while (i < lenA && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                int startB = j;
                while (j < lenB && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                String numA = stripLeadingZeros(a.substring(startA, i));
                String numB = stripLeadingZeros(b.substring(startB, j));
                if (numA.length() != numB.length()) {
                    return Integer.compare(numA.length(), numB.length());
                }
                int cmp = numA.compareTo(numB);
                if (cmp != 0) {
                    return cmp;
                }
                int lenDiff = (i - startA) - (j - startB);
                if (lenDiff != 0) {
                    return lenDiff;
                }
            } else {
                int cmp = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (cmp != 0) {
                    return cmp;
                }
                i++;
                j++;
            }
        }
        return Integer.compare(lenA - i, lenB - j);
    }

    private static String stripLeadingZeros(String s) {
        int idx = 0;
        while (idx < s.length() - 1 && s.charAt(idx) == '0') {
            idx++;
        }
        return s.substring(idx);
    }

    public void setZoom(float zoom) {
        this.fitWidth = false;
        this.fitWidthButton.setSelected(false);
        this.zoom = clampZoom(zoom);
        relayoutCanvas(currentOffsetRatio);
    }

    public float getZoom() {
        return zoom;
    }

    public void zoomIn() {
        setZoom(computeEffectiveZoom() * 1.2f);
    }

    public void zoomOut() {
        setZoom(computeEffectiveZoom() / 1.2f);
    }

    public void setFitWidth(boolean fitWidth) {
        this.fitWidth = fitWidth;
        this.fitWidthButton.setSelected(fitWidth);
        relayoutCanvas(currentOffsetRatio);
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
        rebuildDisplayImage();
        relayoutCanvas(0.0);
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
        return currentIndex;
    }

    @Override
    public int getUnitCount() {
        return imageFiles.size();
    }

    @Override
    public double getCurrentOffsetRatio() {
        return currentOffsetRatio;
    }

    @Override
    public String getCurrentViewportSnippet() {
        List<File> files = this.imageFiles;
        if (files.isEmpty() || currentIndex < 0 || currentIndex >= files.size()) {
            return "";
        }
        return files.get(currentIndex).getName();
    }

    @Override
    public void goToUnit(int unitIndex, double offsetRatio, String anchorId) {
        List<File> files = this.imageFiles;
        if (disposed || files.isEmpty()) {
            return;
        }
        int clampedIndex = Math.max(0, Math.min(files.size() - 1, unitIndex));
        double clampedRatio = clampRatio(offsetRatio);
        this.currentIndex = clampedIndex;
        this.currentOffsetRatio = clampedRatio;

        File target = files.get(clampedIndex);
        try {
            BufferedImage loaded = ImageIO.read(target);
            if (loaded == null) {
                this.rawImage = null;
                this.displayImage = null;
                this.errorMessage = "无法解码图片: " + target.getName();
            } else {
                this.rawImage = loaded;
                this.errorMessage = "";
                rebuildDisplayImage();
            }
        } catch (IOException e) {
            this.rawImage = null;
            this.displayImage = null;
            this.errorMessage = "读取图片失败: " + e.getMessage();
        }

        relayoutCanvas(clampedRatio);
        updateToolbarState();

        ViewerViewportListener listener = this.viewportListener;
        if (listener != null) {
            listener.onUnitChanged(clampedIndex, files.size(), target.getName());
            notifyViewportChanged();
        }
    }

    @Override
    public void applySettings(ReaderSettings settings) {
        if (settings == null) {
            return;
        }
        PdfColorFilter newFilter = settings.getPdfColorFilter();
        boolean filterChanged = (newFilter != this.colorFilter);
        this.colorFilter = newFilter;
        setOpacity(settings.getPdfOpacity());
        if (filterChanged && rawImage != null && !disposed) {
            rebuildDisplayImage();
            canvas.repaint();
        }
    }

    @Override
    public void setViewportListener(ViewerViewportListener listener) {
        this.viewportListener = listener;
    }

    @Override
    public int highlightKeyword(String keyword, boolean caseSensitive, int targetOrdinal) {
        return 0;
    }

    @Override
    public void clearHighlights() {
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        rawImage = null;
        displayImage = null;
        imageFiles = Collections.emptyList();
    }

    private void rebuildDisplayImage() {
        BufferedImage raw = this.rawImage;
        if (raw == null) {
            this.displayImage = null;
            return;
        }
        BufferedImage rotated = rotateImageIfNeeded(raw, rotationDeg);
        this.displayImage = PdfColorFilterProcessor.apply(rotated, colorFilter);
    }

    private void relayoutCanvas(double targetRatio) {
        BufferedImage img = this.displayImage;
        if (img == null) {
            this.drawWidth = 0;
            this.drawHeight = 0;
            canvas.setPreferredSize(new Dimension(200, 200));
            canvas.revalidate();
            canvas.repaint();
            updateToolbarState();
            return;
        }
        float effectiveZoom = computeEffectiveZoom();
        this.drawWidth = Math.max(1, Math.round(img.getWidth() * effectiveZoom));
        this.drawHeight = Math.max(1, Math.round(img.getHeight() * effectiveZoom));
        canvas.setPreferredSize(new Dimension(drawWidth, drawHeight));
        canvas.revalidate();
        canvas.repaint();
        applyScrollRatio(targetRatio);
        updateToolbarState();
    }

    private float computeEffectiveZoom() {
        BufferedImage img = this.displayImage;
        if (!fitWidth || img == null || img.getWidth() <= 0) {
            return zoom;
        }
        int viewportWidth = scrollPane.getViewport().getWidth();
        if (viewportWidth <= 20) {
            return zoom;
        }
        double usable = Math.max(80.0, viewportWidth - 20.0);
        return clampZoom((float) (usable / img.getWidth()));
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

    private void jumpToTypedIndex() {
        int total = getUnitCount();
        if (total <= 0) {
            return;
        }
        try {
            int typed = Integer.parseInt(indexInputField.getText().trim());
            int target = Math.max(0, Math.min(total - 1, typed - 1));
            goToUnit(target, 0.0, null);
        } catch (NumberFormatException ignored) {
            updateToolbarState();
        }
    }

    private void updateToolbarState() {
        int total = getUnitCount();
        indexInputField.setText(total > 0 ? String.valueOf(currentIndex + 1) : "0");
        totalLabel.setText("/ " + total);
        prevButton.setEnabled(total > 0 && currentIndex > 0);
        nextButton.setEnabled(total > 0 && currentIndex + 1 < total);
        int pct = Math.round(computeEffectiveZoom() * 100f);
        zoomLabel.setText(pct + "%");
        nameLabel.setText(getCurrentViewportSnippet());
    }

    private void notifyViewportChanged() {
        ViewerViewportListener listener = this.viewportListener;
        int total = getUnitCount();
        if (listener == null || total <= 0) {
            return;
        }
        double overall = (currentIndex + currentOffsetRatio) / total;
        listener.onViewportChanged(
                currentIndex,
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

    private static BufferedImage rotateImageIfNeeded(BufferedImage src, int normRot) {
        if (src == null || normRot == 0) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int dstW = (normRot == 90 || normRot == 270) ? h : w;
        int dstH = (normRot == 90 || normRot == 270) ? w : h;
        int type = src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage dst = new BufferedImage(dstW, dstH, type);
        Graphics2D g2 = dst.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.translate(dstW / 2.0, dstH / 2.0);
            g2.rotate(Math.toRadians(normRot));
            g2.translate(-w / 2.0, -h / 2.0);
            g2.drawImage(src, 0, 0, null);
        } finally {
            g2.dispose();
        }
        return dst;
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

    private final class ImageCanvas extends JPanel {
        ImageCanvas() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                if (errorMessage != null && !errorMessage.isEmpty()) {
                    g2.setColor(new Color(200, 80, 80));
                    g2.drawString(errorMessage, 20, 32);
                    return;
                }
                BufferedImage img = displayImage;
                if (img == null || drawWidth <= 0 || drawHeight <= 0) {
                    return;
                }
                Composite old = g2.getComposite();
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
                int offsetX = Math.max(0, (getWidth() - drawWidth) / 2);
                g2.drawImage(img, offsetX, 0, drawWidth, drawHeight, null);
                g2.setComposite(old);
            } finally {
                g2.dispose();
            }
        }
    }
}
