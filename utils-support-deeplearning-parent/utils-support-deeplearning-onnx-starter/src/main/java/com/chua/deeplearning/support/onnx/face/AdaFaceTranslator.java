package com.chua.deeplearning.support.onnx.face;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;


/**
* adaface — 自适应人脸特征提取模型（Adaptive Face 认可）
* <p>
* 基于自适应 margin 策略，在低质量人脸上比 arcface 表现更优。
* 典型变体：adaface IR101 (webface12M 训练)，输入 112x112 RGB 人脸图，输出 512 维归一化 嵌入。
* <p>
* 输入要求：
* - 单张人脸图像（已裁剪对齐）
* - 尺寸：112x112（自动 resize）
* - 色彩：RGB
* - 归一化：(pixel - 127.5) / 128.0
* - 通道顺序：CHW
* <p>
* 输出说明：
* - 512 维 float[] 嵌入
* - 已做 L2 归一化
*
* @author CH
* @since 2026-07-22
 */
public class AdaFaceTranslator implements Translator<Image, float[]> {

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);

        // Resize 到 112x112
        array = NDImageUtils.resize(array, 112, 112);

        // 转为 FLOAT32
        if (!array.getDataType().equals(DataType.FLOAT32)) {
            array = array.toType(DataType.FLOAT32, false);
        }

        // 通道转 CHW
        array = array.transpose(2, 0, 1);

        // 归一化：(pixel - 127.5) / 128.0
        array = array.sub(127.5f).div(128.0f);

        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray array = list.singletonOrThrow();
        float[] features = array.toFloatArray();

        // L2 归一化
        float norm = 0.0f;
        for (float feature : features) {
            norm += feature * feature;
        }
        norm = (float) Math.sqrt(norm);

        if (norm > 0.0f) {
            for (int i = 0; i < features.length; i++) {
                features[i] = features[i] / norm;
            }
        }

        return features;
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
