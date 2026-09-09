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
 * 图片缩略图处理器
 * 
 * 处理图片缩略图生成功能
 * 支持格式: "400x300", "400x", "x300"
 * 
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi("thumbnail")
@SpiDescribe("缩略图")
public class ThumbnailImageSettingProcessor extends AbstractImageSettingProcessor {

    @Override
    @Nullable
    public byte[] process(@Nonnull byte[] imageData, @Nonnull String settingValue) {
        if (StringUtils.isEmpty(settingValue)) {
            if (log.isDebugEnabled()) {
                log.debug("缩略图参数为空，返回原始图片");
            }
            return imageData;
        }
        
        try {
            BufferedImage bufferedImage = bytesToImage(imageData);
            if (bufferedImage == null) {
                return imageData;
            }

            // 解析缩略图参数
            ThumbnailSize size = parseThumbnailSize(settingValue);
            if (size == null) {
                log.warn("无效的缩略图参数: {}，返回原始图片", settingValue);
                return imageData;
            }

            // 如果宽高都未指定或都为0，不进行处理
            if ((size.width <= 0 && size.height <= 0)) {
                if (log.isDebugEnabled()) {
                    log.debug("缩略图尺寸无效，返回原始图片");
                }
                return imageData;
            }
            
            int originalWidth = bufferedImage.getWidth();
            int originalHeight = bufferedImage.getHeight();
            
            // 计算目标尺寸
            int targetWidth = size.width;
            int targetHeight = size.height;

            // 如果宽高相等（单一值情况），进行等比例缩放
            if (targetWidth == targetHeight && targetWidth > 0) {
                // 等比例缩放，保持原图比例，以较小的边为准
                double ratio = Math.min((double) targetWidth / originalWidth, (double) targetHeight / originalHeight);
                targetWidth = (int) (originalWidth * ratio);
                targetHeight = (int) (originalHeight * ratio);
            }
            // 如果只指定了宽度，按比例计算高度
            else if (targetWidth > 0 && targetHeight <= 0) {
                targetHeight = (int) ((double) originalHeight * targetWidth / originalWidth);
            }
            // 如果只指定了高度，按比例计算宽度
            else if (targetWidth <= 0) {
                targetWidth = (int) ((double) originalWidth * targetHeight / originalHeight);
            }
            
            // 如果目标尺寸与原始尺寸相同，不进行处理
            if (targetWidth == originalWidth && targetHeight == originalHeight) {
                if (log.isDebugEnabled()) {
                    log.debug("目标尺寸与原始尺寸相同，返回原始图片");
                }
                return imageData;
            }
            
            log.debug("开始生成缩略图，原始尺寸: {}x{}, 目标尺寸: {}x{}",
                originalWidth, originalHeight, targetWidth, targetHeight);

            BufferedImage thumbnail = BufferedImageUtils.zoomImage(bufferedImage, targetWidth, targetHeight);

            log.debug("缩略图生成完成，实际尺寸: {}x{}",
                thumbnail.getWidth(), thumbnail.getHeight());
            
            byte[] result = imageToBytes(thumbnail);
            return result != null ? result : imageData;
            
        } catch (Exception e) {
            log.error("缩略图处理异常", e);
            return imageData;
        }
    }

    /**
     * 解析缩略图尺寸参数
     *
     * @param sizeValue 尺寸参数，如 "400x300", "400x", "x300", "400"
     * @return 解析后的尺寸对象
     */
    private ThumbnailSize parseThumbnailSize(String sizeValue) {
        if (StringUtils.isEmpty(sizeValue)) {
            return null;
        }

        try {
            // 移除空格并转换为小写
            String normalized = sizeValue.trim().toLowerCase();

            // 如果不包含x分隔符，则认为是单一值，进行等比例处理
            if (!normalized.contains("x")) {
                try {
                    int size = Integer.parseInt(normalized);
                    if (size <= 0) {
                        log.warn("缩略图尺寸不能为负数或零: {}", normalized);
                        return null;
                    }
                    // 单一值时，宽高都设置为相同值，进行等比例缩放
                    return new ThumbnailSize(size, size);
                } catch (NumberFormatException e) {
                    log.warn("无效的缩略图尺寸参数: {}", sizeValue);
                    return null;
                }
            }

            String[] parts = normalized.split("x", 2);
            if (parts.length != 2) {
                log.warn("缩略图参数格式错误: {}", sizeValue);
                return null;
            }

            int width = -1;
            int height = -1;

            // 解析宽度
            if (StringUtils.isNotEmpty(parts[0])) {
                width = Integer.parseInt(parts[0]);
                if (width < 0) {
                    log.warn("缩略图宽度不能为负数: {}", parts[0]);
                    return null;
                }
            }

            // 解析高度
            if (StringUtils.isNotEmpty(parts[1])) {
                height = Integer.parseInt(parts[1]);
                if (height < 0) {
                    log.warn("缩略图高度不能为负数: {}", parts[1]);
                    return null;
                }
            }

            return new ThumbnailSize(width, height);

        } catch (NumberFormatException e) {
            log.warn("缩略图参数包含非数字字符: {}", sizeValue);
            return null;
        }
    }

    /**
     * 缩略图尺寸内部类
     */
    private static class ThumbnailSize {
        final int width;
        final int height;
        
        ThumbnailSize(int width, int height) {
            this.width = width;
            this.height = height;
        }
        
        @Override
        public String toString() {
            return width + "x" + height;
        }
    }
}
