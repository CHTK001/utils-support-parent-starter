package com.chua.common.support.ai.chat.aggregate.strategy;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.aggregate.FailoverTemplate;
import com.chua.common.support.utils.CollectionUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 低延迟策略 — 并行发起所有请求，取最先成功返回的。
 *
 * <p>适用于对延迟敏感的场景。⚠️ 会同时消耗多个 Key 的配额。
 * 同步使用并行竞速，流式回退到 {@link FailoverTemplate}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class LatencyStrategy implements RouterStrategy {

    /**
     * 同步执行最长等待时间（毫秒），超过此时间仍未返回则视为全部失败
     */
    private static final long MAX_WAIT_MS = 60_000L;

    /**
     * 竞速轮询间隔（毫秒），每 {@value} 毫秒检查一次 future 状态
     */
    private static final long POLL_INTERVAL_MS = 100L;

    /**
     * 空文本占位：当 provider 返回了响应但文本为 null 时使用
     */
    private static final String EMPTY_TEXT = "";

    @Override
    public WeightedClient select(List<WeightedClient> clients, String prompt) {
        // LatencyStrategy 总是返回首个客户端，真正的选择逻辑在 executeSync 的竞速中
        if (clients.isEmpty()) {
            throw new IllegalArgumentException("No clients available");
        }
        return clients.get(0);
    }

    /**
     * 并行执行同步聊天请求，取最先成功返回的结果。
     *
     * <p>实现机制：为每个 provider 启动独立的 {@link CompletableFuture}，
     * 主线程按 {@link #POLL_INTERVAL_MS} 周期轮询，任意一个 future 成功完成即返回并取消其他 future；
     * 若在 {@link #MAX_WAIT_MS} 内无任何 provider 成功，则抛出 {@link RuntimeException}。</p>
     *
     * @param clients        provider 列表（含权重信息）
     * @param prompt         用户输入
     * @param usageCallback  用量回调，允许为 null
     * @return 最先成功返回的 provider 文本结果
     * @throws IllegalArgumentException 当 {@code clients} 为空时抛出
     * @throws RuntimeException         当所有 provider 在超时时间内均失败时抛出
     * @throws Exception                provider 自身可能抛出的异常
     */
    @Override
    public String executeSync(List<WeightedClient> clients, String prompt,
                              Consumer<AiUsage> usageCallback) throws Exception {
        if (clients.isEmpty()) {
            throw new IllegalArgumentException("No clients configured for latency strategy");
        }

        List<CompletableFuture<Result>> futures = CollectionUtils.newArrayList();

        for (WeightedClient wc : clients) {
            CompletableFuture<Result> future = CompletableFuture.supplyAsync(() -> {
                long start = System.currentTimeMillis();
                try {
                    ChatSyncResponse resp = wc.client().chatSyncWithResponse(prompt);
                    long elapsed = System.currentTimeMillis() - start;
                    if (usageCallback != null && resp != null) {
                        usageCallback.accept(resp.usage());
                    }
                    return new Result(wc, resp != null ? resp.text() : EMPTY_TEXT, elapsed, null);
                } catch (Exception e) {
                    long elapsed = System.currentTimeMillis() - start;
                    return new Result(wc, null, elapsed, e);
                }
            });
            futures.add(future);
        }

        // 等待第一个成功的 future（通过 anyOf 轮询）
        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
        try {
            while (System.currentTimeMillis() < deadline) {
                // 使用短超时轮询，避免 CPU 空转
                try {
                    CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]))
                            .get(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // 本轮未完成，继续下一轮轮询
                }

                for (CompletableFuture<Result> f : futures) {
                    if (f.isDone()) {
                        Result r = f.get();
                        if (r.error == null) {
                            log.debug("[Latency] winner={} ({}ms)", r.client.provider(), r.elapsed);
                            cancelOthers(futures, f);
                            return r.text;
                        }
                    }
                }

                // 全部完成且全部失败，跳出循环交由后续抛出异常
                if (futures.stream().allMatch(CompletableFuture::isDone)) {
                    break;
                }
            }
        } finally {
            // 无论是否成功返回，都确保取消未完成的 future，避免资源泄漏
            cancelAll(futures);
        }

        throw new RuntimeException("All clients failed in latency strategy");
    }

    /**
     * 流式聊天：本策略不直接支持，回退到 {@link FailoverTemplate}。
     *
     * @param clients   provider 列表
     * @param prompt    用户输入
     * @param consumer  流式回调
     * @throws Exception provider 自身可能抛出的异常
     */
    @Override
    public void executeStream(List<WeightedClient> clients, String prompt,
                              Consumer<ChatResponse> consumer) throws Exception {
        log.warn("[Latency] Streaming not supported, falling back to failover");
        FailoverTemplate.executeStream(this, clients, prompt, consumer);
    }

    /**
     * 取消除指定 future 之外的全部未完成 future。
     *
     * @param futures 待清理的 future 列表
     * @param keep    需要保留的 future（赢家）
     */
    private void cancelOthers(List<CompletableFuture<Result>> futures, CompletableFuture<Result> keep) {
        for (CompletableFuture<Result> f : futures) {
            if (f != keep && !f.isDone()) {
                f.cancel(true);
            }
        }
    }

    /**
     * 取消全部未完成 future，用于超时或异常路径下的资源回收。
     *
     * @param futures 待清理的 future 列表
     */
    private void cancelAll(List<CompletableFuture<Result>> futures) {
        for (CompletableFuture<Result> f : futures) {
            if (!f.isDone()) {
                f.cancel(true);
            }
        }
    }

    /**
     * 竞速结果封装：provider、文本、耗时、异常。
     *
     * @param client  产生该结果的 provider
     * @param text    响应文本（provider 失败时为 null）
     * @param elapsed 耗时（毫秒）
     * @param error   provider 抛出的异常（成功时为 null）
     * @author CH
     * @since 4.0.0.42
     */
    private record Result(WeightedClient client, String text, long elapsed, Exception error) {
    }
}
