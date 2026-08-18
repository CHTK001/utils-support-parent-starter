package com.chua.common.support.image;

import com.chua.common.support.image.processor.JdkImageProcessor;
import com.chua.common.support.image.processor.RustImageProcessor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 图像处理器加载器
 *
 * <p>通过 {@link ServiceLoader} 发现全部 {@link ImageProcessor} 实现，
 * 按优先级排序：OpenCV 优先，Rust 次之，JDK 兜底。
 * 若 SPI 未注册任何实现，则默认创建 {@link JdkImageProcessor}。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ImageProcessors {

    /**
     * 处理器实例缓存
     */
    private static volatile ImageProcessor processor;

    /**
     * 私有构造方法，防止实例化
     */
    private ImageProcessors() {
    }

    /**
     * 获取图像处理器
     *
     * <p>Rust 原生实现可用时优先返回，否则回退到 AWT 实现。
     *
     * @return 图像处理器
     */
    public static ImageProcessor getProcessor() {
        if (processor != null) {
            return processor;
        }
        synchronized (ImageProcessors.class) {
            if (processor != null) {
                return processor;
            }
            List<ImageProcessor> processors = new ArrayList<>();
            ServiceLoader.load(ImageProcessor.class).forEach(processors::add);
            processor = processors.stream()
                    .sorted(Comparator.comparingInt(ImageProcessors::priority))
                    .filter(ImageProcessor::available)
                    .findFirst()
                    .orElseGet(JdkImageProcessor::new);
            return processor;
        }
    }

    /**
     * 计算处理器优先级（越小越优先）
     *
     * @param p 处理器
     * @return 优先级值
     */
    private static int priority(ImageProcessor p) {
        return switch (p.name()) {
            case "opencv" -> 0;
            case "rust" -> 1;
            default -> 10;
        };
    }
}
