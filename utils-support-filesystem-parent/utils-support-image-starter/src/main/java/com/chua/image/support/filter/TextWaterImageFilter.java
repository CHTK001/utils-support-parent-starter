package com.chua.image.support.filter;



import com.chua.common.support.spi.annotations.SpiIgnore;
import com.chua.common.support.constant.Position;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 水印
 *
 * @author CH
 * @since 4.0.0.42
 */
@SpiIgnore
public class TextWaterImageFilter extends AbstractImageFilter {

    /** Default_font_size */
    private static final int DEFAULT_FONT_SIZE = 18;
    /** Default_font */
    private static final Font DEFAULT_FONT = new Font("黑体", Font.PLAIN, DEFAULT_FONT_SIZE);
    /** 文本 */
    private String text = "";
    /** 位置 */
    private final Position position;
    /** Font尺寸 */
    private int fontSize = DEFAULT_FONT_SIZE;
    /** 颜色 */
    private Color color = Color.WHITE;
    /** Font */
    private Font font = DEFAULT_FONT;


    public TextWaterImageFilter(String text) {
        this(text, Position.RIGHT_BOTTOM, DEFAULT_FONT, DEFAULT_FONT_SIZE);
    }

    public TextWaterImageFilter(String text, Position position) {
        this(text, position, DEFAULT_FONT, DEFAULT_FONT_SIZE);
    }


    public TextWaterImageFilter(String text, Font font) {
        this(text, Position.RIGHT_BOTTOM, font, DEFAULT_FONT_SIZE);
    }

    public TextWaterImageFilter(String text, Color color) {
        this(text, Position.RIGHT_BOTTOM, DEFAULT_FONT, DEFAULT_FONT_SIZE, color);
    }

    public TextWaterImageFilter(String text, Font font, Color color) {
        this(text, Position.RIGHT_BOTTOM, font, DEFAULT_FONT_SIZE, color);
    }


    public TextWaterImageFilter(String text, Position position, Font font) {
        this(text, position, font, DEFAULT_FONT_SIZE);
    }

    public TextWaterImageFilter(String text, Position position, Font font, Color color) {
        this(text, position, font, DEFAULT_FONT_SIZE, color);
    }

    public TextWaterImageFilter(String text, Position position, Font font, int fontSize) {
        this.text = text;
        this.position = position;
        this.font = font;
        this.fontSize = fontSize;
    }

    public TextWaterImageFilter(String text, Position position, Font font, int fontSize, Color color) {
        this.text = text;
        this.position = position;
        this.font = font;
        this.fontSize = fontSize;
        this.color = color;
    }

    /**
     * 获取字符串占用的宽度
     * <br>
     *
     * @param str      字符串
     * @param fontSize 文字大小
     * @return 字符串占用的宽度
     * @author Shendi <a href='tencent://AddContact/?fromId=45&fromSubId=1&subcmd=all&uin=1711680493'>QQ</a>
     * @since 4.0.0.42
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
        // draw end
        g.dispose();

        return bufferedImage;
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

