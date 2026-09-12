package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.filter.rate.RateLimitProvider;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

/**
* 限流过滤器，通过 SPI 加载 {@link RateLimitProvider} 实现。
*
* <p>统一入口，具体限流算法由 SPI 实现决定：</p>
* <ul>
*   <li>{@code guava} — 令牌桶算法（默认）</li>
*   <li>{@code sliding-window} — 滑动窗口</li>
*   <li>{@code redis} — 分布式限流</li>
* </ul>
*
* <h2>使用方式</h2>
* <pre>{@code
* // 全局限流：每秒 100 次，使用 guava 实现
* RateLimitFilter filter = new RateLimitFilter("guava", 100);
*
* // 按 IP 限流
* RateLimitFilter filter = RateLimitFilter.byIp("guava", 10);
*
* // 按路径限流
* RateLimitFilter filter = RateLimitFilter.byPath("guava", 50, "/api/**");
* }</pre>
*
* @author CH
* @since 2026/07/18
 */
@Slf4j
public class RateLimitFilter implements ServerFilter {

    /** 提供者名称 */
    private final String providerName;
    /** QPS */
    private final double qps;
    /** 密钥策略 */
    private final KeyStrategy keyStrategy;
    /** 路径prefix */
    private final String pathPrefix;

    /** limiter */
    private volatile RateLimitProvider.RateLimiter limiter;

    /**
    * 限流 key 提取策略
     */
    public enum KeyStrategy {
        /** 全局限流 */
        GLOBAL,
        /** 按 IP 限流 */
        BY_IP,
        /** 按路径限流 */
        BY_PATH
    }

    /**
    * 创建全局限流过滤器。
    *
    * @param providerName SPI 提供者名称（如 "guava"）
    * @param qps          每秒最大请求数
     */
    public RateLimitFilter(String providerName, double qps) {
        this(providerName, qps, KeyStrategy.GLOBAL, null);
    }

    /**
    * 创建 RateLimitFilter 实例
    * @param providerName providerName
    * @param double double
    * @param KeyStrategy KeyStrategy
    * @param String String
     */
    private RateLimitFilter(String providerName, double qps, KeyStrategy keyStrategy, String pathPrefix) {
        this.providerName = providerName;
        this.qps = qps;
        this.keyStrategy = keyStrategy;
        this.pathPrefix = pathPrefix;
    }

    /**
    * 按 IP 限流。
     */
    public static RateLimitFilter byIp(String providerName, double qps) {
        return new RateLimitFilter(providerName, qps, KeyStrategy.BY_IP, null);
    }

    /**
    * 按路径限流。
     */
    public static RateLimitFilter byPath(String providerName, double qps, String pathPrefix) {
        return new RateLimitFilter(providerName, qps, KeyStrategy.BY_PATH, pathPrefix);
    }

    @Override
    /** 获取Order */
    public int getOrder() {
        return Integer.MIN_VALUE + 30;
    }

    @Override
    /** SupportProtocols */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[0];
    }

    @Override
    /** 初始化 */
    public void init(ServerFilterConfig config) throws Exception {
        RateLimitProvider provider = ServiceProvider.of(RateLimitProvider.class).getExtension(providerName);
        if (provider == null) {
            log.warn("RateLimitProvider 未找到: {}, 限流过滤器将被禁用", providerName);
            return;
        }
        this.limiter = keyStrategy == KeyStrategy.GLOBAL
                ? provider.create(qps)
                : provider.createPerKey(qps);
        log.info("RateLimitFilter 初始化: provider={}, qps={}, strategy={}", providerName, qps, keyStrategy);
    }

    @Override
    /**
    * Do过滤
    * @param request request
    * @param response response
    * @param chain chain
     */
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        if (limiter == null) {
            chain.doFilter(request, response);
            return;
        }

        String key = resolveKey(request);

        if (!limiter.tryAcquire(key)) {
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
            response.setHeader("X-RateLimit-Limit", String.valueOf((int) qps));
            response.setHeader("X-RateLimit-Remaining", "0");
            response.setBody("{\"error\":\"Too Many Requests\",\"qps\":" + (int) qps + "}");
            response.end();
            return;
        }

        response.setHeader("X-RateLimit-Limit", String.valueOf((int) qps));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(limiter.getRemaining(key)));

        chain.doFilter(request, response);
    }

    /** 解析Key */
    private String resolveKey(ServerRequest request) {
        return switch (keyStrategy) {
            case GLOBAL -> "__global__";
            case BY_IP -> request.getRemoteAddress();
            case BY_PATH -> pathPrefix != null ? pathPrefix : request.getPath();
        };
    }
}
