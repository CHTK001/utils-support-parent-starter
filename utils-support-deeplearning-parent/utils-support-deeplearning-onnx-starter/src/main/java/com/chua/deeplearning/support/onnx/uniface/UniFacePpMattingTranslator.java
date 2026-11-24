// [P3C A20 豁免] A20/A30/A31: 抠图预处理含像素级魔法值，豁免。
package com.chua.deeplearning.support.onnx.uniface;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;
import org.opencv.imgproc.Imgproc;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * UniFace PP-Matting 人像抠图 Translator。
 *
 * <p>对应 UniFace(Python) {@code uniface/matting/ppmatting.py}。预处理：短边超过
 * {@value #MAX_SHORT} 时等比缩小（另一边四舍五入），两边向下取整到 32 的倍数，
 * 归一化到 [-1,1]（即 {@code (x/255 - 0.5) / 0.5}，RGB）。要求图像至少
 * {@value #STRIDE}×{@value #STRIDE}。</p>
 *
 * <p>模型输出 {@code (1, 1, H', W')} alpha matte，取 [0,0] 平面并 clamp 到 [0,1]，
 * 以线性插值放回原图尺寸。输出为与原图同尺寸的灰度 {@link Image}
 * （像素值 = alpha × 255）。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class UniFacePpMattingTranslator implements Translator<Image, Image> {

    /**
     * 短边上限。
     */
    private static final int MAX_SHORT = 512;

    /**
     * 网络步长（边长需为其倍数）。
     */
    private static final int STRIDE = 32;

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
        BufferedImage src = UniFaceImagePreprocessor.unwrap(input);
        int origW = src.getWidth();
        int origH = src.getHeight();
        if (origW < STRIDE || origH < STRIDE) {
            throw new IllegalArgumentException(
                    "PPMatting 需要至少 " + STRIDE + "x" + STRIDE + " 的图像，实际 " + origH + "x" + origW);
        }
        width = origW;
        height = origH;

        int newW = origW;
        int newH = origH;
        int shortSide = Math.min(origW, origH);
        if (shortSide > MAX_SHORT) {
            float scale = MAX_SHORT / (float) shortSide;
            newW = Math.round(origW * scale);
            newH = Math.round(origH * scale);
        }
        int flooredW = newW - newW % STRIDE;
        int flooredH = newH - newH % STRIDE;
        newW = Math.max(STRIDE, flooredW);
        newH = Math.max(STRIDE, flooredH);

        return UniFaceImagePreprocessor.toTensor(input, newW, newH,
                UniFaceImagePreprocessor.HALF_MEAN, UniFaceImagePreprocessor.HALF_STD,
                ctx.getNDManager());
    }

    /**
     * 处理输出
     */
    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray matte = list.getFirst();
        long[] shape = matte.getShape().getShape();
        int h = (int) shape[shape.length - 2];
        int w = (int) shape[shape.length - 1];
        float[] values = matte.toFloatArray();

        BufferedImage alpha = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = alpha.getRaster();
        for (int i = 0; i < values.length; i++) {
            float v = Math.max(0f, Math.min(1f, values[i]));
            raster.setSample(i % w, i / w, 0, Math.round(v * 255f));
        }

        if (w != width || h != height) {
            alpha = ImageUtils.resize(alpha, width, height, Imgproc.INTER_LINEAR);
        }
        return ImageFactory.getInstance().fromImage(alpha);
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
