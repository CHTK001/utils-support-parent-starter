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
 * 图片灰度处理器
 * 
 * 处理图片灰度转换功能
 * 
 * @author CH
 * @since 2024/12/17
 */
@Spi("gray")
@Slf4j
@SpiDescribe("灰度转换")
public class GrayImageSettingProcessor extends AbstractImageSettingProcessor {

    @Override
    @Nullable
    public byte[] process(@Nonnull byte[] imageData, @Nonnull String settingValue) {
        if (StringUtils.isEmpty(settingValue)) {
            return imageData;
        }
        
        try {
            int grayLevel = parseGrayLevel(settingValue);
            
            // 如果灰度级别为0，不进行处理
            if (grayLevel <= 0) {
                return imageData;
            }
            
            BufferedImage bufferedImage = bytesToImage(imageData);
            if (bufferedImage == null) {
                return imageData;
            }
            
            BufferedImage grayImage = BufferedImageUtils.grayImage(bufferedImage);
            if (grayImage == null) {
                return imageData;
            }
            
            byte[] result = imageToBytes(grayImage);
            return result != null ? result : imageData;
            
        } catch (Exception e) {
            log.error("灰度处理异常，返回原始图片", e);
            return imageData;
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
                if (log.isDebugEnabled()) {
                    log.debug("灰度级别小于0，设置为0");
                }
                return 0;
            } else if (level > 100) {
                if (log.isDebugEnabled()) {
                    log.debug("灰度级别大于100，设置为100");
                }
                return 100;
            }
            
            return level;
            
        } catch (NumberFormatException e) {
            log.warn("无效的灰度级别: {}，使用默认值100", grayValue);
            return 100;
        }
    }
}