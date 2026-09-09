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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * DoS 拦截过滤器（简单限速 + IP突发计数）
 *
 * @author CH
 * @since 2025-08-15
 */
@EqualsAndHashCode(callSuper = true)
@Slf4j
@Data
@Spi("dos")
@SpiDescribe("DoS拦截过滤器")
public class DosServletFilter extends UpgradeServletFilter<DosServletFilter.DosConfig> {

    /**
     * 清理触发频率（每处理 N 次请求尝试清理一次）
     */
    private static final int CLEANUP_EVERY_N_CALLS = 1024;

    private final Map<String, WindowCounter> ipCounters = new ConcurrentHashMap<>();
    private int calls;

    public DosServletFilter() {
        super("DosFilter");
        DosConfig cfg = new DosConfig(true, 1_000, 200, 30_000);
        upgradeConfigObject(cfg);
    }

    @Override
    protected void doFilterWithConfigObject(ServletRequest request, ServletResponse response, ServletFilterChain chain, DosConfig config) throws Exception {
        if (!config.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String ip = request.findClientIp();
        if (ip == null) {
            chain.doFilter(request, response);
            return;
        }

        long now = System.currentTimeMillis();
        WindowCounter counter = ipCounters.computeIfAbsent(ip, k -> new WindowCounter());
        counter.lastSeen = now;

        // 低成本触发清理，避免内存无限增长
        if (++calls % CLEANUP_EVERY_N_CALLS == 0) {
            cleanupExpiredCounters(config, now);
        }

        // 处于封禁
        if (counter.blockUntil > now) {
            reject(response);
            return;
        }

        // 滑动窗口计数
        synchronized (counter) {
            if (now - counter.windowStart >= config.windowMs()) {
                counter.windowStart = now;
                counter.count = 0;
            }
            counter.count++;
            if (counter.count > config.maxRequests()) {
                counter.blockUntil = now + config.blockMs();
                reject(response);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private void reject(ServletResponse response) {
        response.setStatusCode(429);
        response.setStatusMessage("Too Many Requests");
        response.setContentType("application/json");
        response.setBodyString("{\"error\":\"Too Many Requests\"}");
        response.addHeader("X-Blocked-Reason", "DOS");
        response.setTerminateEarly(true);
    }

    @Override
    protected boolean validateConfigObject(DosConfig config) {
        if (config == null) {
            return false;
        }
        return config.windowMs > 0 && config.maxRequests > 0 && config.blockMs >= 0;
    }

    @Override
    public String getFilterName() { return "DosServletFilter"; }

    @Override
    public int getOrder() { return 8; }

    @Override
    public String getDescription() {
        DosConfig c = getConfigurationObject();
        return String.format(Locale.ROOT, "DoS拦截 (v%d) enabled=%s", getConfigVersion(), c != null && c.isEnabled());
    }

    @Override
    public List<FilterOption> getFilterOptions() {
        List<FilterOption> options = new ArrayList<>();
        options.add(FilterOption.builder().name("启用状态").key("enabled").type(Boolean.class).description("是否启用").required(true).defaultValue(true).build());
        options.add(FilterOption.builder().name("窗口大小(ms)").key("windowMs").type(Long.class).description("统计窗口大小(毫秒)").required(true).defaultValue(1000L).build());
        options.add(FilterOption.builder().name("最大请求数").key("maxRequests").type(Integer.class).description("窗口内最大请求数").required(true).defaultValue(200).build());
        options.add(FilterOption.builder().name("封禁时长(ms)").key("blockMs").type(Long.class).description("超过阈值的封禁时间").required(true).defaultValue(30000L).build());
        return options;
    }

    @Override
    public boolean supportProtocol(String protocol) {
        return true; // 支持所有协议（TCP/SOCKS5等），仅依赖clientIp
    }

    @Data
    static class WindowCounter {
        long windowStart = System.currentTimeMillis();
        int count = 0;
        long blockUntil = 0;
        long lastSeen = System.currentTimeMillis();
    }

    /**
     * DoS配置
     */
    public record DosConfig(
            boolean enabled,
            long windowMs,
            int maxRequests,
            long blockMs
    ) {
        /**
         * 默认配置
         */
        public DosConfig() {
            this(true, 1_000, 200, 30_000);
        }

        /**
         * 是否启用（兼容方法）
         *
         * @return 是否启用
         */
        public boolean isEnabled() {
            return enabled;
        }
    }

    /**
     * 清理长期不活跃的计数器，降低内存占用
     *
     * @param config 配置
     * @param now    当前时间戳（毫秒）
     */
    private void cleanupExpiredCounters(DosConfig config, long now) {
        long keepMs = Math.max(config.windowMs(), config.blockMs());
        long expireMs = Math.max(keepMs, 60_000L);
        ipCounters.entrySet().removeIf(e -> now - e.getValue().lastSeen > expireMs);
    }
}


