package com.chua.common.support.lang.algorithm.hmac;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;


/**
 * HMAC (Hash-based Message Authentication Code) 工具类
 * <p>
 * 提供基于多种哈希算法（MD5, SHA-1, SHA-256, SHA-512）的HMAC消息认证码生成与验证功能。
 * 支持字符串、字节数组及文件/输入流作为数据源，并支持Hex和Base64编码输出。
 * </p>
 *
 * @author CH
 * @since 2025/10/23
 */
public class HMacUtils {

    private HMacUtils() {
        // 私有构造函数，防止实例化，该类仅提供静态工具方法
    }

    // ==================== HmacMD5 ====================

    /**
     * 使用HmacMD5算法计算数据的摘要，并以十六进制字符串形式返回。
     *
     * @param key  密钥字符串
     * @param data 待加密的数据字符串
     * @return 十六进制格式的HMAC-MD5摘要字符串
     */
    public static String hmacMd5Hex(String key, String data) {
        return HMac.hmacMd5(key).digestHex(data);
    }

    /**
     * 使用HmacMD5算法计算数据的摘要，并以Base64编码形式返回。
     *
     * @param key  密钥字符串
     * @param data 待加密的数据字符串
     * @return Base64编码的HMAC-MD5摘要字符串
     */
    public static String hmacMd5Base64(String key, String data) {
        return HMac.hmacMd5(key).digestBase64(data);
    }

    /**
     * 使用HmacMD5算法计算数据的摘要，以原始字节数组形式返回。
     *
     * @param key  密钥字节数组
     * @param data 待加密的数据字节数组
     * @return HMAC-MD5摘要的原始字节数组
     */
    public static byte[] hmacMd5(byte[] key, byte[] data) {
        return HMac.hmacMd5(key).digest(data);
    }

    // ==================== HmacSHA1 ====================

    /**
     * 使用HmacSHA1算法计算数据的摘要，并以十六进制字符串形式返回。
     *
     * @param key  密钥字符串
     * @param data 待加密的数据字符串
     * @return 十六进制格式的HMAC-SHA1摘要字符串
     */
    public static String hmacSha1Hex(String key, String data) {
        return HMac.hmacSha1(key).digestHex(data);
    }

    /**
     * 使用HmacSHA1算法计算数据的摘要，并以Base64编码形式返回。
     *
     * @param key  密钥字符串
     * @param data 待加密的数据字符串
     * @return Base64编码的HMAC-SHA1摘要字符串
     */
    public static String hmacSha1Base64(String key, String data) {
        return HMac.hmacSha1(key).digestBase64(data);
    }

    /**
     * 使用HmacSHA1算法计算数据的摘要，以原始字节数组形式返回。
     *
     * @param key  密钥字节数组
     * @param data 待加密的数据字节数组
     * @return HMAC-SHA1摘要的原始字节数组
     */
    public static byte[] hmacSha1(byte[] key, byte[] data) {
        return HMac.hmacSha1(key).digest(data);
    }

    // ==================== HmacSHA256 ====================

    /**
     * 使用HmacSHA256算法计算数据的摘要，并以十六进制字符串形式返回。
     *
     * @param key  密钥字符串
     * @param data 待加密的数据字符串
     * @return 十六进制格式的HMAC-SHA256摘要字符串
     */
    public static String hmacSha256Hex(String key, String data) {
        return HMac.hmacSha256(key).digestHex(data);
    }

    /**
     * 使用HmacSHA256算法计算数据的摘要，并以Base64编码形式返回。
     *
     * @param key  密钥字符串
     * @param data 待加密的数据字符串
     * @return Base64编码的HMAC-SHA256摘要字符串
     */
    public static String hmacSha256Base64(String key, String data) {
        return HMac.hmacSha256(key).digestBase64(data);
    }

    /**
     * 使用HmacSHA256算法计算数据的摘要，以原始字节数组形式返回。
     *
     * @param key  密钥字节数组
     * @param data 待加密的数据字节数组
     * @return HMAC-SHA256摘要的原始字节数组
     */
    public static byte[] hmacSha256(byte[] key, byte[] data) {
        return HMac.hmacSha256(key).digest(data);
    }

    // ==================== HmacSHA512 ====================

    /**
     * 使用HmacSHA512算法计算数据的摘要，并以十六进制字符串形式返回。
     *
     * @param key  密钥字符串
     * @param data 待加密的数据字符串
     * @return 十六进制格式的HMAC-SHA512摘要字符串
     */
    public static String hmacSha512Hex(String key, String data) {
        return HMac.hmacSha512(key).digestHex(data);
    }

    /**
     * 使用HmacSHA512算法计算数据的摘要，并以Base64编码形式返回。
     *
     * @param key  密钥字符串
     * @param data 待加密的数据字符串
     * @return Base64编码的HMAC-SHA512摘要字符串
     */
    public static String hmacSha512Base64(String key, String data) {
        return HMac.hmacSha512(key).digestBase64(data);
    }

    /**
     * 使用HmacSHA512算法计算数据的摘要，以原始字节数组形式返回。
     *
     * @param key  密钥字节数组
     * @param data 待加密的数据字节数组
     * @return HMAC-SHA512摘要的原始字节数组
     */
    public static byte[] hmacSha512(byte[] key, byte[] data) {
        return HMac.hmacSha512(key).digest(data);
    }

    // ==================== 通用HMAC处理 ====================

    /**
     * 根据指定的算法计算数据的HMAC摘要，并以十六进制字符串形式返回。
     *
     * @param algorithm HMAC使用的算法枚举
     * @param key       密钥字符串
     * @param data      待加密的数据字符串
     * @return 十六进制格式的HMAC摘要字符串
     */
    public static String digestHex(HmacAlgorithm algorithm, String key, String data) {
        return new HMac(algorithm, key).digestHex(data);
    }

    /**
     * 根据指定的算法计算数据的HMAC摘要，并以Base64编码形式返回。
     *
     * @param algorithm HMAC使用的算法枚举
     * @param key       密钥字符串
     * @param data      待加密的数据字符串
     * @return Base64编码的HMAC摘要字符串
     */
    public static String digestBase64(HmacAlgorithm algorithm, String key, String data) {
        return new HMac(algorithm, key).digestBase64(data);
    }

    /**
     * 根据指定的算法计算数据的HMAC摘要，以原始字节数组形式返回。
     *
     * @param algorithm HMAC使用的算法枚举
     * @param key       密钥字节数组
     * @param data      待加密的数据字节数组
     * @return HMAC摘要的原始字节数组
     */
    public static byte[] digest(HmacAlgorithm algorithm, byte[] key, byte[] data) {
        return new HMac(algorithm, key).digest(data);
    }

    /**
     * 根据指定的算法读取文件内容并计算HMAC摘要，以十六进制字符串形式返回。
     *
     * @param algorithm HMAC使用的算法枚举
     * @param key       密钥字符串
     * @param file      待计算的文件对象
     * @return 十六进制格式的HMAC摘要字符串
     * @throws IOException 当发生IO异常时抛出
     */
    public static String digestHex(HmacAlgorithm algorithm, String key, File file) throws IOException {
        return new HMac(algorithm, key).digestHex(file);
    }

    /**
     * 根据指定的算法读取输入流内容并计算HMAC摘要，以十六进制字符串形式返回。
     *
     * @param algorithm HMAC使用的算法枚举
     * @param key       密钥字符串
     * @param in        待计算的输入流
     * @return 十六进制格式的HMAC摘要字符串
     * @throws IOException 当发生IO异常时抛出
     */
    public static String digestHex(HmacAlgorithm algorithm, String key, InputStream in) throws IOException {
        return new HMac(algorithm, key).digestHex(in);
    }

    /**
     * 验证指定数据在给定密钥下的HMAC摘要是否与预期的十六进制字符串匹配。
     *
     * @param algorithm   HMAC使用的算法枚举
     * @param key         密钥字符串
     * @param data        待验证的数据字符串
     * @param expectedHex 预期的十六进制摘要字符串
     * @return 如果摘要匹配返回true，否则返回false
     */
    public static boolean verify(HmacAlgorithm algorithm, String key, String data, String expectedHex) {
        return new HMac(algorithm, key).verify(data, expectedHex);
    }

    /**
     * 验证指定数据在给定密钥下的HMAC摘要是否与预期的字节数组匹配。
     *
     * @param algorithm      HMAC使用的算法枚举
     * @param key            密钥字节数组
     * @param data           待验证的数据字节数组
     * @param expectedDigest 预期的摘要字节数组
     * @return 如果摘要匹配返回true，否则返回false
     */
    public static boolean verify(HmacAlgorithm algorithm, byte[] key, byte[] data, byte[] expectedDigest) {
        return new HMac(algorithm, key).verify(data, expectedDigest);
    }
}

