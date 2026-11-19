package dev.local.docinspector.model;

/**
 * 阅读器全局设置，支持 IntelliJ XmlSerializer 序列化。
 */
public class ReaderSettings {

    private ThemePreset themePreset = ThemePreset.FOLLOW_IDE;
    private String customBgHex = "";
    private String customFgHex = "";
    private String fontFamily = "Microsoft YaHei";
    private int fontSize = 15;
    private double lineHeight = 1.7;
    private int horizontalPadding = 22;
    private double darkImageBrightness = 0.78;
    private ImagePrivacyMode imagePrivacyMode = ImagePrivacyMode.MUTED_GRAYSCALE;
    private PdfColorFilter pdfColorFilter = PdfColorFilter.ORIGINAL;
    private float pdfOpacity = 1.0f;
    private int autoScrollPxPerSec = 0;
    private int autoMaskIdleSeconds = 0;
    private boolean microBarEnabled = false;

    public ReaderSettings() {
    }

    public ThemePreset getThemePreset() {
        return themePreset;
    }

    public void setThemePreset(ThemePreset themePreset) {
        this.themePreset = themePreset != null ? themePreset : ThemePreset.FOLLOW_IDE;
    }

    public String getCustomBgHex() {
        return customBgHex;
    }

    public void setCustomBgHex(String customBgHex) {
        this.customBgHex = customBgHex != null ? customBgHex.trim() : "";
    }

    public String getCustomFgHex() {
        return customFgHex;
    }

    public void setCustomFgHex(String customFgHex) {
        this.customFgHex = customFgHex != null ? customFgHex.trim() : "";
    }

    public String getFontFamily() {
        return fontFamily;
    }

    public void setFontFamily(String fontFamily) {
        this.fontFamily = (fontFamily == null || fontFamily.isBlank()) ? "Microsoft YaHei" : fontFamily;
    }

    public int getFontSize() {
        return fontSize;
    }

    public void setFontSize(int fontSize) {
        this.fontSize = Math.max(12, Math.min(28, fontSize));
    }

    public double getLineHeight() {
        return lineHeight;
    }

    public void setLineHeight(double lineHeight) {
        if (Double.isNaN(lineHeight)) {
            this.lineHeight = 1.7;
        } else {
            this.lineHeight = Math.max(1.2, Math.min(2.6, lineHeight));
        }
    }

    public int getHorizontalPadding() {
        return horizontalPadding;
    }

    public void setHorizontalPadding(int horizontalPadding) {
        this.horizontalPadding = Math.max(8, Math.min(80, horizontalPadding));
    }

    public double getDarkImageBrightness() {
        return darkImageBrightness;
    }

    public void setDarkImageBrightness(double darkImageBrightness) {
        if (Double.isNaN(darkImageBrightness)) {
            this.darkImageBrightness = 0.78;
        } else {
            this.darkImageBrightness = Math.max(0.2, Math.min(1.0, darkImageBrightness));
        }
    }

    public ImagePrivacyMode getImagePrivacyMode() {
        return imagePrivacyMode;
    }

    public void setImagePrivacyMode(ImagePrivacyMode imagePrivacyMode) {
        this.imagePrivacyMode = imagePrivacyMode != null ? imagePrivacyMode : ImagePrivacyMode.MUTED_GRAYSCALE;
    }

    public PdfColorFilter getPdfColorFilter() {
        return pdfColorFilter;
    }

    public void setPdfColorFilter(PdfColorFilter pdfColorFilter) {
        this.pdfColorFilter = pdfColorFilter != null ? pdfColorFilter : PdfColorFilter.ORIGINAL;
    }

    public float getPdfOpacity() {
        return pdfOpacity;
    }

    public void setPdfOpacity(float pdfOpacity) {
        if (Float.isNaN(pdfOpacity)) {
            this.pdfOpacity = 1.0f;
        } else {
            this.pdfOpacity = Math.max(0.15f, Math.min(1.0f, pdfOpacity));
        }
    }

    public int getAutoScrollPxPerSec() {
        return autoScrollPxPerSec;
    }

    public void setAutoScrollPxPerSec(int autoScrollPxPerSec) {
        this.autoScrollPxPerSec = Math.max(0, Math.min(120, autoScrollPxPerSec));
    }

    public int getAutoMaskIdleSeconds() {
        return autoMaskIdleSeconds;
    }

    public void setAutoMaskIdleSeconds(int autoMaskIdleSeconds) {
        this.autoMaskIdleSeconds = Math.max(0, Math.min(600, autoMaskIdleSeconds));
    }

    public boolean isMicroBarEnabled() {
        return microBarEnabled;
    }

    public void setMicroBarEnabled(boolean microBarEnabled) {
        this.microBarEnabled = microBarEnabled;
    }
}
