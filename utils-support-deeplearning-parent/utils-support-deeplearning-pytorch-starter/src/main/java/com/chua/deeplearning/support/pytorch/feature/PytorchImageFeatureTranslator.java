package com.chua.deeplearning.support.pytorch.feature;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

/**
 * pytorch 图像特征提取 Translator。
 * <p>
 * 224x224 + 镜像net 归一化，输出 L2 归一化特征向量。
 * 适用于 Rnet/CLIP/mobilenet 等 嵌入 模型。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PytorchImageFeatureTranslator implements Translator<Image, float[]> {

    /**
     * 输入边长。
     */
    private static final int IMAGE_SIZE = 224;

    /**
     * 镜像net 均值。
     */
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};

    /**
     * 镜像net 标准差。
     */
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    @Override
    /**
     * 处理输入
    */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        array = NDImageUtils.resize(array, IMAGE_SIZE, IMAGE_SIZE);
        array = NDImageUtils.toTensor(array);
        NDArray mean = ctx.getNDManager().create(MEAN, new Shape(3, 1, 1));
        NDArray std = ctx.getNDManager().create(STD, new Shape(3, 1, 1));
        array = array.sub(mean).div(std);
        return new NDList(array);
    }

    @Override
    /**
     * 处理输出
    */
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.singletonOrThrow();
        while (output.getShape().dimension() > 1 && output.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            output = output.squeeze(0);
        }
        if (!DataType.FLOAT32.equals(output.getDataType())) {
            output = output.toType(DataType.FLOAT32, false);
        }
        // 若仍是多维，展平
        if (output.getShape().dimension() > 1) {
            output = output.reshape(output.size());
        }
        float[] feature = output.toFloatArray();
        return l2Normalize(feature);
    }

    @Override
    /**
     * 获取Batchifier
    */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }

    /**
     * L2 归一化。
     *
     * @param feature 特征向量
     * @return 归一化后的向量
     */
    private static float[] l2Normalize(float[] feature) {
        double sum = 0;
        for (float v : feature) {
            sum += v * v;
        }
        double norm = Math.sqrt(sum);
        if (norm < 1e-12) {
            return feature;
        }
        float[] result = new float[feature.length];
        for (int i = 0; i < feature.length; i++) {
            result[i] = (float) (feature[i] / norm);
        }
        return result;
    }
}
