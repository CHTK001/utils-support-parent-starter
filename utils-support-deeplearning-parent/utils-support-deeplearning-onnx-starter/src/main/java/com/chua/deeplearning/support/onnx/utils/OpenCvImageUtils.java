package com.chua.deeplearning.support.onnx.utils;

import ai.djl.modality.cv.Image;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint2f;
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

    /**
     * FFHQ 512×512 标准 5 点模板（左眼、右眼、鼻、左嘴角、右嘴角）。
     */
    private static final double[][] FACE_TEMPLATE_512 = {
            {192.98138, 239.94708},
            {318.90277, 240.1936},
            {256.63416, 314.01935},
            {201.26117, 371.41043},
            {313.08905, 371.15118}
    };

    /**
     * 5 点仿射对齐人脸到标准模板（修复/超分前处理，避免拉伸变形）。
     *
     * <p>与 AIAS face_restoration_sdk 一致：用 5 点（左眼、右眼、鼻、左嘴角、右嘴角）
     * 最小二乘估计 2×3 仿射矩阵（SVD 求解超定方程），warpAffine 到 512×512 FFHQ 模板。</p>
     *
     * @param src       原图 Mat（BGR）
     * @param keypoints 源人脸 5 点（像素坐标，顺序：左眼、右眼、鼻、左嘴角、右嘴角）
     * @param outSize   输出边长（512）
     * @return 对齐后的 Mat
     */
    public static Mat alignFace(Mat src, java.util.List<float[]> keypoints, int outSize) {
        load();
        if (keypoints == null || keypoints.size() < 5) {
            throw new IllegalArgumentException("人脸关键点不足 5 点: " + (keypoints == null ? 0 : keypoints.size()));
        }
        Mat affine = estimateAffine5Point(keypoints, FACE_TEMPLATE_512);
        // 先按 512 模板对齐，再缩放到目标尺寸
        Mat aligned512 = new Mat();
        Imgproc.warpAffine(src, aligned512, affine, new Size(512, 512),
                Imgproc.INTER_CUBIC, 0, new org.opencv.core.Scalar(135, 133, 132));
        Mat aligned;
        if (outSize == 512) {
            aligned = aligned512;
        } else {
            aligned = new Mat();
            Imgproc.resize(aligned512, aligned, new Size(outSize, outSize), 0, 0, Imgproc.INTER_CUBIC);
            aligned512.release();
        }
        affine.release();
        return aligned;
    }

    /**
     * 5 点最小二乘估计仿射矩阵（2×3）。
     *
     * <p>仿射模型 y = A·x + t，对 5 点建立超定方程组，用 OpenCV {@code Core.solve}
     * 求解最小二乘，等价于 AIAS 的 SVD 解法。</p>
     *
     * @param srcPoints 源 5 点（像素坐标）
     * @param dstPoints 目标 5 点（模板坐标）
     * @return 2×3 仿射矩阵 Mat
     */
    public static Mat estimateAffine5Point(java.util.List<float[]> srcPoints, double[][] dstPoints) {
        // 构造 10 行 × 7 列（6 未知数 + 1 常数）方程
        Mat a = new Mat(10, 6, org.opencv.core.CvType.CV_64F);
        Mat b = new Mat(10, 1, org.opencv.core.CvType.CV_64F);
        for (int i = 0; i < 5; i++) {
            float sx = srcPoints.get(i)[0];
            float sy = srcPoints.get(i)[1];
            double dx = dstPoints[i][0];
            double dy = dstPoints[i][1];
            // 行 2i：   a00*sx + a01*sy + a02 = dx
            a.put(2 * i, 0, sx);
            a.put(2 * i, 1, sy);
            a.put(2 * i, 2, 1);
            a.put(2 * i, 3, 0);
            a.put(2 * i, 4, 0);
            a.put(2 * i, 5, 0);
            b.put(2 * i, 0, dx);
            // 行 2i+1： a10*sx + a11*sy + a12 = dy
            a.put(2 * i + 1, 0, 0);
            a.put(2 * i + 1, 1, 0);
            a.put(2 * i + 1, 2, 0);
            a.put(2 * i + 1, 3, sx);
            a.put(2 * i + 1, 4, sy);
            a.put(2 * i + 1, 5, 1);
            b.put(2 * i + 1, 0, dy);
        }
        Mat x = new Mat(6, 1, org.opencv.core.CvType.CV_64F);
        boolean solved = org.opencv.core.Core.solve(a, b, x, org.opencv.core.Core.DECOMP_SVD);
        if (!solved) {
            throw new IllegalStateException("仿射矩阵最小二乘求解失败");
        }
        double[] xd = new double[6];
        x.get(0, 0, xd);
        Mat affine = new Mat(2, 3, org.opencv.core.CvType.CV_64F);
        affine.put(0, 0, xd[0], xd[1], xd[2]);
        affine.put(1, 0, xd[3], xd[4], xd[5]);
        a.release();
        b.release();
        x.release();
        return affine;
    }
}
