package com.chua.ueba.support.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * UEBA 引擎配置。
 * <p>
 * 从 {@code ueba-config.yaml} 反序列化得到，包含特征定义、AutoEncoder 模型配置、
 * LSTM/GRU 模型配置、风险权重与预处理参数（归一化参数与类别词表）。
 * 训练端（Python）在训练完成后将归一化参数与词表回写到 {@code preprocessing} 段，
 * 推理端（Java）读取同一份配置，保证训练与识别完全一致。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UebaConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 默认 AutoEncoder 异常阈值，重建误差超过该值判定为异常
     */
    public static final double DEFAULT_AE_THRESHOLD = 0.8d;

    /**
     * 默认 LSTM/GRU 模型文件
     */
    public static final String DEFAULT_LSTM_MODEL_FILE = "lstm_attention_behavior.onnx";

    /**
     * 默认 AutoEncoder 模型文件
     */
    public static final String DEFAULT_AE_MODEL_FILE = "autoencoder_ip.onnx";

    /**
     * 特征定义列表，书写顺序即 AutoEncoder 输入向量的维度顺序
     */
    private List<FeatureDefinition> features;

    /**
     * AutoEncoder 模型配置
     */
    private AutoEncoder autoEncoder;

    /**
     * LSTM/GRU 序列模型配置
     */
    private Lstm lstm;

    /**
     * 风险权重与等级阈值配置
     */
    private Risk risk;

    /**
     * 预处理参数：归一化 scaler 与类别词表
     */
    private Preprocessing preprocessing;

    /**
     * 从输入流加载配置。
     *
     * @param in YAML 输入流，不能为 null
     * @return 解析后的配置对象
     * @throws IllegalArgumentException 当 in 为 null 时
     */
    public static UebaConfig load(InputStream in) {
        Objects.requireNonNull(in, "in must not be null");
        Constructor constructor = new Constructor(UebaConfig.class);
        TypeDescription preprocessingDesc = new TypeDescription(Preprocessing.class);
        preprocessingDesc.putMapPropertyType("scalers", String.class, Scaler.class);
        constructor.addTypeDescription(preprocessingDesc);
        Yaml yaml = new Yaml(constructor);
        return yaml.load(in);
    }

    /**
     * 从文件路径加载配置。
     *
     * @param path 配置文件路径，不能为 null
     * @return 解析后的配置对象
     * @throws IllegalArgumentException 当 path 为 null 时
     * @throws UncheckedIOException     当文件读取失败时
     */
    public static UebaConfig load(Path path) {
        Objects.requireNonNull(path, "path must not be null");
        try (InputStream in = Files.newInputStream(path)) {
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("加载 UEBA 配置失败: " + path, e);
        }
    }

    /**
     * AutoEncoder 模型配置。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AutoEncoder implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 隐藏层维度，默认 [32, 16]，仅供训练参考，推理端忽略
         */
        @Builder.Default
        private List<Integer> hiddenDims = new ArrayList<>(List.of(32, 16));

        /**
         * 异常判定阈值（重建误差），小于等于 0 时按训练期自适应阈值处理
         */
        @Builder.Default
        private double threshold = DEFAULT_AE_THRESHOLD;

        /**
         * 模型文件名，默认 autoencoder_ip.onnx
         */
        @Builder.Default
        private String modelFile = DEFAULT_AE_MODEL_FILE;

        /**
         * 显式模型路径，允许为空
         */
        private String modelPath;
    }

    /**
     * LSTM/GRU 序列模型配置。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Lstm implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 行为序列长度（时间步数量），默认 20
         */
        @Builder.Default
        private int windowSize = 20;

        /**
         * 每步数值特征数量，默认 4
         */
        @Builder.Default
        private int numNumericFeatures = 4;

        /**
         * 类别数量，默认 3
         */
        @Builder.Default
        private int numClasses = 3;

        /**
         * 类别标签，顺序与训练类别下标一一对应
         */
        @Builder.Default
        private List<String> classLabels = new ArrayList<>(List.of("normal", "suspicious", "attack"));

        /**
         * 模型文件名，默认 lstm_attention_behavior.onnx
         */
        @Builder.Default
        private String modelFile = DEFAULT_LSTM_MODEL_FILE;

        /**
         * 显式模型路径，允许为空
         */
        private String modelPath;
    }

    /**
     * 风险权重与等级阈值配置。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Risk implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * IP 异常分数权重，默认 0.5
         */
        @Builder.Default
        private double ipWeight = 0.5d;

        /**
         * 行为异常分数权重，默认 0.5
         */
        @Builder.Default
        private double behaviorWeight = 0.5d;

        /**
         * HIGH 风险阈值，默认 0.7
         */
        @Builder.Default
        private double highThreshold = 0.7d;

        /**
         * MEDIUM 风险阈值，默认 0.35
         */
        @Builder.Default
        private double mediumThreshold = 0.35d;
    }

    /**
     * 预处理参数：归一化 scaler 与类别词表。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Preprocessing implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 特征名到归一化参数的映射
         */
        @Builder.Default
        private Map<String, Scaler> scalers = new HashMap<>(16);

        /**
         * 类别值（如 path）到整数 ID 的映射，0 保留给填充位
         */
        @Builder.Default
        private Map<String, Integer> vocab = new HashMap<>(64);
    }

    /**
     * 归一化参数。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Scaler implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * min-max 归一化的最小值，允许为 null
         */
        private Double min;

        /**
         * min-max 归一化的最大值，允许为 null
         */
        private Double max;

        /**
         * z-score 归一化的均值，允许为 null
         */
        private Double mean;

        /**
         * z-score 归一化的标准差，允许为 null
         */
        private Double std;
    }
}
