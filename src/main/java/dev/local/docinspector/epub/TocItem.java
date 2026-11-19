package dev.local.docinspector.epub;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * EPUB 目录节点（兼容 EPUB3 nav.xhtml 与 EPUB2 toc.ncx）。
 */
public final class TocItem {

    private final String title;
    private final String normalizedEntryPath;
    private final String fragmentId;
    private final int spineIndex;
    private final List<TocItem> children;

    public TocItem(String title,
                   String normalizedEntryPath,
                   String fragmentId,
                   int spineIndex,
                   List<TocItem> children) {
        this.title = title != null ? title.trim() : "";
        this.normalizedEntryPath = normalizedEntryPath != null ? normalizedEntryPath : "";
        this.fragmentId = fragmentId != null ? fragmentId : "";
        this.spineIndex = spineIndex;
        this.children = children != null
                ? Collections.unmodifiableList(new ArrayList<>(children))
                : Collections.emptyList();
    }

    public String getTitle() {
        return title;
    }

    public String getNormalizedEntryPath() {
        return normalizedEntryPath;
    }

    public String getFragmentId() {
        return fragmentId;
    }

    public int getSpineIndex() {
        return spineIndex;
    }

    public List<TocItem> getChildren() {
        return children;
    }
}
