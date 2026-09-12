package com.chua.deeplearning.support.onnx.resolution;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.ImageUtils;
import com.chua.deeplearning.support.utils.TensorOptions;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
* GFPGAN 人脸修复/超分 Translator（ONNX 版）。
* <p>
* 预处理与 pytorch 版一致：直接 resize 到 512×512（不做 center-crop），
* mean=[0.5,0.5,0.5] std=[0.5,0.5,0.5] 归一化，
* 输出 [-1,1] → 还原 [0,255]。
* </p>
* <p>
* 注意：GFPGAN 模型输入期望 FFHQ 标准 512×512 对齐人脸，
* 独立使用时应先通过 facepipeline.restorewithalign() 做 5 点仿射对齐。
* </p>
*
* @author CH
* @since 2024/11/08
 */
public class GfpganFaceSuperResolutionTranslator implements Translator<Image, Image> {

    private static final int INPUT_SIZE = 512; // 输入大小
    private static final float[] MEAN = {0.5f, 0.5f, 0.5f}; // MEAN
    private static final float[] STD = {0.5f, 0.5f, 0.5f}; // STD

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDManager manager = ctx.getNDManager();
 // 与 pytorch 版一致：直接 resize 到 512x512，不 center-crop
        float[] pixels = ImageUtils.toTensor(new TensorOptions(input, INPUT_SIZE, MEAN, STD, false));
        NDArray array = manager.create(pixels, new Shape(3, INPUT_SIZE, INPUT_SIZE));
        return new NDList(array);
    }

    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray array = list.get(0);
        long[] shape = array.getShape().getShape();
        if (shape.length == 4) {
            array = array.squeeze(0);
            shape = array.getShape().getShape();
        }
        if (shape.length != 3) {
            throw new IllegalStateException("GFPGAN 输出维度异常: " + java.util.Arrays.toString(shape));
        }

        int h = (int) shape[1];
        int w = (int) shape[2];
        float[] data = array.toFloatArray();

        // [-1,1] → [0,255]
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int stride = h * w;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int r = clampU8(data[idx]);
                int g = clampU8(data[idx + stride]);
                int b = clampU8(data[idx + 2 * stride]);
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return ImageFactory.getInstance().fromImage(img);
    }

    /**
    * clampu8。
    * @param v v
    * @return clampU8的结果
     */
    private static int clampU8(float v) {
        float x = Math.max(-1f, Math.min(1f, v));
        return (int) Math.round((x + 1f) / 2f * 255f);
    }

    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}