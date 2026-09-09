package com.chua.common.support.network.protocol.storage.image;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 图片形状处理器
 * 
 * 处理图片形状变换功能
 * 
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi("shape")
@SpiDescribe("图片形状")
public class ShapeImageSettingProcessor extends AbstractImageSettingProcessor {

    @Override
    @Nullable
    public byte[] process(@Nonnull byte[] imageData, @Nonnull String settingValue) {
        if (StringUtils.isEmpty(settingValue)) {
            return imageData;
        }
        
        try {
            BufferedImage bufferedImage = bytesToImage(imageData);
            if (bufferedImage == null) {
                return imageData;
            }
            
            // 解析形状参数，例如: "pixel,10" 或 "circle,5"
            String[] parts = settingValue.split(",");
            String shapeType = parts[0].trim();
            int intensity = parts.length > 1 ? parseIntValue(parts[1], 10) : 10;
            
            BufferedImage result = processShape(bufferedImage, shapeType, intensity);
            if (result == null) {
                return imageData;
            }
            
            byte[] processed = imageToBytes(result);
            return processed != null ? processed : imageData;
            
        } catch (Exception e) {
            log.error("形状处理异常，返回原始图片", e);
            return imageData;
        }
    }

    /**
     * 处理具体的形状变换
     * 
     * @param bufferedImage 原始图片
     * @param shapeType     形状类型
     * @param intensity     强度
     * @return 处理后的图片
     */
    private BufferedImage processShape(BufferedImage bufferedImage, String shapeType, int intensity) {
        switch (shapeType.toLowerCase()) {
            case "pixel":
                return processPixelShape(bufferedImage, intensity);
            case "circle":
                return processCircleShape(bufferedImage, intensity);
            case "round":
                return processRoundShape(bufferedImage, intensity);
            default:
                if (log.isDebugEnabled()) {
                    log.debug("不支持的形状类型: {}，返回原始图片", shapeType);
                }
                return bufferedImage;
        }
    }

    /**
     * 像素化处理
     * 
     * @param bufferedImage 原始图片
     * @param intensity     像素化强度
     * @return 像素化后的图片
     */
    private BufferedImage processPixelShape(BufferedImage bufferedImage, int intensity) {
        if (intensity <= 1) {
            return bufferedImage;
        }
        
        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();
        BufferedImage result = new BufferedImage(width, height, bufferedImage.getType());
        
        Graphics2D g2d = result.createGraphics();
        
        // 像素化效果：将图片分成小块，每块用平均颜色填充
        for (int x = 0; x < width; x += intensity) {
            for (int y = 0; y < height; y += intensity) {
                int blockWidth = Math.min(intensity, width - x);
                int blockHeight = Math.min(intensity, height - y);
                
                // 获取块的平均颜色
                Color avgColor = getAverageColor(bufferedImage, x, y, blockWidth, blockHeight);
                
                // 填充块
                g2d.setColor(avgColor);
                g2d.fillRect(x, y, blockWidth, blockHeight);
            }
        }
        
        g2d.dispose();
        return result;
    }

    /**
     * 圆形裁剪处理
     * 
     * @param bufferedImage 原始图片
     * @param intensity     圆形半径调整
     * @return 圆形裁剪后的图片
     */
    private BufferedImage processCircleShape(BufferedImage bufferedImage, int intensity) {
        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();
        int diameter = Math.min(width, height) - intensity * 2;
        
        if (diameter <= 0) {
            return bufferedImage;
        }
        
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = result.createGraphics();
        
        // 设置抗锯齿
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // 创建圆形裁剪区域
        int x = (width - diameter) / 2;
        int y = (height - diameter) / 2;
        g2d.setClip(new java.awt.geom.Ellipse2D.Float(x, y, diameter, diameter));
        
        // 绘制原图
        g2d.drawImage(bufferedImage, 0, 0, null);
        g2d.dispose();
        
        return result;
    }

    /**
     * 圆角处理
     * 
     * @param bufferedImage 原始图片
     * @param intensity     圆角半径
     * @return 圆角处理后的图片
     */
    private BufferedImage processRoundShape(BufferedImage bufferedImage, int intensity) {
        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();
        
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = result.createGraphics();
        
        // 设置抗锯齿
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // 创建圆角矩形裁剪区域
        g2d.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, width, height, intensity, intensity));
        
        // 绘制原图
        g2d.drawImage(bufferedImage, 0, 0, null);
        g2d.dispose();
        
        return result;
    }

    /**
     * 获取指定区域的平均颜色
     * 
     * @param image  图片
     * @param x      起始X坐标
     * @param y      起始Y坐标
     * @param width  区域宽度
     * @param height 区域高度
     * @return 平均颜色
     */
    private Color getAverageColor(BufferedImage image, int x, int y, int width, int height) {
        long totalRed = 0, totalGreen = 0, totalBlue = 0;
        int pixelCount = 0;
        
        for (int i = x; i < x + width; i++) {
            for (int j = y; j < y + height; j++) {
                int rgb = image.getRGB(i, j);
                totalRed += (rgb >> 16) & 0xFF;
                totalGreen += (rgb >> 8) & 0xFF;
                totalBlue += rgb & 0xFF;
                pixelCount++;
            }
        }
        
        if (pixelCount == 0) {
            return Color.BLACK;
        }
        
        int avgRed = (int) (totalRed / pixelCount);
        int avgGreen = (int) (totalGreen / pixelCount);
        int avgBlue = (int) (totalBlue / pixelCount);
        
        return new Color(avgRed, avgGreen, avgBlue);
    }

    /**
     * 解析整数值
     * 
     * @param value        字符串值
     * @param defaultValue 默认值
     * @return 解析后的整数
     */
    private int parseIntValue(String value, int defaultValue) {
        if (StringUtils.isEmpty(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            if (log.isDebugEnabled()) {
                log.debug("解析整数失败: {}，使用默认值: {}", value, defaultValue);
            }
            return defaultValue;
        }
    }
}