package com.chua.deeplearning.support.face;

import com.chua.deeplearning.support.model.PredictRectangle;

/**
 * 人脸关键点管线结果（坐标已映射回**原图像素**）。
 *
 * <p>由 {@link FacePipeline#landmarkPipelineLargest(byte[])} 返回。与
 * {@link FacePipeline#landmark(byte[])} 的区别是坐标口径：后者把整图直接喂模型，
 * 拿到的是「相对模型输入图」的归一化坐标，画不到原图上；本结果是在**检测框裁剪脸**上
 * 取点、再按裁剪原点与尺寸映射回原图像素的，可直接叠加到原图。</p>
 *
 * @param box       该点所对应的人脸框（原图像素）
 * @param points    关键点，x,y 交替，**原图像素**（左上角为原点）
 * @param count     关键点个数（{@code points.length / 2}）
 * @param signed    模型输出的归一化约定是否为 [-1,1]（false 表示 [0,1]），便于排查坐标异常
 * @param elapsedMs 端到端耗时（毫秒）
 * @author CH
 * @since 4.0.0.42
 */
public record FaceLandmarkResult(
        PredictRectangle box,
        float[] points,
        int count,
        boolean signed,
        long elapsedMs) {

    /**
     * 规范构造器：数组组件做防御性拷贝。
     *
     * <p>与 {@link FaceDetectionHit} 同口径 —— value 语义下数组必须不可变；
     * 无关键点模型或未检出人脸时组件可能为 {@code null}，保留其 {@code null} 语义。</p>
     *
     * @param points 关键点（原图像素，x,y 交替）
     */
    public FaceLandmarkResult {
        points = points == null ? null : points.clone();
    }

    /**
     * 访问器覆写：返回关键点副本。
     *
     * @return 关键点副本；无则返回 {@code null}
     */
    @Override
    public float[] points() {
        return points == null ? null : points.clone();
    }
}
