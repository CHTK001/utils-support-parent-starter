package com.chua.image.support.network.protocol.storage.image;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.StringUtils;
import com.chua.image.support.bridge.RustImageBridge;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Rust 图片质量处理器
 * 
 * 使用 Rust 实现的高性能图片质量调整
 * 
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi(value = "quality", order = 0)
@SpiDescribe("图片质量（Rust实现）")
public class RustQualityImageSettingProcessor implements com.chua.common.support.network.protocol.storage.image.ImageSettingProcessor {

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
            int quality = parseQuality(settingValue);

            // 如果质量为75（默认值），不进行处理
            if (quality == 75) {
                return imageData;
            }

            // Rust 实现使用 JPEG 编码调整质量
            byte[] result = RustImageBridge.nativeAdjustQuality(imageData, quality);
            return result != null ? result : imageData;

        } catch (Exception e) {
            log.error("[图片处理][Rust质量] 质量处理异常", e);
            return null;
        }
    }

    /**
     * 解析质量参数
     *
     * @param qualityValue 质量值字符串
     * @return 质量级别(0-100)
     */
    private int parseQuality(String qualityValue) {
        if (StringUtils.isEmpty(qualityValue)) {
            // 默认质量
            // return 75;
        }

        try {
            int quality = Integer.parseInt(qualityValue.trim());

            // 限制在0-100范围内
            if (quality < 0) {
                return 0;
            } else if (quality > 100) {
                return 100;
            }

            return quality;

        } catch (NumberFormatException e) {
            log.warn("[图片处理][Rust质量] 无效的质量级别: {}", qualityValue);
            return 75;
        }
    }
}

