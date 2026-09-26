package com.chua.common.support.ai.probe;

import java.util.List;
import java.util.Objects;

/**
 * 真伪探测综合报告。
 *
 * @param results 各维度探测结果列表
 * @param overallConfidence 综合置信度（0.0 ~ 1.0）
 * @param verdict 最终判词
 * @param suspectedModel 疑似真实模型
 * @param proxyFramework 疑似代理框架
 * @param durationMillis 总耗时（毫秒）
 * @author CH
 * @since 4.0.0.42
 */
public record ProbeReport(
    List<ProbeResult> results,
    double overallConfidence,
    String verdict,
    String suspectedModel,
    String proxyFramework,
    long durationMillis
) {

    /**
     * 规范构造器：对各维度探测结果列表做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。
     * 唯一构造点传入的是各探测维度聚合出的非空列表且元素非空，
     * 因此使用 {@link List#copyOf} 拒绝 null 列表与 null 元素。</p>
     *
     * @param results            各维度探测结果列表
     * @param overallConfidence  综合置信度（0.0 ~ 1.0）
     * @param verdict            最终判词
     * @param suspectedModel     疑似真实模型
     * @param proxyFramework     疑似代理框架
     * @param durationMillis     总耗时（毫秒）
     */
    public ProbeReport {
        results = List.copyOf(Objects.requireNonNull(results, "results 不能为 null"));
    }
}
