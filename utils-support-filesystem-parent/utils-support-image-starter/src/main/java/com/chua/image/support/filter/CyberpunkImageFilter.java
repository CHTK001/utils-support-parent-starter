package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;


/**
 * 赛博朋克风格滤镜
 * <p>
 * 模拟赛博朋克（Cyberpunk）视觉风格：
 * 1. 霓虹色调：暗部偏蓝紫、亮部偏品红/青色
 * 2. 高对比：压暗整体+提亮高光
 * 3. 扫描线（可选）：叠加横向细线，模拟 CRT 显示器
 * 4. 色差偏移：RGB 通道轻微错位
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认赛博朋克风格
 * BufferedImage cp = new CyberpunkImageFilter().converter(src);
 *
 * // 加扫描线 + 更强色差
 * CyberpunkImageFilter filter = new CyberpunkImageFilter()
 *         .setScanlines(true)
 *         .setChromaticAberration(2);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>neonStrength</b>（默认 0.6，范围 0.0-1.0）：霓虹色调强度</li>
 *   <li><b>contrast</b>（默认 1.5，范围 1.0-3.0）：对比度系数</li>
 *   <li><b>scanlines</b>（默认 false）：是否叠加扫描线</li>
 *   <li><b>scanlineInterval</b>（默认 3）：扫描线间隔（像素）</li>
 *   <li><b>chromaticAberration</b>（默认 1，范围 0-8）：RGB 通道错位像素数</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>色差偏移越大越"故障风"，但过大会撕裂画面</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("cyberpunk")
@SpiDescribe("赛博朋克风格滤镜")
@Accessors(chain = true)
public class CyberpunkImageFilter extends AbstractImageFilter {

    /**
     * 霓虹色调强度，默认 0.6
     */
    private double neonStrength = 0.6;

    /**
     * 对比度系数，默认 1.5
     */
    private double contrast = 1.5;

    /**
     * 是否叠加扫描线，默认 false
     */
    private boolean scanlines = false;

    /**
     * 扫描线间隔（像素），默认 3
     */
    private int scanlineInterval = 3;

    /**
     * RGB 通道错位像素数，默认 1
     */
    private int chromaticAberration = 1;

    /**
     * 执行赛博朋克滤镜
     *
     * @param src 源图像
     * @param dst 目标图像（未使用）
     * @return 赛博朋克风格图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int p = argb[i];
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                int lum = (int) (r * 0.299 + g * 0.587 + b * 0.114);

                // 色差：R 通道取左移像素，B 通道取右移像素
                int rSrc, bSrc;
                if (chromaticAberration > 0) {
                    int rX = Math.max(0, x - chromaticAberration);
                    int bX = Math.min(w - 1, x + chromaticAberration);
                    rSrc = argb[y * w + rX];
                    bSrc = argb[y * w + bX];
                    r = (rSrc >> 16) & 0xff;
                    b = bSrc & 0xff;
                }

                // 霓虹色调：暗部偏蓝紫，亮部偏品红
                if (neonStrength > 0) {
                    if (lum < 128) {
                        // 暗部：加蓝加红（偏紫）
                        int t = (int) ((128 - lum) / 128.0 * neonStrength * 60);
                        r = clamp(r + t / 2);
                        b = clamp(b + t);
                    } else {
                        // 亮部：加品红（红+蓝）
                        int t = (int) ((lum - 128) / 127.0 * neonStrength * 40);
                        r = clamp(r + t);
                        b = clamp(b + t / 2);
                    }
                }

                // 高对比
                r = clamp((int) (((r - 128) * contrast + 128)));
                g = clamp((int) (((g - 128) * contrast + 128)));
                b = clamp((int) (((b - 128) * contrast + 128)));

                // 压暗整体（赛博朋克偏暗）
                r = (int) (r * 0.85);
                g = (int) (g * 0.75);
                b = (int) (b * 0.9);

                // 扫描线
                if (scanlines && scanlineInterval > 0 && y % scanlineInterval == 0) {
                    int dark = 30;
                    r = clamp(r - dark);
                    g = clamp(g - dark);
                    b = clamp(b - dark);
                }

                outPixels[i] = (0xff << 24) | (r << 16) | (g << 8) | b;
            }
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
    }

    /**
     * 通道值钳制 0-255
     *
     * @param v 原始值
     * @return 钳制后的值
     */
    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
