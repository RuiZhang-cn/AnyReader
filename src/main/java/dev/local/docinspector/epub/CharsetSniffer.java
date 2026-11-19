package dev.local.docinspector.epub;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 字符集嗅探器：BOM -> XML/HTML 声明头 -> 严格 UTF-8 校验 -> GB18030 自动回退。
 */
public final class CharsetSniffer {

    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final int HEADER_SCAN_LIMIT = 1024;
    private static final Pattern ENCODING_PATTERN =
            Pattern.compile("encoding\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHARSET_PATTERN =
            Pattern.compile("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);

    private CharsetSniffer() {
    }

    /**
     * 探测原始字节数组最匹配的字符集。
     */
    public static Charset detect(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return StandardCharsets.UTF_8;
        }

        // 1. BOM 优先
        if (hasUtf8Bom(raw)) {
            return StandardCharsets.UTF_8;
        }
        if (hasUtf16BeBom(raw)) {
            return StandardCharsets.UTF_16BE;
        }
        if (hasUtf16LeBom(raw)) {
            return StandardCharsets.UTF_16LE;
        }

        // 2. 扫描前 1024 字节 ASCII 头部的 encoding / charset 声明
        int headerLen = Math.min(raw.length, HEADER_SCAN_LIMIT);
        String header = new String(raw, 0, headerLen, StandardCharsets.ISO_8859_1);
        String declared = extractDeclaredCharset(header);
        if (declared != null && !declared.isBlank()) {
            String normalized = declared.trim().toUpperCase(Locale.ROOT);
            if ("GBK".equals(normalized) || "GB2312".equals(normalized) || "GB18030".equals(normalized)
                    || "X-GBK".equals(normalized)) {
                return GB18030;
            }
            if ("UTF-8".equals(normalized) || "UTF8".equals(normalized)) {
                return isValidUtf8(raw) ? StandardCharsets.UTF_8 : GB18030;
            }
            try {
                if (Charset.isSupported(declared)) {
                    return Charset.forName(declared);
                }
            } catch (IllegalArgumentException ignored) {
                // 非法编码名继续走回退探测
            }
        }

        // 3. 严格 UTF-8 校验
        if (isValidUtf8(raw)) {
            return StandardCharsets.UTF_8;
        }

        // 4. 自动回退 GB18030
        return GB18030;
    }

    /**
     * 使用探测到的字符集解码字节数组，并自动剥离 BOM。
     */
    public static String decode(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        Charset charset = detect(raw);
        int offset = 0;
        if (StandardCharsets.UTF_8.equals(charset) && hasUtf8Bom(raw)) {
            offset = 3;
        } else if (StandardCharsets.UTF_16BE.equals(charset) && hasUtf16BeBom(raw)) {
            offset = 2;
        } else if (StandardCharsets.UTF_16LE.equals(charset) && hasUtf16LeBom(raw)) {
            offset = 2;
        }
        return new String(raw, offset, raw.length - offset, charset);
    }

    public static boolean isValidUtf8(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return true;
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(raw));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static String extractDeclaredCharset(String header) {
        Matcher encMatcher = ENCODING_PATTERN.matcher(header);
        if (encMatcher.find()) {
            return encMatcher.group(1);
        }
        Matcher csMatcher = CHARSET_PATTERN.matcher(header);
        if (csMatcher.find()) {
            return csMatcher.group(1);
        }
        return null;
    }

    private static boolean hasUtf8Bom(byte[] raw) {
        return raw.length >= 3
                && (raw[0] & 0xFF) == 0xEF
                && (raw[1] & 0xFF) == 0xBB
                && (raw[2] & 0xFF) == 0xBF;
    }

    private static boolean hasUtf16BeBom(byte[] raw) {
        return raw.length >= 2
                && (raw[0] & 0xFF) == 0xFE
                && (raw[1] & 0xFF) == 0xFF;
    }

    private static boolean hasUtf16LeBom(byte[] raw) {
        return raw.length >= 2
                && (raw[0] & 0xFF) == 0xFF
                && (raw[1] & 0xFF) == 0xFE;
    }
}
