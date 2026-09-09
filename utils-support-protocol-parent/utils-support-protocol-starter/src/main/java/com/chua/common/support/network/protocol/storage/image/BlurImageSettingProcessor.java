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
 * 图片模糊处理器
 * 
 * 处理图片模糊效果功能
 * 
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi("blur")
@SpiDescribe("模糊效果")
public class BlurImageSettingProcessor extends AbstractImageSettingProcessor {

    @Override
    @Nullable
    public byte[] process(@Nonnull byte[] imageData, @Nonnull String settingValue) {
        if (StringUtils.isEmpty(settingValue)) {
            return imageData;
        }
        
        try {
            int blurLevel = parseBlurLevel(settingValue);
            
            // 如果模糊级别为0，不进行处理
            if (blurLevel <= 0) {
                return imageData;
            }
            
            BufferedImage bufferedImage = bytesToImage(imageData);
            if (bufferedImage == null) {
                return imageData;
            }
            
            // 将0-100的模糊级别转换为模糊半径
            float blurRadius = blurLevel / 10.0f;
            
            BufferedImage blurImage = BufferedImageUtils.blurImage(bufferedImage, blurRadius);
            if (blurImage == null) {
                return imageData;
            }
            
            byte[] result = imageToBytes(blurImage);
            return result != null ? result : imageData;
            
        } catch (Exception e) {
            log.error("模糊处理异常，返回原始图片", e);
            return imageData;
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
                if (log.isDebugEnabled()) {
                    log.debug("模糊级别小于0，设置为0");
                }
                return 0;
            } else if (level > 100) {
                if (log.isDebugEnabled()) {
                    log.debug("模糊级别大于100，设置为100");
                }
                return 100;
            }
            
            return level;
            
        } catch (NumberFormatException e) {
            log.warn("无效的模糊级别: {}，使用默认值10", blurValue);
            return 10;
        }
    }
}