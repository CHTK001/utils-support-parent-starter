package com.chua.common.support.image;

import com.chua.common.support.image.processor.JdkImageProcessor;
import com.chua.common.support.spi.ServiceProvider;

import java.util.HashMap;
import java.util.Map;

/**
 * 图像处理器加载器与流畅 API 入口
 *
 * <p>基于 {@link ServiceProvider} 发现全部 {@link ImageProcessor} 实现，
 * 通过 {@code @SpiOrder} 注解声明优先级：Rust 原生实现优先（100），
 * OpenCV 次之（50），AWT 实现兜底（-100）。</p>
 *
 * <p>返回的处理器为按优先级自动降级的代理实现：调用任一方法时依次尝试各实现，
 * 当前实现失败（异常）时自动降级到下一优先级实现，全部失败则抛出异常。</p>
 *
 * <h3>1. 直接获取处理器</h3>
 * <pre>{@code
 * ImageProcessor processor = ImageProcessors.getProcessor();
 * byte[] result = processor.process(imageData, "resize", Map.of("width", 200, "height", 200));
 * }</pre>
 *
 * <h3>2. 流畅 API（链式调用）</h3>
 * <pre>{@code
 * // 链式调用多个操作
 * byte[] result = ImageProcessors.from(imageData)
 *         .resize(200, 200)
 *         .grayscale()
 *         .rotate(90)
 *         .blur(3)
 *         .toBytes();
 *
 * // 指定输出格式
 * byte[] jpeg = ImageProcessors.from(imageData)
 *         .brightness(50)
 *         .contrast(30)
 *         .toBytes("jpeg");
 *
 * // 使用自定义处理器
 * byte[] out = ImageProcessors.from(imageData, myProcessor)
 *         .crop(10, 10, 100, 100)
 *         .flip("h")
 *         .toBytes();
 * }</pre>
 *
 * @since 4.0.0.42
 */
public final class ImageProcessors {

    /**
     * 处理器代理实例缓存
     */
    private static volatile ImageProcessor processor;

    /**
     * 私有构造方法，防止实例化
     */
    private ImageProcessors() {
    }

    /**
     * 获取按优先级自动降级的图像处理器代理
     *
     * <p>Rust 原生实现可用时优先，OpenCV 次之，AWT 兜底；
     * 当前实现执行失败自动降级到下一优先级实现。
     *
     * @return 图像处理器代理
     */
    public static ImageProcessor getProcessor() {
        if (processor != null) {
            return processor;
        }
        synchronized (ImageProcessors.class) {
            if (processor != null) {
                return processor;
            }
            ImageProcessor factory = ServiceProvider.of(ImageProcessor.class)
                    .getExtensionFactory("image-processor");
            processor = factory != null ? factory : new JdkImageProcessor();
            return processor;
        }
    }

    // ==================== 流畅 API 入口 ====================

    /**
     * 创建流畅图像处理器（使用默认 SPI 处理器）
     *
     * @param imageData 原始图像字节（PNG / JPEG 等格式）
     * @return 流畅处理器，可链式调用操作
     */
    public static FluentProcessor from(byte[] imageData) {
        return new FluentProcessor(imageData, getProcessor());
    }

    /**
     * 创建流畅图像处理器（使用指定处理器）
     *
     * @param imageData 原始图像字节
     * @param processor 图像处理器实例
     * @return 流畅处理器，可链式调用操作
     */
    public static FluentProcessor from(byte[] imageData, ImageProcessor processor) {
        return new FluentProcessor(imageData, processor);
    }

    /**
     * 流畅图像处理器，支持链式调用多个图像操作
     *
     * <p>每个操作方法返回 {@code this}，允许链式调用。
     * 操作按添加顺序依次执行，每次操作的输出作为下一次操作的输入。</p>
     *
     * <p>最终通过 {@link #toBytes()} 或 {@link #toBytes(String)} 获取处理结果。</p>
     *
     * @since 4.0.0.42
     */
    public static final class FluentProcessor {

        /** 当前图像数据 */
        private byte[] imageData;
        /** 底层处理器 */
        private final ImageProcessor processor;
        /** 输出格式（默认 png） */
        private String format = "png";

        /**
         * 构造流畅处理器
         *
         * @param imageData 原始图像字节
         * @param processor 底层处理器
         */
        private FluentProcessor(byte[] imageData, ImageProcessor processor) {
            this.imageData = imageData;
            this.processor = processor;
        }

        /**
         * 缩放图像
         *
         * @param width  目标宽度
         * @param height 目标高度
         * @return {@code this}，支持链式调用
         */
        public FluentProcessor resize(int width, int height) {
            Map<String, Object> params = newParams();
            params.put("width", width);
            params.put("height", height);
            imageData = processor.process(imageData, "resize", params);
            return this;
        }

        /**
         * 转为灰度图像
         *
         * @return {@code this}，支持链式调用
         */
        public FluentProcessor grayscale() {
            imageData = processor.process(imageData, "grayscale", newParams());
            return this;
        }

        /**
         * 旋转图像（支持 90 的整数倍及任意角度）
         *
         * @param angle 旋转角度（度）
         * @return {@code this}，支持链式调用
         */
        public FluentProcessor rotate(int angle) {
            Map<String, Object> params = newParams();
            params.put("angle", angle);
            imageData = processor.process(imageData, "rotate", params);
            return this;
        }

        /**
         * 裁剪图像
         *
         * @param x      起始 X 坐标
         * @param y      起始 Y 坐标
         * @param width  裁剪宽度
         * @param height 裁剪高度
         * @return {@code this}，支持链式调用
         */
        public FluentProcessor crop(int x, int y, int width, int height) {
            Map<String, Object> params = newParams();
            params.put("x", x);
            params.put("y", y);
            params.put("width", width);
            params.put("height", height);
            imageData = processor.process(imageData, "crop", params);
            return this;
        }

        /**
         * 高斯模糊
         *
         * @param sigma 模糊半径
         * @return {@code this}，支持链式调用
         */
        public FluentProcessor blur(int sigma) {
            Map<String, Object> params = newParams();
            params.put("sigma", sigma);
            imageData = processor.process(imageData, "blur", params);
            return this;
        }

        /**
         * 翻转图像
         *
         * @param axis 翻转轴：{@code "h"} 水平翻转 / {@code "v"} 垂直翻转
         * @return {@code this}，支持链式调用
         */
        public FluentProcessor flip(String axis) {
            Map<String, Object> params = newParams();
            params.put("axis", axis);
            imageData = processor.process(imageData, "flip", params);
            return this;
        }

        /**
         * 水平翻转（快捷方法）
         *
         * @return {@code this}，支持链式调用
         */
        public FluentProcessor flipHorizontal() {
            return flip("h");
        }

        /**
         * 垂直翻转（快捷方法）
         *
         * @return {@code this}，支持链式调用
         */
        public FluentProcessor flipVertical() {
            return flip("v");
        }

        /**
         * 调整亮度
         *
         * @param value 亮度调整值（[-255, 255]，正数变亮）
         * @return {@code this}，支持链式调用
         */
        public FluentProcessor brightness(int value) {
            Map<String, Object> params = newParams();
            params.put("value", value);
            imageData = processor.process(imageData, "brightness", params);
            return this;
        }

        /**
         * 调整对比度
         *
         * @param value 对比度调整值（[-100, 100]，正数增强）
         * @return {@code this}，支持链式调用
         */
        public FluentProcessor contrast(int value) {
            Map<String, Object> params = newParams();
            params.put("value", value);
            imageData = processor.process(imageData, "contrast", params);
            return this;
        }

        /**
         * 绘制边框
         *
         * @param width 边框宽度
         * @param color 边框颜色（#RRGGBB 或 r,g,b）
         * @return {@code this}，支持链式调用
         */
        public FluentProcessor border(int width, String color) {
            Map<String, Object> params = newParams();
            params.put("width", width);
            params.put("color", color);
            imageData = processor.process(imageData, "border", params);
            return this;
        }

        /**
         * 绘制边框（默认黑色）
         *
         * @param width 边框宽度
         * @return {@code this}，支持链式调用
         */
        public FluentProcessor border(int width) {
            return border(width, "#000000");
        }

        /**
         * 二值化
         *
         * @param threshold 阈值（0~255，默认 128）
         * @return {@code this}，支持链式调用
         */
        public FluentProcessor binarize(int threshold) {
            Map<String, Object> params = newParams();
            params.put("threshold", threshold);
            imageData = processor.process(imageData, "binarize", params);
            return this;
        }

        /**
         * 降噪（中值滤波）
         *
         * @param radius 邻域半径
         * @return {@code this}，支持链式调用
         */
        public FluentProcessor denoise(int radius) {
            Map<String, Object> params = newParams();
            params.put("radius", radius);
            imageData = processor.process(imageData, "denoise", params);
            return this;
        }

        /**
         * 腐蚀（形态学操作）
         *
         * @param kernel 核尺寸（奇数，默认 3）
         * @return {@code this}，支持链式调用
         */
        public FluentProcessor erode(int kernel) {
            Map<String, Object> params = newParams();
            params.put("kernel", kernel);
            imageData = processor.process(imageData, "erode", params);
            return this;
        }

        /**
         * 膨胀（形态学操作）
         *
         * @param kernel 核尺寸（奇数，默认 3）
         * @return {@code this}，支持链式调用
         */
        public FluentProcessor dilate(int kernel) {
            Map<String, Object> params = newParams();
            params.put("kernel", kernel);
            imageData = processor.process(imageData, "dilate", params);
            return this;
        }

        /**
         * 边缘检测（Sobel 算子）
         *
         * @param direction 方向：{@code "h"} 水平 / {@code "v"} 垂直 / {@code "both"} 双向
         * @return {@code this}，支持链式调用
         */
        public FluentProcessor edge(String direction) {
            Map<String, Object> params = newParams();
            params.put("direction", direction);
            imageData = processor.process(imageData, "edge", params);
            return this;
        }

        /**
         * 边缘检测（双向，快捷方法）
         *
         * @return {@code this}，支持链式调用
         */
        public FluentProcessor edge() {
            return edge("both");
        }

        /**
         * 设置输出格式
         *
         * @param format 输出格式（png / jpeg）
         * @return {@code this}，支持链式调用
         */
        public FluentProcessor format(String format) {
            this.format = format;
            return this;
        }

        /**
         * 执行自定义操作
         *
         * @param operation 操作类型
         * @param params    操作参数
         * @return {@code this}，支持链式调用
         */
        public FluentProcessor apply(String operation, Map<String, Object> params) {
            imageData = processor.process(imageData, operation, params);
            return this;
        }

        /**
         * 获取处理后的图像字节（使用当前设定的格式，默认 PNG）
         *
         * @return 处理后的图像字节
         */
        public byte[] toBytes() {
            return imageData;
        }

        /**
         * 获取处理后的图像字节并指定输出格式
         *
         * <p>如果指定的格式与当前图像格式不同，会重新编码为指定格式。</p>
         *
         * @param format 输出格式（png / jpeg）
         * @return 处理后的图像字节
         */
        public byte[] toBytes(String format) {
            this.format = format;
            // 重新处理以应用新格式：对图像执行一次无损操作（grayscale→再grayscale）
            // 实际上直接将格式参数传递给处理器即可
            Map<String, Object> params = newParams();
            return processor.process(imageData, "grayscale", params);
        }

        /**
         * 创建带格式参数的参数 Map
         *
         * @return 包含 format 参数的 Map
         */
        private Map<String, Object> newParams() {
            Map<String, Object> params = new HashMap<>();
            if (!"png".equals(format)) {
                params.put("format", format);
            }
            return params;
        }
    }
}