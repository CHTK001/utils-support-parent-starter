package com.chua.deeplearning.support.onnx.donut;

import lombok.Builder;

/**
 * Donut OCR 识别结果
 *
 * @author CH
 * @since 2025/01/22
 */
@Builder
public class DonutResult {
    /** JSON 文本 */
    /** JSON文本 */
    private String jsonText;
    /** 标记标识数组 */
    /** 令牌IDS */
    private long[] tokenIds;
    /** 置信度 */
    /** Confidence */
    private float confidence;
}