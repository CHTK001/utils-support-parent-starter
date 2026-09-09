package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.time.date.unit.DateUnit;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.server.FilterOption;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import com.chua.common.support.network.protocol.server.UpgradeServletFilter;
import com.chua.common.support.core.spi.ServiceProvider;
import com.chua.common.support.resilience.rate.RateLimiterProvider;
import com.chua.common.support.resilience.rate.RateLimiterSetting;
import com.chua.common.support.math.unit.TimeSize;
// 注意：此处不直接导入 com.chua.common.support.math.unit.TimeUnit，避免与 java.util.concurrent.TimeUnit 冲突
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * QPS 限流过滤器
 * <p>
 * 参考 IPRateLimitServletFilter，提供更通用的 QPS 配置：
 * <ul>
 *     <li>时间类型（秒/分/时/天）+ 阈值：支持 QPS/QPM/QPH/QPD 的统一配置</li>
 *     <li>拒绝策略：黑名单（永久/限时）或限流惩罚（永久/限时）</li>
 *     <li>白名单/黑名单（初始）</li>
 * </ul>
 * 触发限流时：
 * <ul>
 *     <li>BLACKLIST：将 IP 加入黑名单（永久或限时），当前请求返回 403</li>
 *     <li>LIMIT：降低该 IP 的限流速率到惩罚阈值（永久或限时），当前请求返回 429</li>
 * </ul>
 *
 * @author CH
 * @since 2025-08-15
 */
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Data
@Spi("qpsRateLimit")
@SpiDescribe(value = "通用QPS限流过滤器")
public class QpsRateLimitServletFilter extends UpgradeServletFilter<QpsRateLimitServletFilter.QpsConfig> {

    /**
     * 每个IP的限流器
     */
    private final ConcurrentMap<String, RateLimiterProvider> keyLimiters = new ConcurrentHashMap<>();

    /**
     * 惩罚性限流（限流策略）结束时间：key -> untilTs
     */
    private final ConcurrentMap<String, Long> throttleUntil = new ConcurrentHashMap<>();

    /**
     * 惩罚性限流速率覆盖（限流策略）：key -> ratePerSecond
     */
    private final ConcurrentMap<String, Double> throttleRateOverrides = new ConcurrentHashMap<>();

    /**
     * 动态黑名单（含过期时间）：key -> untilTs（Long.MAX_VALUE 表示永久）
     */
    private final ConcurrentMap<String, Long> blacklistUntil = new ConcurrentHashMap<>();

    /**
     * 记录最后访问时间（清理）
     */
    private final ConcurrentMap<String, Long> lastAccessTimes = new ConcurrentHashMap<>();

    /**
     * 构造
     */
    public QpsRateLimitServletFilter() {
        super("QpsRateLimitFilter");

        QpsConfig defaultConfig = new QpsConfig();
        defaultConfig.setEnabled(true);
        defaultConfig.setLimiterType("token");
        defaultConfig.setThreshold(100.0);
        defaultConfig.setTimeUnit(DateUnit.SECOND);
        defaultConfig.setRejectStrategy(RejectStrategy.LIMIT);
        defaultConfig.setPenaltyDuration("0S");
        defaultConfig.setPenaltyPermanent(false);
        defaultConfig.setPenaltyThreshold(null); // 未设置则默认与 threshold 相同
        defaultConfig.setWhitelistIps(new LinkedHashSet<>());
        defaultConfig.setBlacklistIps(new LinkedHashSet<>());

        upgradeConfigObject(defaultConfig);
    }

    @Override
    protected void doFilterWithConfigObject(ServletRequest request, ServletResponse response,
                                            ServletFilterChain chain, QpsConfig config) throws Exception {

        if (!config.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        // 提取 key（默认使用 IP）
        String key = request.findClientIp();
        if (key == null || key.isEmpty()) {
            log.warn("无法获取客户端IP地址，跳过QPS限流");
            chain.doFilter(request, response);
            return;
        }

        // 白名单
        if (inSet(key, config.getWhitelistIps())) {
            chain.doFilter(request, response);
            return;
        }

        // 初始黑名单（永久）
        if (inSet(key, config.getBlacklistIps())) {
            IPRateLimitServletFilter.handleBlacklistRejection(request, response, key);
            return;
        }

        // 动态黑名单（带过期）
        if (isInBlacklist(key)) {
            IPRateLimitServletFilter.handleBlacklistRejection(request, response, key);
            return;
        }

        // 计算基准速率（按秒）
        double baseRatePerSecond = toPerSecond(config.getThreshold(), config.getTimeUnit());

        // 惩罚限流（LIMIT）覆盖
        double effectiveRate = getEffectiveRateForKey(key, baseRatePerSecond, config);

        // 获取/创建限流器
        RateLimiterProvider limiter = getOrCreateLimiter(key, effectiveRate, config.getLimiterType());
        if (Math.abs(limiter.getRate() - effectiveRate) > 1e-9) {
            limiter.setRate(effectiveRate);
        }

        lastAccessTimes.put(key, System.currentTimeMillis());

        boolean acquired = limiter.tryAcquire(1, TimeUnit.SECONDS);
        if (acquired) {
            chain.doFilter(request, response);
            return;
        }

        // 限流触发：根据拒绝策略处理
        if (config.getRejectStrategy() == RejectStrategy.BLACKLIST) {
            long until = computePenaltyUntil(config);
            addToBlacklist(key, until);
            IPRateLimitServletFilter.handleBlacklistRejection(request, response, key);
            return;
        }

        // LIMIT 惩罚：降低速率，并在期限内生效
        double penaltyRate = toPerSecond(
                config.getPenaltyThreshold() == null ? config.getThreshold() : config.getPenaltyThreshold(),
                config.getTimeUnit());
        long until = computePenaltyUntil(config);
        throttleRateOverrides.put(key, penaltyRate);
        if (until == Long.MAX_VALUE) {
            throttleUntil.put(key, until);
        } else {
            throttleUntil.put(key, System.currentTimeMillis() + Math.max(0, until - System.currentTimeMillis()));
        }

        handleRateLimitExceeded(request, response, key, effectiveRate);
    }

    private boolean inSet(String key, Set<String> set) {
        return set != null && set.contains(key);
    }

    private boolean isInBlacklist(String key) {
        Long until = blacklistUntil.get(key);
        if (until == null) {
            return false;
        }
        if (until == Long.MAX_VALUE) {
            return true;
        }
        if (System.currentTimeMillis() <= until) {
            return true;
        }
        // 过期清理
        blacklistUntil.remove(key);
        return false;
    }

    private void addToBlacklist(String key, long untilTs) {
        blacklistUntil.put(key, untilTs);
        keyLimiters.remove(key);
        throttleUntil.remove(key);
        throttleRateOverrides.remove(key);
        log.warn("加入黑名单: {} -> {}", key, untilTs == Long.MAX_VALUE ? "永久" : (untilTs - System.currentTimeMillis()) + "ms");
    }

    private double getEffectiveRateForKey(String key, double baseRatePerSecond, QpsConfig config) {
        // 若存在惩罚期，则使用覆盖速率
        Long until = throttleUntil.get(key);
        if (until != null) {
            if (until == Long.MAX_VALUE || System.currentTimeMillis() <= until) {
                Double overrideRate = throttleRateOverrides.get(key);
                if (overrideRate != null && overrideRate > 0) {
                    return overrideRate;
                }
            } else {
                // 过期清理
                throttleUntil.remove(key);
                throttleRateOverrides.remove(key);
            }
        }
        return baseRatePerSecond;
    }

    private long computePenaltyUntil(QpsConfig config) {
        if (Boolean.TRUE.equals(config.getPenaltyPermanent())) {
            return Long.MAX_VALUE;
        }
        String penaltyDuration = config.getPenaltyDuration();
        if (penaltyDuration == null || penaltyDuration.trim().isEmpty()) {
            return System.currentTimeMillis();
        }
        TimeSize timeSize = com.chua.common.support.math.unit.TimeUnit.parse(penaltyDuration);
        return System.currentTimeMillis() + Math.max(0, timeSize.toMillis());
    }

    private RateLimiterProvider getOrCreateLimiter(String key, double ratePerSecond, String limiterType) {
        return keyLimiters.computeIfAbsent(key, k -> {
            if (log.isDebugEnabled()) {
                log.debug("创建限流器: {} -> 类型: {}, 速率: {}/s", k, limiterType, ratePerSecond);
            }
            return ServiceProvider.of(RateLimiterProvider.class)
                    .getNewExtension(limiterType, RateLimiterSetting
                        .builder()
                        .permitsPerSecond((int) ratePerSecond)
                        .build()
                    );
        });
    }

    private double toPerSecond(double threshold, DateUnit unit) {
        if (unit == null) {
            return threshold; // 默认按秒
        }
        switch (unit) {
            case SECOND:
                return threshold;
            case MINUTE:
                return threshold / 60.0;
            case HOUR:
                return threshold / 3600.0;
            case DAY:
                return threshold / 86400.0;
            default:
                // 其他单位以秒处理
                return threshold;
        }
    }

    private void handleRateLimitExceeded(ServletRequest request, ServletResponse response,
                                         String key, double rateLimit) {
        response.setStatusCode(429); // Too Many Requests
        response.setStatusMessage("Too Many Requests");
        response.setContentType("application/json");
        response.addHeader("Retry-After", "1");

        String jsonResponse = String.format(
                "{\"error\":\"Rate limit exceeded\",\"clientIp\":\"%s\",\"rateLimit\":%.1f,\"retryAfter\":1}",
                key, rateLimit);
        response.setBodyString(jsonResponse);
        response.setTerminateEarly(true);

        try {
            com.chua.common.support.network.protocol.event.ServletEventDispatcher.publishAsync(
                    com.chua.common.support.network.protocol.event.ServletEvent.builder()
                            .clientIp(key)
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

    @Override
    protected boolean validateConfigObject(QpsConfig config) {
        if (config == null) {
            log.error("配置对象不能为null");
            return false;
        }
        if (config.getThreshold() <= 0) {
            log.error("阈值必须大于0: {}", config.getThreshold());
            return false;
        }
        if (config.getPenaltyThreshold() != null && config.getPenaltyThreshold() <= 0) {
            log.error("惩罚阈值必须大于0: {}", config.getPenaltyThreshold());
            return false;
        }
        return true;
    }

    @Override
    protected void onConfigurationObjectChanged(QpsConfig oldConfig, QpsConfig newConfig) {
        keyLimiters.clear();
        log.info("QPS限流配置更新，已清空限流器缓存");
    }

    @Override
    public String getFilterName() {
        return "QpsRateLimitServletFilter";
    }

    @Override
    public int getOrder() {
        return 11; // 在 IP 限流之后执行，或根据需要调整
    }

    @Override
    public String getDescription() {
        QpsConfig cfg = getConfigurationObject();
        return String.format("QPS限流过滤器 (@version  %d) - 启用: %s, 阈值: %.1f/%s, 策略: %s",
                getConfigVersion(),
                cfg != null && cfg.isEnabled(),
                cfg != null ? cfg.getThreshold() : 0.0,
                cfg != null && cfg.getTimeUnit() != null ? cfg.getTimeUnit().name() : "SECOND",
                cfg != null && cfg.getRejectStrategy() != null ? cfg.getRejectStrategy().name() : "LIMIT");
    }

    @Override
    public List<FilterOption> getFilterOptions() {
        List<FilterOption> options = new ArrayList<>();

        options.add(FilterOption.builder()
                .name("启用状态")
                .key("enabled")
                .type(Boolean.class)
                .description("是否启用QPS限流")
                .required(true)
                .defaultValue(true)
                .build());

        options.add(FilterOption.builder()
                .name("限流器类型")
                .key("limiterType")
                .type(String.class)
                .description("限流算法类型：token/sliding/guava")
                .required(true)
                .defaultValue("token")
                .build());

        options.add(FilterOption.builder()
                .name("阈值")
                .key("threshold")
                .type(Double.class)
                .description("指定时间单位内允许的最大请求数")
                .required(true)
                .defaultValue(100.0)
                .build());

        options.add(FilterOption.builder()
                .name("时间单位")
                .key("timeUnit")
                .type(DateUnit.class)
                .description("阈值对应的时间单位：SECOND/MINUTE/HOUR/DAY")
                .required(true)
                .defaultValue(DateUnit.SECOND)
                .build());

        options.add(FilterOption.builder()
                .name("拒绝策略")
                .key("rejectStrategy")
                .type(RejectStrategy.class)
                .description("超限后的处理：BLACKLIST=加入黑名单，LIMIT=降低速率限流")
                .required(true)
                .defaultValue(RejectStrategy.LIMIT)
                .build());

        options.add(FilterOption.builder()
                .name("惩罚时间")
                .key("penaltyDuration")
                .type(String.class)
                .description("惩罚时长，支持 30S/5MIN/1H/1D 等格式")
                .required(false)
                .defaultValue("0S")
                .build());

        options.add(FilterOption.builder()
                .name("惩罚是否永久")
                .key("penaltyPermanent")
                .type(Boolean.class)
                .description("是否永久生效（与惩罚时间互斥，优先永久）")
                .required(false)
                .defaultValue(false)
                .build());

        options.add(FilterOption.builder()
                .name("惩罚阈值")
                .key("penaltyThreshold")
                .type(Double.class)
                .description("LIMIT策略下的惩罚阈值，未设置则与阈值相同")
                .required(false)
                .build());

        options.add(FilterOption.builder()
                .name("白名单IP")
                .key("whitelistIps")
                .type(Set.class)
                .description("白名单IP集合")
                .required(false)
                .build());

        options.add(FilterOption.builder()
                .name("黑名单IP")
                .key("blacklistIps")
                .type(Set.class)
                .description("初始黑名单IP集合（永久）")
                .required(false)
                .build());

        return options;
    }

    @Override
    public boolean supportProtocol(String protocol) {
        return true;
    }

    /**
     * 拒绝策略
     */
    public enum RejectStrategy {
        BLACKLIST,
        LIMIT
    }

    /**
     * 配置对象
     */
    @Data
    public static class QpsConfig {
        /** 是否启用 */
        private boolean enabled = true;
        /** 限流器类型：token/sliding/guava */
        private String limiterType = "token";
        /** 阈值（与 timeUnit 共同定义） */
        private double threshold = 100.0;
        /** 阈值对应的时间单位 */
        private DateUnit timeUnit = DateUnit.SECOND;
        /** 拒绝策略 */
        private RejectStrategy rejectStrategy = RejectStrategy.LIMIT;
        /** 惩罚时长（如 30S/5MIN/1H），为空或0S表示无时长，仅当前请求 */
        private String penaltyDuration = "0S";
        /** 是否永久惩罚 */
        private Boolean penaltyPermanent = false;
        /** 惩罚阈值（LIMIT有效），未设置则与 threshold 相同 */
        private Double penaltyThreshold;
        /** 白名单 */
        private Set<String> whitelistIps;
        /** 初始黑名单（永久） */
        private Set<String> blacklistIps;
    }
}


