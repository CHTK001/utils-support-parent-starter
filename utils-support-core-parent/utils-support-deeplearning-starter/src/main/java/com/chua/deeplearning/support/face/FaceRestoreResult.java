package com.chua.deeplearning.support.face;

import com.chua.deeplearning.support.model.PredictRectangle;

/**
   * 人脸修复管线（restorewithalign）单张人脸结果。
 *
 * @param box          检测框（含5点关键点）
 * @param alignedFace  5点仿射对齐后的512×512人脸图（修复前）
 * @param restoredFace 修复后的人脸图（GFPGAN/编码former 输出）
 * @author CH
 * @since 4.0.0.42
 */
public record FaceRestoreResult(
        PredictRectangle box,
        byte[] alignedFace,
        byte[] restoredFace) {
}
