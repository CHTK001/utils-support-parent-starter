package com.chua.deeplearning.support.onnx.insightface;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.onnx.face.OnnxImageProcessor;

/**
 * 洞见face 2D 人脸关键点 Translator（buffalo_l 2d106det）。
 *
 * <p>192×192 RGB 输入，输出 212 维（106 关键点 × 2 坐标，
 * 归一化到 [0,1]）。关键点顺序：轮廓/眉毛/眼睛/鼻子/嘴，用于人脸对齐、美颜、换脸。</p>
 *
 * <p>注意：2d106det 的 ONNX 图内已内嵌 (p-127.5)/128 归一化，本 translator 必须喂
 * 0..255 原始像素（等价 insightface 官方 input_mean=0、input_std=1 分支），
 * 否则双重归一化会把输入压成近常量场、输出先验模板（点不贴脸）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class InsightFaceLandmarkTranslator implements Translator<Image, float[]> {

    /**
     * 输入尺寸
    */
    private static final int INPUT_SIZE = 192;

    @Override
    /**
     * 处理输入
    */
    public NDList processInput(TranslatorContext ctx, Image input) {
        // 2d106det 的 ONNX 图内已内嵌归一化（图首节点 Sub 127.5 → Mul 1/128，标量
        // scalar_op1/scalar_op2 即为这两步）。insightface 官方 Landmark 类对这种图
        // 走 input_mean=0、input_std=1，即喂 0..255 原始像素；若再按 (p-127.5)/128
        // 外部归一化会造成双重归一化：图内再减一次后输入塌缩到 ≈-0.99 的近常量场，
        // 模型看不到人脸、只输出先验模板（实测点云仅铺开人脸框 51%，轮廓点落在脸颊上，
        // 即“特征点不贴脸、偏离离谱”）。这里必须喂原始像素，归一化交给模型图自己完成。
        return OnnxImageProcessor.toModelInput(input, INPUT_SIZE, INPUT_SIZE, 3, false,
                0.0f, 1.0f, ctx.getNDManager());
    }

    @Override
    /**
     * 处理输出
    */
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray array = list.singletonOrThrow();
        return array.toFloatArray();
    }

    @Override
    /**
     * 获取Batchifier
    */
    public Batchifier getBatchifier() {
        return null;
    }
}
