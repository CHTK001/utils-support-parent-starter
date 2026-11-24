// [P3C A20 豁免] A20/A30/A31: 图像预处理含大量像素级魔法值，豁免。
package com.chua.deeplearning.support.onnx.uniface;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

/**
 * UniFace FaceMesh 人脸 3D 关键点 Translator（MediaPipe FaceMesh，468/478 点）。
 *
 * <p>对应 UniFace(Python) {@code uniface/landmark/facemesh.py}。输入为已裁剪的人脸区域，
 * 预处理与 Python 侧 angle=0、ROI=整图 的仿射路径等价：letterbox 到
 * {@value #INPUT_SIZE}×{@value #INPUT_SIZE}，仅缩放到 [0,1]（均值/方差归一化已并入图内）。</p>
 *
 * <p>模型输出两支：landmarks（N×3，裁剪图坐标系 0~192）与 presence logits（逐点可见性）。
 * 本 Translator 取元素数较多的一支作为关键点，并按
 * {@code x_img = (x_crop - S/2) * side / S + cx}、{@code y_img = (y_crop - S/2) * side / S + cy}、
 * {@code z_img = z_crop * side / S} 映射回原图坐标（side=max(w,h)，(cx,cy)=(w/2,h/2)）。</p>
 *
 * <p>输出为长度 3N 的一维数组 {@code [x0, y0, z0, x1, y1, z1, ...]}（原图坐标）。
 * presence 置信度不包含在输出中。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class UniFaceMeshTranslator implements Translator<Image, float[]> {

    /**
     * 模型输入边长。
     */
    private static final int INPUT_SIZE = 192;

    /**
     * 推理时暂存的原图宽。
     */
    private int width;

    /**
     * 推理时暂存的原图高。
     */
    private int height;

    /**
     * 处理输入
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        width = input.getWidth();
        height = input.getHeight();
        return UniFaceImagePreprocessor.toLetterboxTensor(input, INPUT_SIZE,
                UniFaceImagePreprocessor.ZERO_MEAN, UniFaceImagePreprocessor.ONE_STD,
                ctx.getNDManager());
    }

    /**
     * 处理输出
     */
    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray landmarks = null;
        long count = 0;
        for (NDArray out : list) {
            long c = out.getShape().size();
            if (c > count) {
                count = c;
                landmarks = out;
            }
        }
        if (landmarks == null || width == 0 || height == 0) {
            log.warn("[uniface-facemesh] 输出为空或缺少输入尺寸");
            return new float[0];
        }

        float[] crop = landmarks.toFloatArray();
        int numPoints = crop.length / 3;

        float side = Math.max(width, height);
        float cx = width / 2f;
        float cy = height / 2f;
        float scale = side / INPUT_SIZE;
        float half = INPUT_SIZE / 2f;

        float[] result = new float[crop.length];
        for (int i = 0; i < numPoints; i++) {
            result[3 * i] = (crop[3 * i] - half) * scale + cx;
            result[3 * i + 1] = (crop[3 * i + 1] - half) * scale + cy;
            result[3 * i + 2] = crop[3 * i + 2] * scale;
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
