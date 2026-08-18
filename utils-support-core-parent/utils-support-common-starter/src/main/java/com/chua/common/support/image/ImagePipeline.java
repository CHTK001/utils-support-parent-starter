package com.chua.common.support.image;

import java.util.HashMap;
import java.util.Map;

/**
 * 图像处理管线门面。
 *
 * <p>基于 {@link ImageProcessors} SPI 机制，编排灰度化、二值化、降噪、
 * 腐蚀、膨胀等图像预处理步骤。所有功能<strong>默认关闭</strong>，
 * 仅当显式启用对应步骤时才生效，未启用时原样返回输入图像。</p>
 *
 * <p>底层实际处理由 {@link ImageProcessor} SPI 实现完成（Rust 原生优先、
 * OpenCV 次之、JDK AWT 兜底），本门面负责步骤编排与参数传递。</p>
 *
 * <pre>{@code
 * // 全部关闭（默认）：原样返回
 * ImagePipeline allOff = ImagePipeline.builder().build();
 *
 * // 启用灰度 + 二值化
 * ImagePipeline pipeline = ImagePipeline.builder()
 *         .grayscale(true)
 *         .binarize(true, 128)
 *         .build();
 * byte[] out = pipeline.process(inputBytes);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ImagePipeline {

    /**
     * 底层图像处理器（SPI 自动降级）。
     */
    private final ImageProcessor processor;

    /**
     * 是否启用灰度化。
     */
    private final boolean grayscale;

    /**
     * 是否启用二值化。
     */
    private final boolean binarize;

    /**
     * 二值化阈值。
     */
    private final int binarizeThreshold;

    /**
     * 是否启用降噪。
     */
    private final boolean denoise;

    /**
     * 降噪邻域半径。
     */
    private final int denoiseRadius;

    /**
     * 是否启用腐蚀。
     */
    private final boolean erode;

    /**
     * 腐蚀核尺寸。
     */
    private final int erodeKernel;

    /**
     * 是否启用膨胀。
     */
    private final boolean dilate;

    /**
     * 膨胀核尺寸。
     */
    private final int dilateKernel;

    /**
     * 构造图像管线。
     *
     * @param processor        底层处理器
     * @param grayscale        启用灰度化
     * @param binarize         启用二值化
     * @param binarizeThreshold 二值化阈值
     * @param denoise          启用降噪
     * @param denoiseRadius    降噪半径
     * @param erode            启用腐蚀
     * @param erodeKernel      腐蚀核
     * @param dilate           启用膨胀
     * @param dilateKernel     膨胀核
     */
    public ImagePipeline(ImageProcessor processor, boolean grayscale, boolean binarize,
                         int binarizeThreshold, boolean denoise, int denoiseRadius,
                         boolean erode, int erodeKernel, boolean dilate, int dilateKernel) {
        this.processor = processor;
        this.grayscale = grayscale;
        this.binarize = binarize;
        this.binarizeThreshold = binarizeThreshold;
        this.denoise = denoise;
        this.denoiseRadius = denoiseRadius;
        this.erode = erode;
        this.erodeKernel = erodeKernel;
        this.dilate = dilate;
        this.dilateKernel = dilateKernel;
    }

    /**
     * 构建器。
     *
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 处理图像。
     *
     * <p>按「灰度化 → 二值化 → 降噪 → 腐蚀 → 膨胀」顺序执行已启用的步骤。
     * 全部步骤关闭时原样返回输入。</p>
     *
     * @param imageData 原始图像字节（PNG/JPEG 等）
     * @return 处理后的图像字节；未启用任何步骤时返回原图
     */
    public byte[] process(byte[] imageData) {
        if (imageData == null || imageData.length == 0) {
            return imageData;
        }
        byte[] current = imageData;
        if (grayscale) {
            current = apply(current, "grayscale", Map.of());
        }
        if (binarize) {
            Map<String, Object> params = new HashMap<>();
            params.put("threshold", binarizeThreshold);
            current = apply(current, "binarize", params);
        }
        if (denoise) {
            Map<String, Object> params = new HashMap<>();
            params.put("radius", denoiseRadius);
            current = apply(current, "denoise", params);
        }
        if (erode) {
            Map<String, Object> params = new HashMap<>();
            params.put("kernel", erodeKernel);
            current = apply(current, "erode", params);
        }
        if (dilate) {
            Map<String, Object> params = new HashMap<>();
            params.put("kernel", dilateKernel);
            current = apply(current, "dilate", params);
        }
        return current;
    }

    /**
     * 执行单步处理。
     *
     * <p>底层 SPI 处理器执行失败时抛出 {@link IllegalStateException}，
     * 由调用方决定是否捕获降级。</p>
     *
     * @param imageData 图像字节
     * @param operation 操作名
     * @param params    操作参数
     * @return 处理后的图像字节
     */
    private byte[] apply(byte[] imageData, String operation, Map<String, Object> params) {
        try {
            return processor.process(imageData, operation, params);
        } catch (Exception e) {
            throw new IllegalStateException("图像操作失败: " + operation + " -> " + e.getMessage(), e);
        }
    }

    /**
     * 底层图像处理器。
     *
     * @return ImageProcessor
     */
    public ImageProcessor processor() {
        return processor;
    }

    /**
     * 链式构建器。
     *
     * <p>所有步骤默认关闭，仅调用对应 setter 后生效。</p>
     *
     * @author CH
     * @since 4.0.0.42
     */
    public static final class Builder {

        /**
         * 底层处理器，默认走 {@link ImageProcessors#getProcessor()} SPI 自动选择。
         */
        private ImageProcessor processor;

        /**
         * 灰度化开关。
         */
        private boolean grayscale;

        /**
         * 二值化开关。
         */
        private boolean binarize;

        /**
         * 二值化阈值。
         */
        private int binarizeThreshold = 128;

        /**
         * 降噪开关。
         */
        private boolean denoise;

        /**
         * 降噪半径。
         */
        private int denoiseRadius = 1;

        /**
         * 腐蚀开关。
         */
        private boolean erode;

        /**
         * 腐蚀核。
         */
        private int erodeKernel = 3;

        /**
         * 膨胀开关。
         */
        private boolean dilate;

        /**
         * 膨胀核。
         */
        private int dilateKernel = 3;

        /**
         * 指定底层处理器（覆盖 SPI 自动选择）。
         *
         * @param processor 处理器实例
         * @return this
         */
        public Builder processor(ImageProcessor processor) {
            this.processor = processor;
            return this;
        }

        /**
         * 启用/关闭灰度化。
         *
         * @param enabled true 启用
         * @return this
         */
        public Builder grayscale(boolean enabled) {
            this.grayscale = enabled;
            return this;
        }

        /**
         * 启用二值化。
         *
         * @param enabled   是否启用
         * @param threshold 阈值 0~255
         * @return this
         */
        public Builder binarize(boolean enabled, int threshold) {
            this.binarize = enabled;
            this.binarizeThreshold = threshold;
            return this;
        }

        /**
         * 启用二值化（默认阈值 128）。
         *
         * @param enabled 是否启用
         * @return this
         */
        public Builder binarize(boolean enabled) {
            return binarize(enabled, 128);
        }

        /**
         * 启用降噪。
         *
         * @param enabled 是否启用
         * @param radius  邻域半径
         * @return this
         */
        public Builder denoise(boolean enabled, int radius) {
            this.denoise = enabled;
            this.denoiseRadius = radius;
            return this;
        }

        /**
         * 启用降噪（默认半径 1）。
         *
         * @param enabled 是否启用
         * @return this
         */
        public Builder denoise(boolean enabled) {
            return denoise(enabled, 1);
        }

        /**
         * 启用腐蚀。
         *
         * @param enabled 是否启用
         * @param kernel  核尺寸（奇数）
         * @return this
         */
        public Builder erode(boolean enabled, int kernel) {
            this.erode = enabled;
            this.erodeKernel = kernel;
            return this;
        }

        /**
         * 启用腐蚀（默认核 3）。
         *
         * @param enabled 是否启用
         * @return this
         */
        public Builder erode(boolean enabled) {
            return erode(enabled, 3);
        }

        /**
         * 启用膨胀。
         *
         * @param enabled 是否启用
         * @param kernel  核尺寸（奇数）
         * @return this
         */
        public Builder dilate(boolean enabled, int kernel) {
            this.dilate = enabled;
            this.dilateKernel = kernel;
            return this;
        }

        /**
         * 启用膨胀（默认核 3）。
         *
         * @param enabled 是否启用
         * @return this
         */
        public Builder dilate(boolean enabled) {
            return dilate(enabled, 3);
        }

        /**
         * 构建图像管线。
         *
         * @return ImagePipeline
         */
        public ImagePipeline build() {
            ImageProcessor proc = processor != null ? processor : ImageProcessors.getProcessor();
            return new ImagePipeline(proc, grayscale, binarize, binarizeThreshold,
                    denoise, denoiseRadius, erode, erodeKernel, dilate, dilateKernel);
        }
    }
}