package com.chua.image.support.network.protocol.storage.image;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.StringUtils;
import com.chua.image.support.bridge.RustImageBridge;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Rust 图片缩略图处理器
 * 
 * 使用 Rust 实现的高性能图片缩略图生成
 * 支持格式: "400x300", "400x", "x300", "400"
 * 
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi(value = "thumbnail", order = 0)
@SpiDescribe("缩略图（Rust实现）")
public class RustThumbnailImageSettingProcessor implements com.chua.common.support.network.protocol.storage.image.ImageSettingProcessor {

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
            // 先读取图片获取原始尺寸
            BufferedImage bufferedImage = bytesToImage(imageData);
            if (bufferedImage == null) {
                return null;
            }

            // 解析缩略图参数
            ThumbnailSize size = parseThumbnailSize(settingValue);
            if (size == null) {
                return null;
            }

            // 如果宽高都未指定或都为0，不进行处理
            if (size.width <= 0 && size.height <= 0) {
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
                return imageData;
            }
            
            // 调用 Rust 方法生成缩略图
            byte[] result = RustImageBridge.nativeThumbnail(imageData, targetWidth, targetHeight);
            return result != null ? result : imageData;
            
        } catch (Exception e) {
            log.error("[图片处理][Rust缩略图] 缩略图处理异常", e);
            return null;
        }
    }

    /**
     * 将字节数组转换为 BufferedImage
     *
     * @param imageData 图片数据
     * @return BufferedImage，如果转换失败返回 null
     */
    @Nullable
    private BufferedImage bytesToImage(@Nonnull byte[] imageData) {
        try {
            return ImageIO.read(new ByteArrayInputStream(imageData));
        } catch (IOException e) {
            log.warn("[图片处理][Rust缩略图] 无法读取图片数据", e);
            return null;
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
                        return null;
                    }
                    // 单一值时，宽高都设置为相同值，进行等比例缩放
                    return new ThumbnailSize(size, size);
                } catch (NumberFormatException e) {
                    return null;
                }
            }

            String[] parts = normalized.split("x", 2);
            if (parts.length != 2) {
                return null;
            }

            int width = -1;
            int height = -1;

            // 解析宽度
            if (StringUtils.isNotEmpty(parts[0])) {
                width = Integer.parseInt(parts[0]);
                if (width < 0) {
                    return null;
                }
            }

            // 解析高度
            if (StringUtils.isNotEmpty(parts[1])) {
                height = Integer.parseInt(parts[1]);
                if (height < 0) {
                    return null;
                }
            }

            return new ThumbnailSize(width, height);

        } catch (NumberFormatException e) {
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
    }
}

