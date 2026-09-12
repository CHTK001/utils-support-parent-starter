package com.chua.common.support.ai.chat.aggregate;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.aggregate.strategy.RouterStrategy;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
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

    /** 创建 FailoverTemplate 实例 */
    private FailoverTemplate() {
    }

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
        int attempt = 0;

        while (!remaining.isEmpty()) {
            RouterStrategy.WeightedClient wc;
            try {
                wc = selector.select(remaining, prompt);
            } catch (Exception e) {
                break;
            }

            remaining.remove(wc);
            attempt++;

            try {
                wc.client().chat(prompt, consumer);
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("[FailoverTemplate] {} stream failed on attempt {}: {}",
                        wc.provider(), attempt, e.getMessage());
            }
        }

        throw new RuntimeException("All " + clients.size() + " client(s) stream failed", lastError);
    }
}
