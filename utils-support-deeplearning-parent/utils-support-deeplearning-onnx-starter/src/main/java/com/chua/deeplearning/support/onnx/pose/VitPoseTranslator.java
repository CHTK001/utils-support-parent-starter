package com.chua.deeplearning.support.onnx.pose;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

/**
 * vitpose 基础 简单 ONNX
 * <p>
 * : onnx-社区/vitpose-基础-简单
 * </p>
 * <p>
 *      : 17                COCO                   
 * </p>
 * <p>
 * : float[17][3]  (x, y, 信心)
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class VitPoseTranslator implements Translator<Image, float[][]> {

    /** 输入高度 */
    private static final int INPUT_H = 256;
    /** 输入宽度 */
    private static final int INPUT_W = 192;
    /** 关键点数量 */
    private static final int NUM_KEYPOINTS = 17;
    /** 热力图除数 */
    private static final int HEATMAP_DIVISOR = 4;
    /** 均值数组 */
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    /** 标准差数组 */
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    /** 原始宽度 */
    private int originalWidth;
    /** 原始高度 */
    private int originalHeight;

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        originalWidth = input.getWidth();
        originalHeight = input.getHeight();

        NDManager manager = ctx.getNDManager();
        NDArray array = input.toNDArray(manager, Image.Flag.COLOR);

        array = NDImageUtils.resize(array, INPUT_W, INPUT_H, Image.Interpolation.BICUBIC);

        if (!DataType.FLOAT32.equals(array.getDataType())) {
            array = array.toType(DataType.FLOAT32, false);
        }

        array = array.transpose(2, 0, 1).div(255.0f);

        NDArray mean = manager.create(MEAN, new ai.djl.ndarray.types.Shape(3, 1, 1));
        NDArray std = manager.create(STD, new ai.djl.ndarray.types.Shape(3, 1, 1));
        array = array.sub(mean).div(std);

        array = array.expandDims(0);
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public float[][] processOutput(TranslatorContext ctx, NDList list) {
        NDArray heatmaps = list.singletonOrThrow();

        if (heatmaps.getShape().dimension() == 4 && heatmaps.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            heatmaps = heatmaps.squeeze(0);
        }

        long[] shape = heatmaps.getShape().getShape();
        int numKeypoints = (int) shape[0];
        int heatmapH = (int) shape[1];
        int heatmapW = (int) shape[2];

        float[][] keypoints = new float[NUM_KEYPOINTS][3];

        for (int k = 0; k < NUM_KEYPOINTS; k++) {
            if (k >= numKeypoints) {
                keypoints[k] = new float[]{0f, 0f, 0f};
                continue;
            }

            float maxVal = Float.NEGATIVE_INFINITY;
            int maxY = 0;
            int maxX = 0;

            for (int y = 0; y < heatmapH; y++) {
                for (int x = 0; x < heatmapW; x++) {
                    float val = heatmaps.getFloat(k, y, x);
                    if (val > maxVal) {
                        maxVal = val;
                        maxY = y;
                        maxX = x;
                    }
                }
            }

            float mapX = maxX / (float) (heatmapW - 1);
            float mapY = maxY / (float) (heatmapH - 1);

            float x = mapX * originalWidth;
            float y = mapY * originalHeight;

            keypoints[k] = new float[]{x, y, maxVal};
        }

        return keypoints;
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }
}
