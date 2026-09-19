package com.chua.image.support.filter;


import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.BufferedImageUtils;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * USM (Unsharp Mask) 锐化滤镜
 *
 * USM 是一种经典的图像锐化技术，通过从原图像中减去模糊版本来增强边缘和细节。
 * 该技术最初来源于传统摄影的暗房技术，现在广泛应用于数字图像处理。
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认权重 0.6（中等强度）
 * BufferedImage sharp = new ImageUsmFilterImage().converter(src);
 *
 * // 强烈锐化
 * BufferedImage sharp = new ImageUsmFilterImage(1.5).converter(src);
 *
 * // 链式调整 sigma（模糊强度）与 weight（锐化权重）
 * BufferedImage sharp = new ImageUsmFilterImage()
 *         .setSigma(1.0)
 *         .setWeight(0.8)
 *         .converter(src);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>weight</b>（构造器参数，默认 0.6）：锐化权重，控制锐化强度。
 *       0.1-0.5 轻微（人像），0.5-1.0 中等（一般照片），1.0-2.0 强烈（风景/细节）。
 *       值越大锐化越强，但噪声也会被放大。</li>
 *   <li><b>sigma</b>（继承自 {@link ImageGaussianBlurFilter}，默认 2.0）：
 *       高斯模糊标准差，控制"模糊版本"的强度。越大模糊越明显。</li>
 *   <li><b>kernel</b>（继承自 {@link ImageGaussianBlurFilter}）：自定义卷积核，
 *       默认根据 sigma 自动生成，一般无需设置。</li>
 * </ul>
 *
 * <h3>处理流程</h3>
 * <ol>
 *   <li>对原图应用高斯模糊得到"模糊版本"</li>
 *   <li>计算 原图 − 模糊版本 = 边缘增强量</li>
 *   <li>增强量 × weight 加回原图，得到锐化结果</li>
 * </ol>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>高权重 + 小 sigma 组合会放大图像中的噪点，建议先用降噪滤镜</li>
 *   <li>多线程：内部用线程池并行处理 3 个颜色通道</li>
 * </ul>
 *
 * @author CH
 * @版本 1.0.0
 * @since 2021/6/11
 */
@SpiDescribe("USM锐化滤镜")
@Spi("usm")
@Accessors(chain = true)
@Setter
public class ImageUsmFilterImage extends ImageGaussianBlurFilter {

    /**
     * USM 锐化权重，控制锐化强度（默认 0.6）
     * <ul>
     *   <li>0.1-0.5：轻微锐化，适合人像</li>
     *   <li>0.5-1.0：中等锐化，适合一般照片</li>
     *   <li>1.0-2.0：强烈锐化，适合风景或突出细节</li>
     * </ul>
     */
    private double weight;


    /**
     * 默认构造函数
     *
     * 使用默认的锐化权重 0.6，适合大多数图像的中等强度锐化。
     */
    public ImageUsmFilterImage() {
        this.weight = 0.6;
    }

    /**
     * 带权重参数的构造函数
     *
     * @param weight 锐化权重，建议范围 0.1-2.0
     *               - 0.1-0.5: 轻微锐化
     *               - 0.5-1.0: 中等锐化
     *               - 1.0-2.0: 强烈锐化
     */
    public ImageUsmFilterImage(double weight) {
        this.weight = weight;
    }

    @Override
    /** 过滤 */
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        initial(src);
        int total = width * height;
        byte[] r1 = new byte[total];
        byte[] g1 = new byte[total];
        byte[] b1 = new byte[total];
        System.arraycopy(rArr, 0, r1, 0, total);
        System.arraycopy(gArr, 0, g1, 0, total);
        System.arraycopy(bArr, 0, b1, 0, total);
        byte[][] output = new byte[3][total];
        // 高斯模糊
        super.filter(src, dst);
        int r = 0;
        int g = 0;
        int b = 0;

        int r11 = 0;
        int g11 = 0;
        int b11 = 0;

        int r2 = 0;
        int g2 = 0;
        int b2 = 0;

        for (int i = 0; i < total; i++) {
            r11 = r1[i] & 0xff;
            g11 = g1[i] & 0xff;
            b11 = b1[i] & 0xff;

            r2 = rArr[i] & 0xff;
            g2 = gArr[i] & 0xff;
            b2 = bArr[i] & 0xff;

            r = (int) ((r11 - weight * r2) / (1 - weight));
            g = (int) ((g11 - weight * g2) / (1 - weight));
            b = (int) ((b11 - weight * b2) / (1 - weight));

            output[0][i] = (byte) BufferedImageUtils.clamp(r);
            output[1][i] = (byte) BufferedImageUtils.clamp(g);
            output[2][i] = (byte) BufferedImageUtils.clamp(b);
        }

        putRgb(output[0], output[1], output[2]);
        return toBitmap();
    }


}
