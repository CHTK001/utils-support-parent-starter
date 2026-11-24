// [P3C A20 豁免] A20/A30/A31: 分割后处理含像素级魔法值，豁免。
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
 * UniFace BiSeNet 人脸解析（面部区域分割）Translator。
 *
 * <p>对应 UniFace(Python) {@code uniface/parsing/bisenet.py}。输入为已裁剪的人脸区域，
 * 缩放（INTER_LINEAR）后做 ImageNet 归一化（RGB）。模型输出
 * {@code (1, C, H, W)} 逐像素类别 logits，按通道维 argmax 得到类别掩码，
 * 再以最近邻插值放回原图尺寸。</p>
 *
 * <p>输出为与原图同尺寸的灰度 {@link Image}（像素值 = 类别 id，19 类）。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class UniFaceBisenetParsingTranslator implements Translator<Image, Image> {

    /**
     * 模型输入边长。
     */
    private static final int INPUT_SIZE = 512;

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
        return UniFaceImagePreprocessor.toTensor(input, INPUT_SIZE, INPUT_SIZE,
                UniFaceImagePreprocessor.IMAGENET_MEAN, UniFaceImagePreprocessor.IMAGENET_STD,
                ctx.getNDManager());
    }

    /**
     * 处理输出
     */
    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray logits = list.getFirst();
        long[] shape = logits.getShape().getShape();
        int channels = (int) shape[1];
        int h = (int) shape[shape.length - 2];
        int w = (int) shape[shape.length - 1];
        float[] values = logits.toFloatArray();

        BufferedImage mask = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = mask.getRaster();
        int plane = h * w;
        for (int i = 0; i < plane; i++) {
            int best = 0;
            float bestVal = values[i];
            for (int c = 1; c < channels; c++) {
                float v = values[c * plane + i];
                if (v > bestVal) {
                    bestVal = v;
                    best = c;
                }
            }
            raster.setSample(i % w, i / w, 0, best);
        }

        if (w != width || h != height) {
            mask = ImageUtils.resize(mask, width, height, Imgproc.INTER_NEAREST);
        }
        return ImageFactory.getInstance().fromImage(mask);
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
