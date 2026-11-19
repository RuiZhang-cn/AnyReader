package dev.local.docinspector.pdf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PDF 书签大纲节点，支持多级嵌套与 0-based 页码定位。
 */
public final class PdfOutlineItem {

    private final String title;
    private final int pageIndex;
    private final List<PdfOutlineItem> children;

    public PdfOutlineItem(String title, int pageIndex, List<PdfOutlineItem> children) {
        this.title = title != null ? title.trim() : "";
        this.pageIndex = pageIndex;
        this.children = children != null
                ? Collections.unmodifiableList(new ArrayList<>(children))
                : Collections.emptyList();
    }

    public String getTitle() {
        return title;
    }

    /**
     * 目标页码（0-based）；若无法解析则为 -1。
     */
    public int getPageIndex() {
        return pageIndex;
    }

    public List<PdfOutlineItem> getChildren() {
        return children;
    }
}
