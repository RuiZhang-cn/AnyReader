package dev.local.docinspector.epub;

/**
 * EPUB Spine 线性阅读章节条目。
 */
public final class SpineChapter {

    private final int spineIndex;
    private final String idref;
    private final String normalizedEntryPath;
    private String title;
    private final String mediaType;

    public SpineChapter(int spineIndex,
                        String idref,
                        String normalizedEntryPath,
                        String title,
                        String mediaType) {
        this.spineIndex = spineIndex;
        this.idref = idref != null ? idref : "";
        this.normalizedEntryPath = normalizedEntryPath != null ? normalizedEntryPath : "";
        this.title = title != null ? title : "";
        this.mediaType = mediaType != null ? mediaType : "application/xhtml+xml";
    }

    public int getSpineIndex() {
        return spineIndex;
    }

    public String getIdref() {
        return idref;
    }

    public String getNormalizedEntryPath() {
        return normalizedEntryPath;
    }

    public String getTitle() {
        return title;
    }

    void setTitle(String title) {
        if (title != null && !title.isBlank()) {
            this.title = title;
        }
    }

    public String getMediaType() {
        return mediaType;
    }
}
