package com.chua.deeplearning.support.engine;

import com.chua.deeplearning.support.ai.DetectionConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 检测/识别门面运行参数工具。
 * <p>
 * 门面向引擎传递的参数一律为 {@code Map<String,Object>}。本类提供两类构造：
 * </p>
 * <ul>
 *   <li>{@link #of(Float, Float)} —— 传统入口，仅收集显式设置的 threshold / nms，
 *       未设置的键不出现，从而保留各模型 Translator 自身的准确默认值；</li>
 *   <li>{@link #builder()} —— 通用入口，可携带 {@code device} 以及模型自有的额外参数
 *       （如 inputSize、candidates 等），供需要完整控制的调用方使用。</li>
 * </ul>
 * <p>
 * 未在本类显式设置、但已通过 {@link ModelParams} 为该 modelId 持久化的参数，
 * 会由 {@link AbstractIdentificationEngine} 在调用时自动合并进来。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class DetectOptions {

    /**
     * detect期权。
     */
    private DetectOptions() {
    }

    /**
     * 构建运行参数表（仅包含非 空 项）。
     *
     * @param threshold 置信度阈值（空 表示使用模型默认值）
     * @param nms       NMS IOU 阈值（空 表示使用模型默认值）
     * @return 运行参数（可能为空 映射）
     */
    public static Map<String, Object> of(Float threshold, Float nms) {
        Map<String, Object> options = new LinkedHashMap<>();
        if (threshold != null) {
            options.put(DetectionConfiguration.KEY_THRESHOLD, threshold);
        }
        if (nms != null) {
            options.put(DetectionConfiguration.KEY_IOU_THRESHOLD, nms);
        }
        return options;
    }

    /**
     * 创建通用运行参数构造器。
     *
     * @return 参数构造器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 通用运行参数构造器。
     *
     * <p>支持任意键值对，用于承载 {@code device} 与模型自有的额外参数。</p>
     */
    public static final class Builder {

        /**
         * 参数表（保持插入顺序，便于日志与序列化稳定）。
         */
        private final Map<String, Object> options = new LinkedHashMap<>();

        /**
         * 构造器。
         */
        private Builder() {
        }

        /**
         * 设置置信度阈值。
         *
         * @param threshold 阈值；空 表示不设置
         * @return 当前构造器
         */
        public Builder threshold(Float threshold) {
            return put(DetectionConfiguration.KEY_THRESHOLD, threshold);
        }

        /**
         * 设置 NMS IOU 阈值。
         *
         * @param nms 阈值；空 表示不设置
         * @return 当前构造器
         */
        public Builder nms(Float nms) {
            return put(DetectionConfiguration.KEY_IOU_THRESHOLD, nms);
        }

        /**
         * 设置推理设备。
         *
         * @param device cpu / gpu / cuda / auto；空 表示不设置
         * @return 当前构造器
         */
        public Builder device(String device) {
            return put(ModelParams.KEY_DEVICE, device);
        }

        /**
         * 设置线程数。
         *
         * @param threads 线程数；空 表示不设置
         * @return 当前构造器
         */
        public Builder threads(Integer threads) {
            return put(ModelParams.KEY_THREADS, threads);
        }

        /**
         * 设置任意模型特有参数。
         *
         * @param key   参数键；空 键或空 值不生效
         * @param value 参数值
         * @return 当前构造器
         */
        public Builder put(String key, Object value) {
            if (key == null || key.isBlank() || value == null) {
                return this;
            }
            if (value instanceof String text && text.isBlank()) {
                return this;
            }
            options.put(key, value);
            return this;
        }

        /**
         * 合并一份参数表。
         *
         * @param params 参数表；空 表示不处理
         * @return 当前构造器
         */
        public Builder putAll(Map<String, Object> params) {
            if (params == null || params.isEmpty()) {
                return this;
            }
            params.forEach(this::put);
            return this;
        }

        /**
         * 构造参数表。
         *
         * @return 运行参数（可能为空 映射）
         */
        public Map<String, Object> build() {
            return new LinkedHashMap<>(options);
        }
    }
}
