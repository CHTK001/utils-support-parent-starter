package com.chua.common.support.ai.probe;


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
    java.util.List<ProbeResult> results,
    double overallConfidence,
    String verdict,
    String suspectedModel,
    String proxyFramework,
    long durationMillis
) {}
