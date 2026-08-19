package com.chua.image.support.bridge;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Rust 图像处理桥接类
 * 
 * 提供 JNI 接口调用 Rust 实现的图像处理功能
 * 
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class RustImageBridge {
    
    /** Library_name */
    private static final String LIBRARY_NAME = "rust_image_processor";
    /** initialized */
    private static volatile boolean initialized = false;
    
    static {
        try {
            System.loadLibrary(LIBRARY_NAME);
            if (nativeInit()) {
                initialized = true;
                log.info("[图片处理][Rust] Rust 图像处理库初始化成功，版本: {}", nativeGetVersion());
            } else {
                log.warn("[图片处理][Rust] Rust 图像处理库初始化失败");
            }
        } catch (Exception e) {
            log.warn("[图片处理][Rust] 无法加载 Rust 图像处理库: {}", e.getMessage());
        }
    }
    
    /**
     * 检查是否已初始化
     * 
     * @return 是否已初始化
     */
    public static boolean isInitialized() {
        return initialized;
    }
    
    /**
     * 初始化本地库
     * 
     * @return 是否初始化成功
     */
    private static native boolean nativeInit();
    
    /**
     * 获取版本信息
     * 
     * @return 版本字符串
     */
    public static native String nativeGetVersion();
    
    /**
     * 旋转图片
     * 
     * @param imageData 图片数据
     * @param angle 旋转角度
     * @return 处理后的图片数据
     */
    @Nullable
    public static native byte[] nativeRotate(@Nonnull byte[] imageData, int angle);
    
    /**
     * 生成缩略图
     * 
     * @param imageData 图片数据
     * @param width 宽度
     * @param height 高度
     * @return 处理后的图片数据
     */
    @Nullable
    public static native byte[] nativeThumbnail(@Nonnull byte[] imageData, int width, int height);
    
    /**
     * 调整图片质量
     * 
     * @param imageData 图片数据
     * @param quality 质量 (0-100)
     * @return 处理后的图片数据
     */
    @Nullable
    public static native byte[] nativeAdjustQuality(@Nonnull byte[] imageData, int quality);
    
    /**
     * 裁剪图片
     * 
     * @param imageData 图片数据
     * @param x 起始x坐标
     * @param y 起始y坐标
     * @param width 宽度
     * @param height 高度
     * @return 处理后的图片数据
     */
    @Nullable
    public static native byte[] nativeCrop(@Nonnull byte[] imageData, int x, int y, int width, int height);
    
    /**
     * 像素化处理
     * 
     * @param imageData 图片数据
     * @param intensity 像素化强度
     * @return 处理后的图片数据
     */
    @Nullable
    public static native byte[] nativePixelate(@Nonnull byte[] imageData, int intensity);
    
    /**
     * 圆形裁剪处理
     * 
     * @param imageData 图片数据
     * @param intensity 圆形半径调整
     * @return 处理后的图片数据
     */
    @Nullable
    public static native byte[] nativeCircleCrop(@Nonnull byte[] imageData, int intensity);
    
    /**
     * 圆角处理
     * 
     * @param imageData 图片数据
     * @param radius 圆角半径
     * @return 处理后的图片数据
     */
    @Nullable
    public static native byte[] nativeRoundCorner(@Nonnull byte[] imageData, int radius);
    
    /**
     * 模糊处理
     * 
     * @param imageData 图片数据
     * @param blurRadius 模糊半径
     * @return 处理后的图片数据
     */
    @Nullable
    public static native byte[] nativeBlur(@Nonnull byte[] imageData, float blurRadius);
    
    /**
     * 亮度调整
     * 
     * @param imageData 图片数据
     * @param brightnessFactor 亮度因子 (0.0-2.0)
     * @return 处理后的图片数据
     */
    @Nullable
    public static native byte[] nativeBrightness(@Nonnull byte[] imageData, float brightnessFactor);
    
    /**
     * 灰度处理
     * 
     * @param imageData 图片数据
     * @return 处理后的图片数据
     */
    @Nullable
    public static native byte[] nativeGrayscale(@Nonnull byte[] imageData);
}

