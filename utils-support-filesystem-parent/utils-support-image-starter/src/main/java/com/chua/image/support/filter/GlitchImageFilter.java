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
 * 故障风（Glitch Art）滤镜
 * <p>
 * 模拟数字信号故障/数据损坏的视觉效果：
 * 1. RGB 通道水平错位（chromatic shift）：把 R、G、B 各自按不同偏移重排
 * 2. 块状错位（block shift）：随机把若干水平条带左右平移，模拟撕裂
 * 3. 随机像素行交换（row swap）：模拟信号丢帧
 * 4. 数据损坏条（corrupt bar）：在随机位置插入一条纯色/重复像素
 * 5. 轻微噪点
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * // 默认故障风
 * BufferedImage gl = new GlitchImageFilter().converter(src);
 *
 * // 更猛（更多错位 + 换行）
 * GlitchImageFilter filter = new GlitchImageFilter()
 *         .setBlockCount(14)
 *         .setChromaticShift(6)
 *         .setRowSwaps(4);
 * }</pre>
 *
 * <h3>参数说明</h3>
 * <ul>
 *   <li><b>chromaticShift</b>（默认 3，范围 0-32）：RGB 通道水平错位像素</li>
 *   <li><b>blockCount</b>（默认 8，范围 0-64）：块状错位条带数量</li>
 *   <li><b>maxBlockOffset</b>（默认 40，范围 0-512）：块带最大水平位移（像素）</li>
 *   <li><b>rowSwaps</b>（默认 2，范围 0-32）：随机交换的行数量</li>
 *   <li><b>corruptBars</b>（默认 1，范围 0-16）：数据损坏纯色条数量</li>
 *   <li><b>seed</b>（默认 999）：随机种子（固定可复现）</li>
 * </ul>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>输出为 TYPE_INT_RGB（不透明），原图 Alpha 通道丢失</li>
 *   <li>块带/换行基于种子随机，同 seed 结果可复现；改 seed 得到不同故障图案</li>
 * </ul>
 *
 * @author CH
 * @since 2026/9/17
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Spi("glitch")
@SpiDescribe("故障风滤镜")
@Accessors(chain = true)
public class GlitchImageFilter extends AbstractImageFilter {

    /**
    * RGB 通道水平错位像素，默认 3
    */
    private int chromaticShift = 3;

    /**
    * 块状错位条带数量，默认 8
    */
    private int blockCount = 8;

    /**
    * 块带最大水平位移（像素），默认 40
    */
    private int maxBlockOffset = 40;

    /**
    * 随机交换的行数量，默认 2
    */
    private int rowSwaps = 2;

    /**
    * 数据损坏纯色条数量，默认 1
    */
    private int corruptBars = 1;

    /**
    * 随机种子，默认 999
    */
    private int seed = 999;

    /**
    * 执行故障风滤镜
    *
    * @param src 源图像
    * @param dst 目标图像（未使用）
    * @return 故障风图像
    */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] outPixels = new int[w * h];
        Random rnd = new Random(seed);

        // 第一步：RGB 通道错位（复制一份带错位的通道图）
        int[] shifted = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int px = argb[i];
                int rX = x - chromaticShift;
                int gX = x;
                int bX = x + chromaticShift;
                int r = 0, g = 0, b = 0;
                if (rX >= 0 && rX < w) {
                    r = (argb[y * w + rX] >> 16) & 0xff;
                } else {
                    r = (px >> 16) & 0xff;
                }
                if (gX >= 0 && gX < w) {
                    g = (argb[y * w + gX] >> 8) & 0xff;
                } else {
                    g = (px >> 8) & 0xff;
                }
                if (bX >= 0 && bX < w) {
                    b = argb[y * w + bX] & 0xff;
                } else {
                    b = px & 0xff;
                }
                shifted[i] = (0xff << 24) | (r << 16) | (g << 8) | b;
            }
        }

        // 第二步：块状错位（若干水平条带整体左右平移）
        for (int blk = 0; blk < blockCount; blk++) {
            int y0 = rnd.nextInt(Math.max(1, h - 1));
            int y1 = y0 + rnd.nextInt(Math.max(1, h - y0));
            int offset = rnd.nextInt(2 * maxBlockOffset + 1) - maxBlockOffset;
            if (offset == 0 || y1 >= h) {
                continue;
            }
            for (int y = y0; y < y1 && y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int srcX = x - offset;
                    // 越界环绕（模拟撕裂卷边）
                    srcX = ((srcX % w) + w) % w;
                    shifted[y * w + x] = shifted[y * w + srcX];
                }
            }
        }

        // 第三步：随机行交换
        for (int s = 0; s < rowSwaps; s++) {
            int ra = rnd.nextInt(h);
            int rb = rnd.nextInt(h);
            for (int x = 0; x < w; x++) {
                int tmp = shifted[ra * w + x];
                shifted[ra * w + x] = shifted[rb * w + x];
                shifted[rb * w + x] = tmp;
            }
        }

        // 第四步：数据损坏纯色条
        for (int c = 0; c < corruptBars; c++) {
            int cy = rnd.nextInt(h);
            int color = (rnd.nextInt(0x1000000)) | (0xff << 24);
            // 取该行的一个随机像素做纯色（更像"数据损坏"）
            if (rnd.nextBoolean()) {
                color = shifted[cy * w + rnd.nextInt(w)];
            }
            int barH = 1 + rnd.nextInt(4);
            for (int y = cy; y < cy + barH && y < h; y++) {
                for (int x = 0; x < w; x++) {
                    shifted[y * w + x] = color;
                }
            }
        }

        // 第五步：轻微噪点
        for (int i = 0; i < shifted.length; i++) {
            int n = rnd.nextInt(9) - 4;
            int p = shifted[i];
            int r = ImageProcessorUtils.clamp(((p >> 16) & 0xff) + n);
            int g = ImageProcessorUtils.clamp(((p >> 8) & 0xff) + n);
            int b = ImageProcessorUtils.clamp((p & 0xff) + n);
            outPixels[i] = (0xff << 24) | (r << 16) | (g << 8) | b;
        }

        out.setRGB(0, 0, w, h, outPixels, 0, w);
        return out;
    }
}
