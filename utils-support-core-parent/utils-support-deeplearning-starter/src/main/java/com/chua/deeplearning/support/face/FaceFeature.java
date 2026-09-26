package com.chua.deeplearning.support.face;

/**
 * 人脸特征。
 * <p>封装人脸检测结果，包含图片数据、特征向量和置信度。</p>
 *
 * @param imageData  人脸图片字节数据
 * @param feature    特征向量
 * @param confidence 置信度
 * @author CH
 * @since 4.0.0.42
 */
public record FaceFeature(
        byte[] imageData,
        float[] feature,
        float confidence) {

    /**
     * 规范构造器：数组组件做防御性拷贝。
     *
     * <p>value class 前置条件——数组组件必须深不可变。特征向量为识别结果、只读，
     * 允许安全拷贝；两组件在提取失败时可能为 {@code null}，保留其 {@code null} 语义。</p>
     *
     * @param imageData 人脸图片字节数据
     * @param feature   特征向量
     */
    public FaceFeature {
        imageData = imageData == null ? null : imageData.clone();
        feature = feature == null ? null : feature.clone();
    }

    /**
     * 访问器覆写：返回人脸图片字节的副本。
     *
     * @return 图片字节副本；无则返回 {@code null}
     */
    @Override
    public byte[] imageData() {
        return imageData == null ? null : imageData.clone();
    }

    /**
     * 访问器覆写：返回特征向量的副本。
     *
     * @return 特征向量副本；无则返回 {@code null}
     */
    @Override
    public float[] feature() {
        return feature == null ? null : feature.clone();
    }
}
