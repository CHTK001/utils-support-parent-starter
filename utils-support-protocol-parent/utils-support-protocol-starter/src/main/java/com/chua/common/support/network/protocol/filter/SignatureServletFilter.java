package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.server.FilterOption;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import com.chua.common.support.network.protocol.server.UpgradeServletFilter;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 签名校验过滤器
 * <p>支持常见签名算法（HMAC-SHA256、MD5）和时戳/随机串重放保护。</p>
 *
 * @author CH
 * @since 2025-08-15
 */
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Data
@Spi("signature")
@SpiDescribe("签名校验过滤器")
public class SignatureServletFilter extends UpgradeServletFilter<SignatureServletFilter.SignConfig> {

    private static final String DEFAULT_SIGN_HEADER = "X-Sign";
    private static final String DEFAULT_TS_HEADER = "X-Timestamp";
    private static final String DEFAULT_NONCE_HEADER = "X-Nonce";
    private static final String DEFAULT_APP_HEADER = "X-AppId";

    // 简单的nonce缓存（内存），生产可替换为分布式缓存
    private final Set<String> recentNonces = Collections.synchronizedSet(new LinkedHashSet<>());

    public SignatureServletFilter() {
        super("SignatureFilter");
        SignConfig cfg = new SignConfig();
        cfg.setEnabled(true);
        cfg.setAlgorithm(SignAlgorithm.HMAC_SHA256);
        cfg.setHeaderName(DEFAULT_SIGN_HEADER);
        cfg.setTimestampHeader(DEFAULT_TS_HEADER);
        cfg.setNonceHeader(DEFAULT_NONCE_HEADER);
        cfg.setAppIdHeader(DEFAULT_APP_HEADER);
        cfg.setSecretProvider(new HashMap<>()); // appId -> secret
        cfg.setMaxSkewSeconds(300L);
        cfg.setRequireTimestamp(true);
        cfg.setRequireNonce(true);
        cfg.setIncludeHeaders(new LinkedHashSet<>());
        cfg.getIncludeHeaders().add("Content-Type");
        upgradeConfigObject(cfg);
    }

    @Override
    protected void doFilterWithConfigObject(ServletRequest request, ServletResponse response, ServletFilterChain chain, SignConfig config) throws Exception {
        if (!config.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String appId = headerOrParam(request, config.getAppIdHeader(), "appId");
        if (appId == null || appId.isEmpty()) {
            reject(request, response, 401, "Missing appId");
            return;
        }

        String providedSign = headerOrParam(request, config.getHeaderName(), "sign");
        if (providedSign == null || providedSign.isEmpty()) {
            reject(request, response, 401, "Missing signature");
            return;
        }

        String secret = Optional.ofNullable(config.getSecretProvider()).map(m -> m.get(appId)).orElse(null);
        if (secret == null) {
            reject(request, response, 401, "Unknown appId");
            return;
        }

        // 时间戳与随机串校验
        if (config.isRequireTimestamp()) {
            String tsStr = headerOrParam(request, config.getTimestampHeader(), "timestamp");
            if (!validateTimestamp(tsStr, config.getMaxSkewSeconds())) {
                reject(request, response, 401, "Invalid timestamp");
                return;
            }
        }
        if (config.isRequireNonce()) {
            String nonce = headerOrParam(request, config.getNonceHeader(), "nonce");
            if (!validateAndRecordNonce(nonce)) {
                reject(request, response, 401, "Invalid nonce");
                return;
            }
        }

        // 计算签名
        String computed = computeSignature(request, config, secret);
        if (!constantTimeEquals(providedSign, computed)) {
            reject(request, response, 401, "Signature mismatch");
            return;
        }

        chain.doFilter(request, response);
    }

    private String computeSignature(ServletRequest request, SignConfig cfg, String secret) throws Exception {
        StringBuilder base = new StringBuilder();
        base.append(request.getMethod()).append('\n');
        base.append(Optional.ofNullable(request.getPath()).orElse("")).append('\n');

        // 参与签名的参数（按名字典序）
        Map<String, String[]> paramMap = request.getParameterMap();
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String[]> e : paramMap.entrySet()) {
            if (e.getValue() != null && e.getValue().length > 0) {
                sorted.put(e.getKey(), e.getValue()[0]);
            }
        }
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            base.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }

        // 参与签名的头
        if (cfg.getIncludeHeaders() != null) {
            List<String> headers = new ArrayList<>(cfg.getIncludeHeaders());
            headers.sort(String::compareTo);
            for (String h : headers) {
                String v = request.getHeaders() != null ? request.getHeaders().getFirst(h) : null;
                base.append(h).append(':').append(Optional.ofNullable(v).orElse(""));
                base.append('\n');
            }
        }

        // Body
        if (cfg.isIncludeBody() && request.getBody() != null) {
            base.append(new String(request.getBody(), StandardCharsets.UTF_8));
            base.append('\n');
        }

        String payload = base.toString();
        if (cfg.getAlgorithm() == SignAlgorithm.MD5) {
            return md5Hex(payload + secret);
        }
        return hmacSha256Hex(payload, secret);
    }

    private boolean validateTimestamp(String tsStr, long maxSkewSeconds) {
        try {
            long ts = Long.parseLong(tsStr);
            long nowSec = System.currentTimeMillis() / 1000L;
            return Math.abs(nowSec - ts) <= maxSkewSeconds;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean validateAndRecordNonce(String nonce) {
        if (nonce == null || nonce.length() < 8) {
            return false;
        }
        synchronized (recentNonces) {
            if (recentNonces.contains(nonce)) {
                return false;
            }
            // 简单窗口，限制集合大小
            if (recentNonces.size() > 10_000) {
                Iterator<String> it = recentNonces.iterator();
                int remove = 1000;
                while (remove-- > 0 && it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            recentNonces.add(nonce);
        }
        return true;
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }

    private String md5Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
        return toHex(out);
    }

    private String hmacSha256Hex(String data, String key) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        javax.crypto.spec.SecretKeySpec secretKeySpec = new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        return toHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void reject(ServletRequest request, ServletResponse response, int status, String reason) {
        response.setStatusCode(status);
        response.setStatusMessage("Unauthorized");
        response.setContentType("application/json");
        response.setBodyString(String.format(Locale.ROOT, "{\"error\":\"Signature rejected\",\"reason\":\"%s\"}", reason));
        response.addHeader("X-Blocked-Reason", "SIGNATURE");
        response.setTerminateEarly(true);
        try {
            com.chua.common.support.network.protocol.event.ServletEventDispatcher.publishAsync(
                    com.chua.common.support.network.protocol.event.ServletEvent.builder()
                            .clientIp(request.findClientIp())
                            .requestId(request.getRequestId())
                            .path(request.getPath())
                            .method(request.getMethod())
                            .duration(0L)
                            .status(com.chua.common.support.network.protocol.event.ServletRequestStatus.REJECTED)
                            .statusCode(status)
                            .terminated(true)
                            .build());
        } catch (Throwable ignored) {
        }
    }

    private String headerOrParam(ServletRequest request, String header, String param) {
        String v = null;
        if (header != null && request.getHeaders() != null) {
            v = request.getHeaders().getFirst(header);
        }
        if (v == null) {
            v = request.getParameter(param);
        }
        return v;
    }

    @Override
    protected boolean validateConfigObject(SignConfig config) {
        return config != null && config.getAlgorithm() != null && config.getHeaderName() != null;
    }

    @Override
    public String getFilterName() {
        return "SignatureServletFilter";
    }

    @Override
    public int getOrder() {
        return 1; // 越早越好
    }

    @Override
    public String getDescription() {
        SignConfig c = getConfigurationObject();
        return String.format(Locale.ROOT, "签名校验 (v%d) enabled=%s algo=%s", getConfigVersion(), c != null && c.isEnabled(), c != null ? c.getAlgorithm() : SignAlgorithm.HMAC_SHA256);
    }

    @Override
    public List<FilterOption> getFilterOptions() {
        List<FilterOption> options = new ArrayList<>();
        options.add(FilterOption.builder().name("启用状态").key("enabled").type(Boolean.class).description("是否启用").required(true).defaultValue(true).build());
        options.add(FilterOption.builder().name("签名算法").key("algorithm").type(SignAlgorithm.class).description("HMAC_SHA256 或 MD5").required(true).defaultValue(SignAlgorithm.HMAC_SHA256).build());
        options.add(FilterOption.builder().name("签名头").key("headerName").type(String.class).description("签名所在头或参数名").required(true).defaultValue(DEFAULT_SIGN_HEADER).build());
        options.add(FilterOption.builder().name("时间戳头").key("timestampHeader").type(String.class).description("时间戳头名").required(false).defaultValue(DEFAULT_TS_HEADER).build());
        options.add(FilterOption.builder().name("随机串头").key("nonceHeader").type(String.class).description("随机串头名").required(false).defaultValue(DEFAULT_NONCE_HEADER).build());
        options.add(FilterOption.builder().name("AppId头").key("appIdHeader").type(String.class).description("AppId头名").required(false).defaultValue(DEFAULT_APP_HEADER).build());
        options.add(FilterOption.builder().name("包含头").key("includeHeaders").type(Set.class).description("参与签名的请求头名称集合").required(false).build());
        options.add(FilterOption.builder().name("包含Body").key("includeBody").type(Boolean.class).description("请求体是否参与签名").required(false).defaultValue(false).build());
        options.add(FilterOption.builder().name("最大时差(s)").key("maxSkewSeconds").type(Long.class).description("允许客户端时间与服务器时间的最大差值").required(false).defaultValue(300L).build());
        options.add(FilterOption.builder().name("需要时间戳").key("requireTimestamp").type(Boolean.class).description("是否校验时间戳").required(false).defaultValue(true).build());
        options.add(FilterOption.builder().name("需要随机串").key("requireNonce").type(Boolean.class).description("是否校验随机串").required(false).defaultValue(true).build());
        options.add(FilterOption.builder().name("密钥映射").key("secretProvider").type(Map.class).description("appId->secret 映射").required(false).build());
        return options;
    }

    public enum SignAlgorithm { HMAC_SHA256, MD5 }

    @Data
    public static class SignConfig {
        private boolean enabled = true;
        private SignAlgorithm algorithm = SignAlgorithm.HMAC_SHA256;
        private String headerName = DEFAULT_SIGN_HEADER;
        private String timestampHeader = DEFAULT_TS_HEADER;
        private String nonceHeader = DEFAULT_NONCE_HEADER;
        private String appIdHeader = DEFAULT_APP_HEADER;
        private boolean includeBody = false;
        private Set<String> includeHeaders;
        private long maxSkewSeconds = 300L;
        private boolean requireTimestamp = true;
        private boolean requireNonce = true;
        private Map<String, String> secretProvider; // appId -> secret
    }
}


