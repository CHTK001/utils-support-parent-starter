package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;
import java.util.Random;


/**
 * 老电视效果滤镜
 * <p>
 * 模拟 CRT 老电视的观感：
 * 1. 褪色 + 轻微偏色（暖黄调）：模拟老电视色彩老化
 * 2. 隔行扫描线：水平细线压暗
 * 3. RGB 轻微色差（信号同步偏移感）
 * 4. 边缘柔化：模拟 CRT 信号带宽不足
 * 5. 随机雪花噪点（可选）：模拟信号干扰
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认老电视效果
 * BufferedImage tv = new RetroTVImageFilter().converter(src);
 *
 * // 加雪花噪点
 * RetroTVImageFilter filter = new RetroTVImageFilter()
 *         .setStaticNoise(true)
 *         .setFading(0.4);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>fading</b>（默认 0.3，范围 0.0-1.0）：褪色/偏色强度</li>
 *   <li><b>scanlineInterval</b>（默认 2）：扫描线间隔（像素）</li>
 *   <li><b>scanlineDark</b>（默认 0.25，范围 0.0-1.0）：扫描线压暗程度</li>
 *   <li><b>chromatic</b>（默认 1）：RGB 色差像素数（0 关闭）</li>
 *   <li><b>staticNoise</b>（默认 false）：是否叠加雪花噪点</li>
 *   <li><b>seed</b>（默认 2026）：随机种子</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("retrotv")
@SpiDescribe("老电视效果滤镜")
@Accessors(chain = true)
public class RetroTVImageFilter extends AbstractImageFilter {

    /**
    * 褪色强度，默认 0.3
    */
    private double fading = 0.3;

    /**
    * 扫描线间隔（像素），默认 2
    */
    private int scanlineInterval = 2;

    /**
    * 扫描线压暗程度，默认 0.25
    */
    private double scanlineDark = 0.25;

    /**
    * RGB 色差像素数，默认 1
    */
    private int chromatic = 1;

    /**
    * 是否叠加雪花噪点，默认 false
    */
    private boolean staticNoise = false;

    /**
    * 随机种子，默认 2026
    */
    private int seed = 2026;

    /**
    * 执行老电视效果滤镜
    *
    * @param src 源图像
    * @param dst 目标图像（未使用）
    * @return 老电视效果图像
    */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];
        Random rnd = new Random(seed);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int p = argb[i];
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;

                // 色差：R 取左、B 取右
                if (chromatic > 0) {
                    int rX = Math.max(0, x - chromatic);
                    int bX = Math.min(w - 1, x + chromatic);
                    r = (argb[y * w + rX] >> 16) & 0xff;
                    b = argb[y * w + bX] & 0xff;
                }

                // 褪色 + 暖黄调（向 (128,128,110) 中性和偏暖收缩）
                if (fading > 0) {
                    double t = fading;
                    r = (int) (r + (215 - r) * t * 0.4);
                    g = (int) (g + (190 - g) * t * 0.4);
                    b = (int) (b + (140 - b) * t * 0.4);
                    r = ImageProcessorUtils.clamp(r);
                    g = ImageProcessorUtils.clamp(g);
                    b = ImageProcessorUtils.clamp(b);
                }

                // 扫描线
                if (scanlineInterval > 0 && y % scanlineInterval == 0) {
                    int dark = (int) (255 * scanlineDark);
                    r = ImageProcessorUtils.clamp(r - (int) (dark * (r / 255.0 + 0.3)));
                    g = ImageProcessorUtils.clamp(g - (int) (dark * (g / 255.0 + 0.3)));
                    b = ImageProcessorUtils.clamp(b - (int) (dark * (b / 255.0 + 0.3)));
                }

                // 雪花噪点
                if (staticNoise) {
                    int n = rnd.nextInt(30) - 10;
                    r = ImageProcessorUtils.clamp(r + n);
                    g = ImageProcessorUtils.clamp(g + n);
                    b = ImageProcessorUtils.clamp(b + n);
                }

                outPixels[i] = (0xff << 24) | (r << 16) | (g << 8) | b;
            }
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
    }
}
