package com.chua.deeplearning.support.onnx.utils;

import ai.djl.modality.cv.Image;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDouble;
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

    /**
     * OpenCV 是否已加载（静态单例，进程内只加载一次）。
     */
    private static volatile boolean loaded;

    private OpenCvImageUtils() {
    }

    /**
     * 确保 OpenCV 已加载（幂等，进程内只加载一次）。
     *
     * <p>统一在此管理 {@code nu.pattern.OpenCV.loadLocally()}，
     * 各 translator 一律调用本方法，避免散落的重复加载。</p>
     */
    public static void load() {
        if (!loaded) {
            synchronized (OpenCvImageUtils.class) {
                if (!loaded) {
                    nu.pattern.OpenCV.loadLocally();
                    loaded = true;
                }
            }
        }
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
        load();
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

    /**
     * 解码 PNG/JPG 字节为 Mat。
     *
     * @param imageData 图像字节
     * @return Mat，解码失败返回 null
     */
    public static Mat decode(byte[] imageData) {
        load();
        if (imageData == null || imageData.length == 0) {
            return null;
        }
        return org.opencv.imgcodecs.Imgcodecs.imdecode(new MatOfByte(imageData),
                org.opencv.imgcodecs.Imgcodecs.IMREAD_COLOR);
    }

    /**
     * Mat 编码为 PNG 字节。
     *
     * @param mat Mat
     * @return PNG 字节
     */
    public static byte[] encode(Mat mat) {
        load();
        MatOfByte mob = new MatOfByte();
        org.opencv.imgcodecs.Imgcodecs.imencode(".png", mat, mob);
        return mob.toArray();
    }

    /**
     * 图像放大 scale 倍（双线性插值）。
     *
     * @param imageData 图像字节
     * @param scale     缩放倍数
     * @return 放大后 PNG 字节
     */
    public static byte[] upscale(byte[] imageData, double scale) {
        Mat src = decode(imageData);
        if (src == null) {
            return imageData;
        }
        Mat out = new Mat();
        try {
            Imgproc.resize(src, out, new Size(src.cols() * scale, src.rows() * scale),
                    0, 0, Imgproc.INTER_CUBIC);
            return encode(out);
        } finally {
            out.release();
            src.release();
        }
    }

    /**
     * 旋转图像（90/180/270 度）。
     *
     * @param imageData 图像字节
     * @param degree    90/180/270
     * @return 旋转后 PNG 字节
     */
    public static byte[] rotate(byte[] imageData, int degree) {
        Mat src = decode(imageData);
        if (src == null) {
            return imageData;
        }
        Mat out = new Mat();
        try {
            switch (degree) {
                case 90 -> Core.rotate(src, out, Core.ROTATE_90_CLOCKWISE);
                case 270 -> Core.rotate(src, out, Core.ROTATE_90_COUNTERCLOCKWISE);
                default -> Core.rotate(src, out, Core.ROTATE_180);
            }
            return encode(out);
        } finally {
            out.release();
            src.release();
        }
    }

    /**
     * 平均亮度是否低于阈值（深色背景）。
     *
     * @param imageData 图像字节
     * @return true 表示深色背景
     */
    public static boolean isDarkBackground(byte[] imageData) {
        Mat src = decode(imageData);
        if (src == null) {
            return false;
        }
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
            MatOfDouble mean = new MatOfDouble();
            MatOfDouble std = new MatOfDouble();
            try {
                Core.meanStdDev(gray, mean, std);
                return mean.get(0, 0)[0] < 128;
            } finally {
                mean.release();
                std.release();
            }
        } finally {
            gray.release();
            src.release();
        }
    }

    /**
     * 深背景自动反色为白底黑字（提升 OCR 识别率）。
     *
     * @param imageData 图像字节
     * @return 反色后 PNG 字节；浅背景原样返回
     */
    public static byte[] invertIfDark(byte[] imageData) {
        Mat src = decode(imageData);
        if (src == null) {
            return imageData;
        }
        Mat gray = new Mat();
        Mat inv = new Mat();
        try {
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
            MatOfDouble mean = new MatOfDouble();
            MatOfDouble std = new MatOfDouble();
            try {
                Core.meanStdDev(gray, mean, std);
                double avg = mean.get(0, 0)[0];
                if (avg >= 128) {
                    return imageData;
                }
                Core.bitwise_not(src, inv);
                return encode(inv);
            } finally {
                mean.release();
                std.release();
            }
        } finally {
            inv.release();
            gray.release();
            src.release();
        }
    }
}
