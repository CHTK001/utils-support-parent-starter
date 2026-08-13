package com.chua.common.support.utils;

import com.chua.common.support.collection.ConcurrentReferenceHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static com.chua.common.support.constant.CharsetConstant.UTF_8;

/**
 * 摘要算法工具类，提供各类哈希算法的计算功能。
 *
 * <p>支持以下摘要算法：</p>
 * <ul>
 *   <li>MD2、MD5</li>
 *   <li>SHA-1、SHA-224、SHA-256、SHA-384、SHA-512、SHA-512/224、SHA-512/256</li>
 *   <li>SHA3-224、SHA3-256、SHA3-384、SHA3-512（需要 Oracle Java 9+）</li>
 *   <li>SM3（需要 BouncyCastle Provider）</li>
 * </ul>
 *
 * <p>内部使用 {@link ThreadLocal} 缓存 {@link MessageDigest} 实例，在保证线程安全的同时避免重复创建开销。</p>
 *
 * @author CH
 */
public class DigestUtils {

    // ==================== 摘要输出长度常量 ====================

    /**
     * MD5 摘要输出字节长度（16 字节 = 128 位）
     */
    private static final int DIGITS_SIZE = 16;

    // ==================== 密钥长度常量 ====================

    /**
     * 默认 RSA 密钥长度（2048 位）
     */
    private static final int DEFAULT_RSA_KEY_SIZE = 2048;

    /**
     * 默认 AES 密钥长度（256 位）
     */
    private static final int DEFAULT_AES_KEY_SIZE = 256;

    /**
     * 默认 AES IV 长度（16 字节 = 128 位）
     */
    private static final int DEFAULT_IV_LENGTH = DIGITS_SIZE;

    /**
     * 默认 GCM 认证标签长度（128 位）
     */
    private static final int DEFAULT_GCM_TAG_LENGTH = 128;

    /**
     * 默认 PBKDF2 迭代次数
     */
    private static final int DEFAULT_PBKDF2_ITERATIONS = 100000;

    /**
     * 默认盐值长度（32 字节）
     */
    private static final int DEFAULT_SALT_LENGTH = 32;

    /**
     * 通用密钥长度（1024 位）
     */
    private static final int KEY_SIZE = 1024;

    // ==================== OTP/TOTP 常量 ====================

    /**
     * 默认 OTP 验证码位数
     */
    private static final int DEFAULT_OTP_DIGITS = 6;

    /**
     * 默认 TOTP 时间步长（30 秒）
     */
    private static final int DEFAULT_TOTP_PERIOD = 30;

    // ==================== 摘要算法名称常量 ====================

    /**
     * MD2 消息摘要算法
     */
    private static final String MD2 = "MD2";

    /**
     * MD5 消息摘要算法，生成 128 位（16 字节）散列值
     */
    public static final String MD5 = "MD5";

    /**
     * SHA-1 安全散列算法，FIPS PUB 180-4，生成 160 位（20 字节）散列值
     */
    public static final String SHA_1 = "SHA-1";

    /**
     * SHA-224 安全散列算法，FIPS PUB 180-3，生成 224 位散列值
     *
     * @since Oracle Java 8
     */
    public static final String SHA_224 = "SHA-224";

    /**
     * SHA-256 安全散列算法，FIPS PUB 180-2，生成 256 位散列值
     */
    public static final String SHA_256 = "SHA-256";

    /**
     * SHA-384 安全散列算法，FIPS PUB 180-2，生成 384 位散列值
     */
    public static final String SHA_384 = "SHA-384";

    /**
     * SHA-512 安全散列算法，FIPS PUB 180-2，生成 512 位散列值
     */
    public static final String SHA_512 = "SHA-512";

    /**
     * SHA-512/224 安全散列算法，FIPS PUB 180-4，生成 224 位散列值
     *
     * @since Oracle Java 9
     */
    public static final String SHA_512_224 = "SHA-512/224";

    /**
     * SHA-512/256 安全散列算法，FIPS PUB 180-4，生成 256 位散列值
     *
     * @since Oracle Java 9
     */
    public static final String SHA_512_256 = "SHA-512/256";

    /**
     * SHA3-224 安全散列算法，FIPS PUB 202，生成 224 位散列值
     *
     * @since Oracle Java 9
     */
    public static final String SHA3_224 = "SHA3-224";

    /**
     * SHA3-256 安全散列算法，FIPS PUB 202，生成 256 位散列值
     *
     * @since Oracle Java 9
     */
    public static final String SHA3_256 = "SHA3-256";

    /**
     * SHA3-384 安全散列算法，FIPS PUB 202，生成 384 位散列值
     *
     * @since Oracle Java 9
     */
    public static final String SHA3_384 = "SHA3-384";

    /**
     * SHA3-512 安全散列算法，FIPS PUB 202，生成 512 位散列值
     *
     * @since Oracle Java 9
     */
    public static final String SHA3_512 = "SHA3-512";

    // ==================== 国密算法名称常量 ====================

    /**
     * SM2 椭圆曲线公钥加密算法，需要 BouncyCastle Provider
     */
    public static final String SM2 = "SM2";

    /**
     * SM3 密码杂凑算法，生成 256 位散列值，需要 BouncyCastle Provider
     */
    public static final String SM3 = "SM3";

    /**
     * SM4 分组密码算法，需要 BouncyCastle Provider
     */
    public static final String SM4 = "SM4";

    /**
     * SM3withSM2 数字签名算法，需要 BouncyCastle Provider
     */
    public static final String SM3_WITH_SM2 = "SM3withSM2";

    // ==================== 对称加密算法名称常量 ====================

    /**
     * AES 高级加密标准
     */
    public static final String AES = "AES";

    /**
     * DES 数据加密标准
     */
    public static final String DES = "DES";

    /**
     * 3DES（Triple DES）三重数据加密算法
     */
    public static final String TRIPLE_DES = "DESede";

    // ==================== 非对称加密算法名称常量 ====================

    /**
     * RSA 非对称加密算法
     */
    private static final String RSA = "RSA";

    // ==================== 校验和算法名称常量 ====================

    /**
     * CRC32 循环冗余校验算法
     */
    public static final String CRC32 = "CRC32";

    /**
     * Adler32 校验和算法
     */
    public static final String ADLER32 = "Adler32";

    // ==================== HMAC 消息认证码算法名称常量 ====================

    /**
     * HmacMD5 消息认证码算法
     */
    public static final String HMAC_MD5 = "HmacMD5";

    /**
     * HmacSHA1 消息认证码算法
     */
    public static final String HMAC_SHA1 = "HmacSHA1";

    /**
     * HmacSHA256 消息认证码算法
     */
    public static final String HMAC_SHA256 = "HmacSHA256";

    /**
     * HmacSHA512 消息认证码算法
     */
    public static final String HMAC_SHA512 = "HmacSHA512";

    // ==================== 密钥派生算法名称常量 ====================

    /**
     * PBKDF2WithHmacSHA256 基于口令的密钥派生算法
     *
     * <p>用于从口令派生密钥，内置加盐和多次迭代，增强抗暴力破解能力。</p>
     */
    public static final String PBKDF2_WITH_HMAC_SHA256 = "PBKDF2WithHmacSHA256";

    // ==================== MessageDigest 缓存 ====================

    /**
     * 算法名称到 {@link ThreadLocal}<{@link MessageDigest}> 的缓存映射
     *
     * <p>使用 {@link ConcurrentReferenceHashMap} 避免类加载器泄漏，
     * 每个线程独立持有 {@link MessageDigest} 实例，无需同步等待。</p>
     */
    private static final ConcurrentReferenceHashMap<String, ThreadLocal<MessageDigest>> DIGEST_CACHE = new ConcurrentReferenceHashMap<>(16);

    private DigestUtils() {
    }

    // ==================== 通用哈希方法 ====================

    /**
     * 使用指定算法对字节数组进行哈希计算
     *
     * @param algorithm 算法名称，如 {@link #MD5}、{@link #SHA_256}、{@link #SHA3_512} 等
     * @param data      待哈希的字节数组
     * @return 哈希后的字节数组
     * @throws IllegalArgumentException 如果指定算法不支持或 data 为 null
     */
    public static byte[] hash(String algorithm, byte[] data) {
        return getCachedDigest(algorithm).digest(data);
    }

    /**
     * 使用指定算法对字符串进行哈希计算（UTF-8 编码）
     *
     * @param algorithm 算法名称，如 {@link #MD5}、{@link #SHA_256}、{@link #SHA3_512} 等
     * @param data      待哈希的字符串
     * @return 哈希后的字节数组
     * @throws IllegalArgumentException 如果指定算法不支持
     */
    public static byte[] hash(String algorithm, String data) {
        return hash(algorithm, data.getBytes(UTF_8));
    }

    /**
     * 使用指定算法对输入流进行哈希计算（适用于大文件/流式数据）
     *
     * <p>内部以 8KB 缓冲区分块读取，避免一次性加载全部数据到内存。</p>
     *
     * @param algorithm 算法名称，如 {@link #MD5}、{@link #SHA_256}、{@link #SHA3_512} 等
     * @param data      待哈希的输入流（方法内部不会关闭该流）
     * @return 哈希后的字节数组
     * @throws IOException              如果读取流时发生 I/O 错误
     * @throws IllegalArgumentException 如果指定算法不支持
     */
    public static byte[] hash(String algorithm, InputStream data) throws IOException {
        MessageDigest digest = getCachedDigest(algorithm);
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = data.read(buffer)) != -1) {
            digest.update(buffer, 0, bytesRead);
        }
        return digest.digest();
    }

    // ==================== MD5 算法（保持向后兼容） ====================

    /**
     * 计算字符串的 MD5 哈希值（字符串输入）
     *
     * @param content 待计算的内容
     * @return 32 位小写十六进制字符串
     */
    public static String md5(String content) {
        return StringUtils.bytes2string(hash(MD5, content));
    }

    /**
     * 计算字节数组的 MD5 哈希值
     *
     * @param content 待计算的字节数组
     * @return 32 位小写十六进制字符串
     */
    public static String md5(byte[] content) {
        return StringUtils.bytes2string(hash(MD5, content));
    }

    /**
     * 计算字符串的 MD5 哈希值（返回字节数组）
     *
     * @param content 待计算的内容
     * @return 16 字节 MD5 哈希值
     */
    public static byte[] md5Bytes(String content) {
        return hash(MD5, content);
    }

    /**
     * 计算字节数组的 MD5 哈希值（返回字节数组）
     *
     * @param content 待计算的字节数组
     * @return 16 字节 MD5 哈希值
     */
    public static byte[] md5Bytes(byte[] content) {
        return hash(MD5, content);
    }

    /**
     * 计算字节数组的 MD5 哈希值并返回十六进制字符串
     *
     * @param content 待计算的字节数组
     * @return 32 位小写十六进制字符串
     * @deprecated 使用 {@link #md5(byte[])} 替代
     */
    @Deprecated
    public static String getMd5String(byte[] content) {
        return md5(content);
    }

    /**
     * 计算字节数组的 MD5 哈希值
     *
     * @param data 待哈希的字节数组
     * @return 16 字节 MD5 哈希值
     */
    public static byte[] hash(byte[] data) {
        return hash(MD5, data);
    }

    /**
     * 计算字符串的 MD5 哈希值并返回十六进制字符串
     *
     * @param content 待计算的内容
     * @return 32 位小写十六进制字符串
     */
    public static String md5Hex(String content) {
        return md5(content);
    }

    // ==================== MD2 算法 ====================

    /**
     * 计算字符串的 MD2 哈希值
     *
     * @param content 待计算的内容
     * @return 32 位小写十六进制字符串
     */
    public static String md2(String content) {
        return StringUtils.bytes2string(hash(MD2, content));
    }

    /**
     * 计算字节数组的 MD2 哈希值
     *
     * @param content 待计算的字节数组
     * @return 32 位小写十六进制字符串
     */
    public static String md2(byte[] content) {
        return StringUtils.bytes2string(hash(MD2, content));
    }

    /**
     * 计算字符串的 MD2 哈希值（返回字节数组）
     *
     * @param content 待计算的内容
     * @return MD2 哈希值字节数组
     */
    public static byte[] md2Bytes(String content) {
        return hash(MD2, content);
    }

    /**
     * 计算字节数组的 MD2 哈希值（返回字节数组）
     *
     * @param content 待计算的字节数组
     * @return MD2 哈希值字节数组
     */
    public static byte[] md2Bytes(byte[] content) {
        return hash(MD2, content);
    }

    // ==================== SHA-1 算法 ====================

    /**
     * 计算字符串的 SHA-1 哈希值
     *
     * @param content 待计算的内容
     * @return 40 位小写十六进制字符串
     */
    public static String sha1(String content) {
        return StringUtils.bytes2string(hash(SHA_1, content));
    }

    /**
     * 计算字节数组的 SHA-1 哈希值
     *
     * @param content 待计算的字节数组
     * @return 40 位小写十六进制字符串
     */
    public static String sha1(byte[] content) {
        return StringUtils.bytes2string(hash(SHA_1, content));
    }

    /**
     * 计算字符串的 SHA-1 哈希值（返回字节数组）
     *
     * @param content 待计算的内容
     * @return 20 字节 SHA-1 哈希值
     */
    public static byte[] sha1Bytes(String content) {
        return hash(SHA_1, content);
    }

    /**
     * 计算字节数组的 SHA-1 哈希值（返回字节数组）
     *
     * @param content 待计算的字节数组
     * @return 20 字节 SHA-1 哈希值
     */
    public static byte[] sha1Bytes(byte[] content) {
        return hash(SHA_1, content);
    }

    // ==================== SHA-224 算法 ====================

    /**
     * 计算字符串的 SHA-224 哈希值
     *
     * @param content 待计算的内容
     * @return 56 位小写十六进制字符串
     * @since Oracle Java 8
     */
    public static String sha224(String content) {
        return StringUtils.bytes2string(hash(SHA_224, content));
    }

    /**
     * 计算字节数组的 SHA-224 哈希值
     *
     * @param content 待计算的字节数组
     * @return 56 位小写十六进制字符串
     * @since Oracle Java 8
     */
    public static String sha224(byte[] content) {
        return StringUtils.bytes2string(hash(SHA_224, content));
    }

    /**
     * 计算字符串的 SHA-224 哈希值（返回字节数组）
     *
     * @param content 待计算的内容
     * @return 28 字节 SHA-224 哈希值
     * @since Oracle Java 8
     */
    public static byte[] sha224Bytes(String content) {
        return hash(SHA_224, content);
    }

    /**
     * 计算字节数组的 SHA-224 哈希值（返回字节数组）
     *
     * @param content 待计算的字节数组
     * @return 28 字节 SHA-224 哈希值
     * @since Oracle Java 8
     */
    public static byte[] sha224Bytes(byte[] content) {
        return hash(SHA_224, content);
    }

    // ==================== SHA-256 算法 ====================

    /**
     * 计算字符串的 SHA-256 哈希值
     *
     * @param content 待计算的内容
     * @return 64 位小写十六进制字符串
     */
    public static String sha256(String content) {
        return StringUtils.bytes2string(hash(SHA_256, content));
    }

    /**
     * 计算字节数组的 SHA-256 哈希值
     *
     * @param content 待计算的字节数组
     * @return 64 位小写十六进制字符串
     */
    public static String sha256(byte[] content) {
        return StringUtils.bytes2string(hash(SHA_256, content));
    }

    /**
     * 计算字符串的 SHA-256 哈希值（返回字节数组）
     *
     * @param content 待计算的内容
     * @return 32 字节 SHA-256 哈希值
     */
    public static byte[] sha256Bytes(String content) {
        return hash(SHA_256, content);
    }

    /**
     * 计算字节数组的 SHA-256 哈希值（返回字节数组）
     *
     * @param content 待计算的字节数组
     * @return 32 字节 SHA-256 哈希值
     */
    public static byte[] sha256Bytes(byte[] content) {
        return hash(SHA_256, content);
    }

    // ==================== SHA-384 算法 ====================

    /**
     * 计算字符串的 SHA-384 哈希值
     *
     * @param content 待计算的内容
     * @return 96 位小写十六进制字符串
     */
    public static String sha384(String content) {
        return StringUtils.bytes2string(hash(SHA_384, content));
    }

    /**
     * 计算字节数组的 SHA-384 哈希值
     *
     * @param content 待计算的字节数组
     * @return 96 位小写十六进制字符串
     */
    public static String sha384(byte[] content) {
        return StringUtils.bytes2string(hash(SHA_384, content));
    }

    /**
     * 计算字符串的 SHA-384 哈希值（返回字节数组）
     *
     * @param content 待计算的内容
     * @return 48 字节 SHA-384 哈希值
     */
    public static byte[] sha384Bytes(String content) {
        return hash(SHA_384, content);
    }

    /**
     * 计算字节数组的 SHA-384 哈希值（返回字节数组）
     *
     * @param content 待计算的字节数组
     * @return 48 字节 SHA-384 哈希值
     */
    public static byte[] sha384Bytes(byte[] content) {
        return hash(SHA_384, content);
    }

    // ==================== SHA-512 算法 ====================

    /**
     * 计算字符串的 SHA-512 哈希值
     *
     * @param content 待计算的内容
     * @return 128 位小写十六进制字符串
     */
    public static String sha512(String content) {
        return StringUtils.bytes2string(hash(SHA_512, content));
    }

    /**
     * 计算字节数组的 SHA-512 哈希值
     *
     * @param content 待计算的字节数组
     * @return 128 位小写十六进制字符串
     */
    public static String sha512(byte[] content) {
        return StringUtils.bytes2string(hash(SHA_512, content));
    }

    /**
     * 计算字符串的 SHA-512 哈希值（返回字节数组）
     *
     * @param content 待计算的内容
     * @return 64 字节 SHA-512 哈希值
     */
    public static byte[] sha512Bytes(String content) {
        return hash(SHA_512, content);
    }

    /**
     * 计算字节数组的 SHA-512 哈希值（返回字节数组）
     *
     * @param content 待计算的字节数组
     * @return 64 字节 SHA-512 哈希值
     */
    public static byte[] sha512Bytes(byte[] content) {
        return hash(SHA_512, content);
    }

    // ==================== SM3 国密算法 ====================

    /**
     * 计算字符串的 SM3 哈希值
     *
     * <p>需要 BouncyCastle Provider 在运行时可用。</p>
     *
     * @param content 待计算的内容
     * @return 64 位小写十六进制字符串
     */
    public static String sm3(String content) {
        return StringUtils.bytes2string(hash(SM3, content));
    }

    /**
     * 计算字节数组的 SM3 哈希值
     *
     * <p>需要 BouncyCastle Provider 在运行时可用。</p>
     *
     * @param content 待计算的字节数组
     * @return 64 位小写十六进制字符串
     */
    public static String sm3(byte[] content) {
        return StringUtils.bytes2string(hash(SM3, content));
    }

    /**
     * 计算字符串的 SM3 哈希值（返回字节数组）
     *
     * <p>需要 BouncyCastle Provider 在运行时可用。</p>
     *
     * @param content 待计算的内容
     * @return 32 字节 SM3 哈希值
     */
    public static byte[] sm3Bytes(String content) {
        return hash(SM3, content);
    }

    /**
     * 计算字节数组的 SM3 哈希值（返回字节数组）
     *
     * <p>需要 BouncyCastle Provider 在运行时可用。</p>
     *
     * @param content 待计算的字节数组
     * @return 32 字节 SM3 哈希值
     */
    public static byte[] sm3Bytes(byte[] content) {
        return hash(SM3, content);
    }

    // ==================== HMAC-SHA256 算法 ====================

    /**
     * 计算 HMAC-SHA256 消息认证码（字符串输入 + 字符串密钥）
     *
     * @param data 待计算的数据
     * @param key  HMAC 密钥
     * @return 64 位小写十六进制字符串
     */
    public static String hmacSha256(String data, String key) {
        return hmacSha256(data.getBytes(UTF_8), key.getBytes(UTF_8));
    }

    /**
     * 计算 HMAC-SHA256 消息认证码（字节数组输入 + 字节数组密钥）
     *
     * @param data 待计算的数据
     * @param key  HMAC 密钥
     * @return 64 位小写十六进制字符串
     */
    public static String hmacSha256(byte[] data, byte[] key) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            return StringUtils.bytes2string(mac.doFinal(data));
        } catch (Exception e) {
            throw new IllegalArgumentException("HMAC-SHA256 计算失败", e);
        }
    }

    // ==================== 内部工具方法 ====================

    /**
     * 获取指定算法的 {@link MessageDigest} 实例（线程缓存版本）
     *
     * <p>先从缓存 {@link #DIGEST_CACHE} 中获取当前线程的 {@link ThreadLocal}<{@link MessageDigest}>，
     * 如果不存在则创建并缓存。每次调用后自动 {@link MessageDigest#reset()} 以复用实例。</p>
     *
     * @param algorithm 算法名称
     * @return {@link MessageDigest} 实例
     * @throws IllegalArgumentException 如果指定算法不支持
     */
    private static MessageDigest getCachedDigest(String algorithm) {
        ThreadLocal<MessageDigest> threadLocal = DIGEST_CACHE.computeIfAbsent(algorithm,
                k -> ThreadLocal.withInitial(() -> {
                    try {
                        return MessageDigest.getInstance(k);
                    } catch (NoSuchAlgorithmException e) {
                        throw new IllegalArgumentException("不支持的算法: " + k, e);
                    }
                }));

        MessageDigest digest = threadLocal.get();
        digest.reset();
        return digest;
    }
}
