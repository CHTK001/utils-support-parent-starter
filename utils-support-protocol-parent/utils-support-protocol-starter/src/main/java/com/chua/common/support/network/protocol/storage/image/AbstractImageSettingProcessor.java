package com.chua.common.support.network.protocol.storage.image;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 图片设置处理器抽象基类
 * 
 * 提供通用的图片转换方法
 * 
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
public abstract class AbstractImageSettingProcessor implements ImageSettingProcessor {

    /**
     * 将字节数组转换为 BufferedImage
     *
     * @param imageData 图片数据
     * @return BufferedImage，如果转换失败返回 null
     */
    @Nullable
    protected BufferedImage bytesToImage(@Nonnull byte[] imageData) {
        try {
            return ImageIO.read(new ByteArrayInputStream(imageData));
        } catch (IOException e) {
            log.warn("无法读取图片数据", e);
            return null;
        }
    }

    /**
     * 将 BufferedImage 转换为字节数组
     *
     * @param image 图片
     * @return 字节数组，如果转换失败返回 null
     */
    @Nullable
    protected byte[] imageToBytes(@Nonnull BufferedImage image) {
        return imageToBytes(image, "png");
    }

    /**
     * 将 BufferedImage 转换为字节数组
     *
     * @param image  图片
     * @param format 图片格式
     * @return 字节数组，如果转换失败返回 null
     */
    @Nullable
    protected byte[] imageToBytes(@Nonnull BufferedImage image, @Nonnull String format) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, outputStream);
            return outputStream.toByteArray();
        } catch (IOException e) {
            log.warn("无法将图片转换为字节数组", e);
            return null;
        }
    }
}

