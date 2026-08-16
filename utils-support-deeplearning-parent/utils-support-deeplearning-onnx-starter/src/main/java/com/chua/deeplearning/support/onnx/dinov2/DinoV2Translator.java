package com.chua.deeplearning.support.onnx.dinov2;
import com.chua.deeplearning.support.onnx.utils.OpenCvImageUtils;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import javax.annotation.Nonnull;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * DINOv2 视觉特征 Translator。
 * <p>
 * 输入图像 → 短边缩放 + 中心裁剪（OpenCV 预处理）→ CHW 归一化 → float[] 喂入 djl-onnx。
 * 输出 384 维图像特征（取 CLS token）。
 * </p>
 *
 * @author CH
 * @since 2025-01-22
 */
@Slf4j
@Spi("dinov2")
public class DinoV2Translator implements Translator<Image, float[]> {

    /**
     * DINOv2 输入尺寸
     */
    private static final int IMAGE_SIZE = 224;

    /**
     * ImageNet 均值
     */
    private static final float[] IMAGE_MEAN = {0.485f, 0.456f, 0.408f};

    /**
     * ImageNet 标准差
     */
    private static final float[] IMAGE_STD = {0.229f, 0.224f, 0.225f};


    /**
     *                   
     * <p>
     *                                                 
     * 1.                                      224   
     * 2.                 224x224
     * 3.              [0, 1]
     * 4.                                        
     * 5.           NCHW                            
     * </p>
     *
     * @param ctx                     
     * @param input             
     * @return              NDList                   [1, 3, 224, 224]          
     */
    @Override
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
        MatOfByte mob;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(src, "png", baos);
            mob = new MatOfByte(baos.toByteArray());
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
    public float[] processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray output = list.singletonOrThrow();
        // 用 Java 层手动处理：取 CLS token（第 0 行）
        float[] flat = output.toFloatArray();
        long[] shape = output.getShape().getShape();
        if (shape.length >= 3 && shape[1] > 0) {
            int seq = (int) shape[1];
            int dim = (int) shape[2];
            float[] cls = new float[dim];
            System.arraycopy(flat, 0, cls, 0, dim);
            return cls;
        }
        return flat;
    }

    /**
     *                   
     *
     * @return null                     
     */
    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}


