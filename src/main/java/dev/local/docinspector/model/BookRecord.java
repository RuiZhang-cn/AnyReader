package dev.local.docinspector.model;

import dev.local.docinspector.privacy.ObfuscationCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 书架书籍持久化记录。敏感字段（路径、书名、别名）均以 {@link ObfuscationCodec} 编码存储。
 */
public class BookRecord {

    private String bookId = "";
    private String encodedPath = "";
    private String encodedTitle = "";
    private String encodedAlias = "";
    private String groupTag = "Default";
    private boolean pinned = false;
    private BookFormat format = BookFormat.TXT;
    private int totalUnits = 0;
    private int lastUnitIndex = 0;
    private double lastOffsetRatio = 0.0;
    private double overallProgress = 0.0;
    private float pdfZoom = 1.0f;
    private boolean pdfFitWidth = true;
    private int pdfRotation = 0;
    private long lastReadEpochMs = 0L;
    private long addedEpochMs = 0L;

    public BookRecord() {
    }

    public static String computeBookId(String normalizedPath) {
        if (normalizedPath == null) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalizedPath.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i] & 0xFF));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public String displayTitle() {
        String alias = decodedAlias();
        if (!alias.isBlank()) {
            return alias;
        }
        return decodedTitle();
    }

    public String decodedPath() {
        return ObfuscationCodec.decode(encodedPath);
    }

    public String decodedTitle() {
        return ObfuscationCodec.decode(encodedTitle);
    }

    public String decodedAlias() {
        return ObfuscationCodec.decode(encodedAlias);
    }

    public void updateEncodedPath(String newPath) {
        this.encodedPath = ObfuscationCodec.encode(newPath);
    }

    public String getBookId() {
        return bookId;
    }

    public void setBookId(String bookId) {
        this.bookId = bookId != null ? bookId : "";
    }

    public String getEncodedPath() {
        return encodedPath;
    }

    public void setEncodedPath(String encodedPath) {
        this.encodedPath = encodedPath != null ? encodedPath : "";
    }

    public String getEncodedTitle() {
        return encodedTitle;
    }

    public void setEncodedTitle(String encodedTitle) {
        this.encodedTitle = encodedTitle != null ? encodedTitle : "";
    }

    public String getEncodedAlias() {
        return encodedAlias;
    }

    public void setEncodedAlias(String encodedAlias) {
        this.encodedAlias = encodedAlias != null ? encodedAlias : "";
    }

    public String getGroupTag() {
        return groupTag;
    }

    public void setGroupTag(String groupTag) {
        this.groupTag = (groupTag == null || groupTag.isBlank()) ? "Default" : groupTag.trim();
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public BookFormat getFormat() {
        return format;
    }

    public void setFormat(BookFormat format) {
        this.format = format != null ? format : BookFormat.TXT;
    }

    public int getTotalUnits() {
        return totalUnits;
    }

    public void setTotalUnits(int totalUnits) {
        this.totalUnits = Math.max(0, totalUnits);
    }

    public int getLastUnitIndex() {
        return lastUnitIndex;
    }

    public void setLastUnitIndex(int lastUnitIndex) {
        this.lastUnitIndex = Math.max(0, lastUnitIndex);
    }

    public double getLastOffsetRatio() {
        return lastOffsetRatio;
    }

    public void setLastOffsetRatio(double lastOffsetRatio) {
        this.lastOffsetRatio = clampRatio(lastOffsetRatio);
    }

    public double getOverallProgress() {
        return overallProgress;
    }

    public void setOverallProgress(double overallProgress) {
        this.overallProgress = clampRatio(overallProgress);
    }

    public float getPdfZoom() {
        return pdfZoom;
    }

    public void setPdfZoom(float pdfZoom) {
        this.pdfZoom = pdfZoom <= 0f ? 1.0f : Math.max(0.25f, Math.min(4.0f, pdfZoom));
    }

    public boolean isPdfFitWidth() {
        return pdfFitWidth;
    }

    public void setPdfFitWidth(boolean pdfFitWidth) {
        this.pdfFitWidth = pdfFitWidth;
    }

    public int getPdfRotation() {
        return pdfRotation;
    }

    public void setPdfRotation(int pdfRotation) {
        int normalized = ((pdfRotation % 360) + 360) % 360;
        if (normalized != 0 && normalized != 90 && normalized != 180 && normalized != 270) {
            normalized = 0;
        }
        this.pdfRotation = normalized;
    }

    public long getLastReadEpochMs() {
        return lastReadEpochMs;
    }

    public void setLastReadEpochMs(long lastReadEpochMs) {
        this.lastReadEpochMs = lastReadEpochMs;
    }

    public long getAddedEpochMs() {
        return addedEpochMs;
    }

    public void setAddedEpochMs(long addedEpochMs) {
        this.addedEpochMs = addedEpochMs;
    }

    private static double clampRatio(double val) {
        if (Double.isNaN(val) || val < 0.0) {
            return 0.0;
        }
        return Math.min(1.0, val);
    }
}
