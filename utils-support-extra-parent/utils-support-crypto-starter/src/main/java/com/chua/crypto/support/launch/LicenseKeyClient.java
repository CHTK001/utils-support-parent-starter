package com.chua.crypto.support.launch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * 校验服务器密钥下发客户端（零依赖，基于 JDK HttpClient）
 *
 * <p>协议：
 * <pre>
 * POST {license-url}
 * Body(JSON): {"appId":"...","fingerprint":"sha256hex"}
 * 响应 200:   Body = Base64(注册的私钥封装块)
 * 响应其他:   指纹未注册/已吊销，拒绝启动
 * </pre>
 *
 * @author CH
 * @since 2026-08-26
 */
public final class LicenseKeyClient {

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
     * @return 封装块字节（交由 unwrapMaster 解封）
     * @throws IllegalStateException 未注册/网络失败
     */
    public static byte[] fetch(String licenseUrl, String appId, String fingerprint) {
        String json = "{\"appId\":\"" + escape(appId) + "\",\"fingerprint\":\""
                + escape(fingerprint) + "\"}";
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
            byte[] body = response.body();
            if (body.length > 0 && body[0] == 'C') {
                return body;                       // 原始二进制下发
            }
            return Base64.getMimeDecoder().decode(new String(body, StandardCharsets.UTF_8).trim());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("校验服务器连接失败: " + e.getMessage(), e);
        }
    }

    /**
     * JSON 字符串转义
     */
    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
