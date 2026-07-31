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
    private String jsonText;
    private long[] tokenIds;
    private float confidence;
}