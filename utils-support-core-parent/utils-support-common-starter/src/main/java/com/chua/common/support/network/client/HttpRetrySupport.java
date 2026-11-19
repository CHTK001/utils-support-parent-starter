package com.chua.common.support.network.client;

import com.chua.common.support.network.http.HttpMethod;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * HTTP 请求重试支持：统一消费 {@link ClientRequest#getMaxRetries()} 与 {@link ClientSetting#getMaxRetries()}。
 *
 * <p>在此之前两个配置项仅有声明、无任何调用方（哑配置）。本类将重试落实在拦截器链<b>最内层</b>
 * （仅重试网络调用本身），拦截器与 before/after 钩子不会重复执行。</p>
 *
 * <p>重试策略（保守默认，避免产生重复副作用）：</p>
 * <ul>
 *   <li>仅对<b>幂等方法</b>（GET / HEAD / OPTIONS / PUT / DELETE）重试；POST / PATCH 不重试</li>
 *   <li>网络异常（执行器抛出异常）→ 幂等方法重试，非幂等方法直接抛出</li>
 *   <li>可重试状态码（408 / 425 / 429 / 5xx）→ 幂等方法重试，重试耗尽后返回最后一次响应</li>
 *   <li>退避：200ms 起指数递增（上限 2s）并叠加随机抖动；响应带 {@code Retry-After} 头时优先采用（上限 5s）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.54
 */
final class HttpRetrySupport {

    private static final System.Logger LOGGER = System.getLogger(HttpRetrySupport.class.getName());

    /**
     * 视为可重试的响应状态码：请求超时、过早断开、限流与所有服务端错误。
     */
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(408, 425, 429, 500, 502, 503, 504);

    /**
     * 幂等方法集合：重复执行不产生额外副作用，失败后可安全重试。
     */
    private static final Set<HttpMethod> IDEMPOTENT_METHODS =
            Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.PUT, HttpMethod.DELETE);

    /**
     * 首次重试的基础退避时长（毫秒）。
     */
    private static final long BASE_BACKOFF_MILLIS = 200L;

    /**
     * 指数退避时长上限（毫秒）。
     */
    private static final long MAX_BACKOFF_MILLIS = 2000L;

    /**
     * Retry-After 退避上限（毫秒），防止服务端返回过大值导致调用线程长时间阻塞。
     */
    private static final long MAX_RETRY_AFTER_MILLIS = 5000L;

    /**
     * 单次网络调用抽象，便于将同一套重试逻辑接入同步与异步执行路径。
     */
    @FunctionalInterface
    interface NetworkCall {
        /**
         * 执行一次网络调用。
         *
         * @param request 当前请求对象
         * @return 响应对象
         * @throws Exception 底层执行器抛出的任意异常
         */
        ClientResponse execute(ClientRequest request) throws Exception;
    }

    private HttpRetrySupport() {
    }

    /**
     * 解析本次请求实际生效的最大重试次数。
     *
     * <p>请求级配置（{@code > 0}）优先；否则回退到客户端全局配置。</p>
     *
     * @param request 当前请求，可为 null
     * @param setting 客户端全局配置，可为 null（此时仅使用请求级配置）
     * @return 生效的最大重试次数，恒为非负数
     */
    static int resolveMaxRetries(ClientRequest request, ClientSetting setting) {
        int requestRetries = request != null ? request.getMaxRetries() : 0;
        if (requestRetries > 0) {
            return requestRetries;
        }
        return setting != null ? Math.max(0, setting.getMaxRetries()) : 0;
    }

    /**
     * 带重试地执行一次网络调用。
     *
     * @param request    原始请求（用于判定方法幂等性与读取 Retry-After）
     * @param maxRetries 最大重试次数（0 表示不重试，只执行一次）
     * @param call       网络调用，通常为方法引用（如 {@code this::doExecute}）
     * @return 最后一次尝试的响应；重试耗尽且最后一次为异常时抛出该异常
     */
    static ClientResponse executeWithRetry(ClientRequest request, int maxRetries, NetworkCall call) {
        int attempts = Math.max(0, maxRetries) + 1;
        RuntimeException lastError = null;
        ClientResponse lastResponse = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1 && !sleepBeforeRetry(attempt - 1, lastResponse)) {
                // 线程在退避休眠中被中断：停止重试，返回已有结果
                break;
            }
            try {
                ClientResponse response = call.execute(request);
                if (attempt == attempts || !shouldRetryResponse(request, response)) {
                    return response;
                }
                lastResponse = response;
                lastError = null;
                LOGGER.log(System.Logger.Level.DEBUG, "HTTP retryable response {0} {1} -> {2}, retry {3}/{4}",
                        request.getMethod(), request.getUrl(), response.getStatusCode(), attempt, maxRetries);
            } catch (Exception e) {
                RuntimeException wrapped = wrap(e, request);
                if (attempt == attempts || !isIdempotent(request)) {
                    throw wrapped;
                }
                lastError = wrapped;
                LOGGER.log(System.Logger.Level.DEBUG, "HTTP retryable failure {0} {1} -> {2}, retry {3}/{4}",
                        request.getMethod(), request.getUrl(), wrapped.getMessage(), attempt, maxRetries);
            }
        }
        if (lastResponse != null) {
            return lastResponse;
        }
        throw lastError != null ? lastError
                : new IllegalStateException("HTTP retry loop exited without result: " + request.getUrl());
    }

    /**
     * 判断该响应是否值得重试：仅幂等方法且命中可重试状态码。
     */
    private static boolean shouldRetryResponse(ClientRequest request, ClientResponse response) {
        return isIdempotent(request) && response != null && RETRYABLE_STATUSES.contains(response.getStatusCode());
    }

    /**
     * 判断请求方法是否幂等（方法缺失时按 GET 处理）。
     */
    private static boolean isIdempotent(ClientRequest request) {
        HttpMethod method = request != null ? request.getMethod() : HttpMethod.GET;
        return method == null || IDEMPOTENT_METHODS.contains(method);
    }

    /**
     * 重试前休眠：优先使用响应的 {@code Retry-After} 头，否则按指数退避 + 随机抖动。
     *
     * @return false 表示线程在休眠中被中断（已恢复中断标记），调用方应停止重试
     */
    private static boolean sleepBeforeRetry(int retryIndex, ClientResponse lastResponse) {
        long delayMillis = backoffMillis(retryIndex);
        String retryAfter = lastResponse != null ? lastResponse.getHeader("Retry-After") : null;
        Long hinted = parseRetryAfterMillis(retryAfter);
        if (hinted != null) {
            delayMillis = Math.max(delayMillis, hinted);
        }
        delayMillis = Math.min(delayMillis, MAX_RETRY_AFTER_MILLIS);
        if (delayMillis <= 0) {
            return true;
        }
        try {
            Thread.sleep(delayMillis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 指数退避：200ms、400ms、800ms……上限 2s，并叠加 ±20% 随机抖动。
     */
    private static long backoffMillis(int retryIndex) {
        long backoff = BASE_BACKOFF_MILLIS << Math.min(retryIndex, 4);
        backoff = Math.min(backoff, MAX_BACKOFF_MILLIS);
        long jitter = (long) (backoff * 0.2d * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
        return Math.max(0L, backoff + jitter);
    }

    /**
     * 解析 Retry-After 头（仅支持秒数形式），带钳制；HTTP-date 形式不处理，退回指数退避。
     */
    private static Long parseRetryAfterMillis(String retryAfter) {
        if (retryAfter == null || retryAfter.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(retryAfter.trim());
            if (seconds < 0) {
                return null;
            }
            return Math.min(seconds * 1000L, MAX_RETRY_AFTER_MILLIS);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 统一包装底层异常：RuntimeException 原样透出，受检异常包装为 RuntimeException。
     */
    private static RuntimeException wrap(Exception e, ClientRequest request) {
        if (e instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new RuntimeException("HTTP request failed: " + (request != null ? request.getUrl() : null), e);
    }
}
