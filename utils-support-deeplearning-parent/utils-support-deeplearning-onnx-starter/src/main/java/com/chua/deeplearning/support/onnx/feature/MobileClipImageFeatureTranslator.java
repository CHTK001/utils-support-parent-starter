package com.chua.deeplearning.support.onnx.feature;
import com.chua.deeplearning.support.onnx.utils.OpenCvImageUtils;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.image.BufferedImage;

/**
 * MobileCLIP S0 图像特征 Translator。
 *
 * <p>输入图像 → 短边缩放 + 中心裁剪（OpenCV 预处理）→ CHW 归一化 → float[] 喂入 djl-onnx。
 * 输出 512 维图像特征。</p>
 *
 * @author CH
 * @since 2026-05-02
 */
public class MobileClipImageFeatureTranslator implements Translator<Image, float[]> {

    private static final float[] IMAGE_MEAN = {0.48145466f, 0.4578275f, 0.40821073f};
    private static final float[] IMAGE_STD = {0.26862954f, 0.26130258f, 0.27577711f};
    private static final int IMAGE_SIZE = 256;

    @Override
    @Nonnull
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        // OpenCV 预处理：Image → BufferedImage → Mat → 短边缩放 + 中心裁剪 → CHW 归一化 → float[]
        OpenCvImageUtils.load();
        BufferedImage buffered = (BufferedImage) input.getWrappedImage();
        if (buffered == null) {
            throw new IllegalStateException("无法获取图像像素: " + input.getClass().getName());
        }
        float[] pixels = preprocess(buffered);

        // 用 djl-onnx 的 NDManager 创建输入 NDArray（不参与张量计算，仅喂入）
        NDArray array = ctx.getNDManager().create(pixels, new Shape(1, 3, IMAGE_SIZE, IMAGE_SIZE));
        array.setName("pixel_values");
        return new NDList(array);
    }

    /**
     * OpenCV 预处理：短边缩放 + 中心裁剪 → CHW 归一化。
     *
     * @param src 原图
     * @return [3, size, size] 归一化像素
     */
    private float[] preprocess(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        float percent = (float) IMAGE_SIZE / Math.min(w, h);
        int rw = Math.round(w * percent);
        int rh = Math.round(h * percent);

        Mat img;
        org.opencv.core.MatOfByte mob;
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(src, "png", baos);
            mob = new org.opencv.core.MatOfByte(baos.toByteArray());
            img = org.opencv.imgcodecs.Imgcodecs.imdecode(mob, org.opencv.imgcodecs.Imgcodecs.IMREAD_COLOR);
        } catch (Exception e) {
            throw new IllegalStateException("图像转换失败", e);
        }
        try {
            Mat resized = new Mat();
            Imgproc.resize(img, resized, new Size(rw, rh), 0, 0, Imgproc.INTER_CUBIC);
            int x0 = (rw - IMAGE_SIZE) / 2;
            int y0 = (rh - IMAGE_SIZE) / 2;
            Mat crop = new Mat(resized, new Rect(x0, y0, IMAGE_SIZE, IMAGE_SIZE));

            float[] pixels = new float[3 * IMAGE_SIZE * IMAGE_SIZE];
            for (int y = 0; y < IMAGE_SIZE; y++) {
                for (int x = 0; x < IMAGE_SIZE; x++) {
                    double[] bgr = crop.get(y, x);
                    float b = (float) bgr[0] / 255.0f;
                    float g = (float) bgr[1] / 255.0f;
                    float r = (float) bgr[2] / 255.0f;
                    int idx = y * IMAGE_SIZE + x;
                    pixels[idx] = (r - IMAGE_MEAN[0]) / IMAGE_STD[0];
                    pixels[IMAGE_SIZE * IMAGE_SIZE + idx] = (g - IMAGE_MEAN[1]) / IMAGE_STD[1];
                    pixels[2 * IMAGE_SIZE * IMAGE_SIZE + idx] = (b - IMAGE_MEAN[2]) / IMAGE_STD[2];
                }
            }
            crop.release();
            resized.release();
            return pixels;
        } finally {
            img.release();
            mob.release();
        }
    }

    @Override
    @Nonnull
    public float[] processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray output = list.singletonOrThrow();
        if (output.getShape().dimension() > 1 && output.getShape().get(0) == 1) {
            output = output.squeeze(0);
        }
        return output.toFloatArray();
    }

    @Nullable
    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}
