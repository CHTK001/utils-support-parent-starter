package com.chua.image.support.filter;

import com.chua.common.support.image.ImageProcessorUtils;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;

import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 复古棕褐色调图像滤镜
 *
 * 实现经典的复古老照片效果，通过特定的颜色变换矩阵将现代彩色照片
 * 转换为具有怀旧感的棕褐色调图像。添加随机噪声增强复古质感。
 *
 * 技术原理：
 * - 使用标准的棕褐色调变换矩阵
 * - 红色通道：0.393R + 0.769G + 0.189B
 * - 绿色通道：0.349R + 0.686G + 0.168B
 * - 蓝色通道：0.272R + 0.534G + 0.131B
 * - 添加随机噪声模拟老照片的颗粒感
 *
 * 视觉效果：
 * - 温暖的棕褐色调
 * - 降低的对比度和饱和度
 * - 怀旧的复古质感
 * - 轻微的颗粒噪声
 *
 * 应用场景：
 * - 艺术摄影：创建复古风格的艺术作品
 * - 怀旧效果：为现代照片添加历史感
 * - 主题设计：复古主题的视觉设计
 * - 情感表达：营造温暖、怀念的氛围
 *
 * @author CH
 * @version 1.0.0
 * @since 2021/6/11
 */
@Spi("SepiaTone")
@SpiDescribe("复古棕褐色调滤镜")
public class SepiaToneImageFilter extends AbstractImageFilter {

    /**
     * 执行棕褐色调滤镜处理
     *
     * 对输入图像应用棕褐色调效果，使用标准的颜色变换矩阵将RGB颜色
     * 转换为温暖的棕褐色调，并添加随机噪声增强复古质感。
     *
     * @param src 源图像
     * @param dst 目标图像（此参数未使用）
     * @return 应用棕褐色调效果后的图像
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int width = src.getWidth();
        int height = src.getHeight();

        BufferedImage dest = createCompatibleDestImage(src, null);

        int[] inPixels = new int[width * height];
        int[] outPixels = new int[width * height];

        // 获取源图像的像素数据
        getRgb(src, 0, 0, width, height, inPixels);

        int index = 0;
        for (int row = 0; row < height; row++) {
            int ta = 0, tr = 0, tg = 0, tb = 0;
            for (int col = 0; col < width; col++) {
                index = row * width + col;

// 提取ARGB分量
// Alpha通道
ta = (inPixels[index] >> 24) & 0xff;
// 红色通道
tr = (inPixels[index] >> 16) & 0xff;
// 绿色通道
tg = (inPixels[index] >> 8) & 0xff;
// 蓝色通道
tb = inPixels[index] & 0xff;

                // 应用棕褐色调变换矩阵
                int fr = (int) colorBlend(noise(), (tr * 0.393) + (tg * 0.769) + (tb * 0.189), tr);
                int fg = (int) colorBlend(noise(), (tr * 0.349) + (tg * 0.686) + (tb * 0.168), tg);
                int fb = (int) colorBlend(noise(), (tr * 0.272) + (tg * 0.534) + (tb * 0.131), tb);

                // 重新组合ARGB值，确保颜色值在有效范围内
                outPixels[index] = (ta << 24) | (ImageProcessorUtils.clamp(fr) << 16) | (ImageProcessorUtils.clamp(fg) << 8) | ImageProcessorUtils.clamp(fb);
            }
        }

        // 设置处理后的像素数据到目标图像
        setRgb(dest, 0, 0, width, height, outPixels);
        return dest;
    }

    /**
     * 生成随机噪声
     *
     * 生成0.5到1.0之间的随机数，模拟老照片的颗粒感。
     *
     * @return 随机噪声值，范围[0.5, 1.0]
     */
    private double noise() {
        return Math.random() * 0.5 + 0.5;
    }

    /**
     * 颜色混合函数
     *
     * 根据混合比例将两个颜色值进行线性插值混合。
     *
     * @param scale 混合比例，0表示完全使用src，1表示完全使用dest
     * @param dest  目标颜色值（棕褐色调变换后的值）
     * @param src   源颜色值（原始颜色值）
     * @return 混合后的颜色值
     */
    private double colorBlend(double scale, double dest, double src) {
        return (scale * dest + (1.0 - scale) * src);
    }
}
