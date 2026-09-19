package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
 * <p>同时实现同步({@link ServerFilter})与响应式({@link ReactiveServerFilter})
 * 两种链接口:阻塞传输走同步链,NIO/AIO 响应式传输走响应式链
 * (经 whenComplete 释放预算,不改变链的异步语义)。</p>
 *
 * @author CH
 * @since 2026/08/24
 */
public class ConnectionBudgetServerFilter implements ServerFilter, ReactiveServerFilter {

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

    /**
    * 每 IP 在途计数表:key=来源 IP,value=该 IP 当前在途请求数
    */
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
    /**
    * SupportPath:Access Filter,每次请求都触发(显式覆写消除双接口默认方法冲突)
    */
    public String supportPath() {
        return null;
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
        AtomicInteger counter = acquire(request);
        if (counter == null) {
            // 超出跟踪上限的新 IP:放行(内存防护优先)
            chain.doFilter(request, response);
            return;
        }
        if (counter.incrementAndGet() > maxConcurrentPerIp) {
            counter.decrementAndGet();
            reject(response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            counter.decrementAndGet();
        }
    }

    @Override
    /**
     * 响应式Do过滤
     *
     * @param request request
     * @param response response
     * @param chain chain
     */
    public CompletionStage<Void> doFilter(ServerRequest request, ServerResponse response,
                                          ReactiveFilterChain chain) {
        AtomicInteger counter = acquire(request);
        if (counter == null) {
            return chain.doFilter(request, response);
        }
        if (counter.incrementAndGet() > maxConcurrentPerIp) {
            counter.decrementAndGet();
            reject(response);
            return CompletableFuture.completedStage(null);
        }
        // whenComplete 释放预算:无论成功失败都归还,且不改变上游异常传播
        return chain.doFilter(request, response).whenComplete((v, ex) ->
                counter.decrementAndGet());
    }

    /**
     * 获取来源 IP 的在途计数器(超跟踪上限返回 null)。
     *
     * @param request 请求对象
     * @return 计数器或 null(不跟踪)
     */
    private AtomicInteger acquire(ServerRequest request) {
        String ip = request.getRemoteAddress();
        // 达到跟踪上限时不再为新 IP 建表项(内存防护),已有 IP 正常限流
        if (inFlightByIp.size() >= MAX_TRACKED_IPS && !inFlightByIp.containsKey(ip)) {
            return null;
        }
        return inFlightByIp.computeIfAbsent(ip, k -> new AtomicInteger());
    }

    /**
     * 返回 429 标准错误响应并终止链。
     *
     * @param response 响应对象
     */
    private void reject(ServerResponse response) {
        if (!response.isEnded()) {
            response.setStatus(STATUS_TOO_MANY_REQUESTS);
            response.setBody("{\"error\":\"Too Many Requests\",\"limit\":"
                    + maxConcurrentPerIp + "}");
            response.end();
        }
    }
}
