package com.chua.deeplearning.support.onnx.matting.translator;
import com.chua.deeplearning.support.utils.ImageUtils;

import ai.djl.modality.cv.BufferedImageFactory;
import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.image.BufferedImage;

/**
 * MODNet 人像抠图 Translator（OpenCV 预处理 + djl-onnx 推理）。
 *
 * <p>输入图像 → OpenCV 缩放（正方形 targetSize，16 的倍数）→ CHW 归一化 → float[] 喂入 djl-onnx。
 * 输出 alpha mask [1,1,H,W]，合成透明 RGBA 图像。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DjlMattingTranslator implements Translator<Image, Image> {

    /**
     * 目标处理尺寸（MODNet 约束：16 的倍数，正方形）
     */
    private static final int TARGET_SIZE = 512;

    /**
     * 模型输入节点名
     */
    private static final String INPUT_NAME = "input";

    /**
     * 当前原始图像（processInput 保存，processOutput 合成用）
     */
    private BufferedImage originalImage;

    @Override
    @Nonnull
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        // OpenCV 预处理：Image → BufferedImage → Mat → 缩放 → CHW 归一化 → float[]
        ImageUtils.load();
        BufferedImage buffered = (BufferedImage) input.getWrappedImage();
        if (buffered == null) {
            throw new IllegalStateException("无法获取图像像素: " + input.getClass().getName());
        }
        this.originalImage = buffered;
        float[] pixels = preprocess(buffered);

        NDArray array = ctx.getNDManager().create(pixels, new Shape(1, 3, TARGET_SIZE, TARGET_SIZE));
        array.setName(INPUT_NAME);
        return new NDList(array);
    }

    /**
     * OpenCV 预处理：缩放正方形 + CHW 归一化。
     *
     * @param src 原图
     * @return [3, size, size] 归一化像素（RGB）
     */
    private float[] preprocess(BufferedImage src) {
        Mat img;
        try {
            img = ImageUtils.toMat(src);
        } catch (Exception e) {
            throw new IllegalStateException("图像转换失败", e);
        }
        try {
            Mat resized = ImageUtils.resize(img, TARGET_SIZE, TARGET_SIZE, Imgproc.INTER_LINEAR);

            float[] pixels = new float[3 * TARGET_SIZE * TARGET_SIZE];
            for (int y = 0; y < TARGET_SIZE; y++) {
                for (int x = 0; x < TARGET_SIZE; x++) {
                    double[] bgr = resized.get(y, x);
                    int idx = y * TARGET_SIZE + x;
                    pixels[idx] = (float) bgr[2] / 255.0f;                     // R
                    pixels[TARGET_SIZE * TARGET_SIZE + idx] = (float) bgr[1] / 255.0f;  // G
                    pixels[2 * TARGET_SIZE * TARGET_SIZE + idx] = (float) bgr[0] / 255.0f; // B
                }
            }
            resized.release();
            return pixels;
        } finally {
            img.release();
        }
    }

    @Override
    @Nonnull
    public Image processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray alpha = list.singletonOrThrow();
        float[] flat = alpha.toFloatArray();
        long[] shape = alpha.getShape().getShape();
        int h = (int) shape[2];
        int w = (int) shape[3];

        BufferedImage rgba = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float a = flat[y * w + x];
                if (Float.isNaN(a)) {
                    a = 0;
                }
                a = Math.max(0, Math.min(1, a));
                int argb = originalImage.getRGB(
                        Math.min(x * originalImage.getWidth() / w, originalImage.getWidth() - 1),
                        Math.min(y * originalImage.getHeight() / h, originalImage.getHeight() - 1));
                int alphaV = (int) (a * 255);
                rgba.setRGB(x, y, (alphaV << 24) | (argb & 0x00FFFFFF));
            }
        }
        return BufferedImageFactory.getInstance().fromImage(rgba);
    }

    @Nullable
    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}
