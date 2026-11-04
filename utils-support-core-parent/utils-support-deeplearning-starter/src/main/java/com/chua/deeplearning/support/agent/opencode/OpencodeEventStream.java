package com.chua.deeplearning.support.agent.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * opencode 会话持久事件流（SSE）。
 *
 * <p>订阅 {@code GET /api/session/{sessionID}/event?after=<seq>}，按 {@code durable.seq}
 * 维护续播游标：断线后自动带 {@code after=cursor} 重连，已消费的事件不会重复下发。
 * 这正是 opencode v2 事件“可续播”的用法，也是刷新页面/切换实例后仍能接续同一轮任务的
 * 基础。</p>
 *
 * <h3>SSE 解析</h3>
 * <p>只消费 {@code data:} 行，忽略 {@code event:} 行与注释行（{@code :} 开头）；多个
 * {@code data:} 行按 SSE 规范拼接为一条消息。opencode 的事件体为
 * {@code SessionDurableEvent} JSON，形如：</p>
 * <pre>{@code
 * {
 *   "id": "evt_...",
 *   "type": "session.next.text.delta",
 *   "durable": {"aggregateID": "ses_...", "seq": 12, "version": 1},
 *   "location": {"directory": "/work"},
 *   "data": {"sessionID": "ses_...", "delta": "..."}
 * }
 * }</pre>
 *
 * <h3>线程模型</h3>
 * <p>每个流占用一个虚拟线程读 SSE，事件回调在该线程上执行。回调抛异常只会被记录，
 * 不会打断读循环（避免上游一条脏事件把整轮对话打断）。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
public final class OpencodeEventStream implements AutoCloseable {

    /**
     * JSON 解析器
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * SSE data 行前缀
     */
    private static final String DATA_PREFIX = "data:";

    /**
     * SSE event 行前缀（当前不依赖事件名，仅识别并忽略）
     */
    private static final String EVENT_PREFIX = "event:";

    /**
     * SSE 注释行前缀
     */
    private static final String COMMENT_PREFIX = ":";

    /**
     * 会话事件路径模板
     */
    private static final String PATH_TEMPLATE = "/api/session/%s/event";

    /**
     * 重连初始退避（毫秒）
     */
    private static final long INITIAL_BACKOFF_MILLIS = 200L;

    /**
     * 重连最大退避（毫秒）
     */
    private static final long MAX_BACKOFF_MILLIS = 5000L;

    /**
     * 连续失败上限：超过后判定流不可用并回调错误
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 8;

    /**
     * 正常 EOF 后的重连间隔（毫秒），避免服务端主动断开时形成忙循环
     */
    private static final long EOF_RECONNECT_MILLIS = 200L;

    /**
     * 事件流客户端
     */
    private final OpencodeHttpClient client;

    /**
     * opencode 会话 ID
     */
    private final String sessionId;

    /**
     * 事件回调
     */
    private final Consumer<JsonNode> onEvent;

    /**
     * 错误回调（仅连续失败超过上限时触发）
     */
    private final Consumer<Throwable> onError;

    /**
     * 关闭标记
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 首次连接是否已建立（用于避免“开流前就发 prompt”漏事件）
     */
    private final CountDownLatch connected = new CountDownLatch(1);

    /**
     * 当前续播游标（已消费到的最大 durable.seq）
     */
    private volatile long cursor;

    /**
     * 当前读线程
     */
    private volatile Thread worker;

    /**
     * 当前响应体（关闭时用于打断阻塞读）
     */
    private volatile InputStream body;

    /**
     * 构造事件流。
     *
     * @param client    事件流客户端
     * @param sessionId opencode 会话 ID
     * @param after     起始游标（0 表示从头订阅；已有会话应从历史最大 seq 开始）
     * @param onEvent   事件回调
     * @param onError   错误回调
     */
    public OpencodeEventStream(OpencodeHttpClient client, String sessionId, long after,
                               Consumer<JsonNode> onEvent, Consumer<Throwable> onError) {
        this.client = client;
        this.sessionId = sessionId;
        this.cursor = Math.max(after, 0L);
        this.onEvent = onEvent;
        this.onError = onError;
    }

    /**
     * 启动读线程（幂等）。
     */
    public void start() {
        if (worker != null || closed.get()) {
            return;
        }
        synchronized (this) {
            if (worker != null || closed.get()) {
                return;
            }
            worker = Thread.ofVirtual()
                    .name("opencode-event-" + sessionId)
                    .start(this::loop);
        }
    }

    /**
     * 等待首次连接建立（或首次连接尝试结束）。
     *
     * @param timeout 最长等待时间
     * @return true 表示已建立连接
     */
    public boolean awaitConnected(Duration timeout) {
        try {
            return connected.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 当前已消费到的最大事件序号。
     *
     * @return 游标；未收到任何事件时为初始游标
     */
    public long cursor() {
        return cursor;
    }

    /**
     * 关闭事件流。
     */
    @Override
    public void close() {
        closed.set(true);
        closeQuietly(body);
        Thread current = worker;
        if (current != null) {
            current.interrupt();
        }
    }

    /**
     * 读循环：连接 → 读事件 → 断线重连。
     */
    private void loop() {
        int failures = 0;
        long backoff = INITIAL_BACKOFF_MILLIS;
        while (!closed.get()) {
            try {
                HttpResponse<InputStream> response = client.openStream(path());
                body = response.body();
                connected.countDown();
                failures = 0;
                backoff = INITIAL_BACKOFF_MILLIS;
                readStream(body);
            } catch (Throwable t) {
                connected.countDown();
                if (closed.get()) {
                    break;
                }
                failures++;
                log.warn("[opencode] 事件流读取失败（第 {} 次），会话 {}: {}",
                        failures, sessionId, t.getMessage());
                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    notifyError(t);
                    break;
                }
                if (!sleep(backoff)) {
                    break;
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
            } finally {
                closeQuietly(body);
                body = null;
            }
            if (!closed.get()) {
                sleep(EOF_RECONNECT_MILLIS);
            }
        }
    }

    /**
     * 读取一个 SSE 连接直到 EOF 或关闭。
     *
     * @param input 响应体输入流
     * @throws IOException 读失败
     */
    private void readStream(InputStream input) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        String line;
        while (!closed.get() && (line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                dispatch(data);
                continue;
            }
            if (line.startsWith(COMMENT_PREFIX)) {
                continue;
            }
            if (line.startsWith(EVENT_PREFIX)) {
                // 事件名不参与路由：v2 以 data 里的 type 字段为准
                continue;
            }
            if (line.startsWith(DATA_PREFIX)) {
                appendData(data, line.substring(DATA_PREFIX.length()));
            }
        }
        dispatch(data);
    }

    /**
     * 追加 data 片段（去掉 SSE 规范允许的一个前导空格）。
     *
     * @param data  累积缓冲
     * @param chunk 片段
     */
    private static void appendData(StringBuilder data, String chunk) {
        String value = chunk.startsWith(" ") ? chunk.substring(1) : chunk;
        data.append(value);
    }

    /**
     * 派发一条 SSE 消息。
     *
     * @param data 累积缓冲
     */
    private void dispatch(StringBuilder data) {
        if (data.length() == 0) {
            return;
        }
        String payload = data.toString();
        data.setLength(0);
        JsonNode node;
        try {
            node = MAPPER.readTree(payload);
        } catch (IOException e) {
            log.debug("[opencode] 忽略无法解析的事件: {}", e.getMessage());
            return;
        }
        long seq = node.path("durable").path("seq").asLong(-1L);
        if (seq > cursor) {
            cursor = seq;
        }
        try {
            onEvent.accept(node);
        } catch (Throwable t) {
            log.warn("[opencode] 事件回调失败（已忽略该事件）: {}", t.getMessage());
        }
    }

    /**
     * 通知错误回调。
     *
     * @param error 错误
     */
    private void notifyError(Throwable error) {
        if (onError == null) {
            return;
        }
        try {
            onError.accept(error);
        } catch (Throwable t) {
            log.warn("[opencode] 错误回调失败: {}", t.getMessage());
        }
    }

    /**
     * 构造带游标的事件流路径。
     *
     * @return 路径
     */
    private String path() {
        String base = String.format(PATH_TEMPLATE, sessionId);
        return cursor > 0 ? base + "?after=" + cursor : base;
    }

    /**
     * 可中断休眠。
     *
     * @param millis 毫秒
     * @return false 表示被中断或已关闭
     */
    private boolean sleep(long millis) {
        if (closed.get()) {
            return false;
        }
        try {
            Thread.sleep(millis);
            return !closed.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 静默关闭输入流。
     *
     * @param input 输入流
     */
    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException e) {
            log.debug("[opencode] 关闭事件流失败: {}", e.getMessage());
        }
    }
}
