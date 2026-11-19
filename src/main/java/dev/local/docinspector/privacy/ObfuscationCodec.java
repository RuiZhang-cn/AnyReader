package dev.local.docinspector.privacy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 磁盘持久化混淆编解码器。
 * 使用固定应用级盐密钥流逐字节 XOR 后进行无填充 URL-Safe Base64 编码，并加 {@code v1:} 前缀。
 */
public final class ObfuscationCodec {

    private static final String PREFIX_V1 = "v1:";
    private static final byte[] KEY_STREAM = new byte[] {
            (byte) 0x5A, (byte) 0xC3, (byte) 0x7E, (byte) 0x19,
            (byte) 0xA4, (byte) 0x3D, (byte) 0xF1, (byte) 0x62,
            (byte) 0x8B, (byte) 0x0E, (byte) 0x47, (byte) 0xD5,
            (byte) 0x29, (byte) 0x91, (byte) 0x6C, (byte) 0xE8,
            (byte) 0x33, (byte) 0x7A, (byte) 0xB6, (byte) 0x55,
            (byte) 0xC9, (byte) 0x12, (byte) 0x84, (byte) 0xFD,
            (byte) 0x41, (byte) 0xA8, (byte) 0x67, (byte) 0x2E,
            (byte) 0x9F, (byte) 0x05, (byte) 0xD1, (byte) 0x3B
    };

    private ObfuscationCodec() {
    }

    public static String encode(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        byte[] raw = plain.getBytes(StandardCharsets.UTF_8);
        byte[] xored = xor(raw);
        return PREFIX_V1 + Base64.getUrlEncoder().withoutPadding().encodeToString(xored);
    }

    public static String decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return "";
        }
        if (!encoded.startsWith(PREFIX_V1)) {
            return encoded;
        }
        try {
            String payload = encoded.substring(PREFIX_V1.length());
            if (payload.isEmpty()) {
                return "";
            }
            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            byte[] plain = xor(decoded);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static byte[] xor(byte[] input) {
        byte[] out = new byte[input.length];
        for (int i = 0; i < input.length; i++) {
            int key = KEY_STREAM[i % KEY_STREAM.length] ^ ((i * 31) & 0xFF);
            out[i] = (byte) (input[i] ^ key);
        }
        return out;
    }
}
