package com.chua.deeplearning.support.onnx.utils;

import ai.djl.modality.cv.Image;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * OpenCV 图像预处理工具（替代 DJL NDArray / NDImageUtils）。
 *
 * <p>在 DJL onnxruntime-engine 下，NDManager 不支持 resize/div 等张量计算。
 * 本工具用 OpenCV 完成图像操作，返回 float[] 像素，再由
 * {@code ctx.getNDManager().create(float[], shape)} 喂入模型。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class OpenCvImageUtils {

    private OpenCvImageUtils() {
    }

    /**
     * 将 DJL Image 缩放并转 CHW 归一化像素（RGB 顺序，无 mean/std）。
     *
     * @param image DJL 图像
     * @param size  目标尺寸（正方形）
     * @return [3, size, size] float 像素
     */
    public static float[] toTensor(Image image, int size) {
        return toTensor(image, size, null, null, false);
    }

    /**
     * 将 DJL Image 短边缩放 + 中心裁剪，转 CHW 归一化像素（RGB，mean/std）。
     *
     * @param image DJL 图像
     * @param size  目标尺寸
     * @param mean  均值（可为 null）
     * @param std   标准差（可为 null）
     * @return [3, size, size] float 像素
     */
    public static float[] toTensorCenterCrop(Image image, int size, float[] mean, float[] std) {
        return toTensor(image, size, mean, std, true);
    }

    /**
     * 将 DJL Image 转 CHW 归一化像素。
     *
     * @param image      DJL 图像
     * @param size       目标尺寸
     * @param mean       均值（可为 null）
     * @param std        标准差（可为 null）
     * @param centerCrop true 短边缩放+中心裁剪；false 直接拉伸
     * @return [3, size, size] float 像素
     */
    public static float[] toTensor(Image image, int size, float[] mean, float[] std, boolean centerCrop) {
        nu.pattern.OpenCV.loadLocally();
        BufferedImage buffered = (BufferedImage) image.getWrappedImage();
        if (buffered == null) {
            throw new IllegalStateException("无法获取图像像素: " + image.getClass().getName());
        }
        Mat img = toMat(buffered);
        try {
            int w = img.cols();
            int h = img.rows();
            Mat resized;
            if (centerCrop) {
                float percent = (float) size / Math.min(w, h);
                int rw = Math.round(w * percent);
                int rh = Math.round(h * percent);
                resized = new Mat();
                Imgproc.resize(img, resized, new Size(rw, rh), 0, 0, Imgproc.INTER_CUBIC);
                int x0 = (rw - size) / 2;
                int y0 = (rh - size) / 2;
                resized = new Mat(resized, new Rect(x0, y0, size, size));
            } else {
                resized = new Mat();
                Imgproc.resize(img, resized, new Size(size, size), 0, 0, Imgproc.INTER_CUBIC);
            }
            try {
                float[] pixels = new float[3 * size * size];
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        double[] bgr = resized.get(y, x);
                        float b = (float) bgr[0] / 255.0f;
                        float g = (float) bgr[1] / 255.0f;
                        float r = (float) bgr[2] / 255.0f;
                        int idx = y * size + x;
                        pixels[idx] = mean == null ? r : (r - mean[0]) / std[0];
                        pixels[size * size + idx] = mean == null ? g : (g - mean[1]) / std[1];
                        pixels[2 * size * size + idx] = mean == null ? b : (b - mean[2]) / std[2];
                    }
                }
                return pixels;
            } finally {
                resized.release();
            }
        } finally {
            img.release();
        }
    }

    /**
     * BufferedImage → OpenCV Mat（BGR）。
     *
     * @param image 图像
     * @return Mat
     */
    private static Mat toMat(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", baos);
            MatOfByte mob = new MatOfByte(baos.toByteArray());
            Mat mat = org.opencv.imgcodecs.Imgcodecs.imdecode(mob, org.opencv.imgcodecs.Imgcodecs.IMREAD_COLOR);
            mob.release();
            return mat;
        } catch (Exception e) {
            throw new IllegalStateException("图像转换失败", e);
        }
    }
}
