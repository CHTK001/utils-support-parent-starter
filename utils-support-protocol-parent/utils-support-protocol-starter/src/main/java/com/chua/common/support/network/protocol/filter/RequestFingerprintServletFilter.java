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
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.hash.Hasher;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 请求指纹过滤器
 * <p>
 * - 基于请求关键信息计算指纹（method/path/headers/params/body）
 * - 将指纹写入响应头（默认 X-Request-Fingerprint）
 * - 支持在有效期内去重（可选拦截重复请求）
 * - 支持热重载（配置更新后立即生效）
 *
 * @author CH
 * @since 2025-08-15
 */
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Data
@Spi("requestFingerprint")
@SpiDescribe(value = "请求指纹过滤器")
public class RequestFingerprintServletFilter extends UpgradeServletFilter<RequestFingerprintServletFilter.FingerprintConfig> {

    private final Map<String, Long> fpExpire = new ConcurrentHashMap<>();

    public RequestFingerprintServletFilter() {
        super("RequestFingerprintServletFilter");
        FingerprintConfig cfg = new FingerprintConfig();
        upgradeConfigObject(cfg);
    }

    @Override
    protected void doFilterWithConfigObject(ServletRequest request, ServletResponse response, ServletFilterChain chain, FingerprintConfig config) throws Exception {
        // 未启用则放行
        if (!config.enabled) {
            chain.doFilter(request, response);
            return;
        }

        // 计算指纹
        String fp = computeFingerprint(request, config);
        if (fp == null) {
            chain.doFilter(request, response);
            return;
        }

        // 去重拦截
        long now = System.currentTimeMillis();
        long ttl = Math.max(0, config.validityMs);
        Long until = fpExpire.get(fp);
        if (until != null && until >= now) {
            if (config.rejectDuplicate) {
                response.setStatusCode(409);
                response.setStatusMessage("Conflict");
                response.setContentType("application/json");
                response.addHeader("X-Blocked-Reason", "DUPLICATE_REQUEST");
                response.addHeader(config.headerName, fp);
                response.setBodyString("{\"error\":\"Duplicate request\"}");
                response.setTerminateEarly(true);
                return;
            }
        } else {
            // 写入新的有效期
            if (ttl > 0) {
                fpExpire.put(fp, now + ttl);
            }
        }

        // 响应头写入指纹
        response.addHeader(config.headerName, fp);
        chain.doFilter(request, response);

        // 简单清理，按概率触发
        if ((now & 0xFF) == 0) {
            cleanup();
        }
    }

    /**
     * 清理过期指纹
     */
    private void cleanup() {
        long now = System.currentTimeMillis();
        fpExpire.entrySet().removeIf(e -> e.getValue() < now);
    }

    /**
     * 计算请求指纹
     *
     * @param req 请求
     * @param cfg 配置
     * @return 指纹字符串（十六进制）
     */
    private String computeFingerprint(ServletRequest req, FingerprintConfig cfg) {
        try {
            HashFunction hf = selectHash(cfg.algorithm);
            Hasher hasher = hf.newHasher();
            if (cfg.salt != null) {
                hasher.putString(cfg.salt, StandardCharsets.UTF_8);
            }
            if (cfg.includeMethod) {
                hasher.putString(nullToEmpty(req.getMethod()), StandardCharsets.UTF_8);
            }
            if (cfg.includePath) {
                hasher.putString(nullToEmpty(req.getPath()), StandardCharsets.UTF_8);
            }

            if (cfg.includeParams) {
                Map<String, String[]> pm = req.getParameterMap();
                if (pm != null) {
                    pm.keySet().stream().sorted().forEach(k -> {
                        hasher.putString(k, StandardCharsets.UTF_8);
                        String[] vs = pm.get(k);
                        if (vs != null) {
                            for (String v : vs) {
                                hasher.putString(nullToEmpty(v), StandardCharsets.UTF_8);
                            }
                        }
                    });
                }
            }

            if (cfg.includeHeaders != null && !cfg.includeHeaders.isEmpty() && req.getHeaders() != null) {
                List<String> keys = new ArrayList<>(cfg.includeHeaders);
                keys.sort(String::compareTo);
                for (String k : keys) {
                    String v = req.getHeaders().getFirst(k);
                    if (v != null) {
                        hasher.putString(k, StandardCharsets.UTF_8).putString(v, StandardCharsets.UTF_8);
                    }
                }
            }

            if (cfg.includeBody && req.getBody() != null) {
                hasher.putBytes(req.getBody());
            }

            return hasher.hash().toString();
        } catch (Exception e) {
            log.warn("计算请求指纹失败", e);
            return null;
        }
    }

    /**
     * 选择哈希算法
     *
     * @param algorithm 算法名称
     * @return 哈希函数
     */
    private HashFunction selectHash(String algorithm) {
        if (algorithm == null) {
            return Hashing.murmur3_128();
        }
        String a = algorithm.replace("-", "").toLowerCase(Locale.ROOT);
        switch (a) {
            case "sha256": return Hashing.sha256();
            case "sha1": return Hashing.sha1();
            case "md5": return Hashing.md5();
            case "murmur3_32": return Hashing.murmur3_32();
            case "murmur332": return Hashing.murmur3_32();
            case "murmur3_128": return Hashing.murmur3_128();
            case "murmur3128": return Hashing.murmur3_128();
            default: return Hashing.murmur3_128();
        }
    }

    /**
     * null 安全转换
     *
     * @param s 字符串
     * @return 非空字符串
     */
    private String nullToEmpty(String s) { return s == null ? "" : s; }

    @Override
    protected boolean validateConfigObject(FingerprintConfig config) { return config != null && config.headerName != null; }

    @Override
    protected void onConfigurationObjectChanged(FingerprintConfig oldConfig, FingerprintConfig newConfig) {
        // 热重载：当有效期或策略变更时，清理缓存更安全
        fpExpire.clear();
        log.info("RequestFingerprint config updated. cache cleared");
    }

    @Override
    public String getFilterName() { return "RequestFingerprintServletFilter"; }

    @Override
    public int getOrder() { return 40; }

    @Override
    public String getDescription() {
        FingerprintConfig c = getConfigurationObject();
        return String.format(Locale.ROOT, "请求指纹 (v%d) enabled=%s ttl=%d rejectDuplicate=%s",
                getConfigVersion(), c != null && c.enabled, c != null ? c.validityMs : 0L, c != null && c.rejectDuplicate);
    }

    @Override
    public List<FilterOption> getFilterOptions() {
        List<FilterOption> options = new ArrayList<>();
        options.add(FilterOption.builder().name("启用状态").key("enabled").type(Boolean.class).description("是否启用").required(true).defaultValue(false).build());
        options.add(FilterOption.builder().name("响应头名").key("headerName").type(String.class).description("写入指纹的响应头").required(true).defaultValue("X-Request-Fingerprint").build());
        options.add(FilterOption.builder().name("算法").key("algorithm").type(String.class).description("摘要算法: SHA-256/MD5 等").required(true).defaultValue("SHA-256").build());
        options.add(FilterOption.builder().name("盐").key("salt").type(String.class).description("可选盐").required(false).build());
        options.add(FilterOption.builder().name("包含Method").key("includeMethod").type(Boolean.class).description("指纹包含HTTP方法").required(false).defaultValue(true).build());
        options.add(FilterOption.builder().name("包含Path").key("includePath").type(Boolean.class).description("指纹包含路径").required(false).defaultValue(true).build());
        options.add(FilterOption.builder().name("包含Params").key("includeParams").type(Boolean.class).description("指纹包含参数").required(false).defaultValue(true).build());
        options.add(FilterOption.builder().name("包含Headers").key("includeHeaders").type(Set.class).description("参与指纹的头名集合").required(false).build());
        options.add(FilterOption.builder().name("包含Body").key("includeBody").type(Boolean.class).description("指纹包含请求体").required(false).defaultValue(false).build());
        options.add(FilterOption.builder().name("有效期ms").key("validityMs").type(Long.class).description("指纹有效期(去重窗口)").required(true).defaultValue(60000L).build());
        options.add(FilterOption.builder().name("重复拦截").key("rejectDuplicate").type(Boolean.class).description("在有效期内遇到相同指纹是否拦截").required(false).defaultValue(false).build());
        return options;
    }

    @Data
    public static class FingerprintConfig {
        private boolean enabled = false;
        private String headerName = "X-Request-Fingerprint";
        private String algorithm = "SHA-256";
        private String salt;
        private boolean includeMethod = true;
        private boolean includePath = true;
        private boolean includeParams = true;
        private Set<String> includeHeaders = new LinkedHashSet<>();
        private boolean includeBody = false;
        private long validityMs = 60_000;
        private boolean rejectDuplicate = false;
    }
}


