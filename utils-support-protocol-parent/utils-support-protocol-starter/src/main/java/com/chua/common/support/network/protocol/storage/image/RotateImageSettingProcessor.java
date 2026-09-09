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
 * 图片旋转处理器
 * 
 * 处理图片旋转功能
 * 
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi("rotate")
@SpiDescribe("图片旋转")
public class RotateImageSettingProcessor extends AbstractImageSettingProcessor {

    @Override
    @Nullable
    public byte[] process(@Nonnull byte[] imageData, @Nonnull String settingValue) {
        if (StringUtils.isEmpty(settingValue)) {
            if (log.isDebugEnabled()) {
                log.debug("旋转角度为空，返回原始图片");
            }
            return imageData;
        }

        try {
            int angle = Integer.parseInt(settingValue.trim());

            // 如果角度为0或360的倍数，不需要旋转
            if (angle == 0 || angle % 360 == 0) {
                if (log.isDebugEnabled()) {
                    log.debug("旋转角度为0或360的倍数，返回原始图片");
                }
                return imageData;
            }

            // 标准化角度到0-360范围
            angle = angle % 360;
            if (angle < 0) {
                angle += 360;
            }

            BufferedImage bufferedImage = bytesToImage(imageData);
            if (bufferedImage == null) {
                return imageData;
            }

            if (log.isDebugEnabled()) {
                log.debug("开始旋转图片，角度: {}", angle);
            }
            
            BufferedImage rotatedImage = BufferedImageUtils.rotate(bufferedImage, angle);

            if (rotatedImage != null) {
                log.debug("图片旋转完成，原始尺寸: {}x{}, 旋转后尺寸: {}x{}",
                    bufferedImage.getWidth(), bufferedImage.getHeight(),
                    rotatedImage.getWidth(), rotatedImage.getHeight());
                
                byte[] result = imageToBytes(rotatedImage);
                return result != null ? result : imageData;
            } else {
                log.warn("图片旋转失败，返回原始图片");
                return imageData;
            }

        } catch (NumberFormatException e) {
            log.warn("无效的旋转角度: {}，返回原始图片", settingValue);
            return imageData;
        } catch (Exception e) {
            log.error("图片旋转处理异常，返回原始图片", e);
            return imageData;
        }
    }

}