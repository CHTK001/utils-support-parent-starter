package com.chua.deeplearning.support.onnx.face;


import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.OpenCvImageUtils;

import java.awt.image.BufferedImage;


/**
 * R50 人脸特征提取 Translator。
 *
 * <p>使用纯 Java 预处理（BufferedImage resize + RGB 归一化），
 * 避免部分 NDArray 实现（如 ONNX）不支持的 {@code NDImageUtils.resize} 图像操作。</p>
 *
 * @author CH
*/
public class R50FaceFeatureTranslator implements Translator<Image, float[]> {

    /**
     * 模型输入尺寸（arcface 448x448x3 NHWC）
     */
    private static final int INPUT_SIZE = 448;

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        // 纯 Java 预处理：resize 到 448x448，RGB 归一化到 [0,1]，NHWC 布局
        BufferedImage src = (BufferedImage) input.getWrappedImage();
        int size = INPUT_SIZE;
        BufferedImage resized = OpenCvImageUtils.resize(src, size, size, org.opencv.imgproc.Imgproc.INTER_LINEAR);

        int[] rgb = resized.getRGB(0, 0, size, size, null, 0, size);
        float[] data = new float[size * size * 3];
        for (int i = 0; i < rgb.length; i++) {
            int pixel = rgb[i];
            data[i * 3] = ((pixel >> 16) & 0xff) / 255f;
            data[i * 3 + 1] = ((pixel >> 8) & 0xff) / 255f;
            data[i * 3 + 2] = (pixel & 0xff) / 255f;
        }

        NDArray array = ctx.getNDManager().create(data, new Shape(1, size, size, 3));

        return new NDList(array);
    }

    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        // 输出形状 [1, N]，取第一行作为特征向量
        NDArray output = list.singletonOrThrow();
        long rows = output.getShape().get(0);
        long cols = output.getShape().get(1);
        Object arr = output.toArray();
        float[] feature;
        if (arr instanceof float[][] rowsArr && rowsArr.length > 0) {
            feature = rowsArr[0];
        } else if (arr instanceof float[] flat) {
            feature = flat;
        } else {
            feature = new float[(int) cols];
        }
        return feature;
    }

    /**
     * {@inheritDoc}
     *
     * <p>返回 {@code null}（单输入无需 batch，避免 OnnxRuntime NDArray 的 stack 不受支持）。</p>
     */
    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}
