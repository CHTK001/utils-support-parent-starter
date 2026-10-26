package com.chua.deeplearning.support.onnx.ocr.layout.indra;

import java.util.ArrayList;
import java.util.List;

/**
 * indra LineNet（{@code det.onnx}）的预处理与后处理，忠实复刻 {@code indra_ocr/det_post.py}。
 *
 * <p>输入为灰度页，输出为“行”的墨迹框（x0,y0,x1,y1 + score）。核心流程：</p>
 * <ol>
 *   <li>{@link #pageToInput}：长边缩放到 {@code longSide}（≈1024），右/下补白到 32 的倍数，转墨迹图 {@code 1 - gray/255}；</li>
 *   <li>{@link #decode}：对 core logit 做 sigmoid 阈值二值化，4 连通域统计，再用 top/bottom 距离回归上下边界。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class IndraLineNet {

    /** core 带高占行墨迹高的比例。 */
    public static final double CORE_FRAC = 0.4;
    /** core 两端相对行高的收缩比例。 */
    public static final double CORE_X_SHRINK = 0.15;
    /** 输出相对输入的下采样步长。 */
    public static final int OUT_STRIDE = 2;
    /** 输入高宽必须是该值的倍数。 */
    public static final int INPUT_MULTIPLE = 32;

    private IndraLineNet() {
    }

    /**
     * det 模型输入。
     *
     * @param ink   墨迹图，行主序，长度 {@code h*w}，取值 0..1
     * @param h     补白后的高（32 的倍数）
     * @param w     补白后的宽（32 的倍数）
     * @param scale 缩放比（{@code longSide / max(原高,原宽)}）
     */
    public record DetInput(float[] ink, int h, int w, double scale) {
    }

    /**
     * 灰度页转 det 输入（对齐 {@code page_to_input}）。
     *
     * @param gray     灰度图 {@code [H][W]}
     * @param longSide 长边目标尺寸
     * @return det 输入
     */
    public static DetInput pageToInput(byte[][] gray, int longSide) {
        int h = gray.length;
        int w = h == 0 ? 0 : gray[0].length;
        double scale = (double) longSide / Math.max(h, w);
        int nh = Math.max(1, (int) Math.rint(h * scale));
        int nw = Math.max(1, (int) Math.rint(w * scale));
        byte[][] resized = gray;
        if (nh != h || nw != w) {
            resized = IndraImageOps.resize(gray, nw, nh, scale < 1);
        }
        int ph = (int) Math.ceil((double) nh / INPUT_MULTIPLE) * INPUT_MULTIPLE;
        int pw = (int) Math.ceil((double) nw / INPUT_MULTIPLE) * INPUT_MULTIPLE;
        float[] ink = new float[ph * pw];
        for (int y = 0; y < nh; y++) {
            byte[] row = resized[y];
            int base = y * pw;
            for (int x = 0; x < nw; x++) {
                ink[base + x] = 1.0f - (row[x] & 0xFF) / 255.0f;
            }
        }
        return new DetInput(ink, ph, pw, scale);
    }

    /**
     * 解码 det 输出为行框（对齐 {@code decode}）。
     *
     * @param core   core logit 平面 {@code [h*w]}
     * @param top    上距离平面 {@code [h*w]}
     * @param bottom 下距离平面 {@code [h*w]}
     * @param h      半分辨率高
     * @param w      半分辨率宽
     * @param scale  缩放比（用于映射回原图）
     * @return 行框列表，每个为 {@code {x0,y0,x1,y1,score}}（原图像素）
     */
    public static List<float[]> decode(float[] core, float[] top, float[] bottom,
                                       int h, int w, double scale) {
        int n = h * w;
        float[] prob = new float[n];
        boolean[] mask = new boolean[n];
        for (int i = 0; i < n; i++) {
            double p = 1.0 / (1.0 + Math.exp(-core[i]));
            prob[i] = (float) p;
            mask[i] = p > 0.5;
        }
        int[] label = new int[n];
        int[] stack = new int[n];
        List<Comp> comps = new ArrayList<>();
        int next = 0;
        for (int start = 0; start < n; start++) {
            if (!mask[start] || label[start] != 0) {
                continue;
            }
            int id = ++next;
            int sp = 0;
            stack[sp++] = start;
            label[start] = id;
            Comp c = new Comp();
            while (sp > 0) {
                int p = stack[--sp];
                int py = p / w;
                int px = p - py * w;
                c.add(px, py, prob[p], top[p], bottom[p]);
                if (py > 0) {
                    int q = p - w;
                    if (mask[q] && label[q] == 0) {
                        label[q] = id;
                        stack[sp++] = q;
                    }
                }
                if (py + 1 < h) {
                    int q = p + w;
                    if (mask[q] && label[q] == 0) {
                        label[q] = id;
                        stack[sp++] = q;
                    }
                }
                if (px > 0) {
                    int q = p - 1;
                    if (mask[q] && label[q] == 0) {
                        label[q] = id;
                        stack[sp++] = q;
                    }
                }
                if (px + 1 < w) {
                    int q = p + 1;
                    if (mask[q] && label[q] == 0) {
                        label[q] = id;
                        stack[sp++] = q;
                    }
                }
            }
            comps.add(c);
        }
        List<float[]> boxes = new ArrayList<>();
        final int minArea = 2;
        for (Comp c : comps) {
            if (c.count < minArea) {
                continue;
            }
            double y0 = c.sumTop / c.count;
            double y1 = c.sumBot / c.count;
            double score = c.sumScore / c.count;
            double lh = Math.max(1.0, y1 - y0);
            double ex = Math.min(CORE_X_SHRINK * lh, 0.5 * lh);
            double x0 = c.minX * (double) OUT_STRIDE - ex;
            double x1 = (c.minX + c.width()) * (double) OUT_STRIDE + ex;
            boxes.add(new float[]{
                    (float) (x0 / scale), (float) (y0 / scale),
                    (float) (x1 / scale), (float) (y1 / scale),
                    (float) score});
        }
        return boxes;
    }

    /** 单个连通域的累加统计。 */
    private static final class Comp {
        long count;
        double sumTop;
        double sumBot;
        double sumScore;
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;

        void add(int px, int py, float prob, float top, float bot) {
            count++;
            double ys = (py + 0.5) * OUT_STRIDE;
            sumTop += ys - top;
            sumBot += ys + bot;
            sumScore += prob;
            if (px < minX) {
                minX = px;
            }
            if (px > maxX) {
                maxX = px;
            }
        }

        int width() {
            return maxX - minX + 1;
        }
    }
}
