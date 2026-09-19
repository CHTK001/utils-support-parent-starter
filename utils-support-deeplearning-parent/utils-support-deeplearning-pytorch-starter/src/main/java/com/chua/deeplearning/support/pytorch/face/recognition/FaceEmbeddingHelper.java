package com.chua.deeplearning.support.pytorch.face.recognition;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;

/**
 * 人脸特征向量后处理工具。
 *
 * @author CH
 * @since 4.0.0.42
 */
final class FaceEmbeddingHelper {

    /** 创建 face嵌入助手 实例 */
    private FaceEmbeddingHelper() {
    }

    /**
     * 将模型输出转为 L2 归一化特征向量。
     *
     * @param list 模型输出
     * @return 特征向量
     */
    static float[] toFeature(NDList list) {
        NDArray output = list.singletonOrThrow();
        while (output.getShape().dimension() > 1 && output.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            output = output.squeeze(0);
        }
        if (output.getShape().dimension() > 1) {
            output = output.reshape(output.size());
        }
        float[] features = output.toFloatArray();
        double sum = 0.0;
        for (float f : features) {
            sum += f * f;
        }
        float norm = (float) Math.sqrt(sum);
        if (norm <= 1e-12f) {
            return features;
        }
        float[] result = new float[features.length];
        for (int i = 0; i < features.length; i++) {
            result[i] = features[i] / norm;
        }
        return result;
    }
}
