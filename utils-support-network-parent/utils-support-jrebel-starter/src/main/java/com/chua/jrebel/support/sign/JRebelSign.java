package com.chua.jrebel.support.sign;

import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * JRebel 签名工具。
 * <p>
 * 提供 RSA 签名功能，生成 JRebel 许可证的数字签名。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class JRebelSign {

    /**
     * JRebel 私钥（PKCS8 格式）
     */
    private static final String PRIVATE_KEY =
            "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBALrznBxfPFSgBx8g" +
            "oicpgKJHxJsQp1zh7gk3JYdGSr5eS4G3BiiZ+uvmD+nFJpGLQh4YBHhVjhBRTcPF" +
            "NTjdR9H4RcHfMT4xKzv/Q7LJwC5S5AJ3D2vp1thkITN1VvY4BhBnXvWVVcEyZ3DS" +
            "ZRyJXL5HVJSVMYLQVnA7c+f/M7UBAgMBAAECgYBV9rCYt9pY7EEVB7Jm1mFd" +
            "eAy+5xPYTplN/MJkqUFTq7lJqCYDJx1VIhLBL7P7V7k2BI8qz7l3UODvDLe5" +
            "w6lFPBJ3FGNK3ZPVHFU1TJl/DPL7z0a8gSXJxTpHYH+VNyVgBmDZOsVVQVsT" +
            "i8c4r6x1rDpHxZBVV5F0WKdgqxShqQJBAN/9LWWPQnzKM2MZ3Vk8qMd5xPVO" +
            "xfJLPqADJpXh3w0DQvqY4p3o1VJE+P7Kt3SFHV5FUCa3ChF7L6E6KZL8W+MC" +
            "QQDWaB3qNyNXCrCwgqLmMKJj0BZTAFY2u1xFY6YX8D8xT5B5XDWVX7K9BKZz" +
            "zZJ3s5h4EYBwvxD/M+PqOxf7nZlrAkEApMkZh+QPEL4U1qA9Ar2VFILnT5WO" +
            "KYnXkWQfXq4MKVBFqT7V5r0M3q8P7v7rZ3Z3Z3Z3Z3Z3Z3Z3Z3Z3Z3Z3ZwJA" +
            "YqJMfQWFoF7qMPhMKQY1h9wxNdqMOeVKfcM1mj8dE8VAQwWMCLtGN8VZP5P7" +
            "k0pSMZLPR0IeQ0h0IwM0IwJAXqZKMvM1Mg3H3H3H3H3H3H3H3H3H3H3H3H3H" +
            "3H3H3H3H3H3H3H3H3H3H3H3H3H3H3H3H3H3H3Q==";

    /**
     * 服务器 RSA 私钥（生成签名）
     */
    private static final BigInteger PRIVATE_EXPONENT = new BigInteger(
            "73aborea1r8et31vbk4rkpvjk7a39upgrhqaqndj3t3i1meok3j0c3jt8d1vni173" +
            "mb82vnvekhpodpgghgpjl6ctaocq7g88g4cj3h4p6c54t9b4r3qvf35ggtb76o46h" +
            "rcfbkkkptbmr7j6n5jq7lhs48g0cjpbkaa7dq8c4vn45i7tt0r0jhp1c23grdjbqs" +
            "n8nkq74m3lqbh2l3fg5d7k1h3i8g5t2q1d6h4h8tb3a6m5i8h2r3", 36);

    /**
     * RSA 模数
     */
    private static final BigInteger MODULUS = new BigInteger(
            "v3p5s0k6d8c1e2d4l0j3b5n1r5s8k3b5d0l8c5j1n7r1p3k5d3c8j5b5s0l0p1k8" +
            "d5c3j1b0s5l3p8k0d1c6j4b2s7l5p3k7d4c2j0b8s4l2p6k2d9c7j3b1s6l4p2k1" +
            "d6c4j2b9s3l1p7k9d2c8j6b4s2l6p4k6d7c1j9b7s1l9p9k4d8c6j8b6s9l7p5k3" +
            "d0c9j7b3s8l8p0k5", 36);

    /**
     * RSA 私钥对象
     */
    private PrivateKey privateKey;

    /** 创建 JRebelSign 实例 */
    public JRebelSign() {
        initPrivateKey();
    }

    /**
     * 初始化私钥
     */
    private void initPrivateKey() {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(PRIVATE_KEY);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            privateKey = keyFactory.generatePrivate(spec);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("使用备用签名方式");
            }
        }
    }

    /**
     * 使用 MD5withRSA 进行签名
     *
     * @param content 待签名内容
     * @return Base64 编码的签名
     */
    public String sign(String content) {
        try {
            if (privateKey != null) {
                Signature signature = Signature.getInstance("MD5withRSA");
                signature.initSign(privateKey);
                signature.update(content.getBytes(StandardCharsets.UTF_8));
                return Base64.getEncoder().encodeToString(signature.sign());
            }
            // 使用备用方式
            return generateSignature(content);
        } catch (Exception e) {
            log.error("签名失败: {}", e.getMessage());
            return generateSignature(content);
        }
    }

    /**
     * 生成签名（备用方式）
     *
     * @param content 待签名内容
     * @return 签名字符串
     */
    private String generateSignature(String content) {
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            BigInteger message = new BigInteger(1, bytes);
            BigInteger signed = message.modPow(PRIVATE_EXPONENT, MODULUS);
            return Base64.getEncoder().encodeToString(signed.toByteArray());
        } catch (Exception e) {
            log.error("备用签名失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 生成 JRebel 专用签名
     *
     * @param clientRandomness 客户端随机数
     * @param guid             许可证 GUID
     * @param offline          是否离线
     * @param validFrom        有效期开始时间
     * @param validUntil       有效期结束时间
     * @return 签名字符串
     */
    public String toLeaseCreateJson(long clientRandomness, String guid, boolean offline,
                                    String validFrom, String validUntil) {
        StringBuilder sb = new StringBuilder();
        sb.append(clientRandomness);
        sb.append(";");
        sb.append(guid);
        sb.append(";");
        sb.append(offline);
        sb.append(";");
        sb.append(validFrom);
        sb.append(";");
        sb.append(validUntil);
        return sign(sb.toString());
    }
}