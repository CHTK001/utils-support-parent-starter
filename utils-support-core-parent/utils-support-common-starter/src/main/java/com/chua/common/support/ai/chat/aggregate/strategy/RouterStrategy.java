package com.chua.common.support.ai.chat.aggregate.strategy;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatResponse;
import com.chua.common.support.ai.chat.aggregate.FailoverTemplate;

import java.util.List;
import java.util.function.Consumer;

/**
* 路由策略接口 — 从一组候选中选择一个客户端。
*
* <p>策略只需实现 {@link #select(List, String)} 方法，决定\"用哪个\"。
* 故障转移（重试、切换）由 {@link FailoverTemplate} 统一处理。
*
* <p>特殊策略（如 HybridStrategy、LatencyStrategy）可覆写
* {@link #executeSync(List, String, Consumer)} 实现自定义执行逻辑。
*
* @author CH
* @since 4.0.0.42
 */
@FunctionalInterface
public interface RouterStrategy {

    /**
     * 策略名称
     * @return 结果字符串
     */
    default String name() {
        return getClass().getSimpleName()
                .replace("RouterStrategy", "")
                .toLowerCase();
    }

    /**
    * 从候选客户端中选择一个
    *
    * @param clients 候选列表（可修改，已选中的会被外部移除）
    * @param prompt  用户输入
    * @return 选中的客户端
    */
    WeightedClient select(List<WeightedClient> clients, String prompt);

    /**
    * 执行同步对话（默认使用 FailoverTemplate）
    *
    * @param clients       候选列表
    * @param prompt        用户输入
    * @param usageCallback 用量回调
    * @return 响应文本
    * @throws Exception 全部失败
    */
    default String executeSync(List<WeightedClient> clients, String prompt,
                               Consumer<AiUsage> usageCallback) throws Exception {
        return FailoverTemplate.executeSync(this, clients, prompt, usageCallback);
    }

    /**
    * 执行流式对话（默认使用 FailoverTemplate）
    *
    * @param clients  候选列表
    * @param prompt   用户输入
    * @param consumer 流式回调
    * @throws Exception 全部失败
    */
    default void executeStream(List<WeightedClient> clients, String prompt,
                               Consumer<ChatResponse> consumer) throws Exception {
        FailoverTemplate.executeStream(this, clients, prompt, consumer);
    }

    /**
    * 带权重的客户端条目
    *
    * @param provider 服务商
    * @param model    模型
    * @param weight   权重
    * @param client   ChatClient 实例
    * @return 结果值
    */
    record WeightedClient(String provider, String model, int weight, ChatClient client) {
    }
}
