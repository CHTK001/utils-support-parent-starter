package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.matcher.PathMatcher;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.server.FilterOption;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import com.chua.common.support.network.protocol.server.UpgradeServletFilter;
import com.chua.common.support.core.spi.ServiceProvider;
import com.chua.common.support.resilience.rate.RateLimiterProvider;
import com.chua.common.support.resilience.rate.RateLimiterSetting;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.chua.common.support.core.constant.NameConstant.DEFAULT;
import static com.chua.common.support.network.protocol.filter.IPRateLimitServletFilter.handleBlacklistRejection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 地址级别限流Servlet过滤器
 * <p>
 * 对不同的请求路径/URL进行独立的限流控制，支持：
 * 1. 精确路径匹配限流
 * 2. 通配符路径匹配限流
 * 3. 正则表达式路径匹配限流
 * 4. 不同路径的不同限流策略
 * 5. 支持热重载配置更新
 *
 * @author CH
 * @since 2024/7/9
 */
@Slf4j
@Spi("addressRateLimit")
@SpiDescribe(value = "地址级别限流过滤器")
public class AddressRateLimitServletFilter extends UpgradeServletFilter<AddressRateLimitServletFilter.AddressRateLimitConfig> {

    /**
     * 路径限流器映射
     */
    private final ConcurrentMap<String, RateLimiterProvider> pathRateLimiters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PathRateLimitRule> pathRules = new ConcurrentHashMap<>();
    /**
     * 白名单IP列表
     */
    private final List<String> whitelistIps = new ArrayList<>();
    /**
     * 黑名单IP列表
     */
    private final List<String> blacklistIps = new ArrayList<>();

    /**
     * 构造函数
     */
    public AddressRateLimitServletFilter() {
        super("AddressRateLimitFilter");

        // 设置默认配置
        AddressRateLimitConfig defaultConfig = new AddressRateLimitConfig();
        defaultConfig.setEnabled(true);
        defaultConfig.setDefaultRule(new PathRateLimitRule(DEFAULT, "RATE_LIMIT", "token", 100, 100, TimeUnit.SECONDS));

        upgradeConfigObject(defaultConfig);
    }

    /**
     * 路径限流规则
     */
    public static class PathRateLimitRule {
        // Getters
        @Getter
        private final String pattern;
        private final Pattern regex;
        /**
         * 规则类型: RATE_LIMIT / WHITELIST / BLACKLIST
         */
        @Getter
        private final String ruleType;
        @Getter
        private final String limiterType;
        @Getter
        private final int requestsPerSecond;
        @Getter
        private final long capacity;
        @Getter
        private final TimeUnit timeUnit;
        private final boolean isRegex;
        private final boolean isWildcard;

        /**
         * 创建路径限流规则
         *
         * @param pattern           路径模式，支持精确匹配、通配符匹配(*)和正则表达式匹配(regex:开头)
         * @param limiterType       限流器类型，如"token"表示令牌桶算法
         * @param requestsPerSecond 每秒允许的请求数量
         * @param capacity          令牌桶容量或滑动窗口大小
         * @param timeUnit          时间单位，如SECONDS、MINUTES等
         */
        public PathRateLimitRule(String pattern, String limiterType,
                                 int requestsPerSecond, long capacity, TimeUnit timeUnit) {
            this(pattern, "RATE_LIMIT", limiterType, requestsPerSecond, capacity, timeUnit);
        }

        /**
         * 创建路径限流规则（带规则类型）
         *
         * @param pattern           路径模式
         * @param ruleType          规则类型: RATE_LIMIT/WHITELIST/BLACKLIST
         * @param limiterType       限流器类型
         * @param requestsPerSecond QPS（限流时必填）
         * @param capacity          容量（限流时可选）
         * @param timeUnit          时间单位（限流时可选）
         */
        public PathRateLimitRule(String pattern, String ruleType, String limiterType,
                                 int requestsPerSecond, long capacity, TimeUnit timeUnit) {
            this.pattern = pattern;
            this.ruleType = ruleType == null ? "RATE_LIMIT" : ruleType;
            this.limiterType = limiterType != null ? limiterType : "token";
            this.requestsPerSecond = requestsPerSecond;
            this.capacity = capacity;
            this.timeUnit = timeUnit;

            // 判断是否为正则表达式（以regex:开头）
            if (pattern.startsWith("regex:")) {
                this.isRegex = true;
                this.isWildcard = false;
                this.regex = Pattern.compile(pattern.substring(6));
            } else if (pattern.contains("*") || pattern.contains("?")) {
                // 判断是否为通配符模式
                this.isRegex = false;
                this.isWildcard = true;
                // 将通配符转换为正则表达式
                String regexPattern = pattern.replace("*", ".*").replace("?", ".");
                this.regex = Pattern.compile(regexPattern);
            } else {
                // 精确匹配
                this.isRegex = false;
                this.isWildcard = false;
                this.regex = null;
            }
        }

        /**
         * 检查路径是否匹配当前规则
         *
         * @param path 要检查的请求路径
         * @return 如果路径匹配规则返回true，否则返回false
         */
        public boolean matches(String path) {
            if (isRegex || isWildcard) {
                return regex.matcher(path).matches();
            } else {
                return pattern.equals(path);
            }
        }

        public RateLimiterProvider createRateLimiter() {
            return ServiceProvider.of(RateLimiterProvider.class).getNewExtension(limiterType,
                RateLimiterSetting.builder()
                    .permitsPerSecond(requestsPerSecond)
                    .build()
            );
        }

        public boolean isRegex() {
            return isRegex;
        }

        public boolean isWildcard() {
            return isWildcard;
        }
    }

    /**
     * 执行地址级别限流过滤
     *
     * @param request  请求对象，包含请求路径等信息
     * @param response 响应对象，返回限流结果
     * @param chain    过滤器链，继续处理请求或终止处理
     * @throws Exception 处理过程中可能抛出的异常
     */
    @Override
    protected void doFilterWithConfigObject(ServletRequest request, ServletResponse response,
                                            ServletFilterChain chain, AddressRateLimitConfig config) throws Exception {

        if (!config.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getPath();
        if (path == null || path.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        // 查找匹配的限流规则
        PathRateLimitRule rule = findMatchingRule(path, config);
        if (rule == null) {
            // 没有匹配的规则，直接通过
            chain.doFilter(request, response);
            return;
        }
        // 获取客户端IP
        String requestURI = request.getRequestURI();
        // 检查白名单
        if (inListAddress(requestURI, whitelistIps)) {
            if (log.isDebugEnabled()) {
                log.debug("IP在白名单中，跳过限流: {}", requestURI);
            }
            chain.doFilter(request, response);
            return;
        }

        // 检查黑名单
        if (inListAddress(requestURI, blacklistIps)) {
            log.warn("IP在黑名单中，拒绝请求: {}", requestURI);
            handleBlacklistRejection(request, response, requestURI);
            return;
        }


        // RATE_LIMIT
        RateLimiterProvider rateLimiter = getOrCreateRateLimiter(rule.getPattern(), rule);
        if (rateLimiter.tryAcquire()) {
            chain.doFilter(request, response);
        } else {
            handleRateLimitExceeded(request, response, path, rule);
        }
    }

    /**
     * 处理名单
     *
     * @param requestURI  请求地址
     * @param listAddress 名单
     */
    private boolean inListAddress(String requestURI, List<String> listAddress) {
        for (String address : listAddress) {
            if (address.contains("*")) {
                return PathMatcher.INSTANCE.match(address, requestURI);
            }

            if (address.equals(requestURI)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean validateConfigObject(AddressRateLimitConfig config) {
        if (config == null) {
            log.error("配置对象不能为null");
            return false;
        }

        // 验证默认规则
        if (config.getDefaultRule() != null && !validateRule(config.getDefaultRule())) {
            return false;
        }

        // 验证路径规则
        if (config.getPathRules() != null) {
            for (PathRateLimitRule rule : config.getPathRules()) {
                if (!validateRule(rule)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * 验证单个规则
     */
    private boolean validateRule(PathRateLimitRule rule) {
        if (rule == null) {
            log.error("限流规则不能为null");
            return false;
        }

        // 规则类型校验
        String ruleType = rule.getRuleType();
        if (!"RATE_LIMIT".equalsIgnoreCase(ruleType) &&
                !"WHITELIST".equalsIgnoreCase(ruleType) &&
                !"BLACKLIST".equalsIgnoreCase(ruleType)) {
            log.error("无效的规则类型: {}，允许值: RATE_LIMIT/WHITELIST/BLACKLIST", ruleType);
            return false;
        }

        // 限流规则才校验 QPS/容量
        if ("RATE_LIMIT".equalsIgnoreCase(ruleType)) {
            if (rule.getRequestsPerSecond() <= 0) {
                log.error("限流速率必须大于0: {}", rule.getRequestsPerSecond());
                return false;
            }
            if (rule.getCapacity() <= 0) {
                log.error("限流容量必须大于0: {}", rule.getCapacity());
                return false;
            }
        }

        return true;
    }

    @Override
    protected void onConfigurationObjectChanged(AddressRateLimitConfig oldConfig, AddressRateLimitConfig newConfig) {
        log.info("地址限流配置更新:");
        log.info("  启用状态: {} -> {}",
                oldConfig != null ? oldConfig.isEnabled() : "null",
                newConfig != null ? newConfig.isEnabled() : "null");

        if (newConfig != null) {
            log.info("  路径规则数量: {}",
                    newConfig.getPathRules() != null ? newConfig.getPathRules().size() : 0);
            log.info("  默认规则: {}",
                    newConfig.getDefaultRule() != null ? newConfig.getDefaultRule().getPattern() : "null");
        }

        // 清空限流器缓存，强制重新创建
        pathRateLimiters.clear();
        log.info("地址限流器缓存已清空，将使用新配置重新创建");
    }

    @Override
    public String getFilterName() {
        return "AddressRateLimitServletFilter";
    }

    @Override
    public int getOrder() {
        return 15; // 在IP限流之后执行
    }

    @Override
    public String getDescription() {
        AddressRateLimitConfig config = getConfigurationObject();
        return String.format("地址限流过滤器 (@version  %d) - 启用: %s, 规则数: %d, 缓存: %d个路径",
                getConfigVersion(),
                config != null ? config.isEnabled() : "unknown",
                config != null && config.getPathRules() != null ? config.getPathRules().size() : 0,
                pathRateLimiters.size());
    }

    /**
     * 查找匹配的限流规则
     */
    private PathRateLimitRule findMatchingRule(String path, AddressRateLimitConfig config) {
        // 首先查找精确匹配
        if (config.getPathRules() != null) {
            for (PathRateLimitRule rule : config.getPathRules()) {
                if (rule.matches(path)) {
                    return rule;
                }
            }
        }

        // 如果没有匹配的规则，返回默认规则
        return config.getDefaultRule();
    }

    /**
     * 获取或创建指定路径的限流器
     */
    private RateLimiterProvider getOrCreateRateLimiter(String pattern, PathRateLimitRule rule) {
        return pathRateLimiters.computeIfAbsent(pattern, k -> rule.createRateLimiter());
    }

    /**
     * 处理限流超出的情况
     */
    private void handleRateLimitExceeded(ServletRequest request, ServletResponse response,
                                         String path, PathRateLimitRule rule) {
        log.warn("路径限流触发 - 路径: {}, 规则: {}", path, rule.getPattern());

        response.setStatusCode(429);
        response.setStatusMessage("Too Many Requests");
        response.setContentType("application/json");
        response.setBodyString(String.format(
                "{\"error\":\"Rate limit exceeded\",\"message\":\"Too many requests for path %s\",\"code\":429}",
                path));

        // 添加限流相关的响应头
        response.addHeader("X-RateLimit-Limit", String.valueOf((long) rule.getRequestsPerSecond()));
        response.addHeader("X-RateLimit-Remaining", "0");
        response.addHeader("X-RateLimit-Reset", String.valueOf(System.currentTimeMillis() + 1000));
        response.addHeader("X-RateLimit-Path", path);
        response.addHeader("Retry-After", "1");
    }

    /**
     * 解析路径规则配置
     * 格式：path1=type:rate:capacity:timeUnit;path2=type:rate:capacity:timeUnit;...
     */
    private void parsePathRulesConfig(String config) {
        String[] rules = config.split(";");
        for (String rule : rules) {
            String[] parts = rule.split("=", 2);
            if (parts.length == 2) {
                String path = parts[0].trim();
                String ruleConfig = parts[1].trim();
                try {
                    PathRateLimitRule pathRule = parseRuleConfig(path, ruleConfig);
                    pathRules.put(path, pathRule);
                    if (log.isDebugEnabled()) {
                        log.debug("添加路径限流规则: {} -> {}", path, ruleConfig);
                    }
                } catch (Exception e) {
                    log.warn("解析路径限流规则失败: {}", rule, e);
                }
            }
        }
    }

    /**
     * 解析单个规则配置
     * 格式：type:rate:capacity:timeUnit
     */
    private PathRateLimitRule parseRuleConfig(String path, String config) {
        String[] parts = config.split(":");
        if (parts.length < 2) {
            throw new IllegalArgumentException("无效的规则配置: " + config);
        }

        String limiterType = parts[0].toLowerCase();
        // 将旧的类型名称映射到新的类型名称
        if ("token_bucket".equals(limiterType)) {
            limiterType = "token";
        }
        int rate = Integer.parseInt(parts[1]);
        long capacity = parts.length > 2 ? Long.parseLong(parts[2]) : (long) rate;
        TimeUnit timeUnit = parts.length > 3 ? TimeUnit.valueOf(parts[3].toUpperCase()) : TimeUnit.SECONDS;

        return new PathRateLimitRule(path, "RATE_LIMIT", limiterType, rate, capacity, timeUnit);
    }

    // ========== 公共方法 ==========


    /**
     * 清空所有限流规则
     */
    public void clear() {
        pathRules.clear();
        pathRateLimiters.clear();
    }

    /**
     * 添加路径限流规则
     */
    public void addPathRule(String path, String type, int rate, long capacity, TimeUnit timeUnit) {
        PathRateLimitRule rule = new PathRateLimitRule(path, type, rate, capacity, timeUnit);
        pathRules.put(path, rule);
        log.info("添加路径限流规则: {}", path);
    }

    /**
     * 移除路径限流规则
     */
    public void removePathRule(String path) {
        pathRules.remove(path);
        pathRateLimiters.remove(path);
        log.info("移除路径限流规则: {}", path);
    }

    /**
     * 重置指定路径的限流器
     */
    public void resetPathRateLimit(String path) {
        // 移除现有的限流器，下次访问时会重新创建
        pathRateLimiters.remove(path);
        log.info("重置路径限流器: {}", path);
    }

    /**
     * 清除所有路径的限流器
     */
    public void clearAllRateLimiters() {
        pathRateLimiters.clear();
        log.info("清除所有路径限流器");
    }

    /**
     * 获取当前活跃的路径数量
     */
    public int getActivePathCount() {
        return pathRateLimiters.size();
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
                .description("是否启用地址级别限流")
                .required(true)
                .defaultValue(true)
                .build());

        // 默认规则配置
        options.add(FilterOption.builder()
                .name("默认限流规则")
                .key("defaultRule")
                .type(PathRateLimitRule.class)
                .description("默认的路径限流规则，当请求路径不匹配任何规则时使用")
                .required(true)
                .defaultValue(new PathRateLimitRule(DEFAULT, "RATE_LIMIT", "token", 100, 100, TimeUnit.SECONDS))
                .build());

        // 路径规则配置
        options.add(FilterOption.builder()
                .name("路径限流规则")
                .key("pathRules")
                .type(List.class)
                .description("路径限流规则列表，支持精确匹配、通配符和正则表达式；每条规则应包含: pattern, ruleType(RATE_LIMIT/WHITELIST/BLACKLIST)。当 ruleType=RATE_LIMIT 时还需提供 limiterType、requestsPerSecond、capacity、timeUnit")
                .required(false)
                .validation("当 ruleType=RATE_LIMIT 时必须包含 limiterType、requestsPerSecond 和 capacity")
                .build());

        return options;
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
     * 地址限流配置类
     */
    @Data
    public static class AddressRateLimitConfig {
        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * 默认限流规则
         */
        private PathRateLimitRule defaultRule;

        /**
         * 路径限流规则列表
         */
        private List<PathRateLimitRule> pathRules;
    }
}
