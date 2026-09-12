package com.chua.crypto.support.launch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

/**
* 校验服务器密钥下发客户端（零依赖，基于 JDK HTTP客户端）
*
* <h2>协议 v1</h2>
* <pre>
* POST {license-url}
* Body(JSON): {"appId":"...","fingerprint":"sha256hex"}
*
* 响应 200:
*   未启用签名: Body = base64(私钥封装块)
*   启用签名:   Body = "v1." + base64(封装块) + "." + base64(HmacSHA256(secret, 封装块))
* 响应 403:   指纹未注册/已吊销
* </pre>
*
* <h2>可靠性</h2>
* <ul>
*   <li>连接/IO 异常自动重试 2 次（间隔 500ms/1000ms）；403 属业务拒绝不重试</li>
*   <li>启用签名时响应被篡改/secret 不匹配将直接拒绝启动</li>
* </ul>
*
* @author CH
* @since 2026-08-26
 */
public final class LicenseKeyClient {

    /**
    * 签名响应版本前缀
     */
    public static final String SIGNED_PREFIX = "v1.";

    /**
    * 指纹字段的合法形态：64 位十六进制
     */
    private static final Pattern FINGERPRINT_PATTERN = Pattern.compile("\"fingerprint\"\\s*:\\s*\"([0-9a-fA-F]{64})\"");

    /**
    * 最大重试次数（首次 + 2 次重试）
     */
    private static final int MAX_ATTEMPTS = 3;

    /**
    * 私有构造
     */
    private LicenseKeyClient() {
    }

    /**
    * 向校验服务器请求注册的私钥封装块
    *
    * @param licenseUrl  校验服务器地址
    * @param appId       应用标识
    * @param fingerprint 本机指纹十六进制串
    * @param secret      响应签名密钥（可为 空；生产环境必须配置并与服务端一致）
    * @return 封装块字节（交由 unwrapmaster 解封）
    * @throws IllegalStateException 未注册/签名校验失败/网络失败
     */
    public static byte[] fetch(String licenseUrl, String appId, String fingerprint, char[] secret) {
        validateFingerprint(fingerprint);
        byte[] body = postWithRetry(licenseUrl, buildBody(appId, fingerprint));
        return parseResponse(body, secret);
    }

    /**
    * 构造请求体
    *
    * @param appId       应用标识
    * @param fingerprint 指纹
    * @return JSON 字符串
     */
    static String buildBody(String appId, String fingerprint) {
        return "{\"appId\":\"" + escape(appId) + "\",\"fingerprint\":\"" + fingerprint + "\"}";
    }

    /**
    * POST 请求，连接/IO 异常时按固定退避重试
    *
    * @param licenseUrl 地址
    * @param json       请求体
    * @return 成功响应体
     */
    private static byte[] postWithRetry(String licenseUrl, String json) {
        IllegalStateException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(licenseUrl))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<byte[]> response = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build()
                        .send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("校验服务器拒绝: HTTP "
                            + response.statusCode() + "（指纹未注册或已吊销）");
                }
                return response.body();
            } catch (IllegalStateException e) {
                throw e;                                // 业务拒绝不重试
            } catch (Exception e) {
                last = new IllegalStateException("校验服务器连接失败(第" + attempt + "次): " + e.getMessage(), e);
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("校验请求被中断", ie);
                    }
                }
            }
        }
        throw last;
    }

    /**
    * 解析响应：兼容 无签名(基础64) 与 签名(v1.blob.hmac) 两种形态；
    * 客户端配置了 secret 时必须携带合法签名
    *
    * @param body   响应体
    * @param secret 签名密钥（可空）
    * @return 封装块字节
     */
    public static byte[] parseResponse(byte[] body, char[] secret) {
        if (body == null || body.length == 0) {
            throw new IllegalStateException("校验服务器响应为空");
        }
        String text = new String(body, StandardCharsets.UTF_8).trim();
        boolean signed = text.startsWith(SIGNED_PREFIX);

        if (!signed && secret != null && secret.length > 0) {
            throw new IllegalStateException("校验服务器响应未签名，但客户端已启用签名校验");
        }
        if (signed) {
            requireSecret(secret);
            String[] parts = text.split("\\.", 3);
            if (parts.length != 3) {
                throw new IllegalStateException("签名响应格式非法");
            }
            byte[] blob = Base64.getMimeDecoder().decode(parts[1]);
            byte[] expectedMac = hmac(secret, blob);
            byte[] actualMac = Base64.getMimeDecoder().decode(parts[2]);
            if (!java.security.MessageDigest.isEqual(expectedMac, actualMac)) {
                throw new IllegalStateException("响应签名校验失败（secret 不匹配或响应被篡改）");
            }
            return blob;
        }
        return Base64.getMimeDecoder().decode(text);
    }

    /**
    * 校验指纹形态，防止非法输入注入请求
    *
    * @param fingerprint 指纹
     */
    static void validateFingerprint(String fingerprint) {
        if (fingerprint == null || !FINGERPRINT_PATTERN.matcher(
                "{\"fingerprint\":\"" + fingerprint + "\"}").find()) {
            throw new IllegalStateException("本机指纹形态非法");
        }
    }

    /**
    * hmacsha256
    *
    * @param secret 密钥
    * @param data   数据
    * @return 摘要
     */
    private static byte[] hmac(char[] secret, byte[] data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    new String(secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    /**
    * 要求签名场景必须提供 secret
    *
    * @param secret secret
     */
    private static void requireSecret(char[] secret) {
        if (secret == null || secret.length == 0) {
            throw new IllegalStateException("服务端返回签名响应，但未配置 chua.crypto.license-secret");
        }
    }

    /**
    * JSON 字符串转义
    * @param value 值
    * @return escape的结果
     */
    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
