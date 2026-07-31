package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.util.concurrent.*;

/**
 * 请求超时过滤器，设置单个请求的最大处理时间。
 *
 * <p>在指定时间内未完成的请求，自动返回 504 Gateway Timeout。</p>
 *
 * @author CH
 * @since 2026/07/18
 */
public class RequestTimeoutFilter implements ServerFilter {

    private final long timeoutMillis;

    /**
     * 创建超时过滤器。
     *
     * @param timeoutMillis 超时时间（毫秒）
     */
    public RequestTimeoutFilter(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * 创建超时过滤器（秒）。
     */
    public static RequestTimeoutFilter ofSeconds(int seconds) {
        return new RequestTimeoutFilter(seconds * 1000L);
    }

    @Override
    public int getOrder() {
        return Integer.MIN_VALUE + 5;
    }

    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[0];
    }

    @Override
    public void doFilter(ServerRequest request, ServerResponse response,
                         ServerFilterChain chain) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = null;
        try {
            future = executor.submit(() -> {
                try {
                    chain.doFilter(request, response);
                } catch (Exception e) {
                    throw new RuntimeException(e);
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
        } finally {
            executor.shutdownNow();
        }
    }
}
