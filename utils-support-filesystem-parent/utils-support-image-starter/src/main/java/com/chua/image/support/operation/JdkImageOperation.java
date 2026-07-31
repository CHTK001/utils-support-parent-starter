package com.chua.image.support.operation;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.common.support.file.converter.ImageOperation;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * JDK实现的图片操作
 * <p>
 * 使用Java标准库实现图片操作功能
 * 采用响应式设计，在IO线程池中执行图片处理操作
 * </p>
 *
 * @author CH
 */
@Slf4j
@Spi("jdk")
public class JdkImageOperation implements ImageOperation {

    @Override
    public Mono<byte[]> resize(byte[] imageBytes, String format, Integer width, Integer height, Double scale) {
        
        return Mono.fromCallable(() -> {
            if (imageBytes == null || imageBytes.length == 0) {
                throw new IOException("图片数据不能为空");
            
    }
            if (format == null || format.trim().isEmpty()) {
                throw new IOException("图片格式不能为空");
            }

            // 将字节数组转换为BufferedImage
            var image = BufferedImageUtils.getBufferedImage(imageBytes);
            if (image == null) {
                throw new IOException("无法解析图片数据");
            }

            BufferedImage resizedImage;
            if (scale != null) {
                // 按比例缩放
                resizedImage = BufferedImageUtils.scaleImage(image, scale.floatValue());
            } else {
                // 按宽高缩放
                int targetWidth = width != null ? width : image.getWidth();
                int targetHeight = height != null ? height : image.getHeight();
                resizedImage = BufferedImageUtils.zoomImage(image, targetWidth, targetHeight);
            }

            // 将BufferedImage转换回字节数组
            return BufferedImageUtils.toBufferedImageArray(resizedImage, format);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<byte[]> crop(byte[] imageBytes, String format, int x, int y, int width, int height) {
        
        return Mono.fromCallable(() -> {
            if (imageBytes == null || imageBytes.length == 0) {
                throw new IOException("图片数据不能为空");
            
    }
            if (format == null || format.trim().isEmpty()) {
                throw new IOException("图片格式不能为空");
            }

            // 将字节数组转换为BufferedImage
            var image = BufferedImageUtils.getBufferedImage(imageBytes);
            if (image == null) {
                throw new IOException("无法解析图片数据");
            }

            // 检查裁剪区域是否超出图片范围
            if (x < 0 || y < 0 || x + width > image.getWidth() || y + height > image.getHeight()) {
                throw new IOException(String.format("裁剪区域超出图片范围: x=%d, y=%d, width=%d, height=%d, 图片尺寸=%dx%d",
                        x, y, width, height, image.getWidth(), image.getHeight()));
            }

            var croppedImage = BufferedImageUtils.getSubImage(image, x, y, width, height);

            // 将BufferedImage转换回字节数组
            return BufferedImageUtils.toBufferedImageArray(croppedImage, format);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<byte[]> rotate(byte[] imageBytes, String format, int angle) {
        
        return Mono.fromCallable(() -> {
            if (imageBytes == null || imageBytes.length == 0) {
                throw new IOException("图片数据不能为空");
            
    }
            if (format == null || format.trim().isEmpty()) {
                throw new IOException("图片格式不能为空");
            }

            // 将字节数组转换为BufferedImage
            var image = BufferedImageUtils.getBufferedImage(imageBytes);
            if (image == null) {
                throw new IOException("无法解析图片数据");
            }

            var rotatedImage = BufferedImageUtils.rotate(image, angle);

            // 将BufferedImage转换回字节数组
            return BufferedImageUtils.toBufferedImageArray(rotatedImage, format);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<byte[]> filter(byte[] imageBytes, String format, String filterType, ImageOperation.FilterParams params) {
        
        return Mono.fromCallable(() -> {
            if (imageBytes == null || imageBytes.length == 0) {
                throw new IOException("图片数据不能为空");
            
    }
            if (format == null || format.trim().isEmpty()) {
                throw new IOException("图片格式不能为空");
            }

            if (filterType == null || filterType.trim().isEmpty()) {
                throw new IOException("滤镜类型不能为空");
            }

            // 将字节数组转换为BufferedImage
            var image = BufferedImageUtils.getBufferedImage(imageBytes);
            if (image == null) {
                throw new IOException("无法解析图片数据");
            }

            var type = filterType.toLowerCase();
            BufferedImage filteredImage = switch (type) {
                case "gray" -> BufferedImageUtils.grayImage(image);
                case "blur" -> {
                    var radius = params != null && params.getRadius() != null ? params.getRadius() : 5.0f;
                    yield BufferedImageUtils.blurImage(image, radius);
                }
                case "brightness" -> {
                    var brightness = params != null && params.getBrightness() != null ? params.getBrightness() : 1.0f;
                    yield BufferedImageUtils.brightnessImage(image, brightness);
                }
                default -> throw new IOException("不支持的滤镜类型: " + filterType);
            };

            // 将BufferedImage转换回字节数组
            return BufferedImageUtils.toBufferedImageArray(filteredImage, format);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> compress(byte[] imageBytes, String format, float quality, String outputFormat, File output) {
        
        return Mono.fromRunnable(() -> {
            if (imageBytes == null || imageBytes.length == 0) {
                throw new RuntimeException("图片数据不能为空");
            
    }
            if (format == null || format.trim().isEmpty()) {
                throw new RuntimeException("输入图片格式不能为空");
            }

            if (quality < 0.0f || quality > 1.0f) {
                throw new RuntimeException("压缩质量必须在0.0到1.0之间");
            }

            if (outputFormat == null || outputFormat.trim().isEmpty()) {
                throw new RuntimeException("输出格式不能为空");
            }

            if (output == null) {
                throw new RuntimeException("输出文件不能为空");
            }

            // 确保输出目录存在
            var parent = output.getParentFile();
            if (parent != null && !parent.exists()) {
                if (!parent.mkdirs()) {
                    throw new RuntimeException("无法创建输出目录: " + parent.getAbsolutePath());
                }
            }

            try {
                // 将字节数组转换为BufferedImage
                var image = BufferedImageUtils.getBufferedImage(imageBytes);
                if (image == null) {
                    throw new IOException("无法解析图片数据");
                }

                try (var outputStream = new FileOutputStream(output)) {
                    var writers = ImageIO.getImageWritersByFormatName(outputFormat);
                    if (!writers.hasNext()) {
                        throw new IOException("不支持的图片格式: " + outputFormat);
                    }

                    var writer = writers.next();
                    var writeParam = writer.getDefaultWriteParam();

                    // 如果支持压缩，设置压缩质量
                    if (writeParam.canWriteCompressed()) {
                        writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                        writeParam.setCompressionQuality(quality);
                    }

                    try (var imageOutputStream = ImageIO.createImageOutputStream(outputStream)) {
                        writer.setOutput(imageOutputStream);
                        writer.write(null, new javax.imageio.IIOImage(image, null, null), writeParam);
                    }

                    writer.dispose();
                }
            } catch (Exception e) {
                log.error("[图片操作][压缩]图片压缩失败", e);
                throw new RuntimeException("图片压缩失败", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<byte[]> watermark(byte[] imageBytes, String format, ImageOperation.WatermarkParams params) {
        
        return Mono.fromCallable(() -> {
            if (imageBytes == null || imageBytes.length == 0) {
                throw new IOException("图片数据不能为空");
            
    }
            if (format == null || format.trim().isEmpty()) {
                throw new IOException("图片格式不能为空");
            }

            if (params == null) {
                throw new IOException("水印参数不能为空");
            }

            // 将字节数组转换为BufferedImage
            var image = BufferedImageUtils.getBufferedImage(imageBytes);
            if (image == null) {
                throw new IOException("无法解析图片数据");
            }

            // 解析水印图片
            BufferedImage watermarkImage = null;
            if (params.getWatermarkImageBytes() != null && params.getWatermarkImageBytes().length > 0) {
                var watermarkFormat = params.getWatermarkFormat() != null ? params.getWatermarkFormat() : format;
                watermarkImage = BufferedImageUtils.getBufferedImage(params.getWatermarkImageBytes());
            }

            // 创建副本，避免修改原图
            var result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = result.createGraphics();
            graphics.drawImage(image, 0, 0, null);

            // 设置抗锯齿
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // 设置透明度
            var alpha = params.getOpacity() != null ? params.getOpacity() : 0.5f;
            var composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha);
            graphics.setComposite(composite);

            // 计算水印位置
            var position = calculateWatermarkPosition(image, params, watermarkImage);
            var x = position.x;
            var y = position.y;

            // 应用旋转
            if (params.getRotation() != null && params.getRotation() != 0) {
                var transform = new AffineTransform();
                transform.translate(x, y);
                transform.rotate(Math.toRadians(params.getRotation()));
                graphics.setTransform(transform);
                x = 0;
                y = 0;
            }

            // 添加文本水印
            if (params.getText() != null && !params.getText().trim().isEmpty()) {
                addTextWatermark(graphics, params, x, y);
            }

            // 添加图片水印
            if (watermarkImage != null) {
                addImageWatermark(graphics, watermarkImage, x, y);
            }

            graphics.dispose();

            // 将BufferedImage转换回字节数组
            return BufferedImageUtils.toBufferedImageArray(result, format);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 计算水印位置
     *
     * @param image          原始图片
     * @param params         水印参数
     * @param watermarkImage 水印图片（可为null）
     * @return 水印位置坐标
     */
    private Point calculateWatermarkPosition(BufferedImage image, ImageOperation.WatermarkParams params, BufferedImage watermarkImage) {
        var width = image.getWidth();
        var height = image.getHeight();
        var position = params.getPosition() != null ? params.getPosition().toUpperCase() : "BOTTOM_RIGHT";
        var offsetX = params.getOffsetX() != null ? params.getOffsetX() : 0;
        var offsetY = params.getOffsetY() != null ? params.getOffsetY() : 0;

        int x;
        int y;

        // 计算水印尺寸（定位）
        int watermarkWidth = 0;
        int watermarkHeight = 0;

        if (watermarkImage != null) {
            watermarkWidth = watermarkImage.getWidth();
            watermarkHeight = watermarkImage.getHeight();
        } else if (params.getText() != null && !params.getText().trim().isEmpty()) {
            var font = new Font(Font.SANS_SERIF, Font.PLAIN, params.getFontSize() != null ? params.getFontSize() : 24);
            var context = new FontRenderContext(new AffineTransform(), true, true);
            var bounds = font.getStringBounds(params.getText(), context);
            watermarkWidth = (int) bounds.getWidth();
            watermarkHeight = (int) bounds.getHeight();
        }

        // 根据位置计算坐标
        switch (position) {
            case "TOP_LEFT":
                x = 10 + offsetX;
                y = 10 + offsetY;
                break;
            case "TOP_CENTER":
                x = (width - watermarkWidth) / 2 + offsetX;
                y = 10 + offsetY;
                break;
            case "TOP_RIGHT":
                x = width - watermarkWidth - 10 + offsetX;
                y = 10 + offsetY;
                break;
            case "CENTER_LEFT":
                x = 10 + offsetX;
                y = (height - watermarkHeight) / 2 + offsetY;
                break;
            case "CENTER":
                x = (width - watermarkWidth) / 2 + offsetX;
                y = (height - watermarkHeight) / 2 + offsetY;
                break;
            case "CENTER_RIGHT":
                x = width - watermarkWidth - 10 + offsetX;
                y = (height - watermarkHeight) / 2 + offsetY;
                break;
            case "BOTTOM_LEFT":
                x = 10 + offsetX;
                y = height - watermarkHeight - 10 + offsetY;
                break;
            case "BOTTOM_CENTER":
                x = (width - watermarkWidth) / 2 + offsetX;
                y = height - watermarkHeight - 10 + offsetY;
                break;
            case "BOTTOM_RIGHT":
            default:
                x = width - watermarkWidth - 10 + offsetX;
                y = height - watermarkHeight - 10 + offsetY;
                break;
        }

        return new Point(x, y);
    }

    /**
     * 添加文本水印
     *
     * @param graphics 图形对象
     * @param params   水印参数
     * @param x        X坐标
     * @param y        Y坐标
     */
    private void addTextWatermark(Graphics2D graphics, ImageOperation.WatermarkParams params, int x, int y) {
        var fontSize = params.getFontSize() != null ? params.getFontSize() : 24;
        var font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
        graphics.setFont(font);

        var fontColor = params.getFontColor() != null ? params.getFontColor() : Color.WHITE;
        graphics.setColor(fontColor);

        // 绘制文本阴影（可选，增强可读性）
        graphics.setColor(new Color(0, 0, 0, (int) (params.getOpacity() * 255)));
        graphics.drawString(params.getText(), x + 1, y + 1);

        // 绘制文本
        graphics.setColor(fontColor);
        graphics.drawString(params.getText(), x, y);
    }

    /**
     * 添加图片水印
     *
     * @param graphics       图形对象
     * @param watermarkImage 水印图片
     * @param x             X坐标
     * @param y             Y坐标
     */
    private void addImageWatermark(Graphics2D graphics, BufferedImage watermarkImage, int x, int y) {
        graphics.drawImage(watermarkImage, x, y, null);
    }
}



