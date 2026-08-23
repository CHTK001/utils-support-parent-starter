package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单 IP 并发预算过滤器。
 *
 * <p>限制单个来源 IP 的在途并发请求数,防止单一客户端占满
 * 全局并发槽(与 {@code IpRateLimitServerFilter} 的速率限制互补:
 * 那者限制"每秒多少个",本过滤器限制"同时压着多少个")。
 * 高连接数传输层(AIO/IOCP)下是防单源打满的第一道闸。</p>
 *
 * <p>内存防护:跟踪的 IP 数量有硬上限,超限后新 IP 放行(不入表),
 * 避免攻击者用海量伪造源 IP 撑爆过滤器的映射表。</p>
 *
 * @author CH
 * @since 2026/08/24
 */
public class ConnectionBudgetServerFilter implements ServerFilter {

    /**
     * HTTP 429 状态码:请求过多
     */
    private static final int STATUS_TOO_MANY_REQUESTS = 429;

    /**
     * 跟踪 IP 数量硬上限:超出后新来源放行,保护过滤器自身内存
     */
    private static final int MAX_TRACKED_IPS = 65536;

    /**
     * 初始映射容量:按预期活跃 IP 数预估,减少扩容
     */
    private static final int INITIAL_MAP_CAPACITY = 256;

    /** 单 IP 允许的最大在途并发请求数 */
    private final int maxConcurrentPerIp;

    /** 每 IP 在途计数表:key=来源 IP,value=该 IP 当前在途请求数 */
    private final ConcurrentHashMap<String, AtomicInteger> inFlightByIp =
            new ConcurrentHashMap<>(INITIAL_MAP_CAPACITY);

    /**
     * 创建单 IP 并发预算过滤器。
     *
     * @param maxConcurrentPerIp 单 IP 允许的最大在途并发请求数(须大于 0)
     */
    public ConnectionBudgetServerFilter(int maxConcurrentPerIp) {
        this.maxConcurrentPerIp = Math.max(maxConcurrentPerIp, 1);
    }

    @Override
    /** 获取Order:尽早拦截,避免超预算请求占用下游资源 */
    public int getOrder() {
        return Integer.MIN_VALUE + 40;
    }

    @Override
    /** SupportProtocols */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[0];
    }

    @Override
    /**
     * Do过滤
     *
     * @param request request
     * @param response response
     * @param chain chain
     */
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        String ip = request.getRemoteAddress();
        AtomicInteger counter = null;
        // 达到跟踪上限时不再为新 IP 建表项(内存防护),已有 IP 正常限流
        if (inFlightByIp.size() < MAX_TRACKED_IPS || inFlightByIp.containsKey(ip)) {
            counter = inFlightByIp.computeIfAbsent(ip, k -> new AtomicInteger());
        }
        if (counter == null) {
            chain.doFilter(request, response);
            return;
        }
        // CAS 递增防止超卖:超过预算直接拒绝
        if (counter.incrementAndGet() > maxConcurrentPerIp) {
            counter.decrementAndGet();
            if (!response.isEnded()) {
                response.setStatus(STATUS_TOO_MANY_REQUESTS);
                response.setBody("{\"error\":\"Too Many Requests\",\"limit\":"
                        + maxConcurrentPerIp + "}");
                response.end();
            }
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            counter.decrementAndGet();
        }
    }
}
