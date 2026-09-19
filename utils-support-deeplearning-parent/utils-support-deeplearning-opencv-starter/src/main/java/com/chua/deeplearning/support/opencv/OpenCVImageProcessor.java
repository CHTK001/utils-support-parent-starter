package com.chua.deeplearning.support.opencv;

import com.chua.common.support.image.ImageProcessor;
import com.chua.common.support.image.ImageProcessorUtils;
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
import java.util.HashMap;

/**
 * 基于 打开cv 的图像处理器
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
     * 是否已成功加载 打开cv 原生库
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
    /** 处理 */
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
                case "edge" -> edge(mat, params);
                case "templateMatch" -> templateMatch(mat, params);
                default -> mat;
            };
            return imencode(result, params);
        } finally {
            mat.release();
        }
    }

    /**
     * 解码图像字节为 打开cv Mat
     * @param data 数据
     * @return imdecode的结果
     */
    private Mat imdecode(byte[] data) {
        try (CloseableMob mob = closeableMob(data)) {
            return Imgcodecs.imdecode(mob, Imgcodecs.IMREAD_UNCHANGED);
        }
    }

    /**
     * 编码 Mat 为图像字节
     * @param mat mat
     * @param params 参数
     * @return imencode的结果
     */
    private byte[] imencode(Mat mat, Map<String, Object> params) {
        String format = params != null && params.get("format") != null
                ? params.get("format").toString() : "png";
        String ext = "." + format.toLowerCase().replace("jpg", "jpeg");
        try (CloseableMob mob = closeableMob()) {
            Imgcodecs.imencode(ext, mat, mob);
            return mob.toArray();
        }
    }

    /**
     * 将 {@link MatOfByte} 包装为 {@link AutoCloseable}，支持 try-with-resources。
     *
     * @param data 编码输出数据
     * @return 可自动释放的 MatOfByte 包装实例
     */
    private static CloseableMob closeableMob(byte[] data) {
        return new CloseableMob(data);
    }

    /**
     * closeableMob。
     *
     * @return CloseableMob 对象
     */
    private static CloseableMob closeableMob() {
        return new CloseableMob();
    }

    /**
     * 可自动释放的 {@link MatOfByte} 包装器。
     * @author CH
     * @since 4.0.0
     */
    private static class CloseableMob extends MatOfByte implements AutoCloseable {
        CloseableMob(byte[] data) {
            super(data);
        }

        CloseableMob() {
            super();
        }

        @Override
        public void close() {
            release();
        }
    }

    /**
     * 缩放图像
     * @param src src
     * @param params 参数
     * @return resize的结果
     */
    private Mat resize(Mat src, Map<String, Object> params) {
        int width = ImageProcessorUtils.toInt(params.get("width"), 200);
        int height = ImageProcessorUtils.toInt(params.get("height"), 200);
        Mat dst = new Mat();
        Imgproc.resize(src, dst, new Size(width, height));
        return dst;
    }

    /**
     * 转为灰度图像
     * @param src src
     * @return grayscale的结果
     */
    private Mat grayscale(Mat src) {
        Mat dst = new Mat();
        Imgproc.cvtColor(src, dst, Imgproc.COLOR_BGR2GRAY);
        return dst;
    }

    /**
     * 旋转图像
     * @param src src
     * @param params 参数
     * @return rotate的结果
     */
    private Mat rotate(Mat src, Map<String, Object> params) {
        int angle = ImageProcessorUtils.toInt(params.get("angle"), 90) % 360;
        if (angle < 0) {
            angle += 360;
        }
        Mat dst = new Mat();
        if (angle == 0) {
            src.copyTo(dst);
            return dst;
        }
        if (angle == 90) {
            Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE);
        } else if (angle == 180) {
            Core.rotate(src, dst, Core.ROTATE_180);
        } else if (angle == 270) {
            Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE);
        } else {
            // 任意角度旋转：计算旋转后外接矩形尺寸，避免裁剪
            Point center = new Point(src.cols() / 2.0, src.rows() / 2.0);
            Mat rotMat = Imgproc.getRotationMatrix2D(center, angle, 1.0);
            double radians = Math.toRadians(angle);
            double sin = Math.abs(Math.sin(radians));
            double cos = Math.abs(Math.cos(radians));
            int newW = (int) Math.floor(src.cols() * cos + src.rows() * sin);
            int newH = (int) Math.floor(src.cols() * sin + src.rows() * cos);
            // 调整旋转矩阵的平移分量，使图像居中
            double[] m = new double[6];
            rotMat.get(0, 0, m);
            m[2] += (newW - src.cols()) / 2.0;
            m[5] += (newH - src.rows()) / 2.0;
            rotMat.put(0, 0, m);
            Imgproc.warpAffine(src, dst, rotMat, new Size(newW, newH));
            rotMat.release();
        }
        return dst;
    }

    /**
     * 裁剪图像
     * @param src src
     * @param params 参数
     * @return crop的结果
     */
    private Mat crop(Mat src, Map<String, Object> params) {
        int x = ImageProcessorUtils.toInt(params.get("x"), 0);
        int y = ImageProcessorUtils.toInt(params.get("y"), 0);
        int w = ImageProcessorUtils.toInt(params.get("width"), 100);
        int h = ImageProcessorUtils.toInt(params.get("height"), 100);
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
     * @param src src
     * @param params 参数
     * @return blur的结果
     */
    private Mat blur(Mat src, Map<String, Object> params) {
        int sigma = ImageProcessorUtils.toInt(params.get("sigma"), 3);
        int ksize = Math.max(1, sigma) * 2 + 1;
        Mat dst = new Mat();
        Imgproc.GaussianBlur(src, dst, new Size(ksize, ksize), sigma);
        return dst;
    }

    /**
     * 翻转图像
     * @param src src
     * @param params 参数
     * @return flip的结果
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
     * @param src src
     * @param params 参数
     * @return brightness的结果
     */
    private Mat brightness(Mat src, Map<String, Object> params) {
        int value = ImageProcessorUtils.toInt(params.get("value"), 10);
        Mat dst = new Mat();
        src.convertTo(dst, -1, 1.0, value);
        return dst;
    }

    /**
     * 调整对比度
     * @param src src
     * @param params 参数
     * @return contrast的结果
     */
    private Mat contrast(Mat src, Map<String, Object> params) {
        int value = ImageProcessorUtils.toInt(params.get("value"), 10);
        double alpha = (259.0 * (value + 255.0)) / (255.0 * (259.0 - value));
        Mat dst = new Mat();
        src.convertTo(dst, -1, alpha, 0);
        return dst;
    }

    /**
     * 绘制边框
     * @param src src
     * @param params 参数
     * @return border的结果
     */
    private Mat border(Mat src, Map<String, Object> params) {
        int width = ImageProcessorUtils.toInt(params.get("width"), 1);
        width = Math.max(0, width);
        int[] rgb = ImageProcessorUtils.parseColor(params.get("color") != null ? params.get("color").toString() : "#000000");
        Scalar color = new Scalar(rgb[2], rgb[1], rgb[0]); // 打开cv 使用 BGR 顺序
        Mat dst = new Mat();
        Core.copyMakeBorder(src, dst, width, width, width, width, Core.BORDER_CONSTANT, color);
        return dst;
    }

    /**
     * 边缘检测（Canny / Sobel）
     *
     * <p>支持两种算法：
     * <ul>
     *   <li>canny（默认）：双阈值边缘检测，效果好</li>
     *   <li>sobel：Sobel 算子边缘检测</li>
     * </ul>
     *
     * @param src    源图像
     * @param params 参数：方法（canny / sobel，默认 canny），
     * 阈值1（Canny 低阈值，默认 50），
     * 阈值2（Canny 高阈值，默认 150），
     *               direction（sobel 方向：h / v / both，默认 both）
     * @return 边缘检测后的灰度图像
     */
    private Mat edge(Mat src, Map<String, Object> params) {
        String method = params.get("method") != null ? params.get("method").toString() : "canny";
        Mat gray = new Mat();
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);

        Mat result = new Mat();
        if ("sobel".equalsIgnoreCase(method)) {
            // Sobel 边缘检测
            String direction = params.get("direction") != null ? params.get("direction").toString() : "both";
            Mat gradX = new Mat();
            Mat gradY = new Mat();
            Imgproc.Sobel(gray, gradX, CvType.CV_16S, 1, 0);
            Imgproc.Sobel(gray, gradY, CvType.CV_16S, 0, 1);

            Mat absX = new Mat();
            Mat absY = new Mat();
            Core.convertScaleAbs(gradX, absX);
            Core.convertScaleAbs(gradY, absY);

            if ("h".equalsIgnoreCase(direction)) {
                result = absX;
                gradY.release();
                absY.release();
            } else if ("v".equalsIgnoreCase(direction)) {
                result = absY;
                gradX.release();
                absX.release();
            } else {
                Core.addWeighted(absX, 0.5, absY, 0.5, 0, result);
                absX.release();
                absY.release();
            }
            gradX.release();
            gradY.release();
        } else {
            // Canny 边缘检测（默认）
            int threshold1 = ImageProcessorUtils.toInt(params.get("threshold1"), 50);
            int threshold2 = ImageProcessorUtils.toInt(params.get("threshold2"), 150);
            Imgproc.Canny(gray, result, threshold1, threshold2);
        }
        gray.release();
        return result;
    }

    /**
     * 模板匹配
     *
     * <p>在源图像中搜索与模板图像最匹配的区域，返回匹配结果。
     * 支持多种匹配方法，默认使用 TM_CCOEFF_NORMED（归一化相关系数）。</p>
     *
     * <p>参数说明：
     * <ul>
     *   <li>template：模板图像字节数据（必须提供）</li>
     *   <li>method：匹配方法（ccoeff_normed / ccorr_normed / sqdiff_normed，默认 ccoeff_normed）</li>
     *   <li>threshold：匹配阈值（0~1，默认 0.8），仅返回高于此阈值的匹配</li>
     *   <li>maxCount：最大匹配数量（默认 10）</li>
     *   <li>drawMatch：是否在结果图像上绘制匹配框（默认 true）</li>
     * </ul>
     *
     * <p>返回值：匹配结果通过 params["matchResult"] 传出（List&lt;Map&gt;），每项包含：
     * x, y, width, height, score</p>
     *
     * @param src    源图像
     * @param params 参数
     * @return 绘制了匹配框的源图像（或原始图像）
     */
    private Mat templateMatch(Mat src, Map<String, Object> params) {
        // 获取模板图像
        Object templateObj = params.get("template");
        if (templateObj == null) {
            throw new IllegalArgumentException("模板匹配需要提供 template 参数（模板图像字节数据）");
        }
        byte[] templateBytes;
        if (templateObj instanceof byte[]) {
            templateBytes = (byte[]) templateObj;
        } else {
            throw new IllegalArgumentException("template 参数类型必须为 byte[]（图像字节数据）");
        }

        Mat template = imdecode(templateBytes);
        if (template.empty()) {
            throw new IllegalArgumentException("无法解码模板图像数据");
        }

        try {
            // 匹配方法
            int method = parseMatchMethod(params.get("method") != null ? params.get("method").toString() : "ccoeff_normed");

            // 执行模板匹配
            Mat result = new Mat();
            Imgproc.matchTemplate(src, template, result, method);

            // 阈值过滤
            double threshold = params.get("threshold") != null ? ((Number) params.get("threshold")).doubleValue() : 0.8;
            int maxCount = ImageProcessorUtils.toInt(params.get("maxCount"), 10);
            boolean drawMatch = params.get("drawMatch") == null || Boolean.parseBoolean(params.get("drawMatch").toString());

            // 查找匹配位置（NMS 简化版）
            List<Map<String, Object>> matches = new ArrayList<>();
            int tw = template.cols();
            int th = template.rows();

            // 对于 sqdiff 方法，值越小越好；其他方法值越大越好
            boolean lowerBetter = method == Imgproc.TM_SQDIFF || method == Imgproc.TM_SQDIFF_NORMED;

            // 遍历结果矩阵寻找匹配点
            for (int i = 0; i < maxCount; i++) {
                Core.MinMaxLocResult mmr = Core.minMaxLoc(result);
                double bestVal = lowerBetter ? mmr.minVal : mmr.maxVal;
                Point bestLoc = lowerBetter ? mmr.minLoc : mmr.maxLoc;

                // 检查是否满足阈值
                boolean meetsThreshold = lowerBetter ? (bestVal <= (1.0 - threshold)) : (bestVal >= threshold);
                if (!meetsThreshold) {
                    break;
                }

                int x = (int) bestLoc.x;
                int y = (int) bestLoc.y;
                Map<String, Object> match = new HashMap<>();
                match.put("x", x);
                match.put("y", y);
                match.put("width", tw);
                match.put("height", th);
                match.put("score", bestVal);
                matches.add(match);

                // 抑制该匹配区域（避免重复检测）
                int suppressX1 = Math.max(0, x - tw / 2);
                int suppressY1 = Math.max(0, y - th / 2);
                int suppressX2 = Math.min(result.cols(), x + tw);
                int suppressY2 = Math.min(result.rows(), y + th);
                if (suppressX2 > suppressX1 && suppressY2 > suppressY1) {
                    Mat suppressRegion = result.rowRange(suppressY1, suppressY2).colRange(suppressX1, suppressX2);
                    if (lowerBetter) {
                        suppressRegion.setTo(new Scalar(1.0));
                    } else {
                        suppressRegion.setTo(new Scalar(0.0));
                    }
                    suppressRegion.release();
                }
            }

 // 将匹配结果放入 参数 供调用方获取
            params.put("matchResult", matches);

            // 绘制匹配框
            Mat output = new Mat();
            if (drawMatch && !matches.isEmpty()) {
                src.copyTo(output);
                Scalar boxColor = new Scalar(0, 255, 0); // BGR: 绿色
                Scalar textColor = new Scalar(0, 0, 255); // BGR: 红色
                for (Map<String, Object> match : matches) {
                    int mx = (int) match.get("x");
                    int my = (int) match.get("y");
                    Imgproc.rectangle(output, new Point(mx, my), new Point(mx + tw, my + th), boxColor, 2);
                    String label = String.format("%.2f", ((Number) match.get("score")).doubleValue());
                    Imgproc.putText(output, label, new Point(mx, my - 5), Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, textColor, 1);
                }
            } else {
                src.copyTo(output);
            }

            result.release();
            return output;
        } finally {
            template.release();
        }
    }

    /**
     * 解析模板匹配方法
     *
     * @param methodStr 方法名称
     * @return OpenCV 匹配方法常量
     */
    private int parseMatchMethod(String methodStr) {
        return switch (methodStr.toLowerCase()) {
            case "sqdiff" -> Imgproc.TM_SQDIFF;
            case "sqdiff_normed" -> Imgproc.TM_SQDIFF_NORMED;
            case "ccorr" -> Imgproc.TM_CCORR;
            case "ccorr_normed" -> Imgproc.TM_CCORR_NORMED;
            case "ccoeff" -> Imgproc.TM_CCOEFF;
            case "ccoeff_normed" -> Imgproc.TM_CCOEFF_NORMED;
            default -> Imgproc.TM_CCOEFF_NORMED;
        };
    }

    @Override
    /** 名称 */
    public String name() {
        return "opencv";
    }

    @Override
    /** 可用 */
    public boolean available() {
        return loaded;
    }
}
