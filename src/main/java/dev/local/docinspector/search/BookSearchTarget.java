package dev.local.docinspector.search;

/**
 * 全文检索统一目标抽象。
 * <p>
 * EPUB（按 Spine 章节）、PDF（按页）以及后续 TXT/MD（按分块）均实现此接口，
 * 使 {@link FullTextSearchService} 无需感知具体文档格式即可跨单元检索。
 */
public interface BookSearchTarget {

    /**
     * 文档总单元数（章节数/页数/分块数）。
     */
    int getUnitCount();

    /**
     * 指定单元的可读定位标签（如 {@code Ch.3 · 第三章} 或 {@code P.12}）。
     */
    String getUnitLabel(int unitIndex);

    /**
     * 指定单元的纯文本内容；越界或不可读时返回空串。
     */
    String getUnitPlainText(int unitIndex);
}
