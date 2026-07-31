package com.chua.image.support.network.protocol.storage.image;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.StringUtils;
import com.chua.image.support.bridge.RustImageBridge;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Rust 图片旋转处理器
 * 
 * 使用 Rust 实现的高性能图片旋转处理
 * 
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi(value = "rotate", order = 0)
@SpiDescribe("图片旋转（Rust实现）")
public class RustRotateImageSettingProcessor implements com.chua.common.support.network.protocol.storage.image.ImageSettingProcessor {

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
            int angle = Integer.parseInt(settingValue.trim());

            // 如果角度为0或360的倍数，不需要旋转
            if (angle == 0 || angle % 360 == 0) {
                return imageData;
            }

            // 标准化角度到0-360范围
            angle = angle % 360;
            if (angle < 0) {
                angle += 360;
            }

            byte[] result = RustImageBridge.nativeRotate(imageData, angle);
            return result != null ? result : imageData;

        } catch (NumberFormatException e) {
            log.warn("[图片处理][Rust旋转] 无效的旋转角度: {}", settingValue);
            return null;
        } catch (Exception e) {
            log.error("[图片处理][Rust旋转] 旋转处理异常", e);
            return null;
        }
    }
}

