package com.chua.common.support.image;

import java.util.Map;

/**
 * 图像处理器 SPI 接口
 *
 * <p>定义统一的图像处理能力，支持按 {@link java.util.ServiceLoader} 机制注册多种实现：
 * <ul>
 *   <li>Rust 原生实现（FFM 加载 {@code libimage_processor.so}，性能优先）</li>
 *   <li>Java AWT 实现（无原生依赖，作为兜底）</li>
 * </ul>
 * 默认通过 {@link ImageProcessors#getProcessor()} 获取，Rust 实现可用时优先返回。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ImageProcessor {

    /**
     * 处理图像
     *
     * @param imageData 原始图像字节（PNG / JPEG 等格式）
     * @param operation 操作类型：resize / grayscale / rotate
     * @param params    操作参数，如 resize 的 width/height、rotate 的 angle、输出 format
     * @return 处理后的图像字节
     */
    byte[] process(byte[] imageData, String operation, Map<String, Object> params);

    /**
     * 批量处理图像
     *
     * <p>减少跨语言调用次数，提升批量场景吞吐。默认逐张调用 {@link #process}，
     * 原生实现可重写为一次 FFI 调用处理整批。
     *
     * @param images    原始图像字节数组
     * @param operation 操作类型
     * @param params    操作参数
     * @return 处理后的图像字节数组
     */
    default byte[][] processBatch(byte[][] images, String operation, Map<String, Object> params) {
        byte[][] results = new byte[images.length][];
        for (int i = 0; i < images.length; i++) {
            results[i] = process(images[i], operation, params);
        }
        return results;
    }

    /**
     * 处理器名称，用于日志与优先级判断
     *
     * @return 处理器名称，如 "rust" / "awt"
     */
    String name();

    /**
     * 处理器是否可用
     *
     * <p>原生实现需动态库加载成功后返回 true，否则返回 false 以便回退。
     *
     * @return true 可用
     */
    boolean available();
}
