package com.chua.deeplearning.support.onnx.ocr.layout.indra;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * indra OrderNet（{@code order.onnx}）的后处理，忠实复刻 {@code indra_ocr/order_post.py}。
 *
 * <p>提供：行特征构造（10 维几何 + 6 维文本）、成对 logit 的阅读顺序解码、
 * 同块 logit 的平均链接聚类、以及按“块优先、块内 XY-cut”的阅读顺序合成。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class IndraOrderPost {

    /** 几何特征维数。 */
    public static final int N_GEOM = 10;
    /** 文本特征维数。 */
    public static final int N_TEXT = 6;
    /** 总特征维数。 */
    public static final int N_FEATS = N_GEOM + N_TEXT;

    /** 句末字符集。 */
    public static final String SENTENCE_END = ".!?:;。！？：；．";
    /** 项目符号（行首）。 */
    private static final String BULLETS = "•-–▪◦*·→";

    /** OrderNet 布局头预测的 10 个块类别（chandra 块标签）。 */
    public static final String[] BLOCK_CLASSES = {
            "Text", "Section-Header", "Page-Header", "Page-Footer", "List-Group",
            "Table", "Caption", "Footnote", "Table-Of-Contents", "Form"
    };

    /** 需要按行（表格形态）排序的块类别。 */
    public static final Set<String> GRID_LABELS = Set.of("Table", "Form", "Table-Of-Contents");

    private IndraOrderPost() {
    }

    /**
     * 构造行特征（对齐 {@code line_features}）。
     *
     * @param boxes 行框 {@code [N][4]}（x0,y0,x1,y1，原图像素）
     * @param pageW 页宽
     * @param pageH 页高
     * @param texts 行文本（可为 null）
     * @return 特征 {@code [N][16]}
     */
    public static float[][] lineFeatures(float[][] boxes, double pageW, double pageH, List<String> texts) {
        int n = boxes.length;
        float[][] feats = new float[n][N_FEATS];
        if (n == 0) {
            return feats;
        }
        double[] w = new double[n];
        double[] h = new double[n];
        for (int i = 0; i < n; i++) {
            w[i] = Math.max(boxes[i][2] - boxes[i][0], 1e-3);
            h[i] = Math.max(boxes[i][3] - boxes[i][1], 1e-3);
        }
        double medH = median(h);
        for (int i = 0; i < n; i++) {
            double x0 = boxes[i][0];
            double y0 = boxes[i][1];
            double x1 = boxes[i][2];
            double y1 = boxes[i][3];
            float[] f = feats[i];
            f[0] = (float) (x0 / pageW);
            f[1] = (float) (y0 / pageH);
            f[2] = (float) (x1 / pageW);
            f[3] = (float) (y1 / pageH);
            f[4] = (float) ((x0 + x1) / (2 * pageW));
            f[5] = (float) ((y0 + y1) / (2 * pageH));
            f[6] = (float) (w[i] / pageW);
            f[7] = (float) Math.log(h[i] / medH);
            f[8] = (float) (Math.log(w[i] / h[i]) / 4.0);
            f[9] = (float) (medH / pageH * 20.0);
            if (texts != null && i < texts.size()) {
                String t = texts.get(i) == null ? "" : texts.get(i).strip();
                if (!t.isEmpty()) {
                    int cp0 = t.codePointAt(0);
                    int cpLast = t.codePointBefore(t.length());
                    f[10] = (float) (Math.log1p(displayLen(t)) / 5.0);
                    int digits = 0;
                    int cps = 0;
                    for (int ci = 0; ci < t.length(); ) {
                        int cp = t.codePointAt(ci);
                        if (Character.isDigit(cp)) {
                            digits++;
                        }
                        cps++;
                        ci += Character.charCount(cp);
                    }
                    f[11] = cps == 0 ? 0f : (float) digits / cps;
                    boolean bullet = BULLETS.indexOf(cp0) >= 0;
                    if (!bullet && t.length() > 3) {
                        String head = t.substring(0, Math.min(3, t.length()));
                        head = rstrip(head, ".)");
                        bullet = !head.isEmpty() && isAllDigits(head);
                    }
                    f[12] = bullet ? 1f : 0f;
                    f[13] = t.endsWith("-") ? 1f : 0f;
                    f[14] = SENTENCE_END.indexOf(cpLast) >= 0 ? 1f : 0f;
                    f[15] = Character.isUpperCase(cp0) ? 1f : 0f;
                }
            }
        }
        return feats;
    }

    /**
     * 由成对顺序 logit 求解阅读顺序（对齐 {@code decode_order}）。
     *
     * @param a      成对 logit 矩阵 {@code [N][N]}（{@code a[i][j]>0} 表示 i 在 j 之前）
     * @param sweeps 局部插入搜索轮数
     * @return 阅读顺序（行索引）
     */
    public static int[] decodeOrder(double[][] a, int sweeps) {
        int n = a.length;
        int[] identity = new int[n];
        for (int i = 0; i < n; i++) {
            identity[i] = i;
        }
        if (n <= 1) {
            return identity;
        }
        double[][] lp = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                lp[i][j] = logSigmoid(a[i][j]);
            }
        }
        double[] key = new double[n];
        for (int i = 0; i < n; i++) {
            double rowSum = 0;
            double colSum = 0;
            for (int j = 0; j < n; j++) {
                rowSum += lp[i][j];
                colSum += lp[j][i];
            }
            key[i] = colSum - rowSum;
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (x, y) -> {
            int c = Double.compare(key[x], key[y]);
            return c != 0 ? c : Integer.compare(x, y);
        });
        int[] cur = new int[n];
        for (int i = 0; i < n; i++) {
            cur[i] = order[i];
        }
        for (int sweep = 0; sweep < sweeps; sweep++) {
            boolean improved = false;
            int[] snapshot = cur.clone();
            for (int x : snapshot) {
                int[] rest = new int[n - 1];
                int ri = 0;
                int curPos = -1;
                for (int t = 0; t < n; t++) {
                    if (cur[t] == x) {
                        curPos = t;
                    } else {
                        rest[ri++] = cur[t];
                    }
                }
                double[] gains = new double[n];
                double acc = 0.0;
                double[] afterSuf = new double[n];
                double suf = 0.0;
                for (int k = n - 2; k >= 0; k--) {
                    suf += lp[x][rest[k]];
                    afterSuf[k] = suf;
                }
                for (int k = 0; k < n; k++) {
                    double pre = acc;
                    double post = k <= n - 2 ? afterSuf[k] : 0.0;
                    gains[k] = pre + post;
                    if (k <= n - 2) {
                        acc += lp[rest[k]][x];
                    }
                }
                int best = 0;
                for (int k = 1; k < n; k++) {
                    if (gains[k] > gains[best]) {
                        best = k;
                    }
                }
                if (gains[best] > gains[curPos] + 1e-6) {
                    int[] next = new int[n];
                    int idx = 0;
                    for (int k = 0; k < best; k++) {
                        next[idx++] = rest[k];
                    }
                    next[idx++] = x;
                    for (int k = best; k < n - 1; k++) {
                        next[idx++] = rest[k];
                    }
                    cur = next;
                    improved = true;
                }
            }
            if (!improved) {
                break;
            }
        }
        return cur;
    }

    /**
     * 同块 logit 的平均链接聚类（对齐 {@code _cluster}）。
     *
     * @param logits 成对“同块” logit {@code [N][N]}
     * @return 每个块包含的行索引（升序）
     */
    public static List<int[]> cluster(double[][] logits) {
        int n = logits.length;
        List<int[]> result = new ArrayList<>();
        if (n == 0) {
            return result;
        }
        double[][] s = new double[n][n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(logits[i], 0, s[i], 0, n);
            s[i][i] = 0.0;
        }
        double[][] m = new double[n][n];
        double[] size = new double[n];
        boolean[] alive = new boolean[n];
        List<List<Integer>> members = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            size[i] = 1.0;
            alive[i] = true;
            List<Integer> mem = new ArrayList<>();
            mem.add(i);
            members.add(mem);
            for (int j = 0; j < n; j++) {
                m[i][j] = (i == j) ? Double.NEGATIVE_INFINITY : s[i][j];
            }
        }
        int[] best = new int[n];
        double[] bestV = new double[n];
        for (int i = 0; i < n; i++) {
            best[i] = argmax(m[i]);
            bestV[i] = m[i][best[i]];
        }
        double[] row = new double[n];
        for (int iter = 0; iter < n - 1; iter++) {
            int a = argmax(bestV);
            int b = best[a];
            if (bestV[a] <= 0) {
                break;
            }
            int lo = Math.min(a, b);
            int hi = Math.max(a, b);
            a = lo;
            b = hi;
            for (int j = 0; j < n; j++) {
                s[a][j] += s[b][j];
            }
            for (int j = 0; j < n; j++) {
                s[j][a] += s[j][b];
            }
            s[a][a] = 0.0;
            size[a] += size[b];
            alive[b] = false;
            members.get(a).addAll(members.get(b));
            for (int j = 0; j < n; j++) {
                double v = s[a][j] / (size[a] * size[j]);
                if (!alive[j]) {
                    v = Double.NEGATIVE_INFINITY;
                }
                row[j] = v;
            }
            row[a] = Double.NEGATIVE_INFINITY;
            for (int j = 0; j < n; j++) {
                m[a][j] = row[j];
                m[j][a] = row[j];
            }
            for (int j = 0; j < n; j++) {
                m[b][j] = Double.NEGATIVE_INFINITY;
                m[j][b] = Double.NEGATIVE_INFINITY;
            }
            bestV[b] = Double.NEGATIVE_INFINITY;
            best[a] = argmax(row);
            bestV[a] = row[best[a]];
            for (int j = 0; j < n; j++) {
                if (alive[j] && (best[j] == a || best[j] == b)) {
                    best[j] = argmax(m[j]);
                    bestV[j] = m[j][best[j]];
                }
            }
            for (int j = 0; j < n; j++) {
                if (alive[j] && j != a && row[j] > bestV[j]) {
                    best[j] = a;
                    bestV[j] = row[j];
                }
            }
        }
        for (int i = 0; i < n; i++) {
            if (alive[i]) {
                List<Integer> mem = members.get(i);
                int[] arr = new int[mem.size()];
                for (int k = 0; k < arr.length; k++) {
                    arr[k] = mem.get(k);
                }
                Arrays.sort(arr);
                result.add(arr);
            }
        }
        return result;
    }

    /**
     * 带布局头的阅读顺序（块优先、块内 XY-cut / 表格按行）（对齐 {@code decode_blocks_order}）。
     *
     * @param a     成对顺序 logit {@code [N][N]}
     * @param same  同块 logit {@code [N][N]}
     * @param boxes 行框 {@code [N][4]}
     * @param cls   块分类 logit {@code [N][10]}
     * @return 阅读顺序
     */
    public static int[] decodeBlocksOrder(double[][] a, double[][] same, float[][] boxes, float[][] cls) {
        int n = a.length;
        int[] identity = new int[n];
        for (int i = 0; i < n; i++) {
            identity[i] = i;
        }
        if (n <= 1) {
            return identity;
        }
        List<int[]> blocks = cluster(same);
        List<int[]> inner = new ArrayList<>(blocks.size());
        for (int[] blk : blocks) {
            float[][] bx = new float[blk.length][4];
            boolean grid = false;
            if (cls != null) {
                double[] sum = new double[BLOCK_CLASSES.length];
                for (int li : blk) {
                    for (int c = 0; c < BLOCK_CLASSES.length && c < cls[li].length; c++) {
                        sum[c] += cls[li][c];
                    }
                }
                int bestC = argmax(sum);
                grid = GRID_LABELS.contains(BLOCK_CLASSES[bestC]);
            }
            for (int k = 0; k < blk.length; k++) {
                System.arraycopy(boxes[blk[k]], 0, bx[k], 0, 4);
            }
            int[] local = grid ? orderTopDown(bx) : orderXyCut(bx);
            int[] mapped = new int[blk.length];
            for (int k = 0; k < local.length; k++) {
                mapped[k] = blk[local[k]];
            }
            inner.add(mapped);
        }
        int m = blocks.size();
        double[][] g = new double[m][n];
        for (int k = 0; k < m; k++) {
            int[] blk = blocks.get(k);
            for (int li : blk) {
                g[k][li] = 1.0 / blk.length;
            }
        }
        double[][] up = new double[m][m];
        for (int p = 0; p < m; p++) {
            for (int q = p + 1; q < m; q++) {
                double s = 0.0;
                int[] bp = blocks.get(p);
                int[] bq = blocks.get(q);
                for (int ip : bp) {
                    for (int iq : bq) {
                        s += g[p][ip] * a[ip][iq] * g[q][iq];
                    }
                }
                up[p][q] = s;
            }
        }
        double[][] ab = new double[m][m];
        for (int p = 0; p < m; p++) {
            for (int q = 0; q < m; q++) {
                ab[p][q] = up[p][q] - up[q][p];
            }
        }
        int[] blockOrder = decodeOrder(ab, 4);
        int[] out = new int[n];
        int idx = 0;
        for (int k : blockOrder) {
            for (int v : inner.get(k)) {
                out[idx++] = v;
            }
        }
        return out;
    }

    /**
     * 行自上而下（按行高一半归同排）再自左而右（对齐 {@code order_top_down}）。
     *
     * @param boxes 行框 {@code [M][4]}
     * @return 局部索引顺序
     */
    public static int[] orderTopDown(float[][] boxes) {
        int n = boxes.length;
        int[] out = new int[n];
        if (n == 0) {
            return out;
        }
        double[] cy = new double[n];
        double[] hh = new double[n];
        for (int i = 0; i < n; i++) {
            cy[i] = (boxes[i][1] + boxes[i][3]) / 2.0;
            hh[i] = boxes[i][3] - boxes[i][1];
        }
        double h = median(hh);
        double step = Math.max(h * 0.5, 1e-3);
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (x, y) -> {
            long rx = (long) Math.rint(cy[x] / step);
            long ry = (long) Math.rint(cy[y] / step);
            int c = Long.compare(rx, ry);
            return c != 0 ? c : Float.compare(boxes[x][0], boxes[y][0]);
        });
        for (int i = 0; i < n; i++) {
            out[i] = idx[i];
        }
        return out;
    }

    /**
     * 递归 XY-cut 阅读顺序（对齐 {@code order_xycut}）。
     *
     * @param boxes 行框 {@code [M][4]}
     * @return 局部索引顺序
     */
    public static int[] orderXyCut(float[][] boxes) {
        int n = boxes.length;
        List<Integer> out = new ArrayList<>(n);
        int[] ids = new int[n];
        for (int i = 0; i < n; i++) {
            ids[i] = i;
        }
        recXyCut(ids, boxes, out);
        int[] res = new int[out.size()];
        for (int i = 0; i < res.length; i++) {
            res[i] = out.get(i);
        }
        return res;
    }

    /**
     * rec Xy Cut
     * @param boxes 参数
     * @param ids 参数
     * @param out 参数
     * @return 返回值
    */
    private static void recXyCut(int[] ids, float[][] boxes, List<Integer> out) {
        if (ids.length <= 1) {
            for (int id : ids) {
                out.add(id);
            }
            return;
        }
        Cut gy = bestCut(ids, boxes, 1);
        Cut gx = bestCut(ids, boxes, 0);
        if (gy.k < 0 && gx.k < 0) {
            int[] local = orderTopDown(subset(ids, boxes));
            for (int li : local) {
                out.add(ids[li]);
            }
            return;
        }
        Cut c = gy.gap >= gx.gap ? gy : gx;
        int[] left = new int[c.k];
        int[] right = new int[ids.length - c.k];
        for (int t = 0; t < c.k; t++) {
            left[t] = ids[c.o[t]];
        }
        for (int t = c.k; t < ids.length; t++) {
            right[t - c.k] = ids[c.o[t]];
        }
        recXyCut(left, boxes, out);
        recXyCut(right, boxes, out);
    }

    /**
     * best Cut
     * @param axis 整数
     * @param boxes 参数
     * @param ids 参数
     * @return 返回值
    */
    private static Cut bestCut(int[] ids, float[][] boxes, int axis) {
        int m = ids.length;
        double[] lo = new double[m];
        double[] hi = new double[m];
        for (int i = 0; i < m; i++) {
            float[] b = boxes[ids[i]];
            if (axis == 1) {
                lo[i] = b[1];
                hi[i] = b[3];
            } else {
                lo[i] = b[0];
                hi[i] = b[2];
            }
        }
        Integer[] o = new Integer[m];
        for (int i = 0; i < m; i++) {
            o[i] = i;
        }
        Arrays.sort(o, (x, y) -> {
            int c = Double.compare(lo[x], lo[y]);
            return c != 0 ? c : Integer.compare(x, y);
        });
        int[] ord = new int[m];
        for (int i = 0; i < m; i++) {
            ord[i] = o[i];
        }
        double end = hi[ord[0]];
        double bestGap = 0.0;
        int bestK = -1;
        for (int k = 1; k < m; k++) {
            double gap = lo[ord[k]] - end;
            if (gap > bestGap) {
                bestGap = gap;
                bestK = k;
            }
            end = Math.max(end, hi[ord[k]]);
        }
        return new Cut(bestGap, bestK, ord);
    }

    /**
     * 将阅读顺序切成块：相邻行“同块” logit 为正即同块（对齐 {@code split_blocks}）。
     *
     * @param order 阅读顺序
     * @param same  同块 logit {@code [N][N]}
     * @return 块列表（每块为按阅读顺序排列的行索引）
     */
    public static List<int[]> splitBlocks(int[] order, double[][] same) {
        List<List<Integer>> blocks = new ArrayList<>();
        for (int i : order) {
            if (!blocks.isEmpty()) {
                List<Integer> last = blocks.get(blocks.size() - 1);
                int prev = last.get(last.size() - 1);
                if (same[prev][i] > 0) {
                    last.add(i);
                    continue;
                }
            }
            List<Integer> nb = new ArrayList<>();
            nb.add(i);
            blocks.add(nb);
        }
        List<int[]> out = new ArrayList<>(blocks.size());
        for (List<Integer> b : blocks) {
            int[] arr = new int[b.size()];
            for (int k = 0; k < arr.length; k++) {
                arr[k] = b.get(k);
            }
            out.add(arr);
        }
        return out;
    }

    // ------------------------------------------------------------------ 工具

    /** 一段切分结果。 */
    private static final class Cut {
        final double gap;
        final int k;
        final int[] o;

        /**
         * Cut
         * @param gap 双精度数
         * @param k 整数
         * @param o 参数
         * @return 返回值
        */
        Cut(double gap, int k, int[] o) {
            this.gap = gap;
            this.k = k;
            this.o = o;
        }
    }

    /**
     * subset
     * @param boxes 参数
     * @param ids 参数
     * @return 返回值
    */
    private static float[][] subset(int[] ids, float[][] boxes) {
        float[][] out = new float[ids.length][4];
        for (int i = 0; i < ids.length; i++) {
            System.arraycopy(boxes[ids[i]], 0, out[i], 0, 4);
        }
        return out;
    }

    /** {@code log(sigmoid(v))}，数值稳定。 */
    static double logSigmoid(double v) {
        if (v >= 0) {
            return -Math.log1p(Math.exp(-v));
        }
        return v - Math.log1p(Math.exp(v));
    }

    /**
     * argmax
     * @param arr 参数
     * @return 返回值
    */
    private static int argmax(double[] arr) {
        int best = 0;
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > arr[best]) {
                best = i;
            }
        }
        return best;
    }

    static double median(double[] values) {
        if (values.length == 0) {
            return 0.0;
        }
        double[] copy = values.clone();
        Arrays.sort(copy);
        int n = copy.length;
        if ((n & 1) == 1) {
            return copy[n / 2];
        }
        return (copy[n / 2 - 1] + copy[n / 2]) / 2.0;
    }

    /**
     * display Len
     * @param t 字符串
     * @return 返回值
    */
    private static int displayLen(String t) {
        int len = 0;
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            len += isEastAsianWide(cp) ? 2 : 1;
            i += Character.charCount(cp);
        }
        return len;
    }

    /**
     * is East Asian Wide
     * @param cp 整数
     * @return 返回值
    */
    private static boolean isEastAsianWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x2E80 && cp <= 0xA4CF && cp != 0x303F)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE10 && cp <= 0xFE19)
                || (cp >= 0xFE30 && cp <= 0xFE6F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x1F300 && cp <= 0x1F64F)
                || (cp >= 0x1F900 && cp <= 0x1F9FF)
                || (cp >= 0x20000 && cp <= 0x3FFFD);
    }

    /**
     * is All Digits
     * @param s 字符串
     * @return 返回值
    */
    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    /**
     * rstrip
     * @param chars 字符串
     * @param s 字符串
     * @return 返回值
    */
    private static String rstrip(String s, String chars) {
        int end = s.length();
        while (end > 0 && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(0, end);
    }
}
