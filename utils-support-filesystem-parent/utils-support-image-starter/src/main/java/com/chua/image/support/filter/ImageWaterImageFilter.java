package com.chua.image.support.filter;


import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiIgnore;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.constant.Position;
import com.chua.common.support.image.ImagePoint;
import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.common.support.utils.IoUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import static com.chua.common.support.constant.Position.RIGHT_BOTTOM;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;



/**
 * 图片水印滤镜
 *
 * 在图像上叠加一张图片作为水印（Logo、商标、图标等），
 * 用于版权标识、来源标注、素材防盗用。
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 从文件读取水印图片，默认右下角，偏移 (20, 20)
 * ImageWaterImageFilter filter = new ImageWaterImageFilter(new File("logo.png"));
 *
 * // 从 InputStream 读取，自定义位置偏移
 * ImageWaterImageFilter filter = new ImageWaterImageFilter(logoStream, new ImagePoint(80, 80));
 *
 * // 直接传字节
 * ImageWaterImageFilter filter = new ImageWaterImageFilter(logoBytes, Position.LEFT_TOP);
 *
 * BufferedImage watermarked = filter.converter(src);
 * }</pre>
 *
 * <h3>参数说明（构造器）</h3>
 * <ul>
 *   <li><b>bytes / stream / file</b>：水印图片来源（三选一）</li>
 *   <li><b>direction</b>：水印位置（{@link com.chua.common.support.constant.Position} 四角），默认 RIGHT_BOTTOM（final）</li>
 *   <li><b>bytes</b>：水印图片字节（final）</li>
 *   <li><b>point</b>：水印位置点偏移，默认 (20, 20)，可用 setter 修改</li>
 * </ul>
 *
 * <h3>链式设置</h3>
 * <pre>{@code
 * new ImageWaterImageFilter(logoBytes, Position.LEFT_TOP)
 *     .setPoint(new ImagePoint(10, 10));
 * }</pre>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>水印图片尺寸较大时会遮挡主图，建议使用 64×64 以内的小图</li>
 *   <li>水印采用 SRC_ATOP 混合模式绘制，会覆盖主图对应区域</li>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>本类标记为 {@code @SpiIgnore}，不注册到 SPI</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0
 */
@SpiDescribe("水印")
@Spi("water")
@SpiIgnore
@Accessors(chain = true)
@Getter
@Setter
public class ImageWaterImageFilter extends AbstractImageFilter {

    /** 水印方向（final，仅可通过构造器设置） */
    private final Position direction;
    /** DEFAUL水印位置点 */
    private static final ImagePoint DEFAULT_POINT = new ImagePoint(20, 20);
    /** 水印图片字节数组（final，仅可通过构造器设置） */
    private final byte[] bytes;
    /** 水印位置点 */
    private ImagePoint point = DEFAULT_POINT;

    /**
     * 创建 镜像水镜像过滤器 实例
     * @param stream 流
     */
    public ImageWaterImageFilter(InputStream stream) throws IOException {
        this(IoUtils.toByteArray(stream), RIGHT_BOTTOM, DEFAULT_POINT);
    }

    /**
     * 创建 镜像水镜像过滤器 实例
     * @param stream 流
     * @param point point
     */
    public ImageWaterImageFilter(InputStream stream, ImagePoint point) throws IOException {
        this(IoUtils.toByteArray(stream), RIGHT_BOTTOM, point);
    }

    /**
     * 创建 镜像水镜像过滤器 实例
     * @param file 文件
     */
    public ImageWaterImageFilter(File file) throws IOException {
        this(Files.newInputStream(file.toPath()), DEFAULT_POINT);
    }

    /**
     * 创建 镜像水镜像过滤器 实例
     * @param file 文件
     * @param point point
     */
    public ImageWaterImageFilter(File file, ImagePoint point) throws IOException {
        this(Files.newInputStream(file.toPath()), point);
    }

    /**
     * 创建 镜像水镜像过滤器 实例
     * @param file 文件
     * @param position 位置
     */
    public ImageWaterImageFilter(File file, Position position) throws IOException {
        this(IoUtils.toByteArray(Files.newInputStream(file.toPath())), position, DEFAULT_POINT);
    }

    /**
     * 创建 镜像水镜像过滤器 实例
     * @param file 文件
     * @param position 位置
     * @param point point
     */
    public ImageWaterImageFilter(File file, Position position, ImagePoint point) throws IOException {
        this(IoUtils.toByteArray(Files.newInputStream(file.toPath())), position, point);
    }

    /**
     * 创建 镜像水镜像过滤器 实例
     * @param bytes bytes
     * @param position 位置
     */
    public ImageWaterImageFilter(byte[] bytes, Position position) {
        this(bytes, position, DEFAULT_POINT);
    }

    /**
     * 创建 镜像水镜像过滤器 实例
     * @param bytes bytes
     * @param position 位置
     * @param point point
     */
    public ImageWaterImageFilter(byte[] bytes, Position position, ImagePoint point) {
        this.bytes = bytes;
        this.direction = position;
        this.point = point;
    }


    /***
     * 图片位置定位计算
     * @param g 图像
     * @param image 文本
     * @param w 宽
     * @param h 高
     * @param position 位置
     */
    private static void imageCountProcess(Graphics2D g, BufferedImage image, int w, int h, Position position) {
 // 降低_RIGHT
        switch (position) {
            case LEFT_TOP:
                g.drawImage(image, 0, 0, image.getWidth(), image.getHeight(), null);
                break;
            case RIGHT_TOP:
                g.drawImage(image, w - image.getWidth(), 0, image.getWidth(), image.getHeight(), null);
                break;
            case RIGHT_BOTTOM:
                g.drawImage(image, w - image.getWidth(), h - image.getHeight(), image.getWidth(), image.getHeight(), null);
                break;
            case LEFT_BOTTOM:
                g.drawImage(image, 0, h - image.getHeight(), image.getWidth(), image.getHeight(), null);
                break;
            default:
                break;
        }
    }

    @Override
    /** 过滤 */
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        
        return waterFilter(src, dst);
    
    }


    /**
    * 水过滤
    *
    * @param src src
    * @param dst dst
    * @return 水过滤器的结果
    */
    private BufferedImage waterFilter(BufferedImage src, BufferedImage dst) {
        int width = src.getWidth(), height = src.getHeight();
        BufferedImage buf2;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes)) {
            try {
                buf2 = ImageIO.read(bais);
            } catch (IOException e) {
                return src;
            }
            try {
                buf2 = BufferedImageUtils.zoomImage(buf2, point.x, point.y);
            } catch (Exception ignored) {
                // ignored
            }

        } catch (IOException e) {
            return src;
        }

        BufferedImage bufferedImage = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bufferedImage.createGraphics();
        g.drawImage(src, 0, 0, width, height, null);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, 1f));
        imageCountProcess(g, buf2, width, height, direction);
        g.dispose();


        return bufferedImage;
    }

}

