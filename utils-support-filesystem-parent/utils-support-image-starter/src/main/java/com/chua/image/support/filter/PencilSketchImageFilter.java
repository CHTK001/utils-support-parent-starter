package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;


/**
 * 铅笔素描风格滤镜
 * <p>
 * 模拟铅笔素描/线稿效果：
 * 1. 灰度化：转为 NTSC 灰度
 * 2. 边缘检测：Sobel 梯度生成线稿
 * 3. 交叉排线：在暗部叠加斜线纹理，模拟铅笔排线
 * 4. 纸张底色：整体偏暖白底，模拟素描纸
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认素描风格
 * BufferedImage sketch = new PencilSketchImageFilter().converter(src);
 *
 * // 更深的线 + 更强排线
 * PencilSketchImageFilter filter = new PencilSketchImageFilter()
 *         .setEdgeStrength(2.0)
 *         .setHatchStrength(0.6);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>edgeStrength</b>（默认 1.5，范围 0.0-3.0）：边缘线强度，越大线越深</li>
 *   <li><b>hatchStrength</b>（默认 0.3，范围 0.0-1.0）：交叉排线强度，暗部排线越重越"素描"</li>
 *   <li><b>hatchAngle</b>（默认 45，范围 0-90）：排线角度（度）</li>
 *   <li><b>paperTone</b>（默认 240，范围 200-255）：纸张底色亮度</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>结果单色（灰度+暖白底），彩色信息全部丢失</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("pencilsketch")
@SpiDescribe("铅笔素描风格滤镜")
@Accessors(chain = true)
public class PencilSketchImageFilter extends AbstractImageFilter {

    /**
    * 边缘线强度，默认 1.5
    */
    private double edgeStrength = 1.5;

    /**
    * 交叉排线强度，默认 0.3
    */
    private double hatchStrength = 0.3;

    /**
    * 排线角度（度），默认 45
    */
    private int hatchAngle = 45;

    /**
    * 纸张底色亮度，默认 240
    */
    private int paperTone = 240;

    /**
    * 执行素描风格滤镜
    *
    * @param src 源图像
    * @param dst 目标图像（未使用）
    * @return 素描风格图像
    */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];

        // 灰度数组
        int[] gray = new int[w * h];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            gray[i] = ((p >> 16 & 0xff) * 77 + (p >> 8 & 0xff) * 151 + (p & 0xff) * 28) >> 8;
        }

        // Sobel 梯度
        int[] edge = new int[w * h];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                int gx = -gray[i - w - 1] - 2 * gray[i - 1] - gray[i + w - 1]
                        + gray[i - w + 1] + 2 * gray[i + 1] + gray[i + w + 1];
                int gy = -gray[i - w - 1] - 2 * gray[i - w] - gray[i - w + 1]
                        + gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1];
                int mag = (int) (Math.sqrt(gx * gx + gy * gy) / 2.0);
                edge[i] = Math.min(255, mag);
            }
        }

        // 排线纹理（基于坐标的斜线）
        float angleRad = (float) Math.toRadians(hatchAngle);
        float fx = (float) Math.cos(angleRad);
        float fy = (float) Math.sin(angleRad);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int g = gray[i];

                // 反转：暗部变亮（素描纸底色），亮部保留
                int base = paperTone - (255 - g) / 4;

                // 叠加边缘线
                int edgeVal = (int) (edge[i] * edgeStrength);
                base -= edgeVal / 2;

                // 暗部叠加排线
                if (hatchStrength > 0 && g < 160) {
                    float proj = x * fx + y * fy;
                    float phase = (float) Math.floor(proj / 4.0);
                    boolean line = ((int) phase) % 2 == 0;
                    int hatchDark = (int) (hatchStrength * (160 - g) * 0.5);
                    if (line) {
                        base -= hatchDark;
                    }
                }

                int v = clamp(base);
                // 暖白底（R 略高）
                outPixels[i] = (0xff << 24) | (v << 16) | (v >> 1) | (v >> 2);
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
