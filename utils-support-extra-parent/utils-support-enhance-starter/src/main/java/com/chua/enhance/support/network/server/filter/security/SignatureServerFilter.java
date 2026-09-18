package com.chua.enhance.support.network.server.filter.security;

import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.filter.ServerFilterConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/**
* 请求签名验证过滤器，验证请求的签名防篡改。
*
* <p>从请求头中获取签名值（默认 {@code X-Signature}），
* 使用 HMAC-SHA256 或 MD5 对请求体计算签名并与请求头中的签名比对。
* 签名不匹配则返回 403。
*
* <h2>配置参数</h2>
* <ul>
*   <li>{@code signature.secret} — 签名密钥，必填</li>
*   <li>{@code signature.algorithm} — 签名算法，默认 HMAC-SHA256</li>
*   <li>{@code signature.header} — 签名头名称，默认 {@code X-Signature}</li>
* </ul>
*
* @author CH
* @since 2026/07/16
 */
public class SignatureServerFilter implements ServerFilter {

    /**
    * 默认签名头名称
    */
    private static final String DEFAULT_SIGNATURE_HEADER = "X-Signature";
    /**
    * 默认签名算法
    */
    private static final String DEFAULT_ALGORITHM = "HMAC-SHA256";

    /** Secret */
    private String secret;
    /** 算法 */
    private String algorithm = DEFAULT_ALGORITHM;
    /** 签名头部 */
    private String signatureHeader = DEFAULT_SIGNATURE_HEADER;
    /** Excludepaths */
    private final Set<String> excludePaths = new HashSet<>();

    @Override
    /** 初始化 */
    public void init(ServerFilterConfig config) throws Exception {
        this.secret = config.getInitParameter("signature.secret");
        String alg = config.getInitParameter("signature.algorithm");
        if (alg != null && !alg.isEmpty()) {
            this.algorithm = alg;
        }
        String header = config.getInitParameter("signature.header");
        if (header != null && !header.isEmpty()) {
            this.signatureHeader = header;
        }
        String paths = config.getInitParameter("signature.excludePaths");
        if (paths != null) {
            for (String path : paths.split(",")) {
                String trimmed = path.trim();
                if (!trimmed.isEmpty()) {
                    excludePaths.add(trimmed);
                }
            }
        }
    }

    @Override
    /** 执行过滤 */
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        if (secret == null || isExcluded(request.getPath())) {
            chain.doFilter(request, response);
            return;
        }
        String providedSignature = request.getHeader(signatureHeader);
        if (providedSignature == null || providedSignature.isEmpty()) {
            response.end(401, "{\"error\":\"Unauthorized\",\"message\":\"缺少签名\"}");
            return;
        }
        byte[] body = request.getBody();
        String expectedSignature = computeSignature(body);
        if (!providedSignature.equals(expectedSignature)) {
            response.end(403, "{\"error\":\"Forbidden\",\"message\":\"签名验证失败\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    /** 获取订单 */
    public int getOrder() {
        return 15;
    }

    @Override
    /** 获取过滤标识 */
    public String getFilterId() {
        return "SignatureServerFilter";
    }

    /**
    * compute签名
    *
    * @param data 数据
    * @return compute签名的结果
    */
    private String computeSignature(byte[] data) {
        try {
            if ("HMAC-SHA256".equalsIgnoreCase(algorithm)) {
                Mac mac = Mac.getInstance("HmacSHA256");
                SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
                mac.init(keySpec);
                return Base64.getEncoder().encodeToString(mac.doFinal(data));
            }
            if ("MD5".equalsIgnoreCase(algorithm)) {
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] digest = md.digest(data);
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            }
        } catch (Exception e) {
            // 签名计算失败，返回空字符串
        }
        return "";
    }

    /**
    * 是否Excluded
    *
    * @param path 路径
    * @return 是否excluded的结果
    */
    private boolean isExcluded(String path) {
        if (path == null) {
            return false;
        }
        for (String exclude : excludePaths) {
            if (path.startsWith(exclude)) {
                return true;
            }
        }
        return false;
    }
}
