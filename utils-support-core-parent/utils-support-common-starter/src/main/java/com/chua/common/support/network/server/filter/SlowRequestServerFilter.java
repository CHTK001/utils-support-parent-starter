package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

/**
* 慢请求过滤器。
*
* <p>统计单请求处理耗时,超过阈值时按采样率输出告警日志,
* 用于发现拖慢事件循环/占用连接槽的慢 handler。
* 采样控制避免慢请求风暴时的日志洪泛。</p>
*
* <p>同时实现同步({@link ServerFilter})与响应式({@link ReactiveServerFilter})
* 两种链接口:阻塞传输走同步链,NIO/AIO 响应式传输走响应式链
* (经 whenComplete 附加后置统计,不改变链的异步语义)。</p>
*
* @author CH
* @since 2026/08/24
 */
@Slf4j
public class SlowRequestServerFilter implements ServerFilter, ReactiveServerFilter {

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
    /** SupportPath:Access Filter,每次请求都触发(显式覆写消除双接口默认方法冲突) */
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
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            recordLatency(request, start);
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
        long start = System.nanoTime();
        // whenComplete 附加后置统计:无论成功失败都记录,且不改变上游异常传播
        return chain.doFilter(request, response).whenComplete((v, ex) ->
                recordLatency(request, start));
    }

    /**
    * 记录单请求耗时,超阈值按采样率告警。
    *
    * @param request 请求对象
    * @param startNanos 开始时间(nanoTime)
     */
    private void recordLatency(ServerRequest request, long startNanos) {
        long costMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        if (costMillis < thresholdMillis || !log.isWarnEnabled()) {
            return;
        }
        // 采样:仅每第 sampleEvery 条慢请求落日志,防日志洪泛
        long count = slowCount.incrementAndGet();
        if (count % sampleEvery == 1) {
            log.warn("慢请求: {} {} 耗时 {}ms (阈值 {}ms, 累计慢请求 {})",
                    request.getMethod(), request.getPath(), costMillis,
                    thresholdMillis, count);
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
