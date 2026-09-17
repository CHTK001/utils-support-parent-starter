package com.chua.common.support.lang.algorithm.cipher;


/**
* Base64 编码与解码工具类
*
* <p>提供 Base64 编码/解码功能，支持标准 Base64、URL 安全 Base64 和 MIME Base64 三种模式。
* 内部封装 {@link java.util.Base64}，提供更便捷的静态方法。
*
* <h2>使用示例</h2>
* <pre>{@code
* // 标准 Base64 编码
* String encoded = Base64.encode("Hello World");
* byte[] decoded = Base64.decode(encoded);
*
* // URL 安全 Base64
* String urlSafe = Base64.encodeUrlSafe("Hello World");
*
* // 直接操作字节数组
* byte[] raw = Base64.decode(encodedStr);
* }</pre>
*
* @author CH
* @since 2026/07/16
 */
public final class Base64 {

    /** 创建 Base64 实例 */
    private Base64() {
    }

    /**
    * 标准 Base64 编码
    *
    * @param data 待编码的字节数组
    * @return Base64 编码字符串
    */
    public static String encode(byte[] data) {
        return java.util.Base64.getEncoder().encodeToString(data);
    }

    /**
    * 标准 Base64 编码
    *
    * @param data 待编码的字符串
    * @return Base64 编码字符串
    */
    public static String encode(String data) {
        return encode(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
    * 标准 Base64 解码
    *
    * @param data Base64 编码字符串
    * @return 解码后的字节数组
    */
    public static byte[] decode(String data) {
        return java.util.Base64.getDecoder().decode(data);
    }

    /**
    * 标准 Base64 解码为字符串
    *
    * @param data Base64 编码字符串
    * @return 解码后的原始字符串（UTF-8）
    */
    public static String decodeToString(String data) {
        return new String(decode(data), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
    * URL 安全 Base64 编码
    *
    * <p>使用 - 替代 +，_ 替代 /，且不包含填充字符 =。
    *
    * @param data 待编码的字节数组
    * @return URL 安全 Base64 编码字符串
    */
    public static String encodeUrlSafe(byte[] data) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    /**
    * URL 安全 Base64 编码
    *
    * @param data 待编码的字符串
    * @return URL 安全 Base64 编码字符串
    */
    public static String encodeUrlSafe(String data) {
        return encodeUrlSafe(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
    * URL 安全 Base64 解码
    *
    * @param data URL 安全 Base64 编码字符串
    * @return 解码后的字节数组
    */
    public static byte[] decodeUrlSafe(String data) {
        return java.util.Base64.getUrlDecoder().decode(data);
    }

    /**
    * URL 安全 Base64 解码为字符串
    *
    * @param data URL 安全 Base64 编码字符串
    * @return 解码后的原始字符串（UTF-8）
    */
    public static String decodeUrlSafeToString(String data) {
        return new String(decodeUrlSafe(data), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
    * MIME Base64 编码
    *
    * <p>每行最多 76 个字符，使用 \r\n 作为行分隔符。
    *
    * @param data 待编码的字节数组
    * @return MIME Base64 编码字符串
    */
    public static String encodeMime(byte[] data) {
        return java.util.Base64.getMimeEncoder().encodeToString(data);
    }

    /**
    * MIME Base64 解码
    *
    * @param data MIME Base64 编码字符串
    * @return 解码后的字节数组
    */
    public static byte[] decodeMime(String data) {
        return java.util.Base64.getMimeDecoder().decode(data);
    }
}
