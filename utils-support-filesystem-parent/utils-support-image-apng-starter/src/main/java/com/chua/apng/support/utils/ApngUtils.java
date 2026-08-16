package com.chua.apng.support.utils;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * APNG 工具类
 * <p>
 * 提供 APNG 图像处理的通用工具方法，包括：
 * 1. APNG 文件读取和写入
 * 2. 动画帧提取和合并
 * 3. 元数据处理
 * 4. 格式检测和验证
 * 5. 图像质量优化
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ApngUtils {

    /**
     * 检查输入流是否为 APNG 格式
     *
     * @param inputStream 输入流
     * @return 是否为 APNG 格式
     */
    public static boolean isApngFormat(InputStream inputStream) {
        try {
            // 读取文件头部分字节
            byte[] header = new byte[64];
            inputStream.mark(64);
            int bytesRead = inputStream.read(header);
            inputStream.reset();
            
            if (bytesRead < 8) {
                return false;
            }
            
            // 检查 PNG 文件签名
            if (!isPngSignature(header)) {
                return false;
            }
            
            // 检查是否包含 acTL 块（APNG 控制块）
            return containsActlChunk(header);
            
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("检查 APNG 格式失败", e);
            }
            return false;
        }
    }

    /**
     * 检查是否为 PNG 文件签名
     */
    private static boolean isPngSignature(byte[] header) {
        if (header.length < 8) {
            return false;
        }
        
        // PNG 文件签名: 89 50 4E 47 0D 0A 1A 0A
        return header[0] == (byte) 0x89 &&
               header[1] == 0x50 &&
               header[2] == 0x4E &&
               header[3] == 0x47 &&
               header[4] == 0x0D &&
               header[5] == 0x0A &&
               header[6] == 0x1A &&
               header[7] == 0x0A;
    }

    /**
     * 检查是否包含 acTL 块
     */
    private static boolean containsActlChunk(byte[] data) {
        // 简单检查是否包含 "acTL" 字符串
        String dataStr = new String(data);
        return dataStr.contains("acTL");
    }

    /**
     * 获取 APNG 动画信息
     *
     * @param inputStream 输入流
     * @return 动画信息
     */
    public static ApngInfo getApngInfo(InputStream inputStream) {
        try (ImageInputStream imageInputStream = ImageIO.createImageInputStream(inputStream)) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("APNG");
            if (!readers.hasNext()) {
                readers = ImageIO.getImageReadersByFormatName("PNG");
                if (!readers.hasNext()) {
                    return null;
                }
            }
            
            ImageReader reader = readers.next();
            reader.setInput(imageInputStream);
            
            try {
                int numImages = reader.getNumImages(true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                
                return new ApngInfo(numImages, width, height);
                
            } finally {
                reader.dispose();
            }
            
        } catch (Exception e) {
            log.error("获取 APNG 信息失败", e);
            return null;
        }
    }

    /**
     * 提取 APNG 的所有帧
     *
     * @param inputStream 输入流
     * @return 帧列表
     */
    public static List<BufferedImage> extractFrames(InputStream inputStream) {
        List<BufferedImage> frames = new ArrayList<>();
        
        try (ImageInputStream imageInputStream = ImageIO.createImageInputStream(inputStream)) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("APNG");
            if (!readers.hasNext()) {
                readers = ImageIO.getImageReadersByFormatName("PNG");
                if (!readers.hasNext()) {
                    return frames;
                }
            }
            
            ImageReader reader = readers.next();
            reader.setInput(imageInputStream);
            
            try {
                int numImages = reader.getNumImages(true);
                
                for (int i = 0; i < numImages; i++) {
                    BufferedImage image = reader.read(i);
                    frames.add(image);
                }
                
            } finally {
                reader.dispose();
            }
            
        } catch (Exception e) {
            log.error("提取 APNG 帧失败", e);
        }
        
        return frames;
    }

    /**
     * 创建 APNG 动画
     *
     * @param frames 帧列表
     * @param delays 延迟时间列表（毫秒）
     * @return APNG 字节数组
     */
    public static byte[] createApng(List<BufferedImage> frames, List<Integer> delays) {
        if (frames.isEmpty()) {
            return new byte[0];
        }
        
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             ImageOutputStream imageOutputStream = ImageIO.createImageOutputStream(outputStream)) {
            
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("APNG");
            if (!writers.hasNext()) {
                writers = ImageIO.getImageWritersByFormatName("PNG");
                if (!writers.hasNext()) {
                    return new byte[0];
                }
            }
            
            ImageWriter writer = writers.next();
            writer.setOutput(imageOutputStream);
            
            try {
                if (frames.size() == 1) {
                    // 单帧
                    writer.write(frames.get(0));
                } else {
                    // 多帧动画
                    writer.prepareWriteSequence(null);
                    
                    for (int i = 0; i < frames.size(); i++) {
                        BufferedImage frame = frames.get(i);
                        int delay = delays != null && i < delays.size() ? delays.get(i) : 100;
                        
                        javax.imageio.ImageWriteParam writeParam = writer.getDefaultWriteParam();
                        IIOMetadata metadata = writer.getDefaultImageMetadata(
                                new javax.imageio.ImageTypeSpecifier(frame), writeParam);
                        
                        writer.writeToSequence(new javax.imageio.IIOImage(frame, null, metadata), writeParam);
                    }
                    
                    writer.endWriteSequence();
                }
                
            } finally {
                writer.dispose();
            }
            
            return outputStream.toByteArray();
            
        } catch (Exception e) {
            log.error("创建 APNG 失败", e);
            return new byte[0];
        }
    }

    /**
     * 优化图像质量
     *
     * @param image 原始图像
     * @return 优化后的图像
     */
    public static BufferedImage optimizeImage(BufferedImage image) {
        if (image == null) {
            return null;
        }
        
        // 如果已经是最佳格式，直接返回
        if (image.getType() == BufferedImage.TYPE_INT_ARGB) {
            return image;
        }
        
        // 转换为 ARGB 格式
        BufferedImage optimizedImage = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        
        optimizedImage.getGraphics().drawImage(image, 0, 0, null);
        return optimizedImage;
    }

    /**
     * 调整图像大小
     *
     * @param image 原始图像
     * @param width 目标宽度
     * @param height 目标高度
     * @return 调整后的图像
     */
    public static BufferedImage resizeImage(BufferedImage image, int width, int height) {
        if (image == null || width <= 0 || height <= 0) {
            return image;
        }
        
        BufferedImage resizedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        resizedImage.getGraphics().drawImage(
                image.getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
        
        return resizedImage;
    }

    /**
     * 验证 APNG 文件
     *
     * @param data APNG 数据
     * @return 是否有效
     */
    public static boolean validateApng(byte[] data) {
        if (data == null || data.length < 8) {
            return false;
        }
        
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(data)) {
            return isApngFormat(inputStream);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * APNG 信息类
     */
    public static class ApngInfo {
        private final int frameCount;
        private final int width;
        private final int height;

        public ApngInfo(int frameCount, int width, int height) {
            this.frameCount = frameCount;
            this.width = width;
            this.height = height;
        }

        public int getFrameCount() {
            return frameCount;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public boolean isAnimated() {
            return frameCount > 1;
        }

        @Override
        public String toString() {
        
            return String.format("ApngInfo{frames=%d, size=%dx%d, animated=%s}", 
                    frameCount, width, height, isAnimated());
        
    }
    }
}

