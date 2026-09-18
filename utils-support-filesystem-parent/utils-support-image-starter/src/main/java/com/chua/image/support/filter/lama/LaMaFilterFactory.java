package com.chua.image.support.filter.lama;

import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* lama滤镜工厂类
* <p>
* 提供便捷的lama滤镜创建和管理功能，包括：
* 1. 滤镜实例缓存和复用
* 2. 预设配置的快速创建
* 3. 资源管理和清理
* 4. 线程安全的实例管理
* </p>
*
* @author CH
* @since 2024/7/29
 */
@Slf4j
public class LaMaFilterFactory {

    /**
    * 滤镜实例缓存
    * 键: 配置的唯一标识
    * 值: lama滤镜实例
    */
    private static final ConcurrentMap<String, LaMaImageFilter> FILTER_CACHE = new ConcurrentHashMap<>();

    /**
    * 默认模型路径
    */
    private static String defaultModelPath = "models/lama.onnx";

    /**
    * 私有构造函数，防止实例化
    */
    private LaMaFilterFactory() {
    }

    /**
    * 设置默认模型路径
    *
    * @param modelPath 模型路径
    */
    public static void setDefaultModelPath(String modelPath) {
        defaultModelPath = modelPath;
    }

    /**
    * 获取默认模型路径
    *
    * @return 默认模型路径
    */
    public static String getDefaultModelPath() {
        return defaultModelPath;
    }

    /**
    * 创建默认lama滤镜
    *
    * @return LaMa滤镜实例
    */
    public static LaMaImageFilter createDefault() {
        return createDefault(defaultModelPath);
    }

    /**
    * 创建默认lama滤镜
    *
    * @param modelPath 模型路径
    * @return LaMa滤镜实例
    */
    public static LaMaImageFilter createDefault(String modelPath) {
        String cacheKey = "default_" + modelPath;
        return FILTER_CACHE.computeIfAbsent(cacheKey, k -> {
            log.info("创建默认LaMa滤镜: {}", modelPath);
            return LaMaImageFilter.createDefault(modelPath);
        });
    }

    /**
    * 创建高质量lama滤镜
    *
    * @return LaMa滤镜实例
    */
    public static LaMaImageFilter createHighQuality() {
        return createHighQuality(defaultModelPath);
    }

    /**
    * 创建高质量lama滤镜
    *
    * @param modelPath 模型路径
    * @return LaMa滤镜实例
    */
    public static LaMaImageFilter createHighQuality(String modelPath) {
        String cacheKey = "high_quality_" + modelPath;
        return FILTER_CACHE.computeIfAbsent(cacheKey, k -> {
            log.info("创建高质量LaMa滤镜: {}", modelPath);
            return LaMaImageFilter.createHighQuality(modelPath);
        });
    }

    /**
    * 创建快速lama滤镜
    *
    * @return LaMa滤镜实例
    */
    public static LaMaImageFilter createFast() {
        return createFast(defaultModelPath);
    }

    /**
    * 创建快速lama滤镜
    *
    * @param modelPath 模型路径
    * @return LaMa滤镜实例
    */
    public static LaMaImageFilter createFast(String modelPath) {
        String cacheKey = "fast_" + modelPath;
        return FILTER_CACHE.computeIfAbsent(cacheKey, k -> {
            log.info("创建快速LaMa滤镜: {}", modelPath);
            return LaMaImageFilter.createFast(modelPath);
        });
    }

    /**
    * 创建GPU加速lama滤镜
    *
    * @return LaMa滤镜实例
    */
    public static LaMaImageFilter createGpu() {
        return createGpu(defaultModelPath);
    }

    /**
    * 创建GPU加速lama滤镜
    *
    * @param modelPath 模型路径
    * @return LaMa滤镜实例
    */
    public static LaMaImageFilter createGpu(String modelPath) {
        String cacheKey = "gpu_" + modelPath;
        return FILTER_CACHE.computeIfAbsent(cacheKey, k -> {
            log.info("创建GPU加速LaMa滤镜: {}", modelPath);
            return LaMaImageFilter.createGpu(modelPath);
        });
    }

    /**
    * 创建自动mask lama滤镜
    *
    * @param targetColor 目标颜色
    * @return LaMa滤镜实例
    */
    public static LaMaImageFilter createAutoMask(int[] targetColor) {
        return createAutoMask(defaultModelPath, targetColor);
    }

    /**
    * 创建自动mask lama滤镜
    *
    * @param modelPath   模型路径
    * @param targetColor 目标颜色
    * @return LaMa滤镜实例
    */
    public static LaMaImageFilter createAutoMask(String modelPath, int[] targetColor) {
        String cacheKey = "auto_mask_" + modelPath + "_" + java.util.Arrays.toString(targetColor);
        return FILTER_CACHE.computeIfAbsent(cacheKey, k -> {
            log.info("创建自动mask LaMa滤镜: {}, 目标颜色: {}", modelPath, java.util.Arrays.toString(targetColor));
            return LaMaImageFilter.createAutoMask(modelPath, targetColor);
        });
    }

    /**
    * 创建自定义配置的lama滤镜
    *
    * @param config 配置
    * @return LaMa滤镜实例
    */
    public static LaMaImageFilter create(LaMaConfiguration config) {
        String cacheKey = "custom_" + config.toString();
        return FILTER_CACHE.computeIfAbsent(cacheKey, k -> {
            log.info("创建自定义配置LaMa滤镜: {}", config);
            return new LaMaImageFilter(config);
        });
    }

    /**
    * 获取缓存的滤镜实例
    *
    * @param cacheKey 缓存键
    * @return 滤镜实例，不存在时返回null
    */
    public static LaMaImageFilter getCachedFilter(String cacheKey) {
        return FILTER_CACHE.get(cacheKey);
    }

    /**
    * 移除缓存的滤镜实例
    *
    * @param cacheKey 缓存键
    * @return 被移除的滤镜实例，不存在时返回null
    */
    public static LaMaImageFilter removeCachedFilter(String cacheKey) {
        LaMaImageFilter filter = FILTER_CACHE.remove(cacheKey);
        if (filter != null) {
            filter.cleanup();
            log.info("移除并清理缓存的滤镜: {}", cacheKey);
        }
        return filter;
    }

    /**
    * 清理所有缓存的滤镜实例
    */
    public static void clearAllFilters() {
        log.info("开始清理所有缓存的LaMa滤镜，数量: {}", FILTER_CACHE.size());
        
        FILTER_CACHE.values().forEach(filter -> {
            try {
                filter.cleanup();
            } catch (Exception e) {
                log.warn("清理滤镜时出错", e);
            }
        });
        
        FILTER_CACHE.clear();
        log.info("所有LaMa滤镜清理完成");
    }

    /**
    * 获取缓存的滤镜数量
    *
    * @return 缓存的滤镜数量
    */
    public static int getCachedFilterCount() {
        return FILTER_CACHE.size();
    }

    /**
    * 检查是否有缓存的滤镜
    *
    * @param cacheKey 缓存键
    * @return 是否存在
    */
    public static boolean hasCachedFilter(String cacheKey) {
        return FILTER_CACHE.containsKey(cacheKey);
    }

    /**
    * 预热所有缓存的滤镜
    */
    public static void warmUpAllFilters() {
        log.info("开始预热所有缓存的LaMa滤镜");
        
        FILTER_CACHE.values().parallelStream().forEach(filter -> {
            try {
                filter.warmUp();
            } catch (Exception e) {
                log.warn("预热滤镜时出错", e);
            }
        });
        
        log.info("所有LaMa滤镜预热完成");
    }

    /**
    * 便捷方法：直接处理图像
    *
    * @param image 输入图像
    * @return 处理后的图像
    */
    public static BufferedImage processImage(BufferedImage image) {
        return processImage(image, createDefault());
    }

    /**
    * 便捷方法：使用指定滤镜处理图像
    *
    * @param image  输入图像
    * @param filter 滤镜实例
    * @return 处理后的图像
    */
    public static BufferedImage processImage(BufferedImage image, LaMaImageFilter filter) {
        try {
            return filter.converter(image);
        } catch (Exception e) {
            log.error("图像处理失败", e);
            throw new RuntimeException("图像处理失败", e);
        }
    }

    /**
    * 便捷方法：移除指定颜色的对象
    *
    * @param image       输入图像
    * @param targetColor 目标颜色
    * @return 处理后的图像
    */
    public static BufferedImage removeObject(BufferedImage image, int[] targetColor) {
        LaMaImageFilter filter = createAutoMask(targetColor);
        return processImage(image, filter);
    }

    /**
    * 便捷方法：高质量图像修复
    *
    * @param image 输入图像
    * @return 处理后的图像
    */
    public static BufferedImage highQualityRepair(BufferedImage image) {
        LaMaImageFilter filter = createHighQuality();
        return processImage(image, filter);
    }

    /**
    * 便捷方法：快速图像修复
    *
    * @param image 输入图像
    * @return 处理后的图像
    */
    public static BufferedImage fastRepair(BufferedImage image) {
        LaMaImageFilter filter = createFast();
        return processImage(image, filter);
    }

    /**
    * 获取工厂状态信息
    *
    * @return 状态信息
    */
    public static String getFactoryStatus() {
        StringBuilder status = new StringBuilder();
        status.append("LaMa滤镜工厂状态:\n");
        status.append("- 默认模型路径: ").append(defaultModelPath).append("\n");
        status.append("- 缓存滤镜数量: ").append(FILTER_CACHE.size()).append("\n");
        status.append("- 缓存键列表: ").append(FILTER_CACHE.keySet()).append("\n");
        
        return status.toString();
    }

    /**
    * 注册JVM关闭钩子，确保资源清理
    */
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("JVM关闭，清理LaMa滤镜资源");
            clearAllFilters();
        }));
    }
}
