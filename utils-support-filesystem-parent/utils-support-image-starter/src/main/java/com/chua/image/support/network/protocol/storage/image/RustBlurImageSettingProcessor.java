package com.chua.image.support.network.protocol.storage.image;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.StringUtils;
import com.chua.image.support.bridge.RustImageBridge;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Rust 图片模糊处理器
 * 
 * 使用 Rust 实现的高性能图片模糊处理
 * 
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi(value = "blur", order = 0)
@SpiDescribe("模糊效果（Rust实现）")
public class RustBlurImageSettingProcessor implements com.chua.common.support.network.protocol.storage.image.ImageSettingProcessor {

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
            int blurLevel = parseBlurLevel(settingValue);
            
            // 如果模糊级别为0，不进行处理
            if (blurLevel <= 0) {
                return imageData;
            }
            
            // 将0-100的模糊级别转换为模糊半径（0.0-10.0）
            float blurRadius = blurLevel / 10.0f;
            
            byte[] result = RustImageBridge.nativeBlur(imageData, blurRadius);
            return result != null ? result : imageData;
            
        } catch (Exception e) {
            log.error("[图片处理][Rust模糊] 模糊处理异常", e);
            return null;
        }
    }

    /**
     * 解析模糊级别
     * 
     * @param blurValue 模糊值字符串
     * @return 模糊级别(0-100)
     */
    private int parseBlurLevel(String blurValue) {
        if (StringUtils.isEmpty(blurValue)) {
            return 0;
        }
        
        try {
            int level = Integer.parseInt(blurValue.trim());
            
            // 限制在0-100范围内
            if (level < 0) {
                return 0;
            } else if (level > 100) {
                return 100;
            }
            
            return level;
            
        } catch (NumberFormatException e) {
            log.warn("[图片处理][Rust模糊] 无效的模糊级别: {}", blurValue);
            return 10;
        }
    }
}

