package com.chua.common.support.ai.chat.aggregate;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.aggregate.strategy.RouterStrategy;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 公共故障转移模板 — 包装任意 {@link RouterStrategy}，自动处理重试和用量记录。
 *
 * <p>所有扁平策略（failover / round_robin / weighted / cost）共享此模板，
 * 无需各自实现 try-catch 循环。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class FailoverTemplate {

    /**
     * 创建 FailoverTemplate 实例
    */
    private FailoverTemplate() {
    }

    /**
     * 流式首包（首个正文/思考片段）等待超时：超时未返回即判定该密钥卡顿，切换下一密钥。
     * 上游对受限密钥可能“延迟处理”而非返回 429，仅靠异常故障转移会一直干等。
     */
    private static final long FIRST_TOKEN_TIMEOUT_MS = 8000L;

    /**
     * 放弃卡顿客户端后，等待其底层请求真正结束（关闭连接）的最长时间。
     */
    private static final long ABANDON_WAIT_MS = 1500L;

    /**
     * 已开始流式输出后，等待整段回复完成的最长时间（略大于底层客户端的 90s 超时）。
     */
    private static final long COMPLETION_WAIT_SECONDS = 100L;

    /**
     * 执行带故障转移的同步对话
     *
     * @param selector      客户端选择器（策略的 select 方法）
     * @param clients       候选客户端列表
     * @param prompt        用户输入
     * @param usageCallback 用量回调，每次成功调用时触发（可为 null）
     * @return 响应文本
     * @throws Exception 全部客户端失败时抛出
     */
    public static String executeSync(
            RouterStrategy selector,
            List<RouterStrategy.WeightedClient> clients,
            String prompt,
            Consumer<AiUsage> usageCallback) throws Exception {

        List<RouterStrategy.WeightedClient> remaining = new ArrayList<>(clients);
        if (remaining.isEmpty()) {
            throw new IllegalArgumentException("No clients configured");
        }

        Exception lastError = null;
        int attempt = 0;

        while (!remaining.isEmpty()) {
            RouterStrategy.WeightedClient wc;
            try {
                wc = selector.select(remaining, prompt);
            } catch (Exception e) {
                log.warn("[FailoverTemplate] select() failed on attempt {}: {}", attempt + 1, e.getMessage());
                break;
            }

            // 从剩余列表中移除已选择的客户端，避免重复选择
            remaining.remove(wc);

            long start = System.currentTimeMillis();
            attempt++;

            try {
                // 使用 chatSyncWithResponse 获取完整响应（含 AiUsage）
                ChatSyncResponse resp = wc.client().chatSyncWithResponse(prompt);
                long elapsed = System.currentTimeMillis() - start;

                log.debug("[FailoverTemplate] {} succeeded in {}ms (attempt {})",
                        wc.provider(), elapsed, attempt);

                // 记录用量
                if (usageCallback != null && resp != null) {
                    usageCallback.accept(resp.usage());
                }

                return resp != null ? resp.text() : "";
            } catch (Exception e) {
                lastError = e;
                long elapsed = System.currentTimeMillis() - start;
                log.warn("[FailoverTemplate] {} failed after {}ms (attempt {}): {}",
                        wc.provider(), elapsed, attempt, e.getMessage());
            }
        }

        throw new RuntimeException("All " + clients.size() + " client(s) failed", lastError);
    }

    /**
     * 执行带故障转移的流式对话
     *
     * @param selector  客户端选择器
     * @param clients   候选客户端列表
     * @param prompt    用户输入
     * @param consumer  流式响应回调
     * @throws Exception 全部客户端失败时抛出
     */
    public static void executeStream(
            RouterStrategy selector,
            List<RouterStrategy.WeightedClient> clients,
            String prompt,
            Consumer<ChatResponse> consumer) throws Exception {

        List<RouterStrategy.WeightedClient> remaining = new ArrayList<>(clients);
        if (remaining.isEmpty()) {
            throw new IllegalArgumentException("No clients configured");
        }

        Exception lastError = null;

        // 每个候选客户端在独立线程执行，主线程只等待“首包/错误”，从而能对“慢但不报错”的密钥快速切换
        ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "failover-stream");
            t.setDaemon(true);
            return t;
        });
        try {
            while (!remaining.isEmpty()) {
                final RouterStrategy.WeightedClient wc;
                try {
                    wc = selector.select(remaining, prompt);
                } catch (Exception e) {
                    break;
                }
                remaining.remove(wc);

                final CountDownLatch firstSignal = new CountDownLatch(1);
                final CountDownLatch finished = new CountDownLatch(1);
                final AtomicBoolean started = new AtomicBoolean(false);
                final AtomicBoolean abandoned = new AtomicBoolean(false);
                final AtomicReference<Throwable> taskError = new AtomicReference<>(null);

                // 包装回调：首个正文/思考片段或错误到达即放行；被放弃后丢弃该客户端的全部回调
                Consumer<ChatResponse> wrapper = response -> {
                    if (abandoned.get()) {
                        return;
                    }
                    boolean hasContent = (response.getContent() != null && !response.getContent().isEmpty())
                            || (response.getReasoningContent() != null && !response.getReasoningContent().isEmpty());
                    if (hasContent && started.compareAndSet(false, true)) {
                        firstSignal.countDown();
                    } else if (response.getState() == ChatResponse.State.ERROR) {
                        firstSignal.countDown();
                    }
                    try {
                        consumer.accept(response);
                    } catch (Throwable t) {
                        taskError.compareAndSet(null, t);
                    }
                };

                final Future<?> future = pool.submit(() -> {
                    try {
                        wc.client().chat(prompt, wrapper);
                    } catch (Throwable t) {
                        taskError.compareAndSet(null, t);
                    } finally {
                        finished.countDown();
                    }
                });

                boolean signaled = firstSignal.await(FIRST_TOKEN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (!signaled) {
                    // 首包超时：判定该密钥卡顿，关闭并放弃其全部输出，切换下一密钥
                    abandoned.set(true);
                    try {
                        wc.client().close();
                    } catch (Exception ignore) {
                    }
                    future.cancel(true);
                    try {
                        finished.await(ABANDON_WAIT_MS, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignore) {
                    }
                    lastError = new java.util.concurrent.TimeoutException(
                            wc.provider() + " 首包超时(" + FIRST_TOKEN_TIMEOUT_MS + "ms)");
                    log.warn("[FailoverTemplate] {} 首包 {}ms 未返回，切换下一客户端",
                            wc.provider(), FIRST_TOKEN_TIMEOUT_MS);
                    continue;
                }

                // 已收到信号：等待任务彻底结束（正常流式会等到整段回复完成）
                finished.await(COMPLETION_WAIT_SECONDS, TimeUnit.SECONDS);
                Throwable t = taskError.get();
                if (t == null) {
                    return;
                }
                if (started.get()) {
                    // 开始输出后才失败：内容已部分下发，无法干净切换，直接抛出以免重复/错乱
                    if (t instanceof Exception e) {
                        throw e;
                    }
                    throw new RuntimeException(t);
                }
                // 首包前就出错（401/429/连接失败等）：快速切换下一密钥，不浪费等待
                lastError = (t instanceof Exception e) ? e : new RuntimeException(t);
                log.warn("[FailoverTemplate] {} 首包前失败，切换下一客户端: {}",
                        wc.provider(), t.getMessage());
            }
        } finally {
            pool.shutdownNow();
        }

        throw new RuntimeException("All " + clients.size() + " client(s) stream failed", lastError);
    }
}
