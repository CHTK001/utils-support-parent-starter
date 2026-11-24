// [P3C A20 豁免] A20/A30/A31: 关键点后处理含大量索引/缩放魔法值，豁免。
package com.chua.deeplearning.support.onnx.uniface;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;

/**
 * UniFace PIPNet 人脸 2D 关键点 Translator（WFLW 98 点）。
 *
 * <p>对应 UniFace(Python) {@code uniface/landmark/pipnet.py}。输入为已裁剪的人脸区域；
 * 预处理按 PIPNet 约定先做 +10% 边距扩展（左/右/下扩张、上收缩并 clamp），
 * 对预裁剪整图输入即取行区间 {@code [int(0.1h), h)}，再缩放到
 * {@value #INPUT_SIZE}×{@value #INPUT_SIZE} 并做 ImageNet 归一化（RGB）。</p>
 *
 * <p>模型 5 路输出依次为：cls_map (1,n,8,8)、offset_x、offset_y (1,n,8,8)、
 * nb_x、nb_y (1,n*10,8,8)。后处理：对每个关键点取 cls 最大的格子，
 * {@code pred = (col + offset) / scale}（scale = input/stride = 8），
 * 再与 10 个邻居在同格子处的预测按 reverse_index 融合取均值，
 * 最后按 {@code x = merged * crop_w + x1} 映射回原图坐标。</p>
 *
 * <p>输出为长度 196 的一维数组 {@code [x0, y0, x1, y1, ...]}（原图坐标）。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class UniFacePipnetLandmarkTranslator implements Translator<Image, float[]> {

    /**
     * 模型输入边长。
     */
    private static final int INPUT_SIZE = 256;

    /**
     * 网络下采样步长（input/stride = 特征图边长 8）。
     */
    private static final int NET_STRIDE = 32;

    /**
     * 上边距收缩比例。
     */
    private static final double TOP_MARGIN = 0.1;

    /**
     * 推理时暂存的裁剪元信息：x1。
     */
    private int cropX1;

    /**
     * 推理时暂存的裁剪元信息：y1。
     */
    private int cropY1;

    /**
     * 推理时暂存的裁剪元信息：宽。
     */
    private int cropW;

    /**
     * 推理时暂存的裁剪元信息：高。
     */
    private int cropH;

    /**
     * 处理输入
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        BufferedImage src = UniFaceImagePreprocessor.unwrap(input);
        int w = src.getWidth();
        int h = src.getHeight();

        // bbox=[0,0,w-1,h-1] 经 +10% 左/右/下、-10% 上并 clamp 后：
        // x1=0, y1=int(0.1h), crop_w=w, crop_h=h-int(0.1h)
        cropX1 = 0;
        cropY1 = (int) (TOP_MARGIN * h);
        cropW = w;
        cropH = h - cropY1;

        BufferedImage crop = src.getSubimage(cropX1, cropY1, cropW, cropH);
        float[] chw = UniFaceImagePreprocessor.rgbChw(crop, INPUT_SIZE, INPUT_SIZE,
                UniFaceImagePreprocessor.IMAGENET_MEAN, UniFaceImagePreprocessor.IMAGENET_STD);
        return UniFaceImagePreprocessor.wrap(chw, INPUT_SIZE, INPUT_SIZE, ctx.getNDManager());
    }

    /**
     * 处理输出
     */
    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        if (list.size() < 5) {
            log.warn("[uniface-pipnet] 输出路数不足: {}", list.size());
            return new float[0];
        }
        NDArray cls = list.get(0);
        NDArray offsetX = list.get(1);
        NDArray offsetY = list.get(2);
        NDArray nbX = list.get(3);
        NDArray nbY = list.get(4);

        int n = (int) cls.getShape().get(1);
        int feat = (int) cls.getShape().get(2);
        int cells = feat * feat;
        int scale = INPUT_SIZE / NET_STRIDE;

        float[] clsFlat = cls.toFloatArray();
        float[] offXFlat = offsetX.toFloatArray();
        float[] offYFlat = offsetY.toFloatArray();
        float[] nbXFlat = nbX.toFloatArray();
        float[] nbYFlat = nbY.toFloatArray();

        float[] predX = new float[n];
        float[] predY = new float[n];
        int[] maxIds = new int[n];
        for (int i = 0; i < n; i++) {
            int base = i * cells;
            int maxId = 0;
            float maxVal = clsFlat[base];
            for (int c = 1; c < cells; c++) {
                if (clsFlat[base + c] > maxVal) {
                    maxVal = clsFlat[base + c];
                    maxId = c;
                }
            }
            maxIds[i] = maxId;
            int col = maxId % feat;
            int row = maxId / feat;
            predX[i] = (col + offXFlat[base + maxId]) / scale;
            predY[i] = (row + offYFlat[base + maxId]) / scale;
        }

        // 邻居预测：nb 输出形状 (1, n*10, feat, feat)，按 max_ids 取同格子值
        int nbPer = cells;
        float[] nbPredX = new float[n * UniFacePipnetMeanface.NUM_NEIGHBORS];
        float[] nbPredY = new float[n * UniFacePipnetMeanface.NUM_NEIGHBORS];
        for (int i = 0; i < n; i++) {
            int col = maxIds[i] % feat;
            int row = maxIds[i] / feat;
            for (int j = 0; j < UniFacePipnetMeanface.NUM_NEIGHBORS; j++) {
                int nbBase = (i * UniFacePipnetMeanface.NUM_NEIGHBORS + j) * nbPer + maxIds[i];
                nbPredX[i * UniFacePipnetMeanface.NUM_NEIGHBORS + j] = (col + nbXFlat[nbBase]) / scale;
                nbPredY[i * UniFacePipnetMeanface.NUM_NEIGHBORS + j] = (row + nbYFlat[nbBase]) / scale;
            }
        }

        UniFacePipnetMeanface meanface = UniFacePipnetMeanface.getInstance();
        int[] revIdx1 = meanface.reverseIndex1();
        int[] revIdx2 = meanface.reverseIndex2();
        int maxLen = meanface.maxLen();

        float[] result = new float[n * 2];
        for (int i = 0; i < n; i++) {
            double sumX = predX[i];
            double sumY = predY[i];
            for (int m = 0; m < maxLen; m++) {
                int flat = i * maxLen + m;
                int idx = revIdx1[flat] * UniFacePipnetMeanface.NUM_NEIGHBORS + revIdx2[flat];
                sumX += nbPredX[idx];
                sumY += nbPredY[idx];
            }
            double mergedX = sumX / (maxLen + 1);
            double mergedY = sumY / (maxLen + 1);
            result[2 * i] = (float) (mergedX * cropW + cropX1);
            result[2 * i + 1] = (float) (mergedY * cropH + cropY1);
        }
        return result;
    }

    /**
     * 获取Batchifier
     */
    @Override
    public Batchifier getBatchifier() {
        // ONNX Runtime 的 ndarray 不支持 Stack，单图推理不批处理
        return null;
    }
}
