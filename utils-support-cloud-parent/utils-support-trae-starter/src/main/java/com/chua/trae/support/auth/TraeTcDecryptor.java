package com.chua.trae.support.auth;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Trae CN 版 "tc" 加密格式解密器。
 *
 * <p>算法（逆向自 Trae CN IDE，版本 3.3.67）：
 * <ol>
 *   <li>Base64 解码后结构：[6B Header][32B RandomBytes][AES-CBC 密文]</li>
 *   <li>Header "tc"(0x74 0x63) 表示标准 AES 类型，用 SALT_A XOR SALT_B 派生密钥</li>
 *   <li>key/IV 派生：SHA-512(RandomBytes) → step1；SHA-512(step1 || salt) → step2；key=step2[0:16]，iv=step2[16:32]</li>
 *   <li>AES-128-CBC/PKCS5 解密</li>
 *   <li>解密后结构：[64B SHA-512 哈希][明文 JSON]，需验证哈希</li>
 * </ol>
 *
 * <p>纯 JDK JCE 实现，无 native 依赖。
 *
 * @see <a href="https://github.com/ZedeX/trae-local-api/blob/main/CRACK_TUTORIAL.md">TC 解密逆向文档</a>
 * @author CH
 * @since 4.0.0.42
 */
public final class TraeTcDecryptor {

    /** AES 类型头（"tc" 的字节序列） */
    private static final byte[] HEADER_TC = {(byte) 0x74, 0x63, 0x05, 0x10, 0x00, 0x00};
    /** AES_私募 类型头 */
    private static final byte[] HEADER_PRIVATE = {0x12, 0x39, 0x20, 0x20, 0x02, 0x03};

    /** 标准 AES 盐值 A，64 字节，直接来自 Trae CN JS 包硬编码 */
    private static final int[] SALT_A = {82,9,106,213,48,54,165,56,191,64,163,158,129,243,215,251,
        124,227,57,130,155,47,255,135,52,142,67,68,196,222,233,203,84,123,148,50,166,194,35,61,238,
        76,149,11,66,250,195,78,8,46,161,102,40,217,36,178,118,91,162,73,109,139,209,37};
    /** 标准 AES 盐值 B，64 字节 */
    private static final int[] SALT_B = {31,221,168,51,136,7,199,49,177,18,16,89,39,128,236,95,
        96,81,127,169,25,181,74,13,45,229,122,159,147,201,156,239,160,224,59,77,174,42,245,176,
        200,235,187,60,131,83,153,97,23,43,4,126,186,119,214,38,225,105,20,99,85,33,12,125};
    /** AES_私募 盐值 C，64 字节 */
    private static final int[] SALT_C = {191,192,216,250,122,246,220,97,31,254,98,27,8,72,71,176,
        135,99,96,18,127,101,203,104,211,102,191,125,37,72,150,156,51,229,121,35,17,153,141,177,110,
        131,150,128,172,255,254,6,18,140,55,62,236,249,135,64,135,12,117,4,89,149,168,209};
    /** AES_私募 盐值 D，64 字节 */
    private static final int[] SALT_D = {246,204,26,232,232,70,129,109,223,146,169,242,23,241,105,145,
        50,196,165,42,254,120,3,54,244,207,209,85,53,6,138,106,175,148,31,204,186,186,165,182,87,
        142,49,10,39,110,26,154,86,56,173,125,18,64,198,225,99,99,83,82,191,134,76,170};

    /** 随机字节长度 */
    private static final int RANDOM_LEN = 32;
    /** 哈希长度 */
    private static final int HASH_LEN = 64;

    /**
     * 私有构造，防止实例化。
     */
    private TraeTcDecryptor() {
        throw new UnsupportedOperationException("TraeTcDecryptor is a static utility");
    }

    /**
     * 判断字符串是否为 tc 加密格式（基础64 解码后首 2 字节为 "tc"）。
     *
     * @param value 基础64 字符串或明文，不可为 空
     * @return true 表示是 tc 加密串，需解密
     */
    public static boolean isTcEncrypted(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("\"")) {
            return false;
        }
        try {
            byte[] buf = Base64.getDecoder().decode(trimmed);
            if (buf.length < 6) {
                return false;
            }
            return (buf[0] & 0xFF) == 0x74 && (buf[1] & 0xFF) == 0x63
                || (buf[0] & 0xFF) == 0x12 && (buf[1] & 0xFF) == 0x39;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 解密 tc 加密串，返回明文 JSON。
     *
     * @param base64Value 基础64 编码的 tc 加密串，不可为 空
     * @return 解密后的明文 JSON 字符串
     * @throws TcDecryptException 当解密失败或哈希校验不通过时
     */
    public static String decrypt(String base64Value) {
        if (base64Value == null || base64Value.isEmpty()) {
            throw new TcDecryptException("base64Value must not be empty");
        }
        try {
            byte[] buf = Base64.getDecoder().decode(base64Value.trim());
            if (buf.length < 6 + RANDOM_LEN + HASH_LEN) {
                throw new TcDecryptException("Buffer too short: " + buf.length);
            }

            // 识别类型，选盐值对
            boolean isPrivate = (buf[0] & 0xFF) == 0x12 && (buf[1] & 0xFF) == 0x39;
            int[] saltX = isPrivate ? SALT_C : SALT_A;
            int[] saltY = isPrivate ? SALT_D : SALT_B;
            byte[] salt = xor(toBytes(saltX), toBytes(saltY));

            byte[] random = Arrays.copyOfRange(buf, 6, 6 + RANDOM_LEN);
            byte[] cipher = Arrays.copyOfRange(buf, 6 + RANDOM_LEN, buf.length);

            // 密钥派生：SHA-512(random) → step1；SHA-512(step1||salt) → step2
            byte[] step1 = sha512(random);
            byte[] step2 = sha512(concat(step1, salt));
            byte[] aesKey = Arrays.copyOfRange(step2, 0, 16);
            byte[] iv = Arrays.copyOfRange(step2, 16, 32);

            // AES-128-CBC/PKCS5 解密
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
            byte[] decrypted = c.doFinal(cipher);

            // 完整性校验：前 64 字节为 SHA-512(明文)，与后段计算值比对
            byte[] storedHash = Arrays.copyOfRange(decrypted, 0, HASH_LEN);
            byte[] plain = Arrays.copyOfRange(decrypted, HASH_LEN, decrypted.length);
            byte[] computed = sha512(plain);
            if (!Arrays.equals(storedHash, computed)) {
                throw new TcDecryptException("SHA-512 hash verification failed");
            }
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (TcDecryptException e) {
            throw e;
        } catch (Exception e) {
            throw new TcDecryptException("Decrypt failed: " + e.getMessage(), e);
        }
    }

    /**
     * 判断是否为 tc 加密格式的 tc 解密异常。
     * @author CH
     * @since 4.0.0
     */
    public static class TcDecryptException extends RuntimeException {
        /**
         * 创建异常。
         *
         * @param message 消息
         */
        public TcDecryptException(String message) { super(message); }

        /**
         * 创建带原因的异常。
         *
         * @param message 消息
         * @param cause 原因
         */
        public TcDecryptException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * 将 int[] 转 byte[]（低 8 位）。
     *
     * @param arr 整数数组
     * @return 字节数组
     */
    private static byte[] toBytes(int[] arr) {
        byte[] out = new byte[arr.length];
        for (int i = 0; i < arr.length; i++) {
            out[i] = (byte) (arr[i] & 0xFF);
        }
        return out;
    }

    /**
     * 异或两个等长字节数组。
     *
     * @param a 数组 A
     * @param b 数组 B
     * @return 异或结果
     */
    private static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }

    /**
     * 拼接两个字节数组。
     *
     * @param a 前段
     * @param b 后段
     * @return 拼接结果
     */
    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /**
     * 计算 SHA-512 摘要。
     *
     * @param input 输入字节
     * @return 64 字节摘要
     */
    private static byte[] sha512(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-512").digest(input);
        } catch (Exception e) {
            throw new TcDecryptException("SHA-512 unavailable: " + e.getMessage(), e);
        }
    }
}
