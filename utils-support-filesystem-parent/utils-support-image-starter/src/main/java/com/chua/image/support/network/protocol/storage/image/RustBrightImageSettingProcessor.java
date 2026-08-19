package com.chua.image.support.network.protocol.storage.image;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.StringUtils;
import com.chua.image.support.bridge.RustImageBridge;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Rust 图片亮度处理器
 * 
 * 使用 Rust 实现的高性能图片亮度调整
 * 
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = "bright", order = 0)
@SpiDescribe("图片亮度（Rust实现）")
public class RustBrightImageSettingProcessor implements com.chua.common.support.network.protocol.storage.image.ImageSettingProcessor {

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
            int brightness = parseBrightness(settingValue);
            
            // 如果亮度为50（默认值），不进行处理
            if (brightness == 50) {
                return imageData;
            }
            
            // 将0-100的亮度值转换为0.0-2.0的因子
            float brightnessFactor = brightness / 50.0f;
            
            byte[] result = RustImageBridge.nativeBrightness(imageData, brightnessFactor);
            return result != null ? result : imageData;
            
        } catch (Exception e) {
            log.error("[图片处理][Rust亮度] 亮度处理异常", e);
            return null;
        }
    }

    /**
     * 解析亮度参数
     * 
     * @param brightnessValue 亮度值字符串
     * @return 亮度级别(0-100)
     */
    private int parseBrightness(String brightnessValue) {
        if (StringUtils.isEmpty(brightnessValue)) {
            // 默认亮度
            // return 50;
        }
        
        try {
            int brightness = Integer.parseInt(brightnessValue.trim());
            
            // 限制在0-100范围内
            if (brightness < 0) {
                return 0;
            } else if (brightness > 100) {
                return 100;
            }
            
            return brightness;
            
        } catch (NumberFormatException e) {
            log.warn("[图片处理][Rust亮度] 无效的亮度级别: {}", brightnessValue);
            return 50;
        }
    }
}

