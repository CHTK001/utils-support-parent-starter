// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.utils.ThreadUtils;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 请求超时过滤器，设置单个请求的最大处理时间。
 *
 * <p>在指定时间内未完成的请求，自动返回 504 Gateway Timeout。</p>
 *
 * @author CH
 * @since 2026/07/18
 */
public class RequestTimeoutServerFilter implements ServerFilter {

    /**
     * 共享执行线程池：缓存型（核心 0、最大 Integer.MAX_VALUE、空闲 60s 回收）。
     *
     * <p>等价于旧的「每请求 newSingleThreadExecutor」：任务队列是 SynchronousQueue，
     * 每个请求任务仍独占一个工作线程（突发时按需建线程、不排队），但免去每请求
     * 创建并销毁线程池的开销；空闲线程 60 秒后自动回收。链路内无跨请求
     * ThreadLocal 状态（仅无状态的 ThreadLocalRandom 与按调用消费的复用缓冲），
     * 线程复用安全。</p>
     */
    private static final ExecutorService TIMEOUT_EXECUTOR =
            ThreadUtils.newCachedThreadPool("request-timeout-filter");

    /**
     * 超时毫秒
     */
    private final long timeoutMillis;

    /**
     * 创建超时过滤器。
     *
     * @param timeoutMillis 超时时间（毫秒）
     */
    public RequestTimeoutServerFilter(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * 创建超时过滤器（秒）。
     * @param seconds 方法入参 seconds
     * @return 请求超时时间过滤 对象
     */
    public static RequestTimeoutServerFilter ofSeconds(int seconds) {
        return new RequestTimeoutServerFilter(seconds * 1000L);
    }

    /**
     * 获取Order
     */
    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 5;
    }

    /**
     * SupportProtocols
     */
    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[0];
    }

    /**
     * Do过滤
     * @param request request
     * @param response response
     * @param chain chain
     */
    @Override
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        Future<?> future = null;
        try {
            future = TIMEOUT_EXECUTOR.submit(() -> {
                try {
                    chain.doFilter(request, response);
                } catch (Exception e) {
                    throw new RuntimeException("过滤器链执行失败: " + e.getMessage(), e);
                }
            });

            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | TimeoutException e) {
            if (future != null) {
                future.cancel(true);
            }
            if (!response.isEnded()) {
                response.setStatus(504);
                response.setBody("{\"error\":\"Gateway Timeout\",\"timeout\":" + timeoutMillis + "}");
                response.end();
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }
        // 共享线程池不做 shutdownNow：超时任务由 cancel(true) 中断，空闲线程由池按 60s 回收
    }
}