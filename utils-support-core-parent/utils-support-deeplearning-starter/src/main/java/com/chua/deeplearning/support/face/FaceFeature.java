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
}
