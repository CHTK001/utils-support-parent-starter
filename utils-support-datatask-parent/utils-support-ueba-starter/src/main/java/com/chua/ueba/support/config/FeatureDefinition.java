package com.chua.ueba.support.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 特征定义。
 * <p>
 * 对应 {@code ueba-config.yaml} 中 {@code features} 列表的每一项，描述一个分析指标：
 * 名称、类型、归一化方式与聚合窗口。训练端（Python）与推理端（Java）读取同一份定义，
 * {@code features} 的书写顺序即 AutoEncoder 输入向量的维度顺序，两端必须严格一致。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeatureDefinition implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 默认类别特征词表大小（当未显式配置 vocabSize 时）
     */
    public static final int DEFAULT_VOCAB_SIZE = 1000;

    /**
     * 特征类型枚举。
     */
    public enum FeatureType {
        /**
         * 数值特征，如请求频率、路径熵
         */
        NUMERIC,

        /**
         * 类别特征，如路径 ID、HTTP 方法
         */
        CATEGORICAL,

        /**
         * 循环特征，如一天中的时刻（归一化相位）
         */
        CYCLIC
    }

    /**
     * 归一化方式枚举。
     */
    public enum NormalizeType {
        /**
         * 不归一化，使用原始值
         */
        NONE,

        /**
         * min-max 归一化，需提供 min/max
         */
        MINMAX,

        /**
         * z-score 归一化，需提供 mean/std
         */
        ZSCORE
    }

    /**
     * 特征名称，如 request_rate、path_entropy，必须与特征计算器支持的名字一致
     */
    private String name;

    /**
     * 特征类型，默认 NUMERIC
     */
    @Builder.Default
    private FeatureType type = FeatureType.NUMERIC;

    /**
     * 归一化方式，默认 NONE
     */
    @Builder.Default
    private NormalizeType normalize = NormalizeType.NONE;

    /**
     * 聚合窗口（秒），用于计算 request_rate 等与时间相关的指标，默认 60
     */
    @Builder.Default
    private long window = 60L;

    /**
     * 类别特征词表大小，仅 CATEGORICAL 类型使用，默认 1000
     */
    @Builder.Default
    private int vocabSize = DEFAULT_VOCAB_SIZE;

    /**
     * 类别特征候选值，仅 CATEGORICAL 类型使用，允许为空
     */
    @Builder.Default
    private List<String> vocab = new ArrayList<>();

    /**
     * 循环周期（秒），仅 CYCLIC 类型使用，默认 86400（一天）
     */
    @Builder.Default
    private long period = 86400L;
}
