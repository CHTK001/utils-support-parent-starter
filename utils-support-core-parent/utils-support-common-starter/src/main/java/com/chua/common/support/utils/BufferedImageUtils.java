package com.chua.common.support.utils;

import com.chua.common.support.lang.qr.CodePointStyle;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 图片工具类，提供图片读取、缩放、灰度化、旋转、字符画转换等能力。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class BufferedImageUtils {

    /**
     * 缓冲镜像工具。
     */
    private BufferedImageUtils() {}

    /**
      * 安全获取图像类型，将 JDK 内置 TIFF 插件返回的 类型_习俗(0) 替换为 类型_INT_RGB，
      * 避免 新 缓冲镜像(w, h, 0) 抛出 illegal参数异常。
     * @param src src
     * @return safe类型的结果
     */
    public static int safeType(BufferedImage src) {
        int t = src.getType();
        return t == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_RGB : t;
    }

    /**
     * 默认 ASCII 字符集（从左到右由暗到亮，共 10 级）
     */
    private static final String DEFAULT_ASCII_CHARS = "@%#*+=-:. ";

    /**
     * 转为缓冲镜像
     *
     * @param obj obj
     * @return 转为缓冲镜像的结果
     */
    public static BufferedImage toBufferedImage(Object obj) {
        if (obj instanceof BufferedImage) {
            return (BufferedImage) obj;
        }
        if (obj instanceof File) {
            return toBufferedImage((File) obj);
        }
        if (obj instanceof InputStream) {
            return toBufferedImage((InputStream) obj);
        }
        if (obj instanceof byte[]) {
            return toBufferedImage((byte[]) obj);
        }
        if (obj instanceof String) {
            return toBufferedImage((String) obj);
        }
        if (obj instanceof URL) {
            return toBufferedImage((URL) obj);
        }
        throw new IllegalArgumentException("Unsupported image type: " + (obj != null ? obj.getClass() : "null"));
    }

    /**
     * 转为缓冲镜像
     *
     * @param file 文件
     * @return 转为缓冲镜像的结果
     */
    public static BufferedImage toBufferedImage(File file) {
        try {
            return ImageIO.read(file);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read image from file", e);
        }
    }

    /**
     * 转为缓冲镜像
     *
     * @param inputStream 输入流
     * @return 转为缓冲镜像的结果
     */
    public static BufferedImage toBufferedImage(InputStream inputStream) {
        try {
            return ImageIO.read(inputStream);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read image from input stream", e);
        }
    }

    /**
     * 转为缓冲镜像
     *
     * @param bytes bytes
     * @return 转为缓冲镜像的结果
     */
    public static BufferedImage toBufferedImage(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read image from bytes", e);
        }
    }

    /**
     * 转为缓冲镜像
     *
     * @param path 路径
     * @return 转为缓冲镜像的结果
     */
    public static BufferedImage toBufferedImage(String path) {
        try {
            File file = new File(path);
            if (file.exists()) {
                return ImageIO.read(file);
            }
            return ImageIO.read(new URL(path));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read image from path: " + path, e);
        }
    }

    /**
     * 转为缓冲镜像
     *
     * @param url url
     * @return 转为缓冲镜像的结果
     */
    public static BufferedImage toBufferedImage(URL url) {
        try {
            return ImageIO.read(url);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read image from URL", e);
        }
    }

    /**
     * scale镜像
     *
     * @param image 镜像
     * @param width width
     * @param height height
     * @return scale镜像的结果
     */
    public static BufferedImage scaleImage(BufferedImage image, int width, int height) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, width, height, null);
        g.dispose();
        return scaled;
    }

    /**
     * scale镜像
     *
     * @param image 镜像
     * @param scale scale
     * @return scale镜像的结果
     */
    public static BufferedImage scaleImage(BufferedImage image, float scale) {
        int width = Math.round(image.getWidth() * scale);
        int height = Math.round(image.getHeight() * scale);
        return scaleImage(image, width, height);
    }

    /**
     * 将图片转换为ASCII字符画
     *
     * @param image 源图片
     * @return ASCII字符画字符串
     */
    public static String toAscii(BufferedImage image) {
        return toAscii(image, 100, DEFAULT_ASCII_CHARS);
    }

    /**
     * 将图片转换为指定宽度的ASCII字符画
     *
     * @param image 源图片
     * @param width 输出字符宽度
     * @return ASCII字符画字符串
     */
    public static String toAscii(BufferedImage image, int width) {
        return toAscii(image, width, DEFAULT_ASCII_CHARS);
    }

    /**
     * 将图片转换为指定宽度和字符集的ASCII字符画
     *
     * @param image      源图片
     * @param width      输出字符宽度
     * @param asciiChars ASCII字符集（从左到右由暗到亮）
     * @return ASCII字符画字符串
     */
    public static String toAscii(BufferedImage image, int width, String asciiChars) {
        if (image == null) {
            throw new IllegalArgumentException("Image must not be null");
        }
        if (width < 1) {
            throw new IllegalArgumentException("Width must be >= 1, got: " + width);
        }
        if (asciiChars == null || asciiChars.isEmpty()) {
            asciiChars = DEFAULT_ASCII_CHARS;
        }

        // 保持宽高比计算高度（因字符非正方形，高度约为宽度的一半）
        double aspectRatio = (double) image.getHeight() / image.getWidth();
        int height = (int) (width * aspectRatio * 0.55);
        if (height < 1) {
            height = 1;
        }

        // 缩放图片
        BufferedImage scaled = scaleImage(image, width, height);

        String chars = asciiChars;
        int charsetLength = chars.length();
        StringBuilder sb = new StringBuilder();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = scaled.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                // 计算灰度值（加权亮度公式）
                int gray = (r * 30 + g * 59 + b * 11) / 100;

                // 映射到字符
                int index = gray * charsetLength / 256;
                if (index >= charsetLength) {
                    index = charsetLength - 1;
                }
                sb.append(chars.charAt(index));
            }
            sb.append(System.lineSeparator());
        }

        return sb.toString();
    }

    /**
     * 16 级字符集（从暗到亮），用于字符画渲染。
     *
     * <p>每个字符代表的密度级别：</p>
     * <table border="1">
     *   <tr><th>索引</th><th>字符</th><th>灰度区间</th><th>描述</th></tr>
     *   <tr><td>0</td><td>@</td><td>0-15</td><td>最暗，高密度填充区域</td></tr>
     *   <tr><td>1</td><td>#</td><td>16-31</td><td>次暗，紧密填充区域</td></tr>
     *   <tr><td>2</td><td>8</td><td>32-47</td><td>数字密集填充</td></tr>
     *   <tr><td>3</td><td>&amp;</td><td>48-63</td><td>符号密集填充</td></tr>
     *   <tr><td>4</td><td>O</td><td>64-79</td><td>大写字母中密度</td></tr>
     *   <tr><td>5</td><td>o</td><td>80-95</td><td>小写字母中密度</td></tr>
     *   <tr><td>6</td><td>*</td><td>96-111</td><td>星号中低密度</td></tr>
     *   <tr><td>7</td><td>+</td><td>112-127</td><td>加号中低密度</td></tr>
     *   <tr><td>8</td><td>=</td><td>128-143</td><td>等号中等密度</td></tr>
     *   <tr><td>9</td><td>-</td><td>144-159</td><td>减号低密度</td></tr>
     *   <tr><td>10</td><td>:</td><td>160-175</td><td>冒号较低密度</td></tr>
     *   <tr><td>11</td><td>;</td><td>176-191</td><td>分号稀疏区域</td></tr>
     *   <tr><td>12</td><td>.</td><td>192-207</td><td>句点稀疏区域</td></tr>
     *   <tr><td>13</td><td>,</td><td>208-223</td><td>逗号更稀疏区域</td></tr>
     *   <tr><td>14</td><td>空格</td><td>224-239</td><td>接近空白</td></tr>
     *   <tr><td>15</td><td>空格</td><td>240-255</td><td>最亮，纯空白区域</td></tr>
     * </table>
     */
    private static final char[] CHARS_16 = {'@', '#', '8', '&', 'O', 'o', '*', '+', '=', '-', ':', ';', '.', ',', ' ', ' '};

    /**
     * 将图片转为字符画（16 级灰度映射），自动缩放并灰度化。
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>按指定宽度保持宽高比缩放（高度 x0.5 校正字符非正方形差异）</li>
     *   <li>将缩放后的图片转为灰度图</li>
     *   <li>遍历每个像素，按灰度值 0-255 映射到 16 级字符集 {@link #CHARS_16}</li>
     *   <li>每行末尾追加系统换行符</li>
     * </ol>
     *
     * @param image 源图片，不可为空
     * @param width 输出字符画宽度（字符列数），必须 &gt;= 1
     * @return 字符画字符串，每行以换行符结尾
     */
    public static String toCharacterArt(BufferedImage image, int width) {
        if (image == null) {
            throw new IllegalArgumentException("Image must not be null");
        }
        if (width < 1) {
            throw new IllegalArgumentException("Width must be >= 1, got: " + width);
        }
        double aspectRatio = (double) image.getHeight() / image.getWidth();
        int height = (int) (width * aspectRatio * 0.5);
        if (height < 1) {
            height = 1;
        }
        BufferedImage scaled = scaleImage(image, width, height);
        BufferedImage gray = grayImage(scaled);

        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = gray.getRGB(x, y);
                int grayValue = rgb & 0xFF;
                int index = grayValue * 16 / 256;
                if (index >= 16) {
                    index = 15;
                }
                sb.append(CHARS_16[index]);
            }
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }

    /**
     * 将图片转为字符画（16 级灰度映射），默认输出宽度为 100 字符。
     *
     * @param image 源图片，不可为空
     * @return 字符画字符串
     */
    public static String toCharacterArt(BufferedImage image) {
        return toCharacterArt(image, 100);
    }

    /**
      * 将 GIF 图片按帧拆分为 缓冲镜像 数组。
     *
     * @param file GIF 文件
     * @return 每帧一张 缓冲镜像，非 GIF 返回单帧数组
     */
    public static BufferedImage[] readGifFrames(File file) {
        if (file == null || !file.exists()) {
            return new BufferedImage[0];
        }
        try (ImageInputStream iis = ImageIO.createImageInputStream(file)) {
            return readGifFrames(iis);
        } catch (Exception e) {
            BufferedImage image = toBufferedImage(file);
            return image != null ? new BufferedImage[]{image} : new BufferedImage[0];
        }
    }

    /**
      * 将 GIF 图片按帧拆分为 缓冲镜像 数组。
     *
     * @param inputStream GIF 输入流
     * @return 每帧一张 缓冲镜像，非 GIF 返回单帧数组
     */
    public static BufferedImage[] readGifFrames(InputStream inputStream) {
        if (inputStream == null) {
            return new BufferedImage[0];
        }
        try (ImageInputStream iis = ImageIO.createImageInputStream(inputStream)) {
            return readGifFrames(iis);
        } catch (Exception e) {
            BufferedImage image = toBufferedImage(inputStream);
            return image != null ? new BufferedImage[]{image} : new BufferedImage[0];
        }
    }

    /**
      * 将 GIF 图片按帧拆分为 缓冲镜像 数组。
     *
     * @param bytes GIF 字节数组
     * @return 每帧一张 缓冲镜像，非 GIF 返回单帧数组
     */
    public static BufferedImage[] readGifFrames(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new BufferedImage[0];
        }
        return readGifFrames(new ByteArrayInputStream(bytes));
    }

    /**
      * 将 GIF 图片按帧拆分为 缓冲镜像 数组。
     *
     * @param path GIF 文件路径
     * @return 每帧一张 缓冲镜像，非 GIF 返回单帧数组
     */
    public static BufferedImage[] readGifFrames(String path) {
        if (path == null || path.isEmpty()) {
            return new BufferedImage[0];
        }
        File file = new File(path);
        if (file.exists()) {
            return readGifFrames(file);
        }
        try {
            return readGifFrames(new URL(path).openStream());
        } catch (Exception e) {
            return new BufferedImage[0];
        }
    }

    /**
      * 将 GIF 图片按帧拆分为 缓冲镜像 数组。
     *
     * @param url GIF URL
     * @return 每帧一张 缓冲镜像，非 GIF 返回单帧数组
     */
    public static BufferedImage[] readGifFrames(URL url) {
        if (url == null) {
            return new BufferedImage[0];
        }
        try {
            return readGifFrames(url.openStream());
        } catch (Exception e) {
            return new BufferedImage[0];
        }
    }

    /**
      * 通过 镜像输入流 读取 GIF 帧。
     *
     * @param iis GIF 图片输入流
     * @return 每帧一张 缓冲镜像
     * @throws IOException 读取失败时抛出
     */
    private static BufferedImage[] readGifFrames(ImageInputStream iis) throws IOException {
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try {
            reader.setInput(iis, false);
            int numFrames = reader.getNumImages(true);
            List<BufferedImage> frames = new ArrayList<>(numFrames);
            for (int i = 0; i < numFrames; i++) {
                frames.add(reader.read(i));
            }
            return frames.toArray(new BufferedImage[0]);
        } finally {
            reader.dispose();
        }
    }

    /**
     * 将多帧图片生成为 GIF 文件。
     *
     * @param frames  帧图片数组
     * @param delayMs 每帧延迟时间（毫秒）
     * @param output  输出 GIF 文件
     */
    public static void writeGif(BufferedImage[] frames, int delayMs, File output) {
        if (frames == null || frames.length == 0) {
            throw new IllegalArgumentException("Frames must not be empty");
        }
        if (output == null) {
            throw new IllegalArgumentException("Output file must not be null");
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ImageWriteParam writeParam = writer.getDefaultWriteParam();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);
            ImageTypeSpecifier typeSpecifier = ImageTypeSpecifier.createFromBufferedImageType(
                    BufferedImage.TYPE_INT_ARGB);
            for (int i = 0; i < frames.length; i++) {
                BufferedImage frame = frames[i];
                IIOMetadata meta = writer.getDefaultImageMetadata(typeSpecifier, writeParam);
                String metaFormat = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(metaFormat);

                IIOMetadataNode gce = getOrCreateNode(root, "GraphicControlExtension");
                gce.setAttribute("disposalMethod", "none");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", Integer.toString(delayMs / 10));
                gce.setAttribute("transparentColorIndex", "0");

                meta.setFromTree(metaFormat, root);
                writer.writeToSequence(new IIOImage(frame, null, meta), writeParam);
            }
            writer.endWriteSequence();
        } catch (Exception e) {
            throw new RuntimeException("GIF 写入失败", e);
        } finally {
            writer.dispose();
        }
    }

    /**
     * 将多个图片文件生成为 GIF 文件。
     *
     * @param images  图片文件数组
     * @param delayMs 每帧延迟时间（毫秒）
     * @param output  输出 GIF 文件
     */
    public static void writeGif(File[] images, int delayMs, File output) {
        if (images == null || images.length == 0) {
            throw new IllegalArgumentException("Images must not be empty");
        }
        BufferedImage[] frames = new BufferedImage[images.length];
        for (int i = 0; i < images.length; i++) {
            frames[i] = toBufferedImage(images[i]);
        }
        writeGif(frames, delayMs, output);
    }

    /**
     * 获取或创建 XML 子节点。
     *
     * @param root    父节点
     * @param nodeName 子节点名
     * @return 子节点
     */
    private static IIOMetadataNode getOrCreateNode(IIOMetadataNode root, String nodeName) {
        int len = root.getLength();
        for (int i = 0; i < len; i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(nodeName)) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(nodeName);
        root.appendChild(node);
        return node;
    }

    /**
     * 裁剪图片。
     *
     * @param options 裁剪选项，包含源图片、起始坐标和尺寸
     * @return 裁剪后的图片
     */
    public static BufferedImage cropImage(CropImageOptions options) {
        if (options == null || options.image() == null) {
            return null;
        }
        return options.image().getSubimage(options.x(), options.y(),
                Math.min(options.width(), options.image().getWidth() - options.x()),
                Math.min(options.height(), options.image().getHeight() - options.y()));
    }

    /**
     * 水平或垂直合并多张图片。
     *
     * @param images    图片数组
     * @param direction 合并方向，true 水平，false 垂直
     * @return 合并后的图片
     */
    public static BufferedImage mergeImage(BufferedImage[] images, boolean direction) {
        if (images == null || images.length == 0) {
            return null;
        }
        if (images.length == 1) {
            return images[0];
        }
        int totalWidth = 0;
        int totalHeight = 0;
        int maxWidth = 0;
        int maxHeight = 0;
        for (BufferedImage img : images) {
            totalWidth += img.getWidth();
            totalHeight += img.getHeight();
            maxWidth = Math.max(maxWidth, img.getWidth());
            maxHeight = Math.max(maxHeight, img.getHeight());
        }
        int resultWidth = direction ? totalWidth : maxWidth;
        int resultHeight = direction ? maxHeight : totalHeight;
        BufferedImage result = new BufferedImage(resultWidth, resultHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        int offset = 0;
        for (BufferedImage img : images) {
            if (direction) {
                g.drawImage(img, offset, 0, null);
                offset += img.getWidth();
            } else {
                g.drawImage(img, 0, offset, null);
                offset += img.getHeight();
            }
        }
        g.dispose();
        return result;
    }

    /**
     * 水平合并多张图片。
     *
     * @param images 图片数组
     * @return 合并后的图片
     */
    public static BufferedImage mergeHorizontal(BufferedImage... images) {
        return mergeImage(images, true);
    }

    /**
     * 垂直合并多张图片。
     *
     * @param images 图片数组
     * @return 合并后的图片
     */
    public static BufferedImage mergeVertical(BufferedImage... images) {
        return mergeImage(images, false);
    }

    /**
      * 将图片转为 基础64 字符串。
     *
     * @param image  图片
     * @param format 图片格式（png、jpg、gif 等）
     * @return data:image/xxx;base64, 开头的 基础64 字符串
     */
    public static String toBase64(BufferedImage image, String format) {
        if (image == null) {
            return null;
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, baos);
            return "data:image/" + format + ";base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("图片转 Base64 失败", e);
        }
    }

    /**
     * 翻转图片。
     *
     * @param image    源图片
     * @param flipHorizontal 是否水平翻转
     * @param flipVertical   是否垂直翻转
     * @return 翻转后的图片
     */
    public static BufferedImage flipImage(BufferedImage image, boolean flipHorizontal, boolean flipVertical) {
        if (image == null) {
            return null;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        if (flipHorizontal && flipVertical) {
            g.translate(w, h);
            g.scale(-1, -1);
        } else if (flipHorizontal) {
            g.translate(w, 0);
            g.scale(-1, 1);
        } else if (flipVertical) {
            g.translate(0, h);
            g.scale(1, -1);
        }
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return result;
    }

    /**
     * 水平翻转图片。
     *
     * @param image 源图片
     * @return 翻转后的图片
     */
    public static BufferedImage flipHorizontal(BufferedImage image) {
        return flipImage(image, true, false);
    }

    /**
     * 垂直翻转图片。
     *
     * @param image 源图片
     * @return 翻转后的图片
     */
    public static BufferedImage flipVertical(BufferedImage image) {
        return flipImage(image, false, true);
    }

    /**
     * 为图片添加文字水印。
     *
     * @param options 水印选项，包含源图片、文字、颜色、字体、坐标和透明度
     * @return 添加水印后的图片
     */
    public static BufferedImage addTextWatermark(TextWatermarkOptions options) {
        if (options == null || options.image() == null || options.text() == null || options.text().isEmpty()) {
            return options != null ? options.image() : null;
        }
        BufferedImage image = options.image();
        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                Math.max(0, Math.min(1, options.alpha()))));
        g.setColor(options.color() != null ? options.color() : Color.GRAY);
        g.setFont(options.font() != null ? options.font() : new Font("SansSerif", Font.PLAIN, 32));
        g.drawString(options.text(), options.x(), options.y());
        g.dispose();
        return result;
    }

    /**
     * 为图片添加文字水印（默认灰色半透明，底部居中）。
     *
     * @param image 源图片
     * @param text  水印文字
     * @return 添加水印后的图片
     */
    public static BufferedImage addTextWatermark(BufferedImage image, String text) {
        if (image == null || text == null || text.isEmpty()) {
            return image;
        }
        int fontSize = Math.max(16, image.getWidth() / 20);
        Font font = new Font("SansSerif", Font.PLAIN, fontSize);
        int x = (image.getWidth() - text.length() * fontSize / 2) / 2;
        int y = image.getHeight() - fontSize;
        TextWatermarkOptions options = new TextWatermarkOptions(image, text, Color.GRAY, font, x, y, 0.5f);
        return addTextWatermark(options);
    }

    /**
     * 创建缩略图，按最大宽高等比缩放，多余部分透明填充。
     *
     * @param image   源图片
     * @param maxWidth  最大宽度
     * @param maxHeight 最大高度
     * @return 缩略图
     */
    public static BufferedImage thumbnail(BufferedImage image, int maxWidth, int maxHeight) {
        if (image == null) {
            return null;
        }
        double scale = Math.min((double) maxWidth / image.getWidth(), (double) maxHeight / image.getHeight());
        if (scale >= 1) {
            return image;
        }
        int w = (int) (image.getWidth() * scale);
        int h = (int) (image.getHeight() * scale);
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, w, h, null);
        g.dispose();
        return result;
    }

    /**
     * 写入转为流
     *
     * @param image 镜像
     * @param format 格式化
     * @param outputStream 输出流
     */
    public static void writeToStream(BufferedImage image, String format, OutputStream outputStream) {
        try {
            ImageIO.write(image, format, outputStream);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write image to stream", e);
        }
    }

    /**
     * fillshape
     *
     * @param g g
     * @param style style
     * @param unitWidth unitwidth
     * @param arg2 参数2
     * @param arg3 参数3
     * @param i i
     * @param j j
     */
    public static void fillShape(Graphics2D g, CodePointStyle style, int unitWidth, int arg2, int arg3, int i, int j) {
        int x = unitWidth + i * unitWidth;
        int y = unitWidth + j * unitWidth;
        int size = unitWidth - 6;
        switch (style) {
            case CIRCLE:
                g.fill(new Ellipse2D.Double(x, y, size, size));
                break;
            case RECTANGLE:
            case MINI_RECT:
            default:
                g.fill(new Rectangle(x, y, size, size));
                break;
        }
    }

    /**
     * 改变color
     *
     * @param image 镜像
     * @param newColor 新color
     */
    public static void changeColor(BufferedImage image, Color newColor) {
        int width = image.getWidth();
        int height = image.getHeight();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int rgba = image.getRGB(x, y);
                int alpha = (rgba >> 24) & 0xFF;
                if (alpha > 0) {
                    int rgb = newColor.getRGB();
                    image.setRGB(x, y, (alpha << 24) | (rgb & 0x00FFFFFF));
                }
            }
        }
    }

    /**
     * 获取梯度color
     *
     * @param from 从
     * @param to 转为
     * @param steps steps
     * @return 获取梯度color的结果
     */
    public static Color[] getGradientColor(Color from, Color to, int steps) {
        Color[] gradient = new Color[steps];
        float[] fromComponents = from.getRGBColorComponents(null);
        float[] toComponents = to.getRGBColorComponents(null);
        for (int i = 0; i < steps; i++) {
            float ratio = (float) i / (steps - 1);
            float r = fromComponents[0] + ratio * (toComponents[0] - fromComponents[0]);
            float g = fromComponents[1] + ratio * (toComponents[1] - fromComponents[1]);
            float b = fromComponents[2] + ratio * (toComponents[2] - fromComponents[2]);
            gradient[i] = new Color(r, g, b);
        }
        return gradient;
    }

    /**
     * 处理梯度qr编码oblique
     *
     * @param image 镜像
     * @param gradient 梯度
     * @return 处理梯度qr编码oblique的结果
     */
    public static BufferedImage handleGradientQRCodeOblique(BufferedImage image, Color[] gradient) {
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.drawImage(image, 0, 0, null);
        for (int y = 0; y < height; y++) {
            int colorIndex = (y * (gradient.length - 1)) / height;
            g.setColor(gradient[Math.min(colorIndex, gradient.length - 1)]);
            g.drawLine(0, y, width, y);
        }
        g.dispose();
        return result;
    }

    /**
     * 获取缓冲镜像
     *
     * @param bytes bytes
     * @return 获取缓冲镜像的结果
     */
    public static BufferedImage getBufferedImage(byte[] bytes) throws IOException {
        if (bytes == null) {
            return null;
        }
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    /**
     * 获取缓冲镜像
     *
     * @param file 文件
     * @return 获取缓冲镜像的结果
     */
    public static BufferedImage getBufferedImage(File file) throws IOException {
        if (file == null) {
            return null;
        }
        return ImageIO.read(file);
    }

    /**
     * 获取缓冲镜像
     *
     * @param inputStream 输入流
     * @return 获取缓冲镜像的结果
     */
    public static BufferedImage getBufferedImage(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return null;
        }
        return ImageIO.read(inputStream);
    }

    /**
     * 转为缓冲镜像array
     *
     * @param bufferedImage 缓冲镜像
     * @return 转为缓冲镜像array的结果
     */
    public static byte[] toBufferedImageArray(BufferedImage bufferedImage) throws IOException {
        return toBufferedImageArray(bufferedImage, "png");
    }

    /**
     * 转为缓冲镜像array
     *
     * @param bufferedImage 缓冲镜像
     * @param format 格式化
     * @return 转为缓冲镜像array的结果
     */
    public static byte[] toBufferedImageArray(BufferedImage bufferedImage, String format) throws IOException {
        if (bufferedImage == null || format == null) {
            return new byte[0];
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(bufferedImage, format, baos);
            return baos.toByteArray();
        }
    }

    /**
     * zoom镜像
     *
     * @param src src
     * @param width width
     * @param height height
     * @return zoom镜像的结果
     */
    public static BufferedImage zoomImage(BufferedImage src, int width, int height) {
        return scaleImage(src, width, height);
    }

    /**
     * 获取sub镜像
     *
     * @param options 期权
     * @return 获取sub镜像的结果
     */
    public static BufferedImage getSubImage(SubImageOptions options) {
        if (options == null || options.bufferedImage() == null) {
            return null;
        }
        return options.bufferedImage().getSubimage(options.x(), options.y(), options.width(), options.height());
    }

    /**
     * Rotate
     *
     * @param src src
     * @param angle angle
     * @return rotate的结果
     */
    public static BufferedImage rotate(BufferedImage src, int angle) {
        if (src == null) {
            return null;
        }
        double radians = Math.toRadians(angle);
        double sin = Math.abs(Math.sin(radians));
        double cos = Math.abs(Math.cos(radians));
        int width = (int) (src.getWidth() * cos + src.getHeight() * sin);
        int height = (int) (src.getWidth() * sin + src.getHeight() * cos);
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.translate(width / 2, height / 2);
        g.rotate(radians);
        g.drawImage(src, -src.getWidth() / 2, -src.getHeight() / 2, null);
        g.dispose();
        return result;
    }

    /**
     * gray镜像
     *
     * @param src src
     * @return gray镜像的结果
     */
    public static BufferedImage grayImage(BufferedImage src) {
        if (src == null) {
            return null;
        }
        BufferedImage grayImage = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = grayImage.createGraphics();
        g2d.drawImage(src, 0, 0, null);
        g2d.dispose();
        return grayImage;
    }

    /**
     * brightness镜像
     *
     * @param src src
     * @param brightness brightness
     * @return brightness镜像的结果
     */
    public static BufferedImage brightnessImage(BufferedImage src, float brightness) {
        if (src == null || brightness == 1.0f) {
            return src;
        }
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage dest = new BufferedImage(width, height, safeType(src));
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int rgb = src.getRGB(x, y);
                int alpha = (rgb >> 24) & 0xFF;
                int red = Math.min(255, Math.max(0, (int) (((rgb >> 16) & 0xFF) * brightness)));
                int green = Math.min(255, Math.max(0, (int) (((rgb >> 8) & 0xFF) * brightness)));
                int blue = Math.min(255, Math.max(0, (int) ((rgb & 0xFF) * brightness)));
                dest.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
            }
        }
        return dest;
    }

    /**
     * blur镜像
     *
     * @param src src
     * @param radius radius
     * @return blur镜像的结果
     */
    public static BufferedImage blurImage(BufferedImage src, float radius) {
        if (src == null || radius <= 0) {
            return src;
        }
        int width = src.getWidth();
        int height = src.getHeight();
        BufferedImage dest = new BufferedImage(width, height, safeType(src));
        Graphics2D g = dest.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dest;
    }

    /**
     * Clamp
     *
     * @param value 值
     * @return clamp的结果
     */
    public static int clamp(float value) {
        return Math.min(255, Math.max(0, (int) value));
    }

    /**
     * 将整数值钳制到 [0, 255] 范围
     *
     * <p>常用于图像像素分量（R/G/B/A）的溢出保护。
     * 与 {@link #clamp(float)} 互补，本方法接受 int 参数，避免调用处额外的类型转换。</p>
     *
     * @param value 原始值，可能超出 [0, 255]
     * @return 钳制后的值，保证在 [0, 255] 范围内
     */
    public static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /**
     * 计算 ARGB 像素的亮度（灰度值）
     *
     * <p>使用 ITU-R BT.601 标准权重系数：
     * <pre>Y = 0.299 × R + 0.587 × G + 0.114 × B</pre>
     *
     * @param rgb ARGB 像素值（格式：0xaarrggbb）
     * @return 亮度值，范围 [0, 255]
     */
    public static int luminance(int rgb) {
        return (int) (0.299 * ((rgb >> 16) & 0xFF)
                + 0.587 * ((rgb >> 8) & 0xFF)
                + 0.114 * (rgb & 0xFF));
    }

    /**
     * 获取Rgb
     *
     * @param options 期权
     * @return 获取rgb的结果
     */
    public static int[] getRgb(RgbOptions options) {
        if (options == null || options.image() == null) {
            return null;
        }
        BufferedImage image = options.image();
        int type = image.getType();
        if (type == BufferedImage.TYPE_INT_ARGB || type == BufferedImage.TYPE_INT_RGB) {
            return (int[]) image.getRaster().getDataElements(options.x(), options.y(), options.width(), options.height(), options.pixels());
        }
        return image.getRGB(options.x(), options.y(), options.width(), options.height(), options.pixels(), 0, options.width());
    }

    /**
     * 写入转为文件
     *
     * @param image 镜像
     * @param format 格式化
     * @param file 文件
     */
    public static void writeToFile(BufferedImage image, String format, File file) {
        try {
            ImageIO.write(image, format, file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 保存转为流
     *
     * @param image 镜像
     * @param format 格式化
     * @param outputStream 输出流
     */
    public static void saveToStream(BufferedImage image, String format, OutputStream outputStream) {
        writeToStream(image, format, outputStream);
    }
}
