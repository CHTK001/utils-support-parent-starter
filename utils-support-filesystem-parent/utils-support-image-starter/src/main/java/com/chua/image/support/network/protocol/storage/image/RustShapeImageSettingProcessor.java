package com.chua.image.support.network.protocol.storage.image;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.StringUtils;
import com.chua.image.support.bridge.RustImageBridge;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Rust 图片形状处理器
 * 
 * 使用 Rust 实现的高性能图片形状变换
 * 支持像素化、圆形裁剪、圆角处理
 * 
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = "shape", order = 0)
@SpiDescribe("图片形状（Rust实现）")
public class RustShapeImageSettingProcessor implements com.chua.common.support.network.protocol.storage.image.ImageSettingProcessor {

    @Override
    @Nullable
    public byte[] process(@Nonnull byte[] imageData, @Nonnull String settingValue) {
        if (!RustImageBridge.isInitialized()) {
            return null;
        }
        
        if (StringUtils.isEmpty(settingValue)) {
            return imageData;
        }
        
        try {
            // 解析形状参数，例如: "pixel,10" 或 "circle,5"
            String[] parts = settingValue.split(",");
            String shapeType = parts[0].trim().toLowerCase();
            int intensity = parts.length > 1 ? parseIntValue(parts[1], 10) : 10;
            
            byte[] result = processShape(imageData, shapeType, intensity);
            return result != null ? result : imageData;
            
        } catch (Exception e) {
            log.error("[图片处理][Rust形状] 形状处理异常", e);
            return null;
        }
    }

    /**
     * 处理具体的形状变换
     * 
     * @param imageData 原始图片数据
     * @param shapeType 形状类型
     * @param intensity 强度
     * @return 处理后的图片数据
     */
    private byte[] processShape(byte[] imageData, String shapeType, int intensity) {
        return switch (shapeType) {
            case "pixel" -> RustImageBridge.nativePixelate(imageData, intensity);
            case "circle" -> RustImageBridge.nativeCircleCrop(imageData, intensity);
            case "round" -> RustImageBridge.nativeRoundCorner(imageData, intensity);
            default -> {
                if (log.isDebugEnabled()) {
                    log.debug("[图片处理][Rust形状] 不支持的形状类型: {}", shapeType);
                }
                yield null;
            }
        };
    }

    /**
     * 解析整数值
     * 
     * @param value 字符串值
     * @param defaultValue 默认值
     * @return 解析后的整数
     */
    private int parseIntValue(String value, int defaultValue) {
        if (StringUtils.isEmpty(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            if (log.isDebugEnabled()) {
                log.debug("[图片处理][Rust形状] 解析整数失败: {}，使用默认值: {}", value, defaultValue);
            }
            return defaultValue;
        }
    }
}

