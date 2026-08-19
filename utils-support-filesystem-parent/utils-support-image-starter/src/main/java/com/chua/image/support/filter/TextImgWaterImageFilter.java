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
 * 姘村嵃
 *
 * @author CH
 * @since 4.0.0.42
 */
@SpiIgnore
public class TextImgWaterImageFilter extends AbstractImageFilter {

    /** Default_font_size */
    private static final int DEFAULT_FONT_SIZE = 18;
    /** Default_font */
    private static final Font DEFAULT_FONT = new Font("榛戜綋", Font.PLAIN, DEFAULT_FONT_SIZE);
    /** Default_point */
    private static final ImagePoint DEFAULT_POINT = new ImagePoint(20, 20);
    /** 鍥剧墖bytes */
    private final byte[] imageBytes;
    /** 鏂囨湰 */
    private String text;
    /** 浣嶇疆 */
    private Position position = Position.RIGHT_BOTTOM;
    /** Font灏哄 */
    private int fontSize = DEFAULT_FONT_SIZE;
    /** 棰滆壊 */
    private Color color = Color.WHITE;
    /** Font */
    private Font font = DEFAULT_FONT;
    /** 鍥剧墖point */
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
     * 鑾峰彇瀛楃涓插崰鐢ㄧ殑瀹藉害
     * <br>
     *
     * @param str      瀛楃涓?
     * @param fontSize 鏂囧瓧澶у皬
     * @return 瀛楃涓插崰鐢ㄧ殑瀹藉害
     * @author Shendi <a href='tencent://AddContact/?fromId=45&fromSubId=1&subcmd=all&uin=1711680493'>QQ</a>
     * @since 4.0.0.42
     */
    public static int getStrWidth(String str, int fontSize) {
        char[] chars = str.toCharArray();
        int fontSize2 = fontSize / 2;

        int width = 0;

        for (char c : chars) {
            int len = String.valueOf(c).getBytes().length;
            // 姹夊瓧涓?,鍏朵綑1
            // 鍙兘杩樻湁涓€浜涚壒娈婂瓧绗﹀崰鐢?绛夌瓑,缁熺粺璁′负姹夊瓧
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
        // 鍥剧墖涓爣璇?start
        g.setFont(font);

        g.setColor(color);
        //鍥剧墖浣嶇疆瀹氫綅璁＄畻骞朵笖缁樺埗
        imageCountProcess(g, text, w, h, position);
        imageImageCountProcess(g, imageBytes, w, h, imagePoint);
        // draw end
        g.dispose();

        return bufferedImage;
    }

    /***
     * 鍥剧墖浣嶇疆瀹氫綅璁＄畻鍥剧墖浣嶇疆
     * @param g 鍥惧儚
     * @param imageBytes 鍥剧墖
     * @param imagePoint 浣嶇疆
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
     * 鍥剧墖浣嶇疆瀹氫綅璁＄畻
     * @param g 鍥惧儚
     * @param text 鏂囨湰
     * @param width 瀹?
     * @param height 楂?
     * @param direction 浣嶇疆
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

