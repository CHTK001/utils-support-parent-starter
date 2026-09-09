package com.chua.common.support.network.protocol.storage.watermark;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.base.collection.Options;
import com.chua.common.support.media.MediaType;
import com.chua.common.support.network.protocol.storage.FileStorageFactory;
import com.chua.common.support.network.protocol.storage.FileStorageProcessorContext;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.core.utils.BufferedImageUtils;
import com.chua.common.support.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static com.chua.common.support.core.constant.NameConstant.DEFAULT;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 默认图片水印处理器
 * 
 * 处理图片水印添加功能
 * 
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi(DEFAULT)
public class DefaultImageWatermarkProcessor implements ImageWatermarkProcessor {

    @Override
    public void process(FileStorageProcessorContext context) throws Exception {
        MediaType mediaType = context.getMediaType();
        Options options = context.getOptions();
        FileStorageFactory fileStorageFactory = context.getFileStorageFactory();
        
        // 检查是否支持处理
        if (!supports(mediaType, options, fileStorageFactory)) {
            if (log.isDebugEnabled()) {
                log.debug("不支持图片水印处理");
            }
            return;
        }
        
        ServletResponse response = context.getResponse();
        BufferedImage bufferedImage = BufferedImageUtils.toBufferedImage(response.getBody(), options.getString("source"));
        
        if (bufferedImage == null) {
            log.warn("无法解析图片数据");
            return;
        }

        // 处理图片水印
        BufferedImage processedImage = processImageWatermark(bufferedImage, fileStorageFactory);
        if (processedImage == null) {
            if (log.isDebugEnabled()) {
                log.debug("图片水印处理未发生变化");
            }
            return;
        }

        // 转换回字节数组并设置到响应
        byte[] processedBytes = imageToBytes(processedImage, mediaType.subtype());
        response.setBody(processedBytes);
        if (log.isDebugEnabled()) {
            log.debug("图片水印处理完成，设置到响应");
        }
    }

    /**
     * 是否支持处理当前请求
     * 
     * @param mediaType 媒体类型
     * @param options 选项参数
     * @param fileStorageFactory 文件存储工厂
     * @return 如果支持返回true
     */
    public boolean supports(MediaType mediaType, Options options, FileStorageFactory fileStorageFactory) {
        // 只处理图片类型
        if (mediaType == null || !mediaType.isImage()) {
            if (log.isDebugEnabled()) {
                log.debug("不是图片类型，不支持图片水印处理");
            }
            return false;
        }
        
        // 检查是否开启水印功能
        if (!fileStorageFactory.openWatermark()) {
            if (log.isDebugEnabled()) {
                log.debug("未开启水印功能，不处理图片水印");
            }
            return false;
        }
        
        return true;
    }

    /**
     * 处理图片水印
     * 
     * @param bufferedImage 原始图片
     * @param fileStorageFactory 文件存储工厂
     * @return 处理后的图片
     * @throws Exception 处理过程中可能抛出的异常
     */
    public BufferedImage processImageWatermark(BufferedImage bufferedImage, FileStorageFactory fileStorageFactory) throws Exception {
        try {
            // 获取水印设置
            FileStorageFactory.FileStorageSetting setting = fileStorageFactory.getFileStorageSetting();
            if (setting == null) {
                if (log.isDebugEnabled()) {
                    log.debug("文件存储设置为空，不进行水印处理");
                }
                return null;
            }
            
            // 添加文字水印
            BufferedImage result = addTextWatermark(bufferedImage, setting);
            
            if (log.isDebugEnabled()) {
                log.debug("图片水印处理完成");
            }
            return result;
            
        } catch (Exception e) {
            log.error("处理图片水印失败", e);
            return null;
        }
    }

    /**
     * 添加文字水印
     * 
     * @param bufferedImage 原始图片
     * @param setting 文件存储设置
     * @return 添加水印后的图片
     */
    private BufferedImage addTextWatermark(BufferedImage bufferedImage, FileStorageFactory.FileStorageSetting setting) {
        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();
        
        // 创建新的图片对象
        BufferedImage watermarkedImage = new BufferedImage(width, height, bufferedImage.getType());
        Graphics2D g2d = watermarkedImage.createGraphics();
        
        // 绘制原始图片
        g2d.drawImage(bufferedImage, 0, 0, null);
        
        // 设置水印文字
        String watermarkText = getWatermarkText(setting);
        if (StringUtils.isEmpty(watermarkText)) {
            g2d.dispose();
            return bufferedImage; // 没有水印文字，返回原图
        }
        
        // 设置字体和颜色
        Font font = new Font("Arial", Font.BOLD, Math.max(width / 20, 12));
        g2d.setFont(font);
        g2d.setColor(new Color(255, 255, 255, 128)); // 半透明白色
        
        // 设置抗锯齿
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // 获取文字尺寸
        FontMetrics fontMetrics = g2d.getFontMetrics();
        int textWidth = fontMetrics.stringWidth(watermarkText);
        int textHeight = fontMetrics.getHeight();
        
        // 计算水印位置（右下角）
        int x = width - textWidth - 20;
        int y = height - 20;
        
        // 绘制水印文字
        g2d.drawString(watermarkText, x, y);
        
        g2d.dispose();
        return watermarkedImage;
    }

    /**
     * 获取水印文字
     * 
     * @param setting 文件存储设置
     * @return 水印文字
     */
    private String getWatermarkText(FileStorageFactory.FileStorageSetting setting) {
        // 这里可以根据设置获取水印文字，暂时返回默认文字
        return "Watermark";
    }

    /**
     * 将BufferedImage转换为字节数组
     */
    private byte[] imageToBytes(BufferedImage image, String format) throws IOException {
        if (StringUtils.isEmpty(format)) {
            format = "png";
        }
        
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, outputStream);
            return outputStream.toByteArray();
        }
    }
}