package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.server.FilterOption;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import com.chua.common.support.network.protocol.server.UpgradeServletFilter;
import com.chua.common.support.core.spi.ServiceProvider;
import com.chua.common.support.resilience.rate.RateLimiterProvider;
import com.chua.common.support.resilience.rate.RateLimiterSetting;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * IP级别限流Servlet过滤器
 * <p>
 * 对每个IP地址进行独立的请求频率限制，防止单个IP的恶意请求影响服务器性能。
 * 支持多种限流算法和灵活的配置选项。
 * 支持热重载配置更新。
 *
 * @author CH
 * @since 2024/7/9
 */
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Data
@Spi("ipRateLimit")
@SpiDescribe(value = "IP级别限流过滤器")
public class IPRateLimitServletFilter extends UpgradeServletFilter<IPRateLimitServletFilter.IPRateLimitConfig> {

    /**
     * 默认尝试获取令牌的等待时间（毫秒）
     */
    private static final long TRY_ACQUIRE_TIMEOUT_MS = 0L;

    /**
     * 清理触发频率（每处理 N 次请求尝试清理一次）
     */
    private static final long CLEANUP_EVERY_N_CALLS = 1024L;

    /**
     * 每个IP的限流器映射
     */
    private final ConcurrentMap<String, RateLimiterProvider> ipRateLimiters = new ConcurrentHashMap<>();

    /**
     * 每个IP的最后访问时间（清理过期的限流器）
     */
    private final ConcurrentMap<String, Long> lastAccessTimes = new ConcurrentHashMap<>();

    /**
     * 调用计数（用于低成本触发清理）
     */
    private final AtomicLong calls = new AtomicLong(0);
    /**
     * 白名单IP列表
     */
    private Set<String> whitelistIps = new LinkedHashSet<>();
    /**
     * 黑名单IP列表
     */
    private Set<String> blacklistIps = new LinkedHashSet<>();
    /**
     * CIDR级别限流配置
     */
    private Map<String, Double> cidrRateConfigs = new ConcurrentHashMap<>();
    /**
     * 每个IP的限流配置
     */
    private Map<String, Double> ipRateConfigs = new ConcurrentHashMap<>();

    /**
     * 构造函数
     */
    public IPRateLimitServletFilter() {
        super("IPRateLimitFilter");

        // 设置默认配置
        IPRateLimitConfig defaultConfig = new IPRateLimitConfig();
        defaultConfig.setEnabled(true);
        defaultConfig.setDefaultRequestsPerSecond(100);
        defaultConfig.setLimiterType("token");
        defaultConfig.setLimiterExpirationMs(300000L);
        defaultConfig.setBlacklistIps(blacklistIps);
        defaultConfig.setWhitelistIps(whitelistIps);

        upgradeConfigObject(defaultConfig);
    }

    @Override
    protected void doFilterWithConfigObject(ServletRequest request, ServletResponse response,
            ServletFilterChain chain, IPRateLimitConfig config) throws Exception {

        if (!config.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        // 获取客户端IP
        String clientIp = request.findClientIp();
        if (clientIp == null || clientIp.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("无法获取客户端IP地址，跳过限流检查");
            }
            chain.doFilter(request, response);
            return;
        }

        // 检查白名单
        if (config.getWhitelistIps() != null && config.getWhitelistIps().contains(clientIp)) {
            if (log.isDebugEnabled()) {
                log.debug("IP在白名单中，跳过限流: {}", clientIp);
            }
            chain.doFilter(request, response);
            return;
        }

        // 检查黑名单
        if (config.getBlacklistIps() != null && config.getBlacklistIps().contains(clientIp)) {
            log.warn("IP在黑名单中，拒绝请求: {}", clientIp);
            handleBlacklistRejection(request, response, clientIp);
            return;
        }

        // 获取IP的限流配置
        double rateLimit = getIpRateLimit(clientIp, config);

        // 获取或创建限流器
        RateLimiterProvider limiter = getOrCreateRateLimiter(clientIp, rateLimit, config);

        // 更新最后访问时间
        long now = System.currentTimeMillis();
        lastAccessTimes.put(clientIp, now);

        // 低成本触发过期清理，避免内存无限增长
        tryCleanupExpiredLimiters(config, now);

        // 尝试获取令牌
        boolean acquired;
        try {
            acquired = limiter.tryAcquire(TRY_ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            // 限流器异常时不阻塞主链路，保证可用性
            log.warn("[协议][限流] IP限流器执行异常，放行请求: ip={}", clientIp, ex);
            chain.doFilter(request, response);
            return;
        }

        if (acquired) {
            if (log.isDebugEnabled()) {
                log.debug("IP限流检查通过: {} -> 速率: {}/s", clientIp, rateLimit);
            }
            chain.doFilter(request, response);
        } else {
            // 限流是高频路径，避免在生产环境刷屏；仅 debug 输出细节
            if (log.isDebugEnabled()) {
                log.debug("IP限流触发: {} -> 速率: {}/s", clientIp, rateLimit);
            }
            handleRateLimitExceeded(request, response, clientIp, rateLimit);
        }
    }

    @Override
    protected boolean validateConfigObject(IPRateLimitConfig config) {
        if (config == null) {
            log.error("配置对象不能为null");
            return false;
        }

        if (config.getDefaultRequestsPerSecond() <= 0) {
            log.error("默认请求速率必须大于0: {}", config.getDefaultRequestsPerSecond());
            return false;
        }

        if (config.getLimiterExpirationMs() < 0) {
            log.error("限流器过期时间不能为负数: {}", config.getLimiterExpirationMs());
            return false;
        }

        // 验证IP级别配置
        if (config.getIpRateConfigs() != null) {
            for (Map.Entry<String, Double> entry : config.getIpRateConfigs().entrySet()) {
                if (entry.getValue() <= 0) {
                    log.error("IP限流速率必须大于0: {} -> {}", entry.getKey(), entry.getValue());
                    return false;
                }
            }
        }

        // 验证CIDR级别配置
        if (config.getCidrRateConfigs() != null) {
            for (Map.Entry<String, Double> entry : config.getCidrRateConfigs().entrySet()) {
                if (entry.getValue() <= 0) {
                    log.error("CIDR限流速率必须大于0: {} -> {}", entry.getKey(), entry.getValue());
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    protected void onConfigurationObjectChanged(IPRateLimitConfig oldConfig, IPRateLimitConfig newConfig) {
        log.info("IP限流配置更新:");
        log.info("  启用状态: {} -> {}",
                oldConfig != null ? oldConfig.isEnabled() : "null",
                newConfig != null ? newConfig.isEnabled() : "null");
        log.info("  默认速率: {} -> {}",
                oldConfig != null ? oldConfig.getDefaultRequestsPerSecond() : "null",
                newConfig != null ? newConfig.getDefaultRequestsPerSecond() : "null");

        if (newConfig != null) {
            log.info("  IP配置数量: {}",
                    newConfig.getIpRateConfigs() != null ? newConfig.getIpRateConfigs().size() : 0);
            log.info("  CIDR配置数量: {}",
                    newConfig.getCidrRateConfigs() != null ? newConfig.getCidrRateConfigs().size() : 0);
            log.info("  白名单数量: {}",
                    newConfig.getWhitelistIps() != null ? newConfig.getWhitelistIps().size() : 0);
            log.info("  黑名单数量: {}",
                    newConfig.getBlacklistIps() != null ? newConfig.getBlacklistIps().size() : 0);
        }

        // 清空限流器缓存，强制重新创建
        ipRateLimiters.clear();
        lastAccessTimes.clear();
        log.info("IP限流器缓存已清空，将使用新配置重新创建");
    }

    @Override
    public String getFilterName() {
        return "IPRateLimitServletFilter";
    }

    @Override
    public int getOrder() {
        return 10; // 高优先级，在其他过滤器之前执行
    }

    @Override
    public String getDescription() {
        IPRateLimitConfig config = getConfigurationObject();
        return String.format("IP限流过滤器 (@version  %d) - 启用: %s, 默认速率: %.1f/s, 缓存: %d个IP",
                getConfigVersion(),
                config != null ? config.isEnabled() : "unknown",
                config != null ? config.getDefaultRequestsPerSecond() : 0.0,
                ipRateLimiters.size());
    }


    /**
     * 获取IP的限流配置
     */
    public double getIpRateLimit(String clientIp, IPRateLimitConfig config) {
        // 检查IP级别配置
        if (config.getIpRateConfigs() != null) {
            Double ipRate = config.getIpRateConfigs().get(clientIp);
            if (ipRate != null) {
                return ipRate;
            }
        }

        // 检查CIDR级别配置
        if (config.getCidrRateConfigs() != null) {
            for (Map.Entry<String, Double> entry : config.getCidrRateConfigs().entrySet()) {
                if (isIpInCidr(clientIp, entry.getKey())) {
                    return entry.getValue();
                }
            }
        }

        // 返回默认配置
        return config.getDefaultRequestsPerSecond();
    }

    /**
     * 添加黑名单IP
     */
    public void addBlacklist(String ip) {
        blacklistIps.add(ip);
    }

    /**
     * 添加白名单IP
     */
    public void addWhitelist(String ip) {
        whitelistIps.add(ip);
    }

    /**
     * 获取或创建限速器
     */
    public RateLimiterProvider getOrCreateRateLimiter(String clientIp, double rateLimit, String rateType) {
        IPRateLimitConfig config = new IPRateLimitConfig();
        config.setLimiterType(rateType);
        return getOrCreateRateLimiter(clientIp, rateLimit, config);
    }
    /**
     * 获取或创建限流器
     */
    public RateLimiterProvider getOrCreateRateLimiter(String clientIp, double rateLimit, IPRateLimitConfig config) {
        return ipRateLimiters.computeIfAbsent(clientIp, ip -> {
            if (log.isDebugEnabled()) {
                log.debug("为IP创建限流器: {} -> 类型: {}, 速率: {}/s",
                        ip, config.getLimiterType(), rateLimit);
            }

            return ServiceProvider.of(RateLimiterProvider.class)
                    .getNewExtension(config.getLimiterType(), RateLimiterSetting
                        .builder()
                        .permitsPerSecond(Math.max(1, (int) Math.ceil(rateLimit)))
                        .build()
                    );
        });
    }

    /**
     * 清理过期的限流器（低开销触发）
     *
     * @param config 配置
     * @param now    当前时间戳（毫秒）
     */
    private void tryCleanupExpiredLimiters(IPRateLimitConfig config, long now) {
        long c = calls.incrementAndGet();
        if (c % CLEANUP_EVERY_N_CALLS != 0) {
            return;
        }

        long expirationMs = Math.max(0, config.getLimiterExpirationMs());
        if (expirationMs <= 0) {
            return;
        }

        for (Map.Entry<String, Long> entry : lastAccessTimes.entrySet()) {
            String ip = entry.getKey();
            Long last = entry.getValue();
            if (last == null) {
                continue;
            }
            if (now - last > expirationMs) {
                lastAccessTimes.remove(ip);
                ipRateLimiters.remove(ip);
            }
        }
    }

    /**
     * 处理限流超出的情况
     */
    private void handleRateLimitExceeded(ServletRequest request, ServletResponse response,
            String clientIp, double rateLimit) {
        response.setStatusCode(429); // Too Many Requests
        response.setStatusMessage("Too Many Requests");
        response.setContentType("application/json");
        response.addHeader("Retry-After", "1");

        String jsonResponse = String.format(
                "{\"error\":\"Rate limit exceeded\",\"clientIp\":\"%s\",\"rateLimit\":%.1f,\"retryAfter\":1}",
                clientIp, rateLimit);
        response.setBodyString(jsonResponse);
        response.setTerminateEarly(true);

        // 发布REJECTED事件（限流）
        try {
            com.chua.common.support.network.protocol.event.ServletEventDispatcher.publishAsync(
                    com.chua.common.support.network.protocol.event.ServletEvent.builder()
                            .clientIp(clientIp)
                            .requestId(request.getRequestId())
                            .path(request.getPath())
                            .method(request.getMethod())
                            .duration(0L)
                            .status(com.chua.common.support.network.protocol.event.ServletRequestStatus.REJECTED)
                            .statusCode(429)
                            .terminated(true)
                            .build());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 获取当前活跃的IP数量
     */
    public int getActiveIpCount() {
        return ipRateLimiters.size();
    }

    /**
     * 重置指定IP的限流器
     */
    public void resetIpRateLimit(String ip) {
        RateLimiterProvider limiter = ipRateLimiters.get(ip);
        if (limiter != null) {
            // 移除并重新创建限流器来实现重置
            ipRateLimiters.remove(ip);
            lastAccessTimes.remove(ip);
            log.info("重置IP限流器: {}", ip);
        }
    }

    /**
     * 清除所有IP的限流器
     */
    public void clearAllRateLimiters() {
        ipRateLimiters.clear();
        lastAccessTimes.clear();
        log.info("清除所有IP限流器");
    }

    /**
     * 清除指定IP的限流器
     */
    public void clearAddress() {
        blacklistIps.clear();
        whitelistIps.clear();
    }

    public void clear() {
        clearAddress();
        clearAllRateLimiters();
    }
    /**
     * 处理黑名单拒绝的情况
     */
    public static void handleBlacklistRejection(ServletRequest request, ServletResponse response, String clientIp) {
        log.warn("IP {} 在黑名单中，拒绝请求: {} {}", clientIp, request.getMethod(), request.getPath());

        response.setStatusCode(403);
        response.setStatusMessage("Forbidden");
        response.setContentType("application/json");
        response.setBodyString(String.format(
                "{\"error\":\"IP blocked\",\"message\":\"IP %s is in blacklist\",\"code\":403}",
                clientIp));

        response.addHeader("X-Blocked-Reason", "IP_BLACKLIST");

        // 发布REJECTED事件（黑名单）
        try {
            com.chua.common.support.network.protocol.event.ServletEventDispatcher.publishAsync(
                    com.chua.common.support.network.protocol.event.ServletEvent.builder()
                            .clientIp(clientIp)
                            .requestId(request.getRequestId())
                            .path(request.getPath())
                            .method(request.getMethod())
                            .duration(0L)
                            .status(com.chua.common.support.network.protocol.event.ServletRequestStatus.REJECTED)
                            .statusCode(403)
                            .terminated(true)
                            .build());
        } catch (Throwable ignored) {
        }
    }


    /**
     * 检查IP是否在CIDR范围内
     */
    private boolean isIpInCidr(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) {
                return false;
            }

            String networkIp = parts[0];
            int prefixLength = Integer.parseInt(parts[1]);

            long ipLong = ipToLong(ip);
            long networkLong = ipToLong(networkIp);
            long mask = (-1L << (32 - prefixLength));

            return (ipLong & mask) == (networkLong & mask);
        } catch (Exception e) {
            log.warn("CIDR匹配失败: {} in {}", ip, cidr, e);
            return false;
        }
    }

    /**
     * 将IP地址转换为长整型
     */
    private long ipToLong(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid IP address: " + ip);
        }

        long result = 0;
        for (int i = 0; i < 4; i++) {
            result = (result << 8) + Integer.parseInt(parts[i]);
        }
        return result;
    }

    @Override
    public boolean supportProtocol(String protocol) {
        return true;
    }

    @Override
    public List<FilterOption> getFilterOptions() {
        List<FilterOption> options = new ArrayList<>();

        // 基本配置
        options.add(FilterOption.builder()
                .name("启用状态")
                .key("enabled")
                .type(Boolean.class)
                .description("是否启用IP级别限流")
                .required(true)
                .defaultValue(true)
                .build());

        options.add(FilterOption.builder()
                .name("默认请求速率")
                .key("defaultRequestsPerSecond")
                .type(Double.class)
                .description("默认的每秒允许请求数")
                .required(true)
                .defaultValue(100.0)
                .validation("必须大于0")
                .build());

        options.add(FilterOption.builder()
                .name("限流器类型")
                .key("limiterType")
                .type(String.class)
                .description("限流算法类型，如：token（令牌桶）")
                .required(true)
                .defaultValue("token")
                .build());

        options.add(FilterOption.builder()
                .name("限流器过期时间")
                .key("limiterExpirationMs")
                .type(Long.class)
                .description("限流器的过期时间（毫秒）")
                .required(true)
                .defaultValue(300000L)
                .validation("必须大于0")
                .build());

        // IP配置
        options.add(FilterOption.builder()
                .name("IP限流配置")
                .key("ipRateConfigs")
                .type(Map.class)
                .description("特定IP的限流配置 (IP -> 每秒请求数)")
                .required(false)
                .build());

        options.add(FilterOption.builder()
                .name("CIDR限流配置")
                .key("cidrRateConfigs")
                .type(Map.class)
                .description("CIDR网段的限流配置 (CIDR -> 每秒请求数)")
                .required(false)
                .build());

        // 黑白名单
        options.add(FilterOption.builder()
                .name("白名单IP")
                .key("whitelistIps")
                .type(Set.class)
                .description("白名单IP集合，这些IP不受限流限制")
                .required(false)
                .build());

        options.add(FilterOption.builder()
                .name("黑名单IP")
                .key("blacklistIps")
                .type(Set.class)
                .description("黑名单IP集合，这些IP将被直接拒绝")
                .required(false)
                .build());

        return options;
    }

    /**
     * IP限流配置类
     */
    @Data
    public static class IPRateLimitConfig {
        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * 默认每秒允许的请求数
         */
        private double defaultRequestsPerSecond = 100.0;

        /**
         * 限流器类型
         */
        private String limiterType = "token";

        /**
         * 限流器过期时间（毫秒）
         */
        private long limiterExpirationMs = 300000L;

        /**
         * IP级别配置映射 (IP -> 每秒允许请求数)
         */
        private Map<String, Double> ipRateConfigs;

        /**
         * CIDR级别配置映射 (CIDR -> 每秒允许请求数)
         */
        private Map<String, Double> cidrRateConfigs;

        /**
         * 白名单IP集合
         */
        private Set<String> whitelistIps;

        /**
         * 黑名单IP集合
         */
        private Set<String> blacklistIps;
    }
}
