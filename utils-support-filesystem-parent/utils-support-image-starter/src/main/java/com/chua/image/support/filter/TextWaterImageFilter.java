package com.chua.image.support.filter;



import com.chua.common.support.spi.annotations.SpiIgnore;
import com.chua.common.support.constant.Position;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 文本水印滤镜
 *
 * 在图像上叠加一段文字水印，用于版权标识、来源标注等。
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 简单：右下角白字
 * TextWaterImageFilter filter = new TextWaterImageFilter("© 2026 CH");
 * BufferedImage watermarked = filter.converter(src);
 *
 * // 完整：自定义位置 + 字号 + 颜色
 * TextWaterImageFilter filter = new TextWaterImageFilter(
 *         "CH", Position.LEFT_TOP, new Font("黑体", Font.PLAIN, 32), 32, Color.RED);
 * }</pre>
 *
 * <h3>参数说明（构造器）</h3>
 * <ul>
 *   <li><b>text</b>：水印文字内容</li>
 *   <li><b>position</b>：水印位置（{@link com.chua.common.support.constant.Position} 四角），
 *       默认 RIGHT_BOTTOM</li>
 *   <li><b>font</b>：字体（黑体），默认 18 号</li>
 *   <li><b>fontSize</b>：字号，默认 18</li>
 *   <li><b>color</b>：文字颜色，默认白色</li>
 * </ul>
 *
 * <h3>链式设置（@Setter 生成，支持链式）</h3>
 * <pre>{@code
 * new TextWaterImageFilter("text")
 *     .setText("CH")
 *     .setFontSize(32)
 *     .setColor(Color.RED);
 * }</pre>
 * <p>注：position 为 final 字段，仅能通过构造器指定，不支持 setter。</p>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>水印直接绘制在图像上（不透明），输出为 TYPE_INT_RGB（丢失原图透明度）</li>
 *   <li>中文水印需系统有对应中文字体（如"黑体"），否则显示为方框</li>
 *   <li>本类标记为 {@code @SpiIgnore}，不注册到 SPI，仅供直接 new 使用</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0
 */
@SpiIgnore
@Accessors(chain = true)
@Setter
public class TextWaterImageFilter extends AbstractImageFilter {

    /**
     * DEFAULFON缩放比例
    */
    private static final int DEFAULT_FONT_SIZE = 18;
    /**
     * DEFAUL字体
    */
    private static final Font DEFAULT_FONT = new Font("黑体", Font.PLAIN, DEFAULT_FONT_SIZE);
    /**
     * 文本内容
    */
    private String text = "";
    /**
     * 位置（final，仅可通过构造器设置）
    */
    private final Position position;
    /**
     * 字体大小
    */
    private int fontSize = DEFAULT_FONT_SIZE;
    /**
     * 文字颜色
    */
    private Color color = Color.WHITE;
    /**
     * 字体
    */
    private Font font = DEFAULT_FONT;


    /**
     * 创建 文本水镜像过滤器 实例
     * @param text 文本
     */
    public TextWaterImageFilter(String text) {
        this(text, Position.RIGHT_BOTTOM, DEFAULT_FONT, DEFAULT_FONT_SIZE);
    }

    /**
     * 创建 文本水镜像过滤器 实例
     * @param text 文本
     * @param position 位置
     */
    public TextWaterImageFilter(String text, Position position) {
        this(text, position, DEFAULT_FONT, DEFAULT_FONT_SIZE);
    }


    /**
     * 创建 文本水镜像过滤器 实例
     * @param text 文本
     * @param font font
     */
    public TextWaterImageFilter(String text, Font font) {
        this(text, Position.RIGHT_BOTTOM, font, DEFAULT_FONT_SIZE);
    }

    /**
     * 创建 文本水镜像过滤器 实例
     * @param text 文本
     * @param color color
     */
    public TextWaterImageFilter(String text, Color color) {
        this(text, Position.RIGHT_BOTTOM, DEFAULT_FONT, DEFAULT_FONT_SIZE, color);
    }

    /**
     * 创建 文本水镜像过滤器 实例
     * @param text 文本
     * @param font font
     * @param color color
     */
    public TextWaterImageFilter(String text, Font font, Color color) {
        this(text, Position.RIGHT_BOTTOM, font, DEFAULT_FONT_SIZE, color);
    }


    /**
     * 创建 文本水镜像过滤器 实例
     * @param text 文本
     * @param position 位置
     * @param font font
     */
    public TextWaterImageFilter(String text, Position position, Font font) {
        this(text, position, font, DEFAULT_FONT_SIZE);
    }

    /**
     * 创建 文本水镜像过滤器 实例
     * @param text 文本
     * @param position 位置
     * @param font font
     * @param color color
     */
    public TextWaterImageFilter(String text, Position position, Font font, Color color) {
        this(text, position, font, DEFAULT_FONT_SIZE, color);
    }

    /**
     * 创建 文本水镜像过滤器 实例
     * @param text 文本
     * @param position 位置
     * @param font font
     * @param fontSize font大小
     */
    public TextWaterImageFilter(String text, Position position, Font font, int fontSize) {
        this.text = text;
        this.position = position;
        this.font = font;
        this.fontSize = fontSize;
    }

    /**
     * 创建 文本水镜像过滤器 实例
     * @param text 文本
     * @param position 位置
     * @param font font
     * @param fontSize font大小
     * @param color color
     */
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
    /**
     * 过滤
    */
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
        int w = src.getWidth(), h = src.getHeight();

        BufferedImage bufferedImage = new BufferedImage(w, h,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bufferedImage.createGraphics();
        g.drawImage(src, 0, 0, w, h, null);
 // 图片中标识 启动
        g.setFont(font);

        g.setColor(color);
        //图片位置定位计算并且绘制
        imageCountProcess(g, text, w, h, position);
 // draw 结束
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
 // 降低_RIGHT
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

