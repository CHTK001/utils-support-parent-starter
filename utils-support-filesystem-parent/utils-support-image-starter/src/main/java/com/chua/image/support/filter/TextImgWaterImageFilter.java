package com.chua.image.support.filter;


import com.chua.common.support.spi.annotations.SpiIgnore;
import com.chua.common.support.constant.Position;
import com.chua.common.support.image.ImagePoint;
import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.common.support.utils.IoUtils;
import lombok.Setter;
import lombok.experimental.Accessors;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 文本 + 图片水印滤镜
 *
 * 在图像上同时叠加"文字水印"和"图片水印"，用于版权标识、Logo 标注等。
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 文字 + 图片（图片水印在右下角）
 * TextImgWaterImageFilter filter = new TextImgWaterImageFilter(
 *         "© 2026 CH", logoBytes, Position.LEFT_TOP);
 * BufferedImage watermarked = filter.converter(src);
 *
 * // 图片水印位置通过 imagePoint 偏移控制
 * TextImgWaterImageFilter filter = new TextImgWaterImageFilter(
 *         "CH", logoBytes, new ImagePoint(50, 50));
 * }</pre>
 *
 * <h3>参数说明（构造器）</h3>
 * <ul>
 *   <li><b>text</b>：文字水印内容</li>
 *   <li><b>imageBytes</b>：图片水印的字节内容（PNG/JPG 等，ImageIO 可读）</li>
 *   <li><b>position</b>：文字 + 图片水印共用的四角位置，默认 RIGHT_BOTTOM</li>
 *   <li><b>fontSize / color / font</b>：文字样式，默认 18 号白色黑体</li>
 *   <li><b>imagePoint</b>：图片水印的坐标偏移（像素），默认 (20, 20)</li>
 * </ul>
 *
 * <h3>链式设置（@Setter 生成，支持链式）</h3>
 * <pre>{@code
 * new TextImgWaterImageFilter("text", bytes, position)
 *     .setText("CH")
 *     .setImagePoint(new ImagePoint(10, 10))
 *     .setFontSize(24);
 * }</pre>
 * <p>注：imageBytes 为 final 字段，仅可通过构造器设置。</p>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>图片水印尺寸较大时会遮挡主图，建议使用 64×64 以内的小图</li>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>本类标记为 {@code @SpiIgnore}，不注册到 SPI</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0
 */
@SpiIgnore
@Accessors(chain = true)
@Setter
public class TextImgWaterImageFilter extends AbstractImageFilter {

    /**
     * DEFAULFON缩放比例
    */
    private static final int DEFAULT_FONT_SIZE = 18;
    /**
     * DEFAUL字体
    */
    private static final Font DEFAULT_FONT = new Font("黑体", Font.PLAIN, DEFAULT_FONT_SIZE);
    /**
     * DEFAUL水印位置点
    */
    private static final ImagePoint DEFAULT_POINT = new ImagePoint(20, 20);
    /**
     * 镜像水印图片字节数组（final，仅可通过构造器设置）
    */
    private final byte[] imageBytes;
    /**
     * 文本内容
    */
    private String text;
    /**
     * 水印位置
    */
    private Position position = Position.RIGHT_BOTTOM;
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
     * 镜像水印位置点
    */
    private ImagePoint imagePoint = DEFAULT_POINT;


    /**
     * 创建 文本img水镜像过滤器 实例
     * @param text 文本
     * @param stream 流
     * @param position 位置
     */
    public TextImgWaterImageFilter(String text, InputStream stream, Position position) throws IOException {
        this.text = text;
        this.position = position;
        this.imageBytes = IoUtils.toByteArray(stream);
    }

    /**
     * 创建 文本img水镜像过滤器 实例
     * @param text 文本
     * @param imageBytes 镜像bytes
     * @param position 位置
     */
    public TextImgWaterImageFilter(String text, byte[] imageBytes, Position position) {
        this.text = text;
        this.position = position;
        this.imageBytes = imageBytes;
    }

    /**
     * 创建 文本img水镜像过滤器 实例
     * @param text 文本
     * @param stream 流
     * @param imagePoint 镜像point
     */
    public TextImgWaterImageFilter(String text, InputStream stream, ImagePoint imagePoint) throws IOException {
        this.text = text;
        this.imagePoint = imagePoint;
        this.imageBytes = IoUtils.toByteArray(stream);
    }

    /**
     * 创建 文本img水镜像过滤器 实例
     * @param text 文本
     * @param imageBytes 镜像bytes
     * @param imagePoint 镜像point
     */
    public TextImgWaterImageFilter(String text, byte[] imageBytes, ImagePoint imagePoint) {
        this.text = text;
        this.imagePoint = imagePoint;
        this.imageBytes = imageBytes;
    }

    /**
     * 创建 文本img水镜像过滤器 实例
     * @param text 文本
     * @param imageBytes 镜像bytes
     * @param position 位置
     * @param fontSize font大小
     * @param color color
     * @param font font
     * @param imagePoint 镜像point
     */
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
        imageImageCountProcess(g, imageBytes, w, h, imagePoint);
 // draw 结束
        g.dispose();

        return bufferedImage;
    }

    /***
     * 图片位置定位计算图片位置
     * @param g 图像
     * @param imageBytes 图片
     * @param imagePoint 位置
     * @param width width
     * @param height height
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

