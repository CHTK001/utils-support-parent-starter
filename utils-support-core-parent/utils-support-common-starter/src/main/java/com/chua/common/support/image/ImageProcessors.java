package com.chua.common.support.image;

import com.chua.common.support.image.processor.JdkImageProcessor;
import com.chua.common.support.spi.ServiceProvider;

/**
 * 图像处理器加载器
 *
 * <p>基于 {@link ServiceProvider} 发现全部 {@link ImageProcessor} 实现，
 * 通过 {@code @SpiOrder} 注解声明优先级：Rust 原生实现优先（100），
 * OpenCV 次之（50），AWT 实现兜底（-100）。</p>
 *
 * <p>返回的处理器为按优先级自动降级的代理实现：调用任一方法时依次尝试各实现，
 * 当前实现失败（异常）时自动降级到下一优先级实现，全部失败则抛出异常。</p>
 *
 * @author CH
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
}
