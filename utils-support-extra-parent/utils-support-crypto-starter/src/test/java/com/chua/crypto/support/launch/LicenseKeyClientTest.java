package com.chua.crypto.support.launch;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验服务器客户端协议测试：请求构造、指纹校验、签名响应解析
 *
 * @author CH
 * @since 2026-08-26
 */
class LicenseKeyClientTest {

    /**
     * 测试用 secret
     */
    private static final char[] SECRET = "unit-secret".toCharArray();

    /**
     * 指纹形态校验：64 位十六进制通过，其余拒绝
     */
    @Test
    void validateFingerprint() {
        LicenseKeyClient.validateFingerprint("a".repeat(64));
        assertThrows(IllegalStateException.class, () -> LicenseKeyClient.validateFingerprint(null));
        assertThrows(IllegalStateException.class, () -> LicenseKeyClient.validateFingerprint("short"));
        // 注入尝试：携带引号/花括号的输入必须被拒绝
        assertThrows(IllegalStateException.class,
                () -> LicenseKeyClient.validateFingerprint("\"}inject:{"));
    }

    /**
     * 请求体构造
     */
    @Test
    void buildBodyEscapes() {
        assertEquals("{\"appId\":\"demo\",\"fingerprint\":\"abc\"}",
                LicenseKeyClient.buildBody("demo", "abc"));
        assertTrue(LicenseKeyClient.buildBody("a\"b", "c").contains("a\\\"b"),
                "引号应被转义");
    }

    /**
     * 无签名响应解析（兼容形态）
     */
    @Test
    void parseUnsignedResponse() {
        byte[] blob = {1, 2, 3};
        byte[] body = Base64.getEncoder().encode(blob);
        assertArrayEquals(blob, LicenseKeyClient.parseResponse(body, null));
        assertArrayEquals(blob, LicenseKeyClient.parseResponse(body, SECRET));
    }

    /**
     * 签名响应解析：正确 secret 通过，错误/缺失 secret 拒绝，篡改拒绝
     */
    @Test
    void parseSignedResponse() {
        byte[] blob = "real-blob".getBytes();
        byte[] mac = hmac(SECRET, blob);
        String text = "v1." + Base64.getEncoder().encodeToString(blob)
                + "." + Base64.getEncoder().encodeToString(mac);
        byte[] body = text.getBytes();

        assertArrayEquals(blob, LicenseKeyClient.parseResponse(body, SECRET));
        assertArrayEquals(blob, LicenseKeyClient.parseResponse(body, null)); // 服务端未签名场景由服务端保证

        assertThrows(IllegalStateException.class,
                () -> LicenseKeyClient.parseResponse(body, "bad".toCharArray()));
        assertThrows(IllegalStateException.class,
                () -> LicenseKeyClient.parseResponse(
                        ("v1." + Base64.getEncoder().encodeToString("tampered".getBytes())
                                + "." + Base64.getEncoder().encodeToString(mac)).getBytes(),
                        SECRET));
        // 客户端启用签名但服务端未签名 → 拒绝
        assertThrows(IllegalStateException.class,
                () -> LicenseKeyClient.parseResponse(
                        Base64.getEncoder().encode(blob), SECRET));
    }

    /**
     * HmacSHA256（与客户端实现同构）
     */
    private byte[] hmac(char[] secret, byte[] data) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                new String(secret).getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data);
    }
}
