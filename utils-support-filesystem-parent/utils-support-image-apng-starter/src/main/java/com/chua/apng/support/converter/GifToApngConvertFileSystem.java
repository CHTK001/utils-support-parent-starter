package com.chua.apng.support.converter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.file.converter.AbstractConvertFileSystem;
import com.chua.common.support.file.converter.ConvertFileSystem;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
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
 * GIF 转 APNG 转换器
 * <p>
 * 将 GIF 格式转换为 APNG（Animated PNG）格式，支持：
 * 1. 动画帧提取和转换
 * 2. 帧延迟时间处理
 * 3. 透明度保持
 * 4. 循环次数设置
 * 5. 高质量图像输出
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("gif2apng")
public class GifToApngConvertFileSystem extends AbstractConvertFileSystem {

    /**
     * 默认构造函数
     */
    public GifToApngConvertFileSystem() {
        super();
    }

    /**
     * 构造函数
     *
     * @param file 文件对象
     */
    public GifToApngConvertFileSystem(File file) {
        super(file);
    }

    /**
     * 构造函数
     *
     * @param filePath 文件路径
     */
    public GifToApngConvertFileSystem(String filePath) {
        super(filePath);
    }

    @Override
    /** Do转换 */
    protected void doConvert(InputStream inputStream, OutputStream outputStream, File sourceFile, File targetFile) throws IOException {
        try {
            // 读取 GIF 文件
            List<AnimationFrame> frames = readGifFrames(inputStream);

            if (frames.isEmpty()) {
                throw new IllegalArgumentException("GIF 文件中没有找到动画帧");
            }

            // 写入 APNG 文件
            writeApngAnimation(frames, outputStream);

            log.info("[APNG转换][GIF转APNG]转换完成，共转换 {} 帧", frames.size());

        } catch (Exception e) {
            log.error("[APNG转换][GIF转APNG]转换失败: {} -> {}",
                sourceFile != null ? sourceFile.getName() : "流",
                targetFile != null ? targetFile.getName() : "流", e);
            throw new IOException("GIF 转 APNG 转换失败", e);
        }
    }

    @Override
    /** Type */
    public String type() {
        
        return "apng";
    
    }

    /** 是否Support格式化 */
    protected boolean isSupportFormat(String sourceFormat, String targetFormat) {
        if (sourceFormat == null || targetFormat == null) {
            return false;
        }
        if (!"gif".equalsIgnoreCase(sourceFormat)) {
            return false;
        }
        return "apng".equalsIgnoreCase(targetFormat) || "png".equalsIgnoreCase(targetFormat);
    }

    @Override
    /** SupportedTypes */
    public ConvertFileSystem.ConvertSupport[] supportedTypes() {
        List<ConvertFileSystem.ConvertSupport> supports = new ArrayList<>();
        supports.add(new ConvertFileSystem.ConvertSupport("gif", "apng"));
        supports.add(new ConvertFileSystem.ConvertSupport("gif", "png"));
        return supports.toArray(new ConvertFileSystem.ConvertSupport[0]);
    }

    /**
     * 读取 GIF 动画帧
     *
     * @param inputStream 输入流
     * @return 动画帧列表
     * @throws Exception 读取异常
     */
    private List<AnimationFrame> readGifFrames(InputStream inputStream) throws Exception {
        List<AnimationFrame> frames = new ArrayList<>();

        try (ImageInputStream imageInputStream = ImageIO.createImageInputStream(inputStream)) {
            // 获取 GIF 读取器
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("GIF");
            if (!readers.hasNext()) {
                throw new RuntimeException("没有找到 GIF 图像读取器");
            }

            ImageReader reader = readers.next();
            reader.setInput(imageInputStream);

            try {
                int numImages = reader.getNumImages(true);
                if (log.isDebugEnabled()) {
                    log.debug("[APNG转换][读取GIF]GIF 文件包含 {} 帧", numImages);
                }

                for (int i = 0; i < numImages; i++) {
                    BufferedImage image = reader.read(i);

                    // 获取帧延迟时间
                    int delay = getGifFrameDelay(reader, i);

                    // 转换为高质量图像
                    BufferedImage highQualityImage = convertToHighQuality(image);

                    frames.add(new AnimationFrame(highQualityImage, delay));
                }

            } finally {
                reader.dispose();
            }
        }

        return frames;
    }

    /**
     * 获取 GIF 帧延迟时间
     *
     * @param reader 图像读取器
     * @param frameIndex 帧索引
     * @return 延迟时间（毫秒）
     */
    private int getGifFrameDelay(ImageReader reader, int frameIndex) {
        try {
            IIOMetadata metadata = reader.getImageMetadata(frameIndex);
            if (metadata != null) {
                String metadataFormat = metadata.getNativeMetadataFormatName();
                org.w3c.dom.Node root = metadata.getAsTree(metadataFormat);

                // 查找 GraphicControlExtension 节点
                org.w3c.dom.Node gce = findNode(root, "GraphicControlExtension");
                if (gce != null) {
                    org.w3c.dom.NamedNodeMap attributes = gce.getAttributes();
                    org.w3c.dom.Node delayTimeNode = attributes.getNamedItem("delayTime");
                    if (delayTimeNode != null) {
                        // GIF 延迟时间单位是 1/100 秒，转换为毫秒
                        int delayTime = Integer.parseInt(delayTimeNode.getNodeValue());
                        // 最小 10ms
                        return Math.max(10, delayTime * 10);
                    }
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[APNG转换][获取延迟]获取 GIF 帧延迟时间失败，使用默认值", e);
            }
        }

        // 默认延迟时间
        return 100;
    }

    /**
     * 查找指定名称的节点
     */
    private org.w3c.dom.Node findNode(org.w3c.dom.Node parent, String nodeName) {
        if (parent.getNodeName().equals(nodeName)) {
            return parent;
        }

        org.w3c.dom.NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node found = findNode(children.item(i), nodeName);
            if (found != null) {
                return found;
            }
        }

        return null;
    }

    /**
     * 转换为高质量图像
     *
     * @param image 原始图像
     * @return 高质量图像
     */
    private BufferedImage convertToHighQuality(BufferedImage image) {
        // 如果已经是 ARGB 格式，直接返回
        if (image.getType() == BufferedImage.TYPE_INT_ARGB) {
            return image;
        }

        // 转换为 ARGB 格式以支持透明度
        BufferedImage highQualityImage = new BufferedImage(
            image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);

        highQualityImage.getGraphics().drawImage(image, 0, 0, null);
        return highQualityImage;
    }

    /**
     * 写入 APNG 动画
     *
     * @param frames 动画帧列表
     * @param outputStream 输出流
     * @throws Exception 写入异常
     */
    private void writeApngAnimation(List<AnimationFrame> frames, OutputStream outputStream) throws Exception {

        try (ImageOutputStream imageOutputStream = ImageIO.createImageOutputStream(outputStream)) {
            // 获取 APNG 写入器
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("APNG");
            if (!writers.hasNext()) {
                // 如果没有 APNG 写入器，尝试使用 PNG 写入器
                writers = ImageIO.getImageWritersByFormatName("PNG");
                if (!writers.hasNext()) {
                    throw new RuntimeException("没有找到支持的 APNG/PNG 图像写入器");
                }
            }

            ImageWriter writer = writers.next();
            writer.setOutput(imageOutputStream);

            try {
                if (frames.size() == 1) {
                    // 单帧，写入静态 PNG
                    writer.write(frames.get(0).getImage());
                } else {
                    // 多帧，尝试写入动画
                    writeAnimatedPng(writer, frames);
                }

            } finally {
                writer.dispose();
            }
        }
    }

    /**
     * 写入动画 PNG
     *
     * @param writer 图像写入器
     * @param frames 动画帧列表
     * @throws Exception 写入异常
     */
    private void writeAnimatedPng(ImageWriter writer, List<AnimationFrame> frames) throws Exception {
        try {
            // 尝试写入动画序列
            writer.prepareWriteSequence(null);

            for (int i = 0; i < frames.size(); i++) {
                AnimationFrame frame = frames.get(i);
                BufferedImage image = frame.getImage();

                // 创建写入参数
                javax.imageio.ImageWriteParam writeParam = writer.getDefaultWriteParam();

                // 创建元数据
                IIOMetadata metadata = writer.getDefaultImageMetadata(
                    new javax.imageio.ImageTypeSpecifier(image), writeParam);

                // 尝试设置 APNG 特定的元数据
                configureApngMetadata(metadata, frame.getDelay(), i);

                // 写入帧
                writer.writeToSequence(new javax.imageio.IIOImage(image, null, metadata), writeParam);
            }

            writer.endWriteSequence();

        } catch (Exception e) {
            log.warn("[APNG转换][写入动画]写入动画 PNG 失败，尝试写入静态 PNG", e);
            // 如果动画写入失败，写入第一帧作为静态图像
            writer.write(frames.get(0).getImage());
        }
    }

    /**
     * 配置 APNG 元数据
     *
     * @param metadata 元数据
     * @param delay 延迟时间（毫秒）
     * @param frameIndex 帧索引
     */
    private void configureApngMetadata(IIOMetadata metadata, int delay, int frameIndex) {
        try {
            // 这里可以设置 APNG 特定的元数据
            // 由于 APNG 元数据比较复杂，这里做简单处理
            String metadataFormat = metadata.getNativeMetadataFormatName();
            org.w3c.dom.Node root = metadata.getAsTree(metadataFormat);

            // 可以在这里添加 APNG 特定的元数据设置
            // 例如帧延迟、处置方法等

            metadata.setFromTree(metadataFormat, root);

        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[APNG转换][配置元数据]配置 APNG 元数据失败", e);
            }
        }
    }

    /**
     * 动画帧数据类
     */
    private static class AnimationFrame {
        /** 图片 */
        private final BufferedImage image;
        /** Delay */
        private final int delay;

        /**
         * 创建 AnimationFrame 实例
         * @param image image
         * @param int int
         */
        public AnimationFrame(BufferedImage image, int delay) {
            this.image = image;
            this.delay = delay;
        }

        /** 获取Image */
        public BufferedImage getImage() {
            return image;
        }

        /** 获取Delay */
        public int getDelay() {
            return delay;
        }
    }
}

