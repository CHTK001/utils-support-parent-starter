package com.chua.common.support.browser.cdp;

import com.chua.common.support.lang.json.Json;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import com.chua.common.support.utils.StringUtils;

/**
 * 极简 Chrome DevTools Protocol 客户端
 *
 * <p>基于 JDK 自带的 {@link WebSocket} 实现，不引入任何第三方依赖。
 * 只覆盖三个能力：</p>
 * <ul>
 *   <li>按 id 发送命令并同步等待结果；</li>
 *   <li>按方法名订阅浏览器事件；</li>
 *   <li>flatten 模式下的会话级命令（命令体里带 {@code sessionId}）。</li>
 * </ul>
 *
 * <p>一个实例对应一个浏览器级 WebSocket 连接，线程安全：发送串行化，
 * 响应与事件在 WebSocket 回调线程上分发。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
final class CdpConnection implements AutoCloseable {

    /**
     * 单条命令的默认等待时间
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 底层 WebSocket 连接
     */
    private final WebSocket socket;

    /**
     * 已发出但未收到结果的命令
     */
    private final Map<Long, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();

    /**
     * 事件订阅表：方法名 → 处理器
     */
    private final Map<String, List<Consumer<Map<String, Object>>>> handlers = new ConcurrentHashMap<>();

    /**
     * 命令序号
     */
    private final AtomicLong sequence = new AtomicLong();

    /**
     * 是否正在关闭（用于抑制关闭阶段的连接告警）
     */
    private volatile boolean closing;

    /**
     * 发送锁（WebSocket 不允许并发发送）
     */
    private final Object sendLock = new Object();

    /**
     * 构造客户端
     *
     * @param socket 已建立的 WebSocket 连接
     */
    private CdpConnection(WebSocket socket) {
        this.socket = socket;
    }

    /**
     * 连接浏览器级 DevTools 端点
     *
     * @param endpoint 形如 {@code ws://127.0.0.1:9222/devtools/browser/xxx}
     * @param timeout  连接超时
     * @return CDP 客户端
     */
    static CdpConnection connect(URI endpoint, Duration timeout) {
        AtomicReference<CdpConnection> reference = new AtomicReference<>();
        Listener listener = new Listener(reference);
        WebSocket socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(timeout)
                .buildAsync(endpoint, listener)
                .join();
        CdpConnection client = new CdpConnection(socket);
        reference.set(client);
        return client;
    }

    /**
     * 发送命令并等待结果
     *
     * @param method    方法名，如 {@code Target.createTarget}
     * @param params    参数
     * @param sessionId 会话 ID；浏览器级命令传 null
     * @param timeout   等待超时
     * @return 命令结果；上游返回 error 时抛出异常
     */
    Map<String, Object> call(String method, Map<String, Object> params, String sessionId, Duration timeout) {
        long id = sequence.incrementAndGet();
        var message = new LinkedHashMap<String, Object>();
        message.put("id", id);
        message.put("method", method);
        message.put("params", params == null ? Collections.emptyMap() : params);
        if (StringUtils.isNotBlank(sessionId)) {
            message.put("sessionId", sessionId);
        }

        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            CompletableFuture<WebSocket> sent;
            synchronized (sendLock) {
                // 锁只保护「入队」这一步；若在锁内等待写出完成，
                // 读线程（selector 线程）将无法入队自身的事件应答，形成环形等待
                sent = socket.sendText(Json.toJson(message), true);
            }
            sent.join();
            Map<String, Object> result = future.get(
                    (timeout == null ? DEFAULT_TIMEOUT : timeout).toMillis(), TimeUnit.MILLISECONDS);
            Object error = result.get("__error");
            if (error != null) {
                throw new IllegalStateException("CDP " + method + " 失败: " + Json.toJson(error));
            }
            return result;
        } catch (IllegalStateException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 CDP 命令被中断: " + method, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("CDP 命令异常: " + method, e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("CDP 命令超时: " + method, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("发送 CDP 命令失败: " + method, e);
        } finally {
            pending.remove(id);
        }
    }

    /**
     * 发送命令但不等待结果
     *
     * <p>专供事件回调线程使用：WebSocket 的消息分发与命令等待共用同一条
     * 读线程，如果在事件处理器里同步等待（无论是等命令结果还是等写出完成），
     * 读线程会被自身阻塞，造成「对话框 / 帧应答等命令、命令等读线程」的死锁。
     * 这里只把消息入队、不等待完成，响应回来后因查不到 pending 而被自然忽略。</p>
     *
     * @param method    方法名，如 {@code Page.handleJavaScriptDialog}
     * @param params    参数
     * @param sessionId 会话 ID；浏览器级命令传 null
     */
    void post(String method, Map<String, Object> params, String sessionId) {
        long id = sequence.incrementAndGet();
        var message = new LinkedHashMap<String, Object>();
        message.put("id", id);
        message.put("method", method);
        message.put("params", params == null ? Collections.emptyMap() : params);
        if (StringUtils.isNotBlank(sessionId)) {
            message.put("sessionId", sessionId);
        }
        try {
            synchronized (sendLock) {
                // 只入队、绝不等待：调用方通常运行在 WebSocket 读线程上
                socket.sendText(Json.toJson(message), true);
            }
        } catch (RuntimeException e) {
            log.debug("[cdp] 发送 CDP 命令失败: {} - {}", method, e.getMessage());
        }
    }

    /**
     * 订阅浏览器事件
     *
     * @param method  方法名，如 {@code Runtime.bindingCalled}
     * @param handler 处理器，入参为完整事件消息
     */
    void on(String method, Consumer<Map<String, Object>> handler) {
        handlers.computeIfAbsent(method, key -> Collections.synchronizedList(new ArrayList<>())).add(handler);
    }

    /**
     * 分发一条完整消息
     *
     * @param text 原始 JSON 文本
     */
    private void dispatch(String text) {
        Map<String, Object> message;
        try {
            message = Json.fromJson(text);
        } catch (Exception e) {
            log.debug("[cdp] 忽略无法解析的消息: {}", e.getMessage());
            return;
        }
        if (message == null) {
            return;
        }

        Object rawId = message.get("id");
        if (rawId instanceof Number number) {
            CompletableFuture<Map<String, Object>> future = pending.remove(number.longValue());
            if (future != null) {
                Object error = message.get("error");
                Object result = message.get("result");
                var value = new LinkedHashMap<String, Object>();
                if (error != null) {
                    value.put("__error", error);
                } else if (result instanceof Map<?, ?> map) {
                    map.forEach((key, item) -> value.put(String.valueOf(key), item));
                }
                future.complete(value);
            }
            return;
        }

        Object method = message.get("method");
        if (!(method instanceof String name)) {
            return;
        }
        List<Consumer<Map<String, Object>>> list = handlers.get(name);
        if (list == null || list.isEmpty()) {
            return;
        }
        List<Consumer<Map<String, Object>>> snapshot;
        synchronized (list) {
            snapshot = new ArrayList<>(list);
        }
        for (Consumer<Map<String, Object>> handler : snapshot) {
            try {
                handler.accept(message);
            } catch (Exception e) {
                log.warn("[cdp] 事件处理失败 {}: {}", name, e.getMessage());
            }
        }
    }

    /**
     * 关闭连接
     */
    @Override
    public void close() {
        closing = true;
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("[cdp] 关闭连接失败: {}", e.getMessage());
        }
        failPending("CDP 连接已关闭");
    }

    /**
     * 把全部未完成的命令标记为失败
     *
     * @param message 失败原因
     */
    private void failPending(String message) {
        for (CompletableFuture<Map<String, Object>> future : pending.values()) {
            future.completeExceptionally(new IllegalStateException(message));
        }
        pending.clear();
    }

    /**
     * WebSocket 监听器：拼帧、分发、驱动读取
     */
    private static final class Listener implements WebSocket.Listener {

        /**
         * 客户端引用（构造顺序决定了需要延迟注入）
         */
        private final AtomicReference<CdpConnection> reference;

        /**
         * 分片缓冲
         */
        private final StringBuilder buffer = new StringBuilder();

        /**
         * 构造监听器
         *
         * @param reference 客户端引用
         */
        private Listener(AtomicReference<CdpConnection> reference) {
            this.reference = reference;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                CdpConnection client = reference.get();
                if (client != null) {
                    client.dispatch(text);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            CdpConnection client = reference.get();
            if (client != null && client.closing) {
                return;
            }
            log.warn("[cdp] WebSocket 异常: {}", error.getMessage());
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            CdpConnection client = reference.get();
            if (client == null || client.closing) {
                return null;
            }
            log.debug("[cdp] WebSocket 已被对端关闭: {} {}", statusCode, reason);
            client.failPending("CDP 连接已被浏览器关闭（" + statusCode + " " + reason + "）");
            return null;
        }
    }
}
