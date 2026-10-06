package com.chua.common.support.ai.decision;

import lombok.Builder;
import lombok.Data;

/**
 * 概率决策客户端配置。
 *
 * <p>封装一次决策调用所需的全部配置：走哪个 {@code provider}、
 * 模型资产在何处、推理线程数等。通过 Builder 模式构建，部分字段可选。
 * 与 {@link com.chua.common.support.ai.chat.ChatClientSetting} 对应，
 * 是决策侧的门面配置入口。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class DecisionSetting {

    /**
     * 决策服务商名称。
     *
     * <p>用于 SPI 查找对应的 {@link DecisionClient} 实现，如 {@code "laya"}。
     * 为空时回退到 {@code "laya"}。</p>
     */
    @Builder.Default
    private String provider = "laya";

    /**
     * 本地模型资产目录。
     *
     * <p>本地推理引擎（如 Laya）要求该目录下包含 {@code config.json}、
     * {@code model.onnx} 与 {@code tokenizer.json} 三个文件。</p>
     */
    private String modelPath;

    /**
     * 推理线程数。
     *
     * <p>null 表示使用实现方的默认值（Laya 为 4）。必须为正数。</p>
     */
    private Integer threads;

    /**
     * 校准文件路径，可为空。
     *
     * <p>对应 laya-java 的 {@code loadCalibration}：应用外部温度校准结果，
     * 不改变模型权重。为 null 时使用模型资产自带的校准。</p>
     */
    private String calibrationPath;
}
