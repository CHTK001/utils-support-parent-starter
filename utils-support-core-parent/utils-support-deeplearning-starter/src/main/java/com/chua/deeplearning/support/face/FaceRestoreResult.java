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

    /**
     * 规范构造器：数组组件做防御性拷贝。
     *
     * <p>value class 前置条件——数组组件必须深不可变。关键点不足时组件可能为
     * {@code null}，保留其 {@code null} 语义。</p>
     *
     * @param alignedFace  对齐人脸图
     * @param restoredFace 修复后人脸图
     */
    public FaceRestoreResult {
        alignedFace = alignedFace == null ? null : alignedFace.clone();
        restoredFace = restoredFace == null ? null : restoredFace.clone();
    }

    /**
     * 访问器覆写：返回对齐人脸图的副本。
     *
     * @return 对齐人脸图副本；无则返回 {@code null}
     */
    @Override
    public byte[] alignedFace() {
        return alignedFace == null ? null : alignedFace.clone();
    }

    /**
     * 访问器覆写：返回修复后人脸图的副本。
     *
     * @return 修复后人脸图副本；无则返回 {@code null}
     */
    @Override
    public byte[] restoredFace() {
        return restoredFace == null ? null : restoredFace.clone();
    }
}
