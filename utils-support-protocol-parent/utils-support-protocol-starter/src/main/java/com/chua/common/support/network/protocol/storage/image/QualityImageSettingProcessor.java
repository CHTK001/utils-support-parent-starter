package com.chua.common.support.network.protocol.storage.image;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 图片质量处理器
 * 
 * 处理图片质量调整功能
 * 
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi("quality")
@SpiDescribe("图片质量")
public class QualityImageSettingProcessor extends AbstractImageSettingProcessor {

    @Override
    @Nullable
    public byte[] process(@Nonnull byte[] imageData, @Nonnull String settingValue) {
        if (StringUtils.isEmpty(settingValue)) {
            return imageData;
        }

        try {
            int quality = parseQuality(settingValue);

            // 如果质量为75（默认值），不进行处理
            if (quality == 75) {
                return imageData;
            }

            BufferedImage bufferedImage = bytesToImage(imageData);
            if (bufferedImage == null) {
                return imageData;
            }

            // 通过重新编码来调整质量
            byte[] result = adjustImageQuality(bufferedImage, quality);
            return result != null ? result : imageData;

        } catch (Exception e) {
            log.error("图片质量处理异常，返回原始图片", e);
            return imageData;
        }
    }

    /**
     * 调整图片质量
     *
     * @param bufferedImage 原始图片
     * @param quality       质量级别(0-100)
     * @return 调整质量后的图片字节数组
     * @throws Exception 处理过程中可能抛出的异常
     */
    private byte[] adjustImageQuality(BufferedImage bufferedImage, int quality) throws Exception {
        // 将质量值转换为0.0-1.0的范围
        float qualityFloat = quality / 100.0f;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            // 获取JPEG写入器
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) {
                log.warn("没有找到JPEG写入器，返回原始图片");
                return null;
            }

            ImageWriter writer = writers.next();
            ImageWriteParam param = writer.getDefaultWriteParam();

            // 设置压缩模式和质量
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(qualityFloat);
            }

            // 创建输出流
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);

                // 转换为RGB格式（JPEG不支持透明度）
                BufferedImage rgbImage = new BufferedImage(
                    bufferedImage.getWidth(),
                    bufferedImage.getHeight(),
                    BufferedImage.TYPE_INT_RGB
                );
                rgbImage.getGraphics().drawImage(bufferedImage, 0, 0, null);

                // 写入图片
                writer.write(null, new IIOImage(rgbImage, null, null), param);
                writer.dispose();
            }

            return baos.toByteArray();
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
            return 75; // 默认质量
        }

        try {
            int quality = Integer.parseInt(qualityValue.trim());

            // 限制在0-100范围内
            if (quality < 0) {
                if (log.isDebugEnabled()) {
                    log.debug("质量级别小于0，设置为0");
                }
                return 0;
            } else if (quality > 100) {
                if (log.isDebugEnabled()) {
                    log.debug("质量级别大于100，设置为100");
                }
                return 100;
            }

            return quality;

        } catch (NumberFormatException e) {
            log.warn("无效的质量级别: {}，使用默认值75", qualityValue);
            return 75;
        }
    }
}