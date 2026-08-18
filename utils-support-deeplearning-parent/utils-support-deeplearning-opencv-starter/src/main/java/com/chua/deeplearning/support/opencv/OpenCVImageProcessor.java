package com.chua.deeplearning.support.opencv;

import com.chua.common.support.image.ImageProcessor;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiOrder;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 OpenCV 的图像处理器
 *
 * <p>通过 {@code org.openpnp:opencv} 加载 OpenCV 原生库，提供高性能图像处理能力。
 * 支持操作：resize / grayscale / rotate / crop / blur / flip / brightness / contrast / border。</p>
 *
 * <p>OpenCV 原生库加载失败时 {@link #available()} 返回 false，
 * 上层自动回退到 Rust 或 JDK 实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("image-processor")
@SpiOrder(50)
@Slf4j
public class OpenCVImageProcessor implements ImageProcessor {

    /**
     * 是否已成功加载 OpenCV 原生库
     */
    private static volatile boolean loaded = false;

    static {
        try {
            OpencvNative.ensureLoaded();
            loaded = true;
            log.info("[OpenCVImageProcessor] OpenCV 原生库加载成功");
        } catch (Throwable e) {
            log.warn("[OpenCVImageProcessor] OpenCV 原生库加载失败，将回退到其他实现: {}", e.getMessage());
            loaded = false;
        }
    }

    @Override
    public byte[] process(byte[] imageData, String operation, Map<String, Object> params) {
        if (!loaded) {
            throw new IllegalStateException("OpenCV 原生库未加载");
        }
        Mat mat = imdecode(imageData);
        if (mat.empty()) {
            throw new IllegalArgumentException("无法解码图像数据");
        }
        try {
            Mat result = switch (operation) {
                case "resize" -> resize(mat, params);
                case "grayscale" -> grayscale(mat);
                case "rotate" -> rotate(mat, params);
                case "crop" -> crop(mat, params);
                case "blur" -> blur(mat, params);
                case "flip" -> flip(mat, params);
                case "brightness" -> brightness(mat, params);
                case "contrast" -> contrast(mat, params);
                case "border" -> border(mat, params);
                default -> mat;
            };
            return imencode(result, params);
        } finally {
            mat.release();
        }
    }

    /**
     * 解码图像字节为 OpenCV Mat
     */
    private Mat imdecode(byte[] data) {
        MatOfByte mob = new MatOfByte(data);
        try {
            return Imgcodecs.imdecode(mob, Imgcodecs.IMREAD_UNCHANGED);
        } finally {
            mob.release();
        }
    }

    /**
     * 编码 Mat 为图像字节
     */
    private byte[] imencode(Mat mat, Map<String, Object> params) {
        String format = params != null && params.get("format") != null
                ? params.get("format").toString() : "png";
        String ext = "." + format.toLowerCase().replace("jpg", "jpeg");
        MatOfByte mob = new MatOfByte();
        try {
            Imgcodecs.imencode(ext, mat, mob);
            return mob.toArray();
        } finally {
            mob.release();
        }
    }

    /**
     * 缩放图像
     */
    private Mat resize(Mat src, Map<String, Object> params) {
        int width = toInt(params.get("width"), 200);
        int height = toInt(params.get("height"), 200);
        Mat dst = new Mat();
        Imgproc.resize(src, dst, new Size(width, height));
        return dst;
    }

    /**
     * 转为灰度图像
     */
    private Mat grayscale(Mat src) {
        Mat dst = new Mat();
        Imgproc.cvtColor(src, dst, Imgproc.COLOR_BGR2GRAY);
        return dst;
    }

    /**
     * 旋转图像
     */
    private Mat rotate(Mat src, Map<String, Object> params) {
        int angle = toInt(params.get("angle"), 90) % 360;
        Mat dst = new Mat();
        if (angle == 90) {
            Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE);
        } else if (angle == 180) {
            Core.rotate(src, dst, Core.ROTATE_180);
        } else if (angle == 270) {
            Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE);
        } else {
            // 任意角度旋转
            Point center = new Point(src.cols() / 2.0, src.rows() / 2.0);
            Mat rotMat = Imgproc.getRotationMatrix2D(center, angle, 1.0);
            Imgproc.warpAffine(src, dst, rotMat, src.size());
            rotMat.release();
        }
        return dst;
    }

    /**
     * 裁剪图像
     */
    private Mat crop(Mat src, Map<String, Object> params) {
        int x = toInt(params.get("x"), 0);
        int y = toInt(params.get("y"), 0);
        int w = toInt(params.get("width"), 100);
        int h = toInt(params.get("height"), 100);
        x = Math.max(0, Math.min(x, src.cols()));
        y = Math.max(0, Math.min(y, src.rows()));
        w = Math.min(w, src.cols() - x);
        h = Math.min(h, src.rows() - y);
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("裁剪尺寸非法");
        }
        return new Mat(src, new Rect(x, y, w, h));
    }

    /**
     * 高斯模糊
     */
    private Mat blur(Mat src, Map<String, Object> params) {
        int sigma = toInt(params.get("sigma"), 3);
        int ksize = Math.max(1, sigma) * 2 + 1;
        Mat dst = new Mat();
        Imgproc.GaussianBlur(src, dst, new Size(ksize, ksize), sigma);
        return dst;
    }

    /**
     * 翻转图像
     */
    private Mat flip(Mat src, Map<String, Object> params) {
        String axis = params.get("axis") != null ? params.get("axis").toString() : "h";
        Mat dst = new Mat();
        int flipCode = "v".equalsIgnoreCase(axis) ? 0 : 1;
        Core.flip(src, dst, flipCode);
        return dst;
    }

    /**
     * 调整亮度
     */
    private Mat brightness(Mat src, Map<String, Object> params) {
        int value = toInt(params.get("value"), 10);
        Mat dst = new Mat();
        src.convertTo(dst, -1, 1.0, value);
        return dst;
    }

    /**
     * 调整对比度
     */
    private Mat contrast(Mat src, Map<String, Object> params) {
        int value = toInt(params.get("value"), 10);
        double alpha = (259.0 * (value + 255.0)) / (255.0 * (259.0 - value));
        Mat dst = new Mat();
        src.convertTo(dst, -1, alpha, 0);
        return dst;
    }

    /**
     * 绘制边框
     */
    private Mat border(Mat src, Map<String, Object> params) {
        int width = toInt(params.get("width"), 1);
        width = Math.max(0, width);
        Scalar color = parseColor(params.get("color") != null ? params.get("color").toString() : "#000000");
        Mat dst = new Mat();
        Core.copyMakeBorder(src, dst, width, width, width, width, Core.BORDER_CONSTANT, color);
        return dst;
    }

    /**
     * 解析颜色字符串为 OpenCV Scalar（BGR 顺序）
     */
    private Scalar parseColor(String colorStr) {
        String s = colorStr.trim();
        int r = 0, g = 0, b = 0;
        if (s.startsWith("#") && s.length() == 7) {
            try {
                r = Integer.parseInt(s.substring(1, 3), 16);
                g = Integer.parseInt(s.substring(3, 5), 16);
                b = Integer.parseInt(s.substring(5, 7), 16);
            } catch (NumberFormatException ignored) {
            }
        } else {
            String[] parts = s.split(",");
            if (parts.length == 3) {
                try {
                    r = Integer.parseInt(parts[0].trim());
                    g = Integer.parseInt(parts[1].trim());
                    b = Integer.parseInt(parts[2].trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        // OpenCV 使用 BGR 顺序
        return new Scalar(b, g, r);
    }

    /**
     * 将参数转为整数
     */
    private int toInt(Object value, int defaultVal) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultVal;
    }

    @Override
    public String name() {
        return "opencv";
    }

    @Override
    public boolean available() {
        return loaded;
    }
}