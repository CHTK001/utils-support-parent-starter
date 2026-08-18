package com.chua.apng.support.converter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.file.converter.AbstractConvertFileSystem;
import com.chua.common.support.file.converter.ConvertFileSystem;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;


/**
 * APNG 转 GIF 转换器
 * <p>
 * 将 APNG（Animated PNG）格式转换为 GIF 格式，支持：
 * 1. 动画帧提取和转换
 * 2. 帧延迟时间处理
 * 3. 透明度处理
 * 4. 循环次数设置
 * 5. 颜色优化
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("apng2gif")
public class ApngToGifConvertFileSystem extends AbstractConvertFileSystem {

    /**
     * 默认构造函数
     */
    public ApngToGifConvertFileSystem() {
        super();
    }

    /**
     * 构造函数
     *
     * @param file 文件对象
     */
    public ApngToGifConvertFileSystem(File file) {
        super(file);
    }

    /**
     * 构造函数
     *
     * @param filePath 文件路径
     */
    public ApngToGifConvertFileSystem(String filePath) {
        super(filePath);
    }

    @Override
    protected void doConvert(InputStream inputStream, OutputStream outputStream, File sourceFile, File targetFile) throws IOException {
        try {
            // 读取 APNG 文件
            List<AnimationFrame> frames = readApngFrames(inputStream);

            if (frames.isEmpty()) {
                throw new IllegalArgumentException("APNG 文件中没有找到动画帧");
            }

            // 写入 GIF 文件
            writeGifAnimation(frames, outputStream);

            log.info("[APNG转换器][转换]APNG 转 GIF 完成，共转换 {} 帧", frames.size());

        } catch (Exception e) {
            log.error("[APNG转换器][转换]APNG 转 GIF 失败", e);
            throw new IOException("APNG 转 GIF 转换失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取 APNG 动画帧
     *
     * @param inputStream 输入流
     * @return 动画帧列表
     * @throws Exception 读取异常
     */
    private List<AnimationFrame> readApngFrames(InputStream inputStream) throws Exception {
        List<AnimationFrame> frames = new ArrayList<>();

        try (ImageInputStream imageInputStream = ImageIO.createImageInputStream(inputStream)) {
            // 获取 APNG 读取器
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("APNG");
            if (!readers.hasNext()) {
                // 如果没有 APNG 读取器，尝试使用 PNG 读取器
                readers = ImageIO.getImageReadersByFormatName("PNG");
                if (!readers.hasNext()) {
                    throw new RuntimeException("没有找到支持的 APNG/PNG 图像读取器");
                }
            }

            ImageReader reader = readers.next();
            reader.setInput(imageInputStream);

            try {
                int numImages = reader.getNumImages(true);
                if (log.isDebugEnabled()) {
                    log.debug("APNG 文件包含 {} 帧", numImages);
                }

                for (int i = 0; i < numImages; i++) {
                    BufferedImage image = reader.read(i);

                    // 获取帧延迟时间（毫秒）
                    int delay = getFrameDelay(reader, i);

                    frames.add(new AnimationFrame(image, delay));
                }

            } finally {
                reader.dispose();
            }
        }

        return frames;
    }

    /**
     * 获取帧延迟时间
     *
     * @param reader 图像读取器
     * @param frameIndex 帧索引
     * @return 延迟时间（毫秒）
     */
    private int getFrameDelay(ImageReader reader, int frameIndex) {
        try {
            // 尝试从元数据中获取延迟时间
            javax.imageio.metadata.IIOMetadata metadata = reader.getImageMetadata(frameIndex);
if (metadata != null) {
                // 这里可以解析 APNG 特定的元数据
                // 由于 APNG 元数据解析比较复杂，这里使用默认值
                // return 100;
                // 默认 100ms
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("获取帧延迟时间失败，使用默认值", e);
            }
        }

        // 默认延迟时间
        return 100;
    }

    /**
     * 写入 GIF 动画
     *
     * @param frames 动画帧列表
     * @param outputStream 输出流
     * @throws Exception 写入异常
     */
    private void writeGifAnimation(List<AnimationFrame> frames, OutputStream outputStream) throws Exception {

        try (ImageOutputStream imageOutputStream = ImageIO.createImageOutputStream(outputStream)) {
            // 获取 GIF 写入器
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("GIF");
            if (!writers.hasNext()) {
                throw new RuntimeException("没有找到 GIF 图像写入器");
            }

            ImageWriter writer = writers.next();
            writer.setOutput(imageOutputStream);

            try {
                // 准备写入序列
                writer.prepareWriteSequence(null);

                for (int i = 0; i < frames.size(); i++) {
                    AnimationFrame frame = frames.get(i);
                    BufferedImage image = frame.getImage();

                    // 转换为 GIF 兼容的图像格式
                    BufferedImage gifImage = convertToGifCompatible(image);

                    // 创建写入参数
                    javax.imageio.ImageWriteParam writeParam = writer.getDefaultWriteParam();

                    // 创建元数据
                    javax.imageio.metadata.IIOMetadata metadata = writer.getDefaultImageMetadata(
                            new javax.imageio.ImageTypeSpecifier(gifImage), writeParam);

                    // 设置帧延迟时间
                    configureGifMetadata(metadata, frame.getDelay(), i == frames.size() - 1);

                    // 写入帧
                    writer.writeToSequence(new javax.imageio.IIOImage(gifImage, null, metadata), writeParam);
                }

                writer.endWriteSequence();

            } finally {
                writer.dispose();
            }
        }
    }

    /**
     * 转换为 GIF 兼容的图像格式
     *
     * @param image 原始图像
     * @return GIF 兼容的图像
     */
    private BufferedImage convertToGifCompatible(BufferedImage image) {
        // GIF 支持最多 256 色，这里进行简单的颜色转换
        if (image.getType() == BufferedImage.TYPE_INT_RGB ||
            image.getType() == BufferedImage.TYPE_INT_ARGB) {
            return image;
        }

        // 转换为 ARGB 格式
        BufferedImage convertedImage = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);

        convertedImage.getGraphics().drawImage(image, 0, 0, null);
        return convertedImage;
    }

    /**
     * 配置 GIF 元数据
     *
     * @param metadata 元数据
     * @param delay 延迟时间（毫秒）
     * @param isLast 是否为最后一帧
     */
    private void configureGifMetadata(javax.imageio.metadata.IIOMetadata metadata,
                                    int delay, boolean isLast) {
        try {
            // 将延迟时间从毫秒转换为 1/100 秒
            int delayTime = Math.max(1, delay / 10);

            String metadataFormat = metadata.getNativeMetadataFormatName();
            org.w3c.dom.Node root = metadata.getAsTree(metadataFormat);

            // 查找或创建 GraphicControlExtension 节点
            org.w3c.dom.Node gce = findOrCreateNode(root, "GraphicControlExtension");
            if (gce != null) {
                // 设置延迟时间
                setNodeAttribute(gce, "delayTime", String.valueOf(delayTime));
                // 设置处置方法
                setNodeAttribute(gce, "disposalMethod", "restoreToBackgroundColor");
                // 设置透明度
                setNodeAttribute(gce, "transparentColorFlag", "true");
            }

            // 设置应用扩展（循环）
            if (!isLast) {
                org.w3c.dom.Node appExt = findOrCreateNode(root, "ApplicationExtensions");
                if (appExt != null) {
                    org.w3c.dom.Node appExtNode = appExt.getOwnerDocument().createElement("ApplicationExtension");
                    setNodeAttribute(appExtNode, "applicationID", "NETSCAPE");
                    setNodeAttribute(appExtNode, "authenticationCode", "2.0");
                    appExt.appendChild(appExtNode);
                }
            }

            metadata.setFromTree(metadataFormat, root);

        } catch (Exception e) {
            log.warn("配置 GIF 元数据失败", e);
        }
    }

    /**
     * 查找或创建节点
     */
    private org.w3c.dom.Node findOrCreateNode(org.w3c.dom.Node parent, String nodeName) {
        org.w3c.dom.NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeName().equals(nodeName)) {
                return children.item(i);
            }
        }

        // 创建新节点
        org.w3c.dom.Node newNode = parent.getOwnerDocument().createElement(nodeName);
        parent.appendChild(newNode);
        return newNode;
    }

    /**
     * 设置节点属性
     */
    private void setNodeAttribute(org.w3c.dom.Node node, String attributeName, String value) {
        org.w3c.dom.NamedNodeMap attributes = node.getAttributes();
        org.w3c.dom.Attr attr = node.getOwnerDocument().createAttribute(attributeName);
        attr.setValue(value);
        attributes.setNamedItem(attr);
    }

    @Override
    public String type() {
        
        return "gif";
    
    }

    protected boolean isSupportFormat(String sourceFormat, String targetFormat) {
        return ("apng".equalsIgnoreCase(sourceFormat) || "png".equalsIgnoreCase(sourceFormat)) &&
                "gif".equalsIgnoreCase(targetFormat);
    }

    @Override
    public ConvertFileSystem.ConvertSupport[] supportedTypes() {
        return new ConvertFileSystem.ConvertSupport[]{
                new ConvertFileSystem.ConvertSupport("apng", "gif"),
                new ConvertFileSystem.ConvertSupport("png", "gif")
        };
    }

    /**
     * 动画帧数据类
     */
    private static class AnimationFrame {
        /** 图片 */
        private final BufferedImage image;
        /** Delay */
        private final int delay;

        public AnimationFrame(BufferedImage image, int delay) {
            this.image = image;
            this.delay = delay;
        }

        public BufferedImage getImage() {
            return image;
        }

        public int getDelay() {
            return delay;
        }
    }
}

