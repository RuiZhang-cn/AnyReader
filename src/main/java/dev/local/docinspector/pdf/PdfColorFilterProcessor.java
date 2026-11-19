package dev.local.docinspector.pdf;

import dev.local.docinspector.model.PdfColorFilter;

import java.awt.image.BufferedImage;

/**
 * PDF 与图片集护眼滤镜像素处理器。
 */
public final class PdfColorFilterProcessor {

    private PdfColorFilterProcessor() {
    }

    /**
     * 对输入图像应用指定的 {@link PdfColorFilter} 像素变换。
     * {@link PdfColorFilter#ORIGINAL} 直接返回原图，不做额外拷贝。
     */
    public static BufferedImage apply(BufferedImage src, PdfColorFilter filter) {
        if (src == null || filter == null || filter == PdfColorFilter.ORIGINAL) {
            return src;
        }
        int width = src.getWidth();
        int height = src.getHeight();
        if (width <= 0 || height <= 0) {
            return src;
        }

        boolean hasAlpha = src.getColorModel().hasAlpha();
        int targetType = hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage dst = new BufferedImage(width, height, targetType);

        int[] pixels = src.getRGB(0, 0, width, height, null, 0, width);
        for (int i = 0; i < pixels.length; i++) {
            int argb = pixels[i];
            int a = (argb >>> 24) & 0xFF;
            int r = (argb >>> 16) & 0xFF;
            int g = (argb >>> 8) & 0xFF;
            int b = argb & 0xFF;

            int outR;
            int outG;
            int outB;
            switch (filter) {
                case DARK_INVERT -> {
                    int invR = 255 - r;
                    int invG = 255 - g;
                    int invB = 255 - b;
                    // 映射至柔和深灰底 #1E1F22 (30,31,34) ~ 浅灰字 #CDD3DE (205,211,222)
                    outR = 30 + (invR * (205 - 30)) / 255;
                    outG = 31 + (invG * (211 - 31)) / 255;
                    outB = 34 + (invB * (222 - 34)) / 255;
                }
                case WARM_SEPIA -> {
                    // 映射至温和护眼羊皮纸暖色调：底 #F4ECD8 (244,236,216)，字 #3B3124 (59,49,36)
                    outR = 59 + (r * (244 - 59)) / 255;
                    outG = 49 + (g * (236 - 49)) / 255;
                    outB = 36 + (b * (216 - 36)) / 255;
                }
                case GRAYSCALE_STEALTH -> {
                    int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                    int dimmed = 45 + (lum * 150) / 255;
                    outR = dimmed;
                    outG = dimmed;
                    outB = dimmed;
                }
                default -> {
                    outR = r;
                    outG = g;
                    outB = b;
                }
            }

            pixels[i] = (hasAlpha ? (a << 24) : 0xFF000000)
                    | (outR << 16)
                    | (outG << 8)
                    | outB;
        }
        dst.setRGB(0, 0, width, height, pixels, 0, width);
        return dst;
    }
}
