package com.chua.deeplearning.support.onnx.yolo.obb;

import java.util.List;

/**
 * OBB
 * <p>
 *                            OBB - Oriented Bounding Box
 *
 * @param rotatedBoxList OBB
 *
 * @author CH
 * @since 2025-01-22
 */
public record ObbResult(
        List<YoloRotatedBox> rotatedBoxList
) {
}
