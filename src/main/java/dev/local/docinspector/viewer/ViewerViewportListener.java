package dev.local.docinspector.viewer;

/**
 * 阅读器视口状态变化回调接口，供 EPUB / PDF / 图片集 / TXT 统一上报进度与摘要。
 */
public interface ViewerViewportListener {

    /**
     * 当章节/页码或滚动比例发生有效变化时触发（通常已在 EDT 线程）。
     *
     * @param unitIndex       当前单元索引（0-based，EPUB 为 Spine 索引，PDF 为页码）
     * @param offsetRatio     当前单元内垂直滚动比例（0.0 ~ 1.0）
     * @param totalUnits      总单元数
     * @param overallProgress 全书综合进度（0.0 ~ 1.0）
     * @param viewportSnippet 当前视口首段/首句可读摘要（可能为空串，不为 null）
     */
    void onViewportChanged(int unitIndex,
                           double offsetRatio,
                           int totalUnits,
                           double overallProgress,
                           String viewportSnippet);

    /**
     * 当切章或翻页时触发，用于同步目录高亮与标题栏。
     */
    default void onUnitChanged(int unitIndex, int totalUnits, String unitTitle) {
    }

    /**
     * 当用户在阅读区内产生交互时触发，用于重置老板键自动遮罩计时。
     */
    default void onUserInteraction() {
    }
}
