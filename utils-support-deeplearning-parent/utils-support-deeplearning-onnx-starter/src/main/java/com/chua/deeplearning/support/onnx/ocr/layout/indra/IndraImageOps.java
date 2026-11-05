// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.deeplearning.support.onnx.ocr.layout.indra;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * indra 版面分析所需的纯 Java 图像基础算子（灰度解码 + 双线性/面积重采样）。
 *
 * <p>indra 官方实现基于 OpenCV（{@code cv2.cvtColor} / {@code cv2.resize}）。
 * 为避免引入本地库依赖，这里用纯 Java 复刻其语义：</p>
 * <ul>
 *   <li>灰度化：BT.601 亮度 {@code 0.299R + 0.587G + 0.114B}（四舍五入），对齐 {@code cv2.IMREAD_GRAYSCALE}；</li>
 *   <li>缩小用面积平均（对齐 {@code INTER_AREA}），放大用双线性（对齐 {@code INTER_LINEAR}，半像素中心 + 边界复制）。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class IndraImageOps {

    private IndraImageOps() {
    }

    /**
     * 解码图像字节为灰度图。
     *
     * @param data 图像字节（PNG/JPG 等）
     * @return 灰度像素 {@code [H][W]}，取值 0..255
     * @throws IOException 解码失败
     */
    public static byte[][] toGray(byte[] data) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
        if (img == null) {
            throw new IOException("无法解码图像数据（非 PNG/JPG 等可识别格式）");
        }
        int w = img.getWidth();
        int h = img.getHeight();
        byte[][] gray = new byte[h][w];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            byte[] gr = gray[y];
            for (int x = 0; x < w; x++) {
                int rgb = row[x];
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int v = (int) (0.299 * r + 0.587 * g + 0.114 * b + 0.5);
                gr[x] = (byte) (v < 0 ? 0 : Math.min(v, 255));
            }
        }
        return gray;
    }

    /**
     * 重采样灰度图（对齐 {@code cv2.resize}）。
     *
     * @param src     源灰度图 {@code [H][W]}
     * @param dw      目标宽
     * @param dh      目标高
     * @param areaAvg 是否用面积平均（缩小场景）；否则双线性
     * @return 重采样结果 {@code [dh][dw]}
     */
    public static byte[][] resize(byte[][] src, int dw, int dh, boolean areaAvg) {
        int sh = src.length;
        int sw = sh == 0 ? 0 : src[0].length;
        byte[][] out = new byte[dh][dw];
        if (sw == 0 || sh == 0) {
            return out;
        }
        if (dw == sw && dh == sh) {
            for (int y = 0; y < dh; y++) {
                System.arraycopy(src[y], 0, out[y], 0, sw);
            }
            return out;
        }
        if (areaAvg) {
            resizeArea(src, sw, sh, out, dw, dh);
        } else {
            resizeLinear(src, sw, sh, out, dw, dh);
        }
        return out;
    }

    /**
     * 面积平均重采样（{@code INTER_AREA}）：每个目标像素取源矩形区域的精确加权平均。
     * @param dh 整数
     * @param dw 整数
     * @param out 参数
     * @param sh 整数
     * @param src 参数
     * @param sw 整数
     * @return 返回值
    */
    private static void resizeArea(byte[][] src, int sw, int sh, byte[][] out, int dw, int dh) {
        double sx = (double) sw / dw;
        double sy = (double) sh / dh;
        for (int dy = 0; dy < dh; dy++) {
            double y0 = dy * sy;
            double y1 = (dy + 1) * sy;
            int iy0 = (int) Math.floor(y0);
            int iy1 = (int) Math.ceil(y1);
            byte[] orow = out[dy];
            for (int dx = 0; dx < dw; dx++) {
                double x0 = dx * sx;
                double x1 = (dx + 1) * sx;
                int ix0 = (int) Math.floor(x0);
                int ix1 = (int) Math.ceil(x1);
                double sum = 0.0;
                double wsum = 0.0;
                for (int iy = iy0; iy < iy1; iy++) {
                    double wy = Math.min(iy + 1.0, y1) - Math.max(iy, y0);
                    if (wy <= 0) {
                        continue;
                    }
                    byte[] srow = src[Math.min(Math.max(iy, 0), sh - 1)];
                    for (int ix = ix0; ix < ix1; ix++) {
                        double wx = Math.min(ix + 1.0, x1) - Math.max(ix, x0);
                        if (wx <= 0) {
                            continue;
                        }
                        int v = srow[Math.min(Math.max(ix, 0), sw - 1)] & 0xFF;
                        double wgt = wx * wy;
                        sum += v * wgt;
                        wsum += wgt;
                    }
                }
                int val = wsum > 0 ? (int) Math.round(sum / wsum) : 0;
                orow[dx] = (byte) (val < 0 ? 0 : Math.min(val, 255));
            }
        }
    }

    /**
     * 双线性重采样（{@code INTER_LINEAR}）：源坐标 {@code (d+0.5)*scale-0.5}，边界复制。
     * @param dh 整数
     * @param dw 整数
     * @param out 参数
     * @param sh 整数
     * @param src 参数
     * @param sw 整数
     * @return 返回值
    */
    private static void resizeLinear(byte[][] src, int sw, int sh, byte[][] out, int dw, int dh) {
        double sx = (double) sw / dw;
        double sy = (double) sh / dh;
        for (int dy = 0; dy < dh; dy++) {
            double fy = (dy + 0.5) * sy - 0.5;
            int iy = (int) Math.floor(fy);
            double gy = fy - iy;
            int y0 = Math.min(Math.max(iy, 0), sh - 1);
            int y1 = Math.min(Math.max(iy + 1, 0), sh - 1);
            byte[] r0 = src[y0];
            byte[] r1 = src[y1];
            byte[] orow = out[dy];
            for (int dx = 0; dx < dw; dx++) {
                double fx = (dx + 0.5) * sx - 0.5;
                int ix = (int) Math.floor(fx);
                double gx = fx - ix;
                int x0 = Math.min(Math.max(ix, 0), sw - 1);
                int x1 = Math.min(Math.max(ix + 1, 0), sw - 1);
                double v00 = r0[x0] & 0xFF;
                double v01 = r0[x1] & 0xFF;
                double v10 = r1[x0] & 0xFF;
                double v11 = r1[x1] & 0xFF;
                double top = v00 + (v01 - v00) * gx;
                double bot = v10 + (v11 - v10) * gx;
                double v = top + (bot - top) * gy;
                int val = (int) Math.round(v);
                orow[dx] = (byte) (val < 0 ? 0 : Math.min(val, 255));
            }
        }
    }
}