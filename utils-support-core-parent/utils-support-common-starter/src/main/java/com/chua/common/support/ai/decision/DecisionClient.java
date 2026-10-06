package com.chua.common.support.ai.decision;

import com.chua.common.support.spi.ServiceProvider;

import java.util.List;
import java.util.Objects;

/**
 * 概率决策客户端门面。
 *
 * <p>与 {@link com.chua.common.support.ai.chat.ChatClient} 对应的决策侧门面：
 * 调用方通过工厂方法按 provider 名称拿到实例，一次调用完成「同一上下文下
 * 联合求值多个问题」。实现类通过 SPI 机制按 provider 名称注册，
 * 本接口本身也是 {@link DecisionProvider}，可直接装配进
 * {@link com.chua.common.support.ai.rule.RuleExecutor#withDecision}。</p>
 *
 * <p>链式配置示例：</p>
 * <pre>{@code
 *   DecisionBatch batch = DecisionClient.create("laya", "/models/laya-java-model")
 *       .decide("重复扣款了，请退款。", List.of(
 *           DecisionQuery.choice("department", "哪个部门处理？", Map.of("billing", "账单", "support", "技术")),
 *           DecisionQuery.noul("refund", "客户是否要求退款？")));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DecisionClient extends DecisionProvider, AutoCloseable {

    /**
     * 通过完整配置创建概率决策客户端。
     *
     * @param setting 客户端配置，包含 provider、modelPath 等
     * @return DecisionClient 实例
     */
    static DecisionClient create(DecisionSetting setting) {
        Objects.requireNonNull(setting, "setting 不能为 null");
        String provider = setting.getProvider() == null || setting.getProvider().isBlank()
                ? "laya" : setting.getProvider();
        return ServiceProvider.of(DecisionClient.class)
                .getNewExtension(provider, setting);
    }

    /**
     * 创建指定 provider 的概率决策客户端。
     *
     * @param provider   决策服务商名称，如 {@code "laya"}
     * @param modelPath  本地模型资产目录
     * @return DecisionClient 实例
     */
    static DecisionClient create(String provider, String modelPath) {
        return create(DecisionSetting.builder()
                .provider(provider).modelPath(modelPath).build());
    }

    /**
     * 对一批问题求值。
     *
     * <p>默认实现把 {@link DecisionRequest} 拆开后委托给
     * {@link #decide(Object, List)}，保持与 {@link DecisionProvider}
     * 的批量语义一致：一次调用回答全部问题。</p>
     *
     * @param request 决策求值请求，含上下文与全部问题
     * @return 决策结果批次
     */
    @Override
    default DecisionBatch decide(DecisionRequest request) {
        Objects.requireNonNull(request, "request 不能为 null");
        return decide(request.state(), request.queries());
    }

    /**
     * 在同一上下文下对一批问题求值。
     *
     * @param state   决策上下文，可为 null（纯知识型问题）
     * @param queries 问题列表，不可为空
     * @return 决策结果批次
     */
    DecisionBatch decide(Object state, List<DecisionQuery> queries);

    /**
     * 关闭客户端。
     *
     * <p>释放底层推理资源。默认空实现。</p>
     */
    @Override
    default void close() {
    }
}
