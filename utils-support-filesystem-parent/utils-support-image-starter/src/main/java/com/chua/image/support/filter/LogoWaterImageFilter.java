package com.chua.image.support.filter;

import com.chua.common.support.constant.Position;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Random;

import javax.imageio.ImageIO;


/**
 * Logo 平铺水印滤镜
 * <p>
 * 把一张 Logo/图片以平铺（tiling）方式覆盖到整幅画面上，
 * 支持：
 * 1. 半透明：alpha 控制水印可见度
 * 2. 旋转：整幅平铺网格统一旋转
 * 3. 缩放：水印相对画布的比例
 * 4. 间距：平铺间距
 * 5. 随机/规则两种排布
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 从文件读 Logo，默认平铺
 * LogoWaterImageFilter filter = new LogoWaterImageFilter(new File("logo.png"));
 * BufferedImage tiled = filter.converter(src);
 *
 * // 半透明 + 旋转 + 更大
 * LogoWaterImageFilter filter = new LogoWaterImageFilter(logoBytes)
 *         .setAlpha(0.15)
 *         .setRotation(30)
 *         .setScale(0.5);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>watermark</b>（构造器必填）：水印图片（byte[]/File/BufferedImage 三选一）</li>
 *   <li><b>alpha</b>（默认 0.3，范围 0.0-1.0）：水印透明度</li>
 *   <li><b>scale</b>（默认 0.3，范围 0.05-1.0）：水印占画布宽度的比例</li>
 *   <li><b>rotation</b>（默认 0，范围 0-360）：平铺网格旋转角度</li>
 *   <li><b>gap</b>（默认 0.5，范围 0.0-2.0）：间距倍率（相对水印尺寸）</li>
 *   <li><b>randomize</b>（默认 false）：是否随机排布（不整齐）</li>
 *   <li><b>seed</b>（默认 42）：随机种子</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>水印图建议带透明通道（PNG）；不透明图会连背景一起盖住主图</li>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>本类标记为 {@code @SpiIgnore}，不注册到 SPI，仅供直接 new 使用</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("logowater")
@SpiDescribe("Logo平铺水印滤镜")
@Accessors(chain = true)
public class LogoWaterImageFilter extends AbstractImageFilter {

    /**
    * 水印图片
    */
    private final BufferedImage watermark;

    /**
    * 水印透明度，默认 0.3
    */
    private double alpha = 0.3;

    /**
    * 水印占画布宽度比例，默认 0.3
    */
    private double scale = 0.3;

    /**
    * 平铺网格旋转角度，默认 0
    */
    private int rotation = 0;

    /**
    * 间距倍率，默认 0.5
    */
    private double gap = 0.5;

    /**
    * 是否随机排布，默认 false
    */
    private boolean randomize = false;

    /**
    * 随机种子，默认 42
    */
    private int seed = 42;

    /**
    * 构造器：从字节数组加载水印
    *
    * @param bytes 水印图片字节（PNG/JPEG 等）
    * @throws IOException 解析失败
    */
    public LogoWaterImageFilter(byte[] bytes) throws IOException {
        this.watermark = ImageIO.read(new ByteArrayInputStream(bytes));
        if (this.watermark == null) {
            throw new IOException("水印图片解析失败");
        }
    }

    /**
    * 构造器：从文件加载水印
    *
    * @param file 水印图片文件
    * @throws IOException 读取失败
    */
    public LogoWaterImageFilter(File file) throws IOException {
        this.watermark = ImageIO.read(file);
        if (this.watermark == null) {
            throw new IOException("水印图片读取失败: " + file);
        }
    }

    /**
    * 构造器：直接传 BufferedImage
    *
    * @param watermark 水印图片
    */
    public LogoWaterImageFilter(BufferedImage watermark) {
        this.watermark = watermark;
    }

    /**
    * 执行 Logo 平铺水印
    *
    * @param src 源图像
    * @param dst 目标图像（未使用）
    * @return 平铺水印后图像
    */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, null);

        // 水印尺寸
        int wmW = Math.max(8, (int) (w * scale));
        int wmH = (int) (wmW * ((double) watermark.getHeight() / watermark.getWidth()));
        // 平铺步进（含间距）
        int stepW = (int) (wmW * (1 + gap));
        int stepH = (int) (wmH * (1 + gap));

        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(1.0, alpha)));

        // 旋转：绕画布中心
        g.rotate(Math.toRadians(rotation), w / 2.0, h / 2.0);

        // 平铺：从左上到右下，超出一屏保证旋转后无缺口
        int extra = Math.max(w, h);
        Random rnd = randomize ? new Random(seed) : null;

        for (int y = -extra; y < h + extra; y += stepH) {
            for (int x = -extra; x < w + extra; x += stepW) {
                int px = x;
                int py = y;
                if (randomize) {
                    px += rnd.nextInt(stepW / 2);
                    py += rnd.nextInt(stepH / 2);
                }
                g.drawImage(watermark, px, py, wmW, wmH, null);
            }
        }

        g.dispose();
        return out;
    }
}
