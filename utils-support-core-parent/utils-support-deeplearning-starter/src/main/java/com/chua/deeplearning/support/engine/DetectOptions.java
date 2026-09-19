package com.chua.deeplearning.support.engine;

import com.chua.deeplearning.support.ai.DetectionConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 检测/识别门面运行参数工具。
 * <p>
 * 仅收集显式设置的键，未设置的键不出现，从而保留各模型
 * Translator 自身的准确默认值。
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
}
