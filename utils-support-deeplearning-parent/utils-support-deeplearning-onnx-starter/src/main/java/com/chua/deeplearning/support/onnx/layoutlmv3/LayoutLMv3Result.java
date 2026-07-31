package com.chua.deeplearning.support.onnx.layoutlmv3;

import java.util.List;

/**
 * LayoutLMv3 文档区域识别结果
 *
 * @author CH
 * @since 4.0.0.42
 */
public record LayoutLMv3Result(List<DocumentRegion> regions) {
}