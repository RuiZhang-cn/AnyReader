package dev.local.docinspector.model;

import dev.local.docinspector.privacy.ObfuscationCodec;

/**
 * 书签持久化记录。位置标签、摘录和备注均以 {@link ObfuscationCodec} 编码存储。
 */
public class BookmarkRecord {

    private String bookmarkId = "";
    private String bookId = "";
    private int unitIndex = 0;
    private double offsetRatio = 0.0;
    private String encodedLocationLabel = "";
    private String encodedSnippet = "";
    private String encodedNote = "";
    private long createdAtEpochMs = 0L;

    public BookmarkRecord() {
    }

    public String decodedLocationLabel() {
        return ObfuscationCodec.decode(encodedLocationLabel);
    }

    public String decodedSnippet() {
        return ObfuscationCodec.decode(encodedSnippet);
    }

    public String decodedNote() {
        return ObfuscationCodec.decode(encodedNote);
    }

    public String getBookmarkId() {
        return bookmarkId;
    }

    public void setBookmarkId(String bookmarkId) {
        this.bookmarkId = bookmarkId != null ? bookmarkId : "";
    }

    public String getBookId() {
        return bookId;
    }

    public void setBookId(String bookId) {
        this.bookId = bookId != null ? bookId : "";
    }

    public int getUnitIndex() {
        return unitIndex;
    }

    public void setUnitIndex(int unitIndex) {
        this.unitIndex = Math.max(0, unitIndex);
    }

    public double getOffsetRatio() {
        return offsetRatio;
    }

    public void setOffsetRatio(double offsetRatio) {
        if (Double.isNaN(offsetRatio) || offsetRatio < 0.0) {
            this.offsetRatio = 0.0;
        } else {
            this.offsetRatio = Math.min(1.0, offsetRatio);
        }
    }

    public String getEncodedLocationLabel() {
        return encodedLocationLabel;
    }

    public void setEncodedLocationLabel(String encodedLocationLabel) {
        this.encodedLocationLabel = encodedLocationLabel != null ? encodedLocationLabel : "";
    }

    public String getEncodedSnippet() {
        return encodedSnippet;
    }

    public void setEncodedSnippet(String encodedSnippet) {
        this.encodedSnippet = encodedSnippet != null ? encodedSnippet : "";
    }

    public String getEncodedNote() {
        return encodedNote;
    }

    public void setEncodedNote(String encodedNote) {
        this.encodedNote = encodedNote != null ? encodedNote : "";
    }

    public long getCreatedAtEpochMs() {
        return createdAtEpochMs;
    }

    public void setCreatedAtEpochMs(long createdAtEpochMs) {
        this.createdAtEpochMs = createdAtEpochMs;
    }
}
