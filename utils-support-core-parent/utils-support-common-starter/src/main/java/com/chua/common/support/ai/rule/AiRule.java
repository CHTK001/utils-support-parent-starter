package com.chua.common.support.ai.rule;

import java.util.List;
import java.util.Map;

/**
 * AI 决策规则。
 *
 * <p>{@link Rule} 的一种实现：条件不再是进程内 lambda / 表达式，
 * 而是一批声明式 {@code DecisionQuery}，由规则自己在
 * {@link #matched(RuleContext)} 里按需向底座求值（默认 laya-java 本地推理，
 * 在 laya 模块实现本接口的 SPI）。决策答案按阈值判定是否命中，
 * 命中后执行 {@code then} 动作。</p>
 *
 * <p>底座配置是强类型属性（{@link #modelPath()} 等），不再是
 * {@code Map<String, Object>}：模型目录、线程数、校准文件三个属性
 * 是 laya 本地推理的固定装配项，进 Map 会让拼错 key 在运行时才暴露，
 * 强类型在编译期就拦住。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface AiRule extends Rule {

    /**
     * 本规则声明的决策问题。
     *
     * @return 不可变问题列表
     */
    List<AiRuleQuery> queries();

    /**
     * AI 底座名，缺省为 {@code laya}。
     *
     * @return 底座名
     */
    default String provider() {
        return "laya";
    }

    /**
     * 本地模型资产目录。
     *
     * <p>本地推理引擎要求该目录下包含 {@code config.json}、
     * {@code model.onnx} 与 {@code tokenizer.json} 三个文件。
     * 为 null 表示使用底座默认。</p>
     *
     * @return 模型目录，可为 null
     */
    default String modelPath() {
        return null;
    }

    /**
     * 推理线程数。
     *
     * <p>null 表示使用底座默认值（Laya 为 4）。非 null 时必须为正数。</p>
     *
     * @return 推理线程数，可为 null
     */
    default Integer threads() {
        return null;
    }

    /**
     * 校准文件路径，可为空。
     *
     * <p>对应 laya-java 的 {@code loadCalibration}：应用外部温度校准结果，
     * 不改变模型权重。为 null 时使用模型资产自带的校准。</p>
     *
     * @return 校准文件路径，可为 null
     */
    default String calibrationPath() {
        return null;
    }

    /**
     * AI 底座实例。
     *
     * <p>默认按 {@link #provider()} 经 SPI 查找 {@code DecisionClient}；
     * 实现方可覆写为常驻客户端或自定义装配。</p>
     *
     * @return 决策底座
     */
    default com.chua.common.support.ai.decision.DecisionProvider client() {
        com.chua.common.support.ai.decision.DecisionSetting.DecisionSettingBuilder builder =
                com.chua.common.support.ai.decision.DecisionSetting.builder().provider(provider());
        if (modelPath() != null) {
            builder.modelPath(modelPath());
        }
        if (threads() != null) {
            builder.threads(threads());
        }
        if (calibrationPath() != null) {
            builder.calibrationPath(calibrationPath());
        }
        return com.chua.common.support.ai.decision.DecisionClient.create(builder.build());
    }

    /**
     * AI 决策上下文，null 表示使用执行期 input。
     *
     * @return 决策上下文
     */
    default Object state() {
        return null;
    }

    /**
     * 命中阈值判定。
     *
     * <p>默认语义：任一问题的结论标签满足期望即命中，
     * 实现方可覆写为阈值 / 加权等判定。</p>
     *
     * @param answers 标识到答案标签的映射
     * @param flattened 展平后的决策变量
     * @return 命中返回 true
     */
    boolean hit(Map<String, String> answers, Map<String, Object> flattened);

    @Override
    default RuleType type() {
        return RuleType.AI;
    }
}
