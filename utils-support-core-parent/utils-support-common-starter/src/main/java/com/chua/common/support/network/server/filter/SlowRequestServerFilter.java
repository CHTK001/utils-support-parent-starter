package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 慢请求过滤器。
 *
 * <p>统计单请求处理耗时,超过阈值时按采样率输出告警日志,
 * 用于发现拖慢事件循环/占用连接槽的慢 handler。
 * 采样控制避免慢请求风暴时的日志洪泛。</p>
 *
 * @author CH
 * @since 2026/08/24
 */
@Slf4j
public class SlowRequestServerFilter implements ServerFilter {

    /**
     * 默认采样间隔:每 N 条慢请求记录一条日志
     */
    private static final int DEFAULT_SAMPLE_EVERY = 10;

    /** 慢请求判定阈值(毫秒) */
    private final long thresholdMillis;

    /** 采样间隔:每 N 条慢请求记录一条日志 */
    private final int sampleEvery;

    /** 慢请求累计计数(用于采样与观测) */
    private final AtomicLong slowCount = new AtomicLong();

    /**
     * 创建慢请求过滤器(默认采样间隔 10)。
     *
     * @param thresholdMillis 慢请求判定阈值(毫秒)
     */
    public SlowRequestServerFilter(long thresholdMillis) {
        this.thresholdMillis = thresholdMillis;
        this.sampleEvery = DEFAULT_SAMPLE_EVERY;
    }

    /**
     * 创建慢请求过滤器。
     *
     * @param thresholdMillis 慢请求判定阈值(毫秒)
     * @param sampleEvery     采样间隔:每 N 条慢请求记录一条日志
     */
    public SlowRequestServerFilter(long thresholdMillis, int sampleEvery) {
        this.thresholdMillis = thresholdMillis;
        this.sampleEvery = Math.max(sampleEvery, 1);
    }

    @Override
    /** 获取Order */
    public int getOrder() {
        return 200;
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
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long costMillis = (System.nanoTime() - start) / 1_000_000L;
            if (costMillis >= thresholdMillis && log.isWarnEnabled()) {
                // 采样:仅每第 sampleEvery 条慢请求落日志,防日志洪泛
                long count = slowCount.incrementAndGet();
                if (count % sampleEvery == 1) {
                    log.warn("慢请求: {} {} 耗时 {}ms (阈值 {}ms, 累计慢请求 {})",
                            request.getMethod(), request.getPath(), costMillis,
                            thresholdMillis, count);
                }
            }
        }
    }

    /**
     * 获取慢请求累计数量(监控接入用)。
     *
     * @return 慢请求总数
     */
    public long getSlowCount() {
        return slowCount.get();
    }
}
