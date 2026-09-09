package com.chua.common.support.network.protocol.storage.image;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.core.utils.BufferedImageUtils;
import com.chua.common.support.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 图片亮度处理器
 * 
 * 处理图片亮度调整功能
 * 
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi("bright")
@SpiDescribe("图片亮度")
public class BrightImageSettingProcessor extends AbstractImageSettingProcessor {

    @Override
    @Nullable
    public byte[] process(@Nonnull byte[] imageData, @Nonnull String settingValue) {
        if (StringUtils.isEmpty(settingValue)) {
            return imageData;
        }
        
        try {
            int brightness = parseBrightness(settingValue);
            
            // 如果亮度为50（默认值），不进行处理
            if (brightness == 50) {
                return imageData;
            }
            
            BufferedImage bufferedImage = bytesToImage(imageData);
            if (bufferedImage == null) {
                return imageData;
            }
            
            // 将0-100的亮度值转换为0.0-2.0的因子
            float brightnessFactor = brightness / 50.0f;
            
            BufferedImage brightImage = BufferedImageUtils.brightnessImage(bufferedImage, brightnessFactor);
            if (brightImage == null) {
                return imageData;
            }
            
            byte[] result = imageToBytes(brightImage);
            return result != null ? result : imageData;
            
        } catch (Exception e) {
            log.error("亮度处理异常，返回原始图片", e);
            return imageData;
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
            return 50; // 默认亮度
        }
        
        try {
            int brightness = Integer.parseInt(brightnessValue.trim());
            
            // 限制在0-100范围内
            if (brightness < 0) {
                if (log.isDebugEnabled()) {
                    log.debug("亮度级别小于0，设置为0");
                }
                return 0;
            } else if (brightness > 100) {
                if (log.isDebugEnabled()) {
                    log.debug("亮度级别大于100，设置为100");
                }
                return 100;
            }
            
            return brightness;
            
        } catch (NumberFormatException e) {
            log.warn("无效的亮度级别: {}，使用默认值50", brightnessValue);
            return 50;
        }
    }
}