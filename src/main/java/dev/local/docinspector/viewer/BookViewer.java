package dev.local.docinspector.viewer;

import dev.local.docinspector.model.ReaderSettings;

import javax.swing.JComponent;

/**
 * 统一阅读视图抽象，供 EPUB / PDF / 图片集 / 纯文本与工作区主面板共用。
 */
public interface BookViewer extends AutoCloseable {

    /**
     * 返回可嵌入 Swing 容器的根组件。
     */
    JComponent getComponent();

    /**
     * 当前所处单元索引（0-based）。
     */
    int getCurrentUnitIndex();

    /**
     * 总单元数（章节数/页数/图片张数）。
     */
    int getUnitCount();

    /**
     * 当前单元内垂直滚动比例（0.0 ~ 1.0）。
     */
    double getCurrentOffsetRatio();

    /**
     * 当前视口首段/首句摘要（用于状态栏微读与书签摘录）。
     */
    String getCurrentViewportSnippet();

    /**
     * 跳转到指定单元并恢复比例或锚点。
     *
     * @param unitIndex   目标单元索引（0-based）
     * @param offsetRatio 垂直滚动比例（0.0 ~ 1.0）
     * @param anchorId    可选页内锚点 ID（仅 HTML/EPUB 使用，可为 null）
     */
    void goToUnit(int unitIndex, double offsetRatio, String anchorId);

    default void goToUnit(int unitIndex, double offsetRatio) {
        goToUnit(unitIndex, offsetRatio, null);
    }

    default void nextUnit() {
        int next = getCurrentUnitIndex() + 1;
        if (next < getUnitCount()) {
            goToUnit(next, 0.0, null);
        }
    }

    default void prevUnit() {
        int prev = getCurrentUnitIndex() - 1;
        if (prev >= 0) {
            goToUnit(prev, 0.0, null);
        }
    }

    /**
     * 实时应用主题、字体、图片隐私策略、滤镜或自动滚屏设置。
     */
    void applySettings(ReaderSettings settings);

    /**
     * 注册视口状态监听器。
     */
    void setViewportListener(ViewerViewportListener listener);

    /**
     * 在当前单元内高亮关键词并滚动到第 {@code targetOrdinal} 处匹配（0-based）。
     *
     * @return 当前单元内的匹配总数；无匹配或不支持时返回 0
     */
    int highlightKeyword(String keyword, boolean caseSensitive, int targetOrdinal);

    /**
     * 从全文检索结果跳转到指定单元并高亮定位第 {@code matchOrdinalInUnit} 处匹配。
     *
     * @return 目标单元内的匹配总数；无匹配或不支持时返回 0
     */
    default int jumpToSearchHit(int unitIndex, String keyword, boolean caseSensitive, int matchOrdinalInUnit) {
        if (unitIndex != getCurrentUnitIndex()) {
            goToUnit(unitIndex, 0.0, null);
        }
        return highlightKeyword(keyword, caseSensitive, matchOrdinalInUnit);
    }

    /**
     * 清除当前单元的高亮标记。
     */
    void clearHighlights();

    /**
     * 释放浏览器/渲染器/文件句柄与后台线程资源。
     */
    void dispose();

    @Override
    default void close() {
        dispose();
    }
}
