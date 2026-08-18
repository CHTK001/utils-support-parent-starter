package com.chua.image.support.filter;


import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiIgnore;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.constant.Position;
import com.chua.common.support.image.ImagePoint;
import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.common.support.utils.IoUtils;

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
 * 水印
 *
 * @author CH
 * @since 4.0.0.42
 */
@SpiDescribe("水印")
@Spi("water")
@SpiIgnore
public class ImageWaterImageFilter extends AbstractImageFilter {

    /** Direction */
    private final Position direction;
    /** Default_point */
    private static final ImagePoint DEFAULT_POINT = new ImagePoint(20, 20);
    /** Bytes */
    private final byte[] bytes;
    /** Point */
    private ImagePoint point = DEFAULT_POINT;

    public ImageWaterImageFilter(InputStream stream) throws IOException {
        this(IoUtils.toByteArray(stream), RIGHT_BOTTOM, DEFAULT_POINT);
    }

    public ImageWaterImageFilter(InputStream stream, ImagePoint point) throws IOException {
        this(IoUtils.toByteArray(stream), RIGHT_BOTTOM, point);
    }

    public ImageWaterImageFilter(File file) throws IOException {
        this(Files.newInputStream(file.toPath()), DEFAULT_POINT);
    }

    public ImageWaterImageFilter(File file, ImagePoint point) throws IOException {
        this(Files.newInputStream(file.toPath()), point);
    }

    public ImageWaterImageFilter(File file, Position position) throws IOException {
        this(IoUtils.toByteArray(Files.newInputStream(file.toPath())), position, DEFAULT_POINT);
    }

    public ImageWaterImageFilter(File file, Position position, ImagePoint point) throws IOException {
        this(IoUtils.toByteArray(Files.newInputStream(file.toPath())), position, point);
    }

    public ImageWaterImageFilter(byte[] bytes, Position position) {
        this(bytes, position, DEFAULT_POINT);
    }

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
        //LOWER_RIGHT
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
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        
        return waterFilter(src, dst);
    
    }


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

