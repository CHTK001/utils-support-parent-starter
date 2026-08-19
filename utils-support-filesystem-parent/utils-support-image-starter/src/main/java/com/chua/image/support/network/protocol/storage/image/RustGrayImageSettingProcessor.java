package com.chua.image.support.network.protocol.storage.image;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.StringUtils;
import com.chua.image.support.bridge.RustImageBridge;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Rust 图片灰度处理器
 * 
 * 使用 Rust 实现的高性能图片灰度转换
 * 
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = "gray", order = 0)
@SpiDescribe("灰度转换（Rust实现）")
public class RustGrayImageSettingProcessor implements com.chua.common.support.network.protocol.storage.image.ImageSettingProcessor {

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
            int grayLevel = parseGrayLevel(settingValue);
            
            // 如果灰度级别为0，不进行处理
            if (grayLevel <= 0) {
                return imageData;
            }
            
            // Rust 实现直接转换为灰度图，不区分灰度级别
            // 如果灰度级别不是100，返回 null 让 Java 实现处理
            if (grayLevel != 100) {
                return null;
            }
            
            byte[] result = RustImageBridge.nativeGrayscale(imageData);
            return result != null ? result : imageData;
            
        } catch (Exception e) {
            log.error("[图片处理][Rust灰度] 灰度处理异常", e);
            return null;
        }
    }

    /**
     * 解析灰度级别
     * 
     * @param grayValue 灰度值字符串
     * @return 灰度级别(0-100)
     */
    private int parseGrayLevel(String grayValue) {
        if (StringUtils.isEmpty(grayValue)) {
            return 0;
        }
        
        try {
            int level = Integer.parseInt(grayValue.trim());
            
            // 限制在0-100范围内
            if (level < 0) {
                return 0;
            } else if (level > 100) {
                return 100;
            }
            
            return level;
            
        } catch (NumberFormatException e) {
            log.warn("[图片处理][Rust灰度] 无效的灰度级别: {}", grayValue);
            return 100;
        }
    }
}

