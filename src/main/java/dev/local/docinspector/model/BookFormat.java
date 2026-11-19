package dev.local.docinspector.model;

import java.io.File;
import java.util.Locale;

/**
 * 支持的本地文档与图片集格式。
 */
public enum BookFormat {
    EPUB,
    PDF,
    TXT,
    MARKDOWN,
    IMAGE_DIR;

    public static BookFormat fromFile(File file) {
        if (file == null) {
            return null;
        }
        if (file.isDirectory()) {
            return IMAGE_DIR;
        }
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".epub")) {
            return EPUB;
        }
        if (name.endsWith(".pdf")) {
            return PDF;
        }
        if (name.endsWith(".md") || name.endsWith(".markdown")) {
            return MARKDOWN;
        }
        if (name.endsWith(".txt") || name.endsWith(".text") || name.endsWith(".log")) {
            return TXT;
        }
        if (name.endsWith(".png")
                || name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".gif")
                || name.endsWith(".webp")
                || name.endsWith(".bmp")) {
            return IMAGE_DIR;
        }
        return null;
    }
}
