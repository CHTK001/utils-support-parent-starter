package com.chua.image.support.filter;


import com.chua.common.support.spi.annotations.SpiIgnore;
import com.chua.common.support.constant.Position;
import com.chua.common.support.image.ImagePoint;
import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.common.support.utils.IoUtils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 水印
 *
 * @author CH
 */
@SpiIgnore
public class TextImgWaterImageFilter extends AbstractImageFilter {

    /** DEFAULFON缩放比例 */
    private static final int DEFAULT_FONT_SIZE = 18;
    /** DEFAUL字体 */
    private static final Font DEFAULT_FONT = new Font("黑体", Font.PLAIN, DEFAULT_FONT_SIZE);
    /** DEFAUL水印位置点 */
    private static final ImagePoint DEFAULT_POINT = new ImagePoint(20, 20);
    /** image水印图片字节数组 */
    private final byte[] imageBytes;
    /** 文本内容 */
    private String text;
    /** 位置 */
    private Position position = Position.RIGHT_BOTTOM;
    /** 字体缩放比例 */
    private int fontSize = DEFAULT_FONT_SIZE;
    /** 颜色 */
    private Color color = Color.WHITE;
    /** 字体 */
    private Font font = DEFAULT_FONT;
    /** image水印位置点 */
    private ImagePoint imagePoint = DEFAULT_POINT;


    public TextImgWaterImageFilter(String text, InputStream stream, Position position) throws IOException {
        this.text = text;
        this.position = position;
        this.imageBytes = IoUtils.toByteArray(stream);
    }

    public TextImgWaterImageFilter(String text, byte[] imageBytes, Position position) {
        this.text = text;
        this.position = position;
        this.imageBytes = imageBytes;
    }

    public TextImgWaterImageFilter(String text, InputStream stream, ImagePoint imagePoint) throws IOException {
        this.text = text;
        this.imagePoint = imagePoint;
        this.imageBytes = IoUtils.toByteArray(stream);
    }

    public TextImgWaterImageFilter(String text, byte[] imageBytes, ImagePoint imagePoint) {
        this.text = text;
        this.imagePoint = imagePoint;
        this.imageBytes = imageBytes;
    }

    public TextImgWaterImageFilter(String text, byte[] imageBytes, Position position, int fontSize, Color color, Font font, ImagePoint imagePoint) {
        this.text = text;
        this.imageBytes = imageBytes;
        this.position = position;
        this.fontSize = fontSize;
        this.color = color;
        this.font = font;
        this.imagePoint = imagePoint;
    }

    /**
     * 获取字符串占用的宽度
     * <br>
     *
     * @param str      字符串
     * @param fontSize 文字大小
     * @return 字符串占用的宽度
     * @author Shendi <a href='tencent://AddContact/?fromId=45&fromSubId=1&subcmd=all&uin=1711680493'>QQ</a>
     */
    public static int getStrWidth(String str, int fontSize) {
        char[] chars = str.toCharArray();
        int fontSize2 = fontSize / 2;

        int width = 0;

        for (char c : chars) {
            int len = String.valueOf(c).getBytes().length;
            // 汉字为3,其余1
            // 可能还有一些特殊字符占用2等等,统统计为汉字
            if (len != 1) {
                width += fontSize;
            } else {
                width += fontSize2;
            }
        }

        return width;
    }

    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        
        return waterFilter(src, dst);
    
    }

    private BufferedImage waterFilter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth(), h = src.getHeight();

        BufferedImage bufferedImage = new BufferedImage(w, h,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bufferedImage.createGraphics();
        g.drawImage(src, 0, 0, w, h, null);
        // 图片中标识 start
        g.setFont(font);

        g.setColor(color);
        //图片位置定位计算并且绘制
        imageCountProcess(g, text, w, h, position);
        imageImageCountProcess(g, imageBytes, w, h, imagePoint);
        // draw end
        g.dispose();

        return bufferedImage;
    }

    /***
     * 图片位置定位计算图片位置
     * @param g 图像
     * @param imageBytes 图片
     * @param imagePoint 位置
     */
    private void imageImageCountProcess(Graphics2D g, byte[] imageBytes, int width, int height, ImagePoint imagePoint) {
        BufferedImage image;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes)) {
            try {
                image = ImageIO.read(bais);
            } catch (IOException e) {
                return;
            }
            try {
                image = BufferedImageUtils.zoomImage(image, imagePoint.x, imagePoint.y);
            } catch (Exception ignored) {
                // ignored
            }

        } catch (IOException e) {
            return;
        }
        switch (position) {
            case LEFT_TOP:
                g.drawImage(image, 0, 0, image.getWidth(), image.getHeight(), null);
                break;
            case RIGHT_TOP:
                g.drawImage(image, width - image.getWidth(), 0, image.getWidth(), image.getHeight(), null);
                break;
            case RIGHT_BOTTOM:
                g.drawImage(image, width - image.getWidth(), height - image.getHeight() - fontSize / 2 * 3, image.getWidth(), image.getHeight(), null);
                break;
            case LEFT_BOTTOM:
                g.drawImage(image, 0, height - image.getHeight() - fontSize / 2 * 3, image.getWidth(), image.getHeight(), null);
                break;
            default:
                break;
        }
    }

    /***
     * 图片位置定位计算
     * @param g 图像
     * @param text 文本
     * @param width 宽
     * @param height 高
     * @param direction 位置
     */
    private void imageCountProcess(Graphics2D g, String text, int width, int height, Position direction) {
        //LOWER_RIGHT
        switch (direction) {
            case LEFT_TOP:
                g.drawString(text, getStrWidth(text, fontSize), 0);
                break;
            case RIGHT_TOP:
                g.drawString(text, getStrWidth(text, fontSize), 0);
                break;
            case RIGHT_BOTTOM:
                g.drawString(text, width - getStrWidth(text, fontSize), height - fontSize / 2);
                break;
            case LEFT_BOTTOM:
                g.drawString(text, getStrWidth(text, fontSize), height - fontSize / 2);
                break;
            default:
                break;
        }
    }


}

