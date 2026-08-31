package com.chua.deeplearning.support.utils;

import ai.djl.modality.cv.Image;
import com.chua.common.support.image.ImageProcessor;
import com.chua.common.support.image.ImageProcessors;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.model.PredictRectangle;

/**
 * 图像预处理工具（替代 DJL NDArray / NDImageUtils）。
 *
 * <p>在 DJL onnxruntime-engine 下，NDManager 不支持 resize/div 等张量计算。
 * 本工具完成图像操作，返回 float[] 像素，再由
 * {@code ctx.getNDManager().create(float[], shape)} 喂入模型。</p>
 *
 * <p>字节级操作（裁剪/旋转等）优先委托 {@link ImageProcessor} SPI 代理执行
 * （按 {@code @SpiOrder} 优先级：Rust &gt; OpenCV &gt; AWT，失败自动降级），
 * 代理执行失败时回退到本地 OpenCV 实现，保证兼容性。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ImageUtils {

    /**
     * OpenCV 是否已加载（静态单例，进程内只加载一次）。
     */
    private static volatile boolean loaded;

    /**
     * 工具类私有构造，防止实例化。
     */
    private ImageUtils() {
    }

    /**
     * 获取按优先级自动降级的图像处理器代理。
     *
     * @return 图像处理器代理
     */
    private static ImageProcessor processor() {
        return ImageProcessors.getProcessor();
    }

    /**
     * 确保 OpenCV 已加载（幂等，进程内只加载一次）。
     *
     * <p>统一在此管理 {@code nu.pattern.OpenCV.loadLocally()}，
     * 各 translator 一律调用本方法，避免散落的重复加载。</p>
     */
    public static void load() {
        if (!loaded) {
            synchronized (ImageUtils.class) {
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
        TensorOptions options = new TensorOptions(image, size, null, null, false);
        return toTensor(options);
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
        TensorOptions options = new TensorOptions(image, size, mean, std, true);
        return toTensor(options);
    }

    /**
     * 将 DJL Image 直接 resize 到目标尺寸，转 CHW 归一化像素（RGB，mean/std）。
     *
     * @param image DJL 图像
     * @param size  目标尺寸
     * @param mean  均值（可为 null）
     * @param std   标准差（可为 null）
     * @return [3, size, size] float 像素
     */
    public static float[] toTensorResize(Image image, int size, float[] mean, float[] std) {
        TensorOptions options = new TensorOptions(image, size, mean, std, false);
        return toTensor(options);
    }

    /**
     * 将 DJL Image 转 CHW 归一化像素。
     *
     * @param options 张量转换选项，包含图像、尺寸、均值、标准差和裁剪策略
     * @return [3, size, size] float 像素
     */
    public static float[] toTensor(TensorOptions options) {
        load();
        BufferedImage buffered = (BufferedImage) options.image().getWrappedImage();
        if (buffered == null) {
            throw new IllegalStateException("无法获取图像像素: " + options.image().getClass().getName());
        }
        Mat img = toMat(buffered);
        try {
            int w = img.cols();
            int h = img.rows();
            Mat resized;
            if (options.centerCrop()) {
                float percent = (float) options.size() / Math.min(w, h);
                int rw = Math.round(w * percent);
                int rh = Math.round(h * percent);
                resized = new Mat();
                Imgproc.resize(img, resized, new Size(rw, rh), 0, 0, Imgproc.INTER_CUBIC);
                int x0 = (rw - options.size()) / 2;
                int y0 = (rh - options.size()) / 2;
                resized = new Mat(resized, new Rect(x0, y0, options.size(), options.size()));
            } else {
                resized = new Mat();
                Imgproc.resize(img, resized, new Size(options.size(), options.size()), 0, 0, Imgproc.INTER_CUBIC);
            }
            try {
                float[] pixels = new float[3 * options.size() * options.size()];
                float[] mean = options.mean();
                float[] std = options.std();
                for (int y = 0; y < options.size(); y++) {
                    for (int x = 0; x < options.size(); x++) {
                        double[] bgr = resized.get(y, x);
                        float b = (float) bgr[0] / 255.0f;
                        float g = (float) bgr[1] / 255.0f;
                        float r = (float) bgr[2] / 255.0f;
                        int idx = y * options.size() + x;
                        pixels[idx] = mean == null ? r : (r - mean[0]) / std[0];
                        pixels[options.size() * options.size() + idx] = mean == null ? g : (g - mean[1]) / std[1];
                        pixels[2 * options.size() * options.size() + idx] = mean == null ? b : (b - mean[2]) / std[2];
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
    /**
     * BufferedImage → Mat（BGR）。
     *
     * <p>直接像素拷贝（避免 PNG 编解码往返），输出 CV_8UC3 BGR Mat，调用方负责 release。</p>
     *
     * @param image 图像
     * @return Mat
     */
    public static Mat toMat(BufferedImage image) {
        load();
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 0 || h <= 0) {
            throw new IllegalStateException("图像尺寸非法: " + w + "x" + h);
        }
        Mat mat = new Mat(h, w, org.opencv.core.CvType.CV_8UC3);
        byte[] bgrRow = new byte[w * 3];
        int[] argbRow = new int[w];
        for (int y = 0; y < h; y++) {
            image.getRGB(0, y, w, 1, argbRow, 0, w);
            for (int x = 0; x < w; x++) {
                int argb = argbRow[x];
                int i = x * 3;
                bgrRow[i] = (byte) (argb & 0xFF);          // B
                bgrRow[i + 1] = (byte) ((argb >> 8) & 0xFF);  // G
                bgrRow[i + 2] = (byte) ((argb >> 16) & 0xFF); // R
            }
            mat.put(y, 0, bgrRow);
        }
        return mat;
    }

    /**
     * OpenCV Mat（BGR）→ BufferedImage。
     *
     * <p>直接像素拷贝（避免 PNG 编解码往返），输出 {@link BufferedImage#TYPE_INT_RGB}，
     * 保证 getRGB 与 Mat BGR 值无损往返。</p>
     *
     * @param mat Mat（BGR）
     * @return BufferedImage
     */
    public static BufferedImage toBufferedImage(Mat mat) {
        load();
        if (mat == null || mat.empty()) {
            return null;
        }
        int w = mat.cols();
        int h = mat.rows();
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        byte[] bgrRow = new byte[w * 3];
        int[] argbRow = new int[w];
        for (int y = 0; y < h; y++) {
            mat.get(y, 0, bgrRow);
            for (int x = 0; x < w; x++) {
                int i = x * 3;
                argbRow[x] = 0xFF000000
                        | ((bgrRow[i + 2] & 0xFF) << 16)   // R
                        | ((bgrRow[i + 1] & 0xFF) << 8)    // G
                        | (bgrRow[i] & 0xFF);              // B
            }
            image.setRGB(0, y, w, 1, argbRow, 0, w);
        }
        return image;
    }

    /**
     * 按指定宽高缩放（返回新 Mat，调用方负责 release）。
     *
     * @param src           源 Mat（BGR）
     * @param width         目标宽
     * @param height        目标高
     * @param interpolation 插值方式（Imgproc.INTER_*）
     * @return 缩放后的新 Mat
     */
    public static Mat resize(Mat src, int width, int height, int interpolation) {
        load();
        Mat resized = new Mat();
        Imgproc.resize(src, resized, new Size(width, height), 0, 0, interpolation);
        return resized;
    }

    /**
     * BufferedImage 按指定宽高缩放。
     *
     * @param image         源图像
     * @param width         目标宽
     * @param height        目标高
     * @param interpolation 插值方式（Imgproc.INTER_*）
     * @return 缩放后的 BufferedImage
     */
    public static BufferedImage resize(BufferedImage image, int width, int height, int interpolation) {
        Mat src = toMat(image);
        try {
            Mat resized = resize(src, width, height, interpolation);
            try {
                return toBufferedImage(resized);
            } finally {
                resized.release();
            }
        } finally {
            src.release();
        }
    }

    /**
     * 图像字节按指定宽高缩放（返回 PNG 字节）。
     *
     * <p>优先委托 {@link ImageProcessor} SPI 代理（Rust &gt; OpenCV &gt; AWT），
     * 代理执行失败时回退到本地 OpenCV 实现。</p>
     *
     * @param imageData     图像字节
     * @param width         目标宽
     * @param height        目标高
     * @param interpolation 插值方式（Imgproc.INTER_*，SPI 实现采用其默认插值）
     * @return 缩放后 PNG 字节
     */
    public static byte[] resize(byte[] imageData, int width, int height, int interpolation) {
        try {
            Map<String, Object> params = new HashMap<>(3);
            params.put("width", width);
            params.put("height", height);
            return processor().process(imageData, "resize", params);
        } catch (Exception e) {
            return resizeLocal(imageData, width, height, interpolation);
        }
    }

    /**
     * 本地 OpenCV 实现：图像字节按指定宽高缩放。
     *
     * @param imageData     图像字节
     * @param width         目标宽
     * @param height        目标高
     * @param interpolation 插值方式
     * @return 缩放后 PNG 字节
     */
    private static byte[] resizeLocal(byte[] imageData, int width, int height, int interpolation) {
        Mat src = decode(imageData);
        if (src == null || src.empty()) {
            return imageData;
        }
        Mat resized = resize(src, width, height, interpolation);
        try {
            return encode(resized);
        } finally {
            resized.release();
            src.release();
        }
    }

    /**
     * 按像素矩形裁剪（返回新 Mat，调用方负责 release）。
     *
     * @param options 裁剪选项，包含源 Mat 和像素坐标尺寸
     * @return 裁剪后的新 Mat
     */
    public static Mat crop(Mat src, ImageCropOptions options) {
        load();
        int left = clamp(options.x(), 0, src.cols() - 1);
        int top = clamp(options.y(), 0, src.rows() - 1);
        int w = Math.max(1, Math.min(options.width(), src.cols() - left));
        int h = Math.max(1, Math.min(options.height(), src.rows() - top));
        return new Mat(src, new Rect(left, top, w, h));
    }

    /**
     * 按像素矩形裁剪（返回 PNG 字节）。
     *
     * <p>优先委托 {@link ImageProcessor} SPI 代理（Rust &gt; OpenCV &gt; AWT），
     * 代理执行失败时回退到本地 OpenCV 实现。</p>
     *
     * @param options 裁剪选项，包含图像字节和像素坐标尺寸
     * @return 裁剪后 PNG 字节
     */
    public static byte[] crop(ImageCropOptions options) {
        try {
            Map<String, Object> params = new HashMap<>(4);
            params.put("x", options.x());
            params.put("y", options.y());
            params.put("width", options.width());
            params.put("height", options.height());
            return processor().process(options.imageData(), "crop", params);
        } catch (Exception e) {
            return cropLocal(options.imageData(), options.x(), options.y(), options.width(), options.height());
        }
    }

    /**
     * 本地 OpenCV 实现：按像素矩形裁剪。
     *
     * @param imageData 图像字节
     * @param x         左边界
     * @param y         上边界
     * @param width     宽度
     * @param height    高度
     * @return 裁剪后 PNG 字节
     */
    private static byte[] cropLocal(byte[] imageData, int x, int y, int width, int height) {
        Mat src = decode(imageData);
        if (src == null || src.empty()) {
            return imageData;
        }
        ImageCropOptions options = new ImageCropOptions(null, x, y, width, height);
        Mat sub = crop(src, options);
        try {
            return encode(sub);
        } finally {
            sub.release();
            src.release();
        }
    }

    /**
     * 按像素矩形裁剪（便捷方法）。
     *
     * @param imageData 图像字节
     * @param x         左边界
     * @param y         上边界
     * @param width     宽度
     * @param height    高度
     * @return 裁剪后 PNG 字节
     */
    public static byte[] crop(byte[] imageData, int x, int y, int width, int height) {
        return cropLocal(imageData, x, y, width, height);
    }

    /**
     * 按归一化或像素坐标裁剪（便捷方法）。
     *
     * @param imageData 图像字节
     * @param x         左边界（归一化 <=1.5 或像素）
     * @param y         上边界
     * @param width     宽度
     * @param height    高度
     * @return 裁剪后 PNG 字节
     */
    public static byte[] cropNormalizedOrPixel(byte[] imageData, float x, float y, float width, float height) {
        return cropNormalizedOrPixel(new NormalizedCropOptions(imageData, x, y, width, height));
    }

    /**
     * 按旋转矩形扶正裁剪（便捷方法）。
     *
     * @param imageData 图像字节
     * @param cx        中心 x
     * @param cy        中心 y
     * @param rw        旋转矩形宽
     * @param rh        旋转矩形高
     * @param angle     旋转角度（度）
     * @return 扶正后 PNG 字节
     */
    public static byte[] cropRotated(byte[] imageData, float cx, float cy, float rw, float rh, float angle) {
        return cropRotated(new RotatedCropOptions(imageData, cx, cy, rw, rh, angle));
    }

    /**
     * 按检测框裁剪（宽高 &lt;= 1.5 视为归一化坐标，否则按像素）。
     *
     * @param options 裁剪选项，包含图像字节和坐标（归一化或像素）
     * @return 裁剪后 PNG 字节
     */
    public static byte[] cropNormalizedOrPixel(NormalizedCropOptions options) {
        Mat src = decode(options.imageData());
        if (src == null || src.empty()) {
            return options.imageData();
        }
        try {
            int imgW = src.cols();
            int imgH = src.rows();
            boolean normalized = options.width() <= 1.5f && options.height() <= 1.5f
                    && options.x() <= 1.5f && options.y() <= 1.5f;
            int left;
            int top;
            int w;
            int h;
            if (normalized) {
                left = clamp(Math.round(options.x() * imgW), 0, imgW - 1);
                top = clamp(Math.round(options.y() * imgH), 0, imgH - 1);
                w = Math.max(1, Math.round(options.width() * imgW));
                h = Math.max(1, Math.round(options.height() * imgH));
            } else {
                left = clamp(Math.round(options.x()), 0, imgW - 1);
                top = clamp(Math.round(options.y()), 0, imgH - 1);
                w = Math.max(1, Math.round(options.width()));
                h = Math.max(1, Math.round(options.height()));
            }
            w = Math.min(w, imgW - left);
            h = Math.min(h, imgH - top);
            Mat sub = new Mat(src, new Rect(left, top, Math.max(1, w), Math.max(1, h)));
            try {
                return encode(sub);
            } finally {
                sub.release();
            }
        } finally {
            src.release();
        }
    }

    /**
     * 将数值限定在 [min, max] 区间内。
     *
     * @param value 原始数值
     * @param min   下限
     * @param max   上限
     * @return 限定后的数值
     */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 将 byte[] 解码为 BufferedImage。
     *
     * @param imageData 图像字节
     * @return BufferedImage，解码失败返回 null
     */
    public static BufferedImage toBufferedImage(byte[] imageData) {
        Mat src = decode(imageData);
        if (src == null || src.empty()) {
            return null;
        }
        try {
            return toBufferedImage(src);
        } finally {
            src.release();
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
    public static byte[] encode(java.awt.image.BufferedImage bi) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(bi, "png", baos);
            return baos.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
public static byte[] encode(Mat mat) {
        return encode(mat, ".png");
    }

    /**
     * Mat 编码为指定格式字节。
     *
     * @param mat    Mat
     * @param format 图像格式（.png / .jpg）
     * @return 图像字节
     */
    public static byte[] encode(Mat mat, String format) {
        load();
        MatOfByte mob = new MatOfByte();
        org.opencv.imgcodecs.Imgcodecs.imencode(format, mat, mob);
        return mob.toArray();
    }

    /**
     * 按旋转矩形扶正裁剪：绕旋转矩形中心反旋转 angle，将倾斜文字扶正为水平，
     * 再输出 rw x rh 水平矩形区域（多余部分白底填充）。
     *
     * <p>与整块 deskew 的区别：旋转中心为旋转矩形中心、输出尺寸等于 rw x rh，
     * 避免中心错位与多余背景，提升大角度文字的识别率。</p>
     *
     * @param options 裁剪选项，包含原图字节和旋转矩形参数
     * @return 扶正后 rw x rh 的 PNG 字节；处理失败返回原图
     */
    public static byte[] cropRotated(RotatedCropOptions options) {
        Mat src = decode(options.imageData());
        if (src == null || src.empty()) {
            return options.imageData();
        }
        try {
            Point center = new Point(options.cx(), options.cy());
            Mat rot = Imgproc.getRotationMatrix2D(center, options.angle(), 1.0);
            Mat rotated = new Mat();
            try {
                Imgproc.warpAffine(src, rotated, rot, src.size(), Imgproc.INTER_CUBIC,
                        Core.BORDER_CONSTANT, new Scalar(255, 255, 255));
                if (rotated.empty()) {
                    return options.imageData();
                }
                int x = clamp((int) Math.round(options.cx() - options.rw() / 2), 0, rotated.cols() - 1);
                int y = clamp((int) Math.round(options.cy() - options.rh() / 2), 0, rotated.rows() - 1);
                int w = Math.max(1, Math.min((int) Math.round(options.rw()), rotated.cols() - x));
                int h = Math.max(1, Math.min((int) Math.round(options.rh()), rotated.rows() - y));
                Mat sub = new Mat(rotated, new Rect(x, y, w, h));
                try {
                    return encode(sub);
                } finally {
                    sub.release();
                }
            } finally {
                rotated.release();
                rot.release();
            }
        } catch (Exception e) {
            return options.imageData();
        } finally {
            src.release();
        }
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
     * <p>优先委托 {@link ImageProcessor} SPI 代理（Rust &gt; OpenCV &gt; AWT），
     * 代理执行失败时回退到本地 OpenCV 实现。</p>
     *
     * @param imageData 图像字节
     * @param degree    90/180/270
     * @return 旋转后 PNG 字节
     */
    public static byte[] rotate(byte[] imageData, int degree) {
        try {
            Map<String, Object> params = new HashMap<>(1);
            params.put("angle", degree);
            return processor().process(imageData, "rotate", params);
        } catch (Exception e) {
            return rotateLocal(imageData, degree);
        }
    }

    /**
     * 本地 OpenCV 实现：旋转图像。
     *
     * @param imageData 图像字节
     * @param degree    90/180/270
     * @return 旋转后 PNG 字节
     */
    private static byte[] rotateLocal(byte[] imageData, int degree) {
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
     * 对倾斜文字/图像执行 deskew（绕中心 warpAffine 旋转扶正，白底填充）。
     *
     * <p>用于 OCR 裁剪块小角度倾斜矫正，或文档扫描件倾斜修复。
     * 角度越小效果越好，超过 30° 建议用 {@link #rotate} 处理 90° 倍角。</p>
     *
     * @param imageData 图像字节
     * @param angle     旋转角度（度），正=顺时针
     * @return 扶正后 PNG 字节；处理失败返回原图
     */
    public static byte[] deskew(byte[] imageData, float angle) {
        try {
            load();
            Mat src = org.opencv.imgcodecs.Imgcodecs.imdecode(
                    new MatOfByte(imageData), org.opencv.imgcodecs.Imgcodecs.IMREAD_COLOR);
            if (src == null || src.empty()) {
                return imageData;
            }
            try {
                Point center = new Point(src.cols() / 2.0, src.rows() / 2.0);
                Mat rot = Imgproc.getRotationMatrix2D(center, angle, 1.0);
                Mat dst = new Mat();
                Imgproc.warpAffine(src, dst, rot, src.size(), Imgproc.INTER_CUBIC,
                        org.opencv.core.Core.BORDER_CONSTANT, new org.opencv.core.Scalar(255, 255, 255));
                MatOfByte mob = new MatOfByte();
                org.opencv.imgcodecs.Imgcodecs.imencode(".png", dst, mob);
                byte[] result = mob.toArray();
                dst.release();
                rot.release();
                return result;
            } finally {
                src.release();
            }
        } catch (Exception e) {
            return imageData;
        }
    }

    /**
     * 平均亮度是否低于阈值（深色背景）。
     *
     * @param imageData 图像字节
     * @return true 表示深色背景
     */
    public static boolean isDarkBackground(byte[] imageData) {        Mat src = decode(imageData);
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
     * 图像模糊度评分（Laplacian 方差平方，越大越清晰）。
     *
     * <p>对灰度图做 Laplacian 二阶差分，统计方差作为清晰度指标；
     * 与 {@code OpencvImageQualityAssessor} 口径一致，阈值经验值 100。</p>
     *
     * @param imageData 图像字节
     * @return 模糊度评分，解码失败返回 0
     */
    public static double blurScore(byte[] imageData) {
        Mat src = decode(imageData);
        if (src == null || src.empty()) {
            return 0;
        }
        Mat gray = new Mat();
        Mat lap = new Mat();
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble std = new MatOfDouble();
        try {
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.Laplacian(gray, lap, org.opencv.core.CvType.CV_64F);
            Core.meanStdDev(lap, mean, std);
            double lapStd = std.get(0, 0)[0];
            return Math.pow(lapStd, 2);
        } catch (Exception e) {
            return 0;
        } finally {
            mean.release();
            std.release();
            lap.release();
            gray.release();
            src.release();
        }
    }

    /**
     * 图像灰度均值（平均亮度 0~255）。
     *
     * @param imageData 图像字节
     * @return 平均亮度，解码失败返回 0
     */
    public static double meanGray(byte[] imageData) {
        Mat src = decode(imageData);
        if (src == null || src.empty()) {
            return 0;
        }
        Mat gray = new Mat();
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble std = new MatOfDouble();
        try {
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
            Core.meanStdDev(gray, mean, std);
            return mean.get(0, 0)[0];
        } finally {
            std.release();
            mean.release();
            gray.release();
            src.release();
        }
    }

    /**
     * 图像灰度标准差（对比度）。
     *
     * @param imageData 图像字节
     * @return 对比度，解码失败返回 0
     */
    public static double stdDevGray(byte[] imageData) {
        Mat src = decode(imageData);
        if (src == null || src.empty()) {
            return 0;
        }
        Mat gray = new Mat();
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble std = new MatOfDouble();
        try {
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
            Core.meanStdDev(gray, mean, std);
            return std.get(0, 0)[0];
        } finally {
            std.release();
            mean.release();
            gray.release();
            src.release();
        }
    }

    /**
     * 图像是否模糊（模糊度评分低于阈值）。
     *
     * @param imageData 图像字节
     * @param threshold 清晰度阈值，低于视为模糊（经验值 100）
     * @return true 表示模糊
     */
    public static boolean isBlurry(byte[] imageData, double threshold) {
        return blurScore(imageData) < threshold;
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
     * 计算 5 点仿射矩阵（源 5 点 → FFHQ 512 模板），供对齐与贴回复用。
     *
     * @param keypoints 源人脸 5 点（像素坐标，顺序：左眼、右眼、鼻、左嘴角、右嘴角）
     * @return 2×3 仿射矩阵 Mat（调用方负责 release）
     */
    public static Mat estimateFaceAffine512(java.util.List<float[]> keypoints) {
        load();
        if (keypoints == null || keypoints.size() < 5) {
            throw new IllegalArgumentException("人脸关键点不足 5 点: " + (keypoints == null ? 0 : keypoints.size()));
        }
        return estimateAffine5Point(keypoints, FACE_TEMPLATE_512);
    }

    /**
     * 将修复后的人脸（对齐 512 空间）通过逆仿射贴回原图，并用软 mask 与背景融合。
     *
     * <p>流程（AIAS face_restoration_sdk 同款）：<br>
     * 1. 逆仿射变换 restoredFace 到原图尺寸；<br>
     * 2. 逆仿射变换 softMask 到原图尺寸（软 mask，插值后保持边缘渐变）；<br>
     * 3. 像素级融合：result = mask * restored + (1 - mask) * background。</p>
     *
     * @param background  原图 Mat（BGR，不修改）
     * @param restoredFace 修复后的对齐人脸 Mat（512×512，BGR）
     * @param softMask     人脸软 mask Mat（512×512，单通道 0~255 灰度）
     * @param affine       对齐时使用的 2×3 仿射矩阵
     * @return 融合后的新 Mat（BGR，原图尺寸）
     */
    public static Mat pasteFace(Mat background, Mat restoredFace, Mat softMask, Mat affine) {
        load();
        int w = background.cols();
        int h = background.rows();

        // 逆仿射矩阵
        Mat inverseAffine = new Mat();
        Imgproc.invertAffineTransform(affine, inverseAffine);

        // 修复人脸逆变换到原图尺寸
        Mat invRestored = new Mat();
        Imgproc.warpAffine(restoredFace, invRestored, inverseAffine, new Size(w, h),
                Imgproc.INTER_LINEAR, org.opencv.core.Core.BORDER_CONSTANT, new org.opencv.core.Scalar(0, 0, 0));

        // 软 mask 逆变换到原图尺寸（保持渐变）
        Mat invMask = new Mat();
        Imgproc.warpAffine(softMask, invMask, inverseAffine, new Size(w, h),
                Imgproc.INTER_LINEAR, org.opencv.core.Core.BORDER_CONSTANT, new org.opencv.core.Scalar(0));

        // 转为 CV_32F 做加权融合
        Mat bgF = new Mat();
        Mat reF = new Mat();
        Mat mkF = new Mat();
        background.convertTo(bgF, org.opencv.core.CvType.CV_32FC3);
        invRestored.convertTo(reF, org.opencv.core.CvType.CV_32FC3);
        invMask.convertTo(mkF, org.opencv.core.CvType.CV_32FC1);
        // mask 归一化到 [0,1]
        Mat mkNorm = new Mat();
        Core.multiply(mkF, new org.opencv.core.Scalar(1.0 / 255.0), mkNorm);

        // result = mask * restored + (1-mask) * background
        // 单通道 mask 广播到 3 通道（merge 复制三次）
        Mat mask3 = new Mat();
        java.util.List<Mat> maskChannels = java.util.List.of(mkNorm, mkNorm, mkNorm);
        Core.merge(maskChannels, mask3);
        Mat maskedRestored = new Mat();
        Mat maskedBg = new Mat();
        Mat oneMinus = new Mat();
        Mat ones3 = new Mat(bgF.size(), org.opencv.core.CvType.CV_32FC3, org.opencv.core.Scalar.all(1.0));
        Core.subtract(ones3, mask3, oneMinus);
        Core.multiply(mask3, reF, maskedRestored);
        Core.multiply(oneMinus, bgF, maskedBg);
        Mat resultF = new Mat();
        Core.add(maskedRestored, maskedBg, resultF);
        Mat result = new Mat();
        resultF.convertTo(result, org.opencv.core.CvType.CV_8UC3);

        inverseAffine.release();
        invRestored.release();
        invMask.release();
        bgF.release();
        reF.release();
        mkF.release();
        mkNorm.release();
        mask3.release();
        ones3.release();
        maskedRestored.release();
        maskedBg.release();
        oneMinus.release();
        resultF.release();
        return result;
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

    /**
     * 在图像上绘制检测框（支持旋转框）+ 中文文本标签。
     * <p>用 AWT Graphics2D 绘制中文文字（OpenCV putText 不支持中文），
     * 检测框用 OpenCV polylines（支持旋转框）。</p>
     *
     * @param imageData 原图字节
     * @param boxes     检测结果（含角度/rw/rh/cx/cy）
     * @param labels    对应每个框的文本标签（可为 null）
     * @return 标注后 JPEG 字节
     */
    public static byte[] drawDetectionsWithLabels(byte[] imageData, List<DetectionInfo> boxes, List<String> labels) {
        load();
        Mat src = org.opencv.imgcodecs.Imgcodecs.imdecode(new MatOfByte(imageData), org.opencv.imgcodecs.Imgcodecs.IMREAD_COLOR);
        if (src == null) return imageData;
        try {
            for (DetectionInfo box : boxes) {
                if (Math.abs(box.angle()) > 0.5f && box.rw() > 0 && box.rh() > 0) {
                    RotatedRect rr = new RotatedRect(new Point(box.cx(), box.cy()),
                            new Size(box.rw(), box.rh()), box.angle());
                    Point[] pts = new Point[4];
                    rr.points(pts);
                    MatOfPoint poly = new MatOfPoint(pts);
                    Imgproc.polylines(src, List.of(poly), true, new Scalar(0, 255, 0), 2);
                    poly.release();
                } else {
                    int x = (int) box.x(), y = (int) box.y(), w = (int) box.width(), h = (int) box.height();
                    Imgproc.rectangle(src, new Point(x, y), new Point(x + w, y + h), new Scalar(0, 255, 0), 2);
                }
            }
            try {
                MatOfByte mob = new MatOfByte();
                org.opencv.imgcodecs.Imgcodecs.imencode(".png", src, mob);
                BufferedImage bi = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(mob.toArray()));
                if (bi != null) {
                    java.awt.Graphics2D g = bi.createGraphics();
                    g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g.setFont(new java.awt.Font("Microsoft YaHei", java.awt.Font.PLAIN, 18));
                    for (int i = 0; i < boxes.size(); i++) {
                        DetectionInfo box = boxes.get(i);
                        String label = (labels != null && i < labels.size() && labels.get(i) != null)
                                ? labels.get(i) : String.format("%.2f", box.confidence());
                        java.awt.FontMetrics fm = g.getFontMetrics();
                        int tw = fm.stringWidth(label) + 6;
                        int th = fm.getHeight();
                        double angle = box.angle();
                        if (Math.abs(angle) > 0.5f && box.rw() > 0 && box.rh() > 0) {
                            // 旋转框：计算框左上角在旋转后的位置，标签贴附在此
                            double rad = Math.toRadians(angle);
                            double sin = Math.sin(rad);
                            double hh = box.rh() / 2.0;
                            // 标签贴在旋转框顶部中心（紧贴框外沿）
                            double tx = box.cx() + (-hh) * sin;
                            double ty = box.cy() + (-hh) * Math.cos(rad);
                            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                            g2.rotate(rad, tx, ty);
                            g2.setColor(new java.awt.Color(0, 120, 0, 180));
                            g2.fillRect((int) tx, (int) ty - th + 4, tw, th);
                            g2.setColor(java.awt.Color.WHITE);
                            g2.drawString(label, (int) tx + 3, (int) ty - 2);
                            g2.dispose();
                        } else {
                            int x = (int) box.x(), y = (int) box.y();
                            g.setColor(new java.awt.Color(0, 120, 0, 180));
                            g.fillRect(x, y - th + 4, tw, th);
                            g.setColor(java.awt.Color.WHITE);
                            g.drawString(label, x + 3, y - 2);
                        }
                    }
                    g.dispose();
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    javax.imageio.ImageIO.write(bi, "jpg", baos);
                    return baos.toByteArray();
                }
                return mob.toArray();
            } catch (java.io.IOException e) {
                MatOfByte mob = new MatOfByte();
                org.opencv.imgcodecs.Imgcodecs.imencode(".jpg", src, mob);
                return mob.toArray();
            }
        } finally {
            src.release();
        }
    }

    /**
     * 在图像上绘制检测框（支持旋转框角度）。
     *
     * @param imageData 原图
     * @param boxes     检测结果
     * @return 标注后 JPEG 字节
     */
    public static byte[] drawDetections(byte[] imageData, List<DetectionInfo> boxes) {
        load();
        Mat src = org.opencv.imgcodecs.Imgcodecs.imdecode(new MatOfByte(imageData), org.opencv.imgcodecs.Imgcodecs.IMREAD_COLOR);
        if (src == null) return imageData;
        try {
            for (DetectionInfo box : boxes) {
                if (Math.abs(box.angle()) > 0.5f && box.rw() > 0 && box.rh() > 0) {
                    RotatedRect rr = new RotatedRect(new Point(box.cx(), box.cy()),
                            new Size(box.rw(), box.rh()), box.angle());
                    Point[] pts = new Point[4];
                    rr.points(pts);
                    MatOfPoint poly = new MatOfPoint(pts);
                    Imgproc.polylines(src, List.of(poly), true, new Scalar(0, 255, 0), 2);
                    poly.release();
                } else {
                    int x = (int) box.x(), y = (int) box.y(), w = (int) box.width(), h = (int) box.height();
                    Imgproc.rectangle(src, new Point(x, y), new Point(x + w, y + h), new Scalar(0, 255, 0), 2);
                }
            }
            MatOfByte mob = new MatOfByte();
            org.opencv.imgcodecs.Imgcodecs.imencode(".jpg", src, mob);
            return mob.toArray();
        } finally {
            src.release();
        }
    }
}
