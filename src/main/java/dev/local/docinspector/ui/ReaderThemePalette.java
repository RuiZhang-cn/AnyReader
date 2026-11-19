package dev.local.docinspector.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import dev.local.docinspector.model.ReaderSettings;
import dev.local.docinspector.model.ThemePreset;

import java.awt.Color;
import java.util.Locale;

/**
 * 阅读器配色与排版解析中心。
 * 支持 5 套预设（含 {@link ThemePreset#FOLLOW_IDE} 动态读取 IDEA 全局配色与编辑器字体，带无容器安全回退）
 * 以及自定义前/背景色覆盖。
 */
public final class ReaderThemePalette {

    public static final String DEFAULT_IDE_BG = "#2B2D30";
    public static final String DEFAULT_IDE_FG = "#DFE1E5";
    public static final String DEFAULT_IDE_FONT = "JetBrains Mono";
    public static final int DEFAULT_IDE_FONT_SIZE = 14;

    private final ThemePreset preset;
    private final String bgHex;
    private final String fgHex;
    private final String mutedHex;
    private final String borderHex;
    private final String accentHex;
    private final Color bgColor;
    private final Color fgColor;
    private final Color mutedColor;
    private final Color borderColor;
    private final Color accentColor;
    private final String fontFamily;
    private final int fontSize;
    private final double lineHeight;
    private final int horizontalPadding;
    private final boolean dark;

    private ReaderThemePalette(
            ThemePreset preset,
            String bgHex,
            String fgHex,
            String mutedHex,
            String borderHex,
            String accentHex,
            String fontFamily,
            int fontSize,
            double lineHeight,
            int horizontalPadding) {
        this.preset = preset;
        this.bgHex = bgHex;
        this.fgHex = fgHex;
        this.mutedHex = mutedHex;
        this.borderHex = borderHex;
        this.accentHex = accentHex;
        this.bgColor = parseHexColor(bgHex, new Color(0x2B, 0x2D, 0x30));
        this.fgColor = parseHexColor(fgHex, new Color(0xDF, 0xE1, 0xE5));
        this.mutedColor = parseHexColor(mutedHex, new Color(0x86, 0x8A, 0x91));
        this.borderColor = parseHexColor(borderHex, new Color(0x3E, 0x41, 0x47));
        this.accentColor = parseHexColor(accentHex, new Color(0x54, 0x8A, 0xF7));
        this.fontFamily = fontFamily;
        this.fontSize = fontSize;
        this.lineHeight = lineHeight;
        this.horizontalPadding = horizontalPadding;
        this.dark = isDarkColor(this.bgColor);
    }

    public static ReaderThemePalette resolve(ReaderSettings settings) {
        IdeThemeSnapshot ide = readIdeThemeSnapshot();
        return resolve(settings, ide.bgHex(), ide.fgHex(), ide.fontFamily(), ide.fontSize());
    }

    public static ReaderThemePalette resolve(
            ReaderSettings settings,
            String ideBgHex,
            String ideFgHex,
            String ideFontFamily,
            int ideFontSize) {
        ReaderSettings s = settings != null ? settings : new ReaderSettings();
        ThemePreset preset = s.getThemePreset();

        String safeIdeBg = isValidHex(ideBgHex) ? normalizeHex(ideBgHex) : DEFAULT_IDE_BG;
        String safeIdeFg = isValidHex(ideFgHex) ? normalizeHex(ideFgHex) : DEFAULT_IDE_FG;

        String bg;
        String fg;
        String muted;
        String border;
        String accent;

        switch (preset) {
            case CODE_CAMOUFLAGE -> {
                bg = "#1E1F22";
                fg = "#BCBEC4";
                muted = "#7A7E85";
                border = "#393B40";
                accent = "#56A8F5";
            }
            case NIGHT_DARK -> {
                bg = "#181A1F";
                fg = "#CDD3DE";
                muted = "#7B8496";
                border = "#2C313C";
                accent = "#61AFEF";
            }
            case WARM_PARCHMENT -> {
                bg = "#F4ECD8";
                fg = "#3B3124";
                muted = "#7E6E58";
                border = "#DECFAF";
                accent = "#8B5E3C";
            }
            case LIGHT_PAPER -> {
                bg = "#FAFAFA";
                fg = "#24292F";
                muted = "#57606A";
                border = "#D0D7DE";
                accent = "#0969DA";
            }
            case FOLLOW_IDE -> {
                bg = safeIdeBg;
                fg = safeIdeFg;
                boolean darkBg = isDarkHex(bg);
                muted = darkBg ? "#868A91" : "#6E7781";
                border = darkBg ? "#3E4147" : "#D8DEE4";
                accent = darkBg ? "#548AF7" : "#0969DA";
            }
            default -> {
                bg = DEFAULT_IDE_BG;
                fg = DEFAULT_IDE_FG;
                muted = "#868A91";
                border = "#3E4147";
                accent = "#548AF7";
            }
        }

        if (isValidHex(s.getCustomBgHex())) {
            bg = normalizeHex(s.getCustomBgHex());
        }
        if (isValidHex(s.getCustomFgHex())) {
            fg = normalizeHex(s.getCustomFgHex());
        }

        String fontFamily = s.getFontFamily();
        int fontSize = s.getFontSize();
        if (preset == ThemePreset.FOLLOW_IDE && "Microsoft YaHei".equals(fontFamily)
                && ideFontFamily != null && !ideFontFamily.isBlank()) {
            fontFamily = ideFontFamily;
        }
        if (preset == ThemePreset.FOLLOW_IDE && fontSize == 15 && ideFontSize >= 12 && ideFontSize <= 28) {
            fontSize = ideFontSize;
        }

        return new ReaderThemePalette(
                preset,
                bg,
                fg,
                muted,
                border,
                accent,
                fontFamily,
                fontSize,
                s.getLineHeight(),
                s.getHorizontalPadding()
        );
    }

    public static IdeThemeSnapshot readIdeThemeSnapshot() {
        String bg = DEFAULT_IDE_BG;
        String fg = DEFAULT_IDE_FG;
        String font = DEFAULT_IDE_FONT;
        int size = DEFAULT_IDE_FONT_SIZE;
        try {
            if (ApplicationManager.getApplication() != null) {
                EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
                if (scheme != null) {
                    Color cBg = scheme.getDefaultBackground();
                    Color cFg = scheme.getDefaultForeground();
                    if (cBg != null) {
                        bg = toHex(cBg);
                    }
                    if (cFg != null) {
                        fg = toHex(cFg);
                    }
                    String schemeFont = scheme.getEditorFontName();
                    if (schemeFont != null && !schemeFont.isBlank()) {
                        font = schemeFont;
                    }
                    int schemeSize = scheme.getEditorFontSize();
                    if (schemeSize >= 10 && schemeSize <= 32) {
                        size = schemeSize;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new IdeThemeSnapshot(bg, fg, font, size);
    }

    public record IdeThemeSnapshot(String bgHex, String fgHex, String fontFamily, int fontSize) {
    }

    public ThemePreset getPreset() {
        return preset;
    }

    public String getBgHex() {
        return bgHex;
    }

    public String getFgHex() {
        return fgHex;
    }

    public String getMutedHex() {
        return mutedHex;
    }

    public String getBorderHex() {
        return borderHex;
    }

    public String getAccentHex() {
        return accentHex;
    }

    public Color getBgColor() {
        return bgColor;
    }

    public Color getFgColor() {
        return fgColor;
    }

    public Color getMutedColor() {
        return mutedColor;
    }

    public Color getBorderColor() {
        return borderColor;
    }

    public Color getAccentColor() {
        return accentColor;
    }

    public String getFontFamily() {
        return fontFamily;
    }

    public int getFontSize() {
        return fontSize;
    }

    public double getLineHeight() {
        return lineHeight;
    }

    public int getHorizontalPadding() {
        return horizontalPadding;
    }

    public boolean isDark() {
        return dark;
    }

    public static String toHex(Color c) {
        if (c == null) {
            return "#000000";
        }
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    public static boolean isValidHex(String hex) {
        if (hex == null) {
            return false;
        }
        String trimmed = hex.trim();
        return trimmed.matches("^#?[0-9a-fA-F]{6}$");
    }

    public static String normalizeHex(String hex) {
        String trimmed = hex.trim().toUpperCase(Locale.ROOT);
        return trimmed.startsWith("#") ? trimmed : ("#" + trimmed);
    }

    public static Color parseHexColor(String hex, Color fallback) {
        if (!isValidHex(hex)) {
            return fallback;
        }
        try {
            return Color.decode(normalizeHex(hex));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static boolean isDarkHex(String hex) {
        return isDarkColor(parseHexColor(hex, new Color(0x2B, 0x2D, 0x30)));
    }

    public static boolean isDarkColor(Color color) {
        if (color == null) {
            return true;
        }
        double luminance = (0.299 * color.getRed() + 0.587 * color.getGreen() + 0.114 * color.getBlue()) / 255.0;
        return luminance < 0.5;
    }
}
