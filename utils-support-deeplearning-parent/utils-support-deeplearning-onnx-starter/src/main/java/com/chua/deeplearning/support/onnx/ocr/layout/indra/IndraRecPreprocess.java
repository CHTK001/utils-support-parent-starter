package com.chua.deeplearning.support.onnx.ocr.layout.indra;

import java.util.List;

/**
 * indra 识别器（{@code rec.onnx}）的预处理，忠实复刻 {@code indra_ocr/rec_preproc.py}。
 *
 * <p>一行文本的裁剪规则（训练与推理必须一致）：</p>
 * <ol>
 *   <li>按行高在上下各扩 {@code PAD_Y}、左右各扩 {@code PAD_X} 行高；</li>
 *   <li>从页面裁剪该区域（页外补白/补黑，浅色文字反相）；</li>
 *   <li>等比缩放到高 {@code window*32}（window 对应 {@code meta.rec_window}，默认 3），宽对齐 8 的倍数。</li>
 * </ol>
 * 输出为墨迹图（0=纸、1=墨）。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class IndraRecPreprocess {

    /** 单窗口行高。 */
    public static final int LINE_HEIGHT = 32;
    /** 上下扩展比例（相对行高）。 */
    public static final double PAD_Y = 0.2;
    /** 左右扩展比例（相对行高）。 */
    public static final double PAD_X = 0.3;
    /** 宽对齐倍数（时间步步长）。 */
    public static final int WIDTH_MULTIPLE = 8;
    /** 裁剪最大宽度。 */
    public static final int MAX_WIDTH = 4096;

    private IndraRecPreprocess() {
    }

    /**
     * 识别器批输入。
     *
     * @param data   墨迹图批次，行主序，形状 {@code [B,1,H,Wm]}
     * @param widths 每行有效宽（时间步）
     * @param h      高（{@code window*32}）
     * @param wm     批宽（对齐 8 的倍数）
     */
    public record RecBatch(float[] data, long[] widths, int h, int wm) {
    }

    /**
     * 裁剪一行并转为墨迹图（对齐 {@code crop_line}）。
     *
     * @param gray   灰度页 {@code [H][W]}
     * @param box    行框 {@code {x0,y0,x1,y1,...}}
     * @param window 高度窗口数（{@code meta.rec_window}）
     * @return 墨迹图 {@code [window*32][outW]}
     */
    public static float[][] cropLine(byte[][] gray, float[] box, int window) {
        double padY = (window * (1 + 2 * PAD_Y) - 1) / 2.0;
        int height = LINE_HEIGHT * window;

        double x0 = box[0];
        double y0 = box[1];
        double x1 = box[2];
        double y1 = box[3];
        double h = Math.max(1.0, y1 - y0);
        double bigX0 = x0 - PAD_X * h;
        double bigX1 = x1 + PAD_X * h;
        double bigY0 = y0 - padY * h;
        double bigY1 = y1 + padY * h;

        int H = gray.length;
        int W = H == 0 ? 0 : gray[0].length;
        int ix0 = (int) Math.floor(bigX0);
        int iy0 = (int) Math.floor(bigY0);
        int ix1 = (int) Math.ceil(bigX1);
        int iy1 = (int) Math.ceil(bigY1);
        int cw = Math.max(1, ix1 - ix0);
        int ch = Math.max(1, iy1 - iy0);
        int sx0 = Math.max(0, ix0);
        int sy0 = Math.max(0, iy0);
        int sx1 = Math.min(W, ix1);
        int sy1 = Math.min(H, iy1);

        boolean dark = isDarkLine(gray, box);
        byte fill = (byte) (dark ? 0 : 255);
        byte[][] crop = new byte[ch][cw];
        for (int yy = 0; yy < ch; yy++) {
            java.util.Arrays.fill(crop[yy], fill);
        }
        if (sx1 > sx0 && sy1 > sy0) {
            for (int y = sy0; y < sy1; y++) {
                byte[] src = gray[y];
                byte[] dst = crop[y - iy0];
                for (int x = sx0; x < sx1; x++) {
                    dst[x - ix0] = src[x];
                }
            }
        }
        int outW = Math.max(WIDTH_MULTIPLE,
                Math.min(MAX_WIDTH, (int) Math.rint((double) cw * height / ch)));
        boolean area = ch > height;
        byte[][] resized = IndraImageOps.resize(crop, outW, height, area);
        float[][] ink = new float[height][outW];
        for (int y = 0; y < height; y++) {
            byte[] row = resized[y];
            float[] irow = ink[y];
            for (int x = 0; x < outW; x++) {
                float v = (row[x] & 0xFF) / 255.0f;
                irow[x] = dark ? v : 1.0f - v;
            }
        }
        return ink;
    }

    /**
     * 将多行墨迹图右侧补零拼成一批（对齐 {@code pad_batch}）。
     *
     * @param lines 各行墨迹图（同高）
     * @return 批输入
     */
    public static RecBatch padBatch(List<float[][]> lines) {
        int b = lines.size();
        int H = lines.get(0).length;
        int wm = 0;
        for (float[][] l : lines) {
            if (l[0].length > wm) {
                wm = l[0].length;
            }
        }
        wm = (int) Math.ceil((double) wm / WIDTH_MULTIPLE) * WIDTH_MULTIPLE;
        float[] data = new float[b * H * wm];
        long[] widths = new long[b];
        for (int i = 0; i < b; i++) {
            float[][] line = lines.get(i);
            int lw = line[0].length;
            int base = i * H * wm;
            for (int y = 0; y < H; y++) {
                System.arraycopy(line[y], 0, data, base + y * wm, lw);
            }
            widths[i] = lw;
        }
        return new RecBatch(data, widths, H, wm);
    }

    /**
     * 判断是否为浅色文字深色底（对齐 {@code is_dark_line}）。
     *
     * @param gray 灰度页
     * @param box  行框
     * @return true 表示反相行（浅字深底）
     */
    public static boolean isDarkLine(byte[][] gray, float[] box) {
        int H = gray.length;
        int W = H == 0 ? 0 : gray[0].length;
        int x0 = (int) box[0];
        int y0 = (int) box[1];
        int x1 = (int) box[2];
        int y1 = (int) box[3];
        int by0 = Math.max(0, y0);
        int by1 = Math.min(H, y1 + 1);
        int bx0 = Math.max(0, x0);
        int bx1 = Math.min(W, x1 + 1);
        if (bx1 <= bx0 || by1 <= by0) {
            return false;
        }
        int[] hist = new int[256];
        int n = 0;
        for (int y = by0; y < by1; y++) {
            byte[] row = gray[y];
            for (int x = bx0; x < bx1; x++) {
                hist[row[x] & 0xFF]++;
                n++;
            }
        }
        if (n == 0) {
            return false;
        }
        int[] cum = new int[256];
        int acc = 0;
        for (int k = 0; k < 256; k++) {
            acc += hist[k];
            cum[k] = acc;
        }
        double lo = percentile(cum, n, 0.02);
        double med = percentile(cum, n, 0.5);
        double hi = percentile(cum, n, 0.98);
        return hi - med > 2 * (med - lo) + 8;
    }

    /**
     * percentile
     * @param cum 参数
     * @param n 整数
     * @param q 双精度数
     * @return 返回值
    */
    private static double percentile(int[] cum, int n, double q) {
        double x = q * (n - 1);
        int i = (int) Math.floor(x);
        double g = x - i;
        double lo = searchSortedRight(cum, i);
        double hi = searchSortedRight(cum, Math.min(i + 1, n - 1));
        double d = hi - lo;
        return g >= 0.5 ? hi - d * (1 - g) : lo + d * g;
    }

    /**
     * search Sorted Right
     * @param cum 参数
     * @param v 双精度数
     * @return 返回值
    */
    private static int searchSortedRight(int[] cum, double v) {
        for (int k = 0; k < cum.length; k++) {
            if (cum[k] > v) {
                return k;
            }
        }
        return cum.length;
    }
}
