package com.chua.deeplearning.support.onnx.ocr.layout.indra;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * indra 版面 Markdown 渲染，忠实复刻 {@code indra_ocr/layout_post.py} 的 {@code page_markdown}。
 *
 * <p>按块类别渲染：</p>
 * <ul>
 *   <li>{@code Section-Header} → {@code ## } + 段落文字；</li>
 *   <li>{@code Page-Header}/{@code Page-Footer} → 默认丢弃（{@code keepFurniture} 时保留）；</li>
 *   <li>{@code Table} → HTML 表格：优先用 OrderNet 的 {@code tab} 头（行列合并），否则按几何行列装配；</li>
 *   <li>{@code List-Group} → 每项一行；</li>
 *   <li>其余 → 段落（`-` 断词合并、CJK 之间不加空格）。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class IndraMarkdown {

    private static final int HEADER = 2;
    private static final int FOOTER = 3;
    private static final int SECTION = 1;
    private static final int LIST = 4;
    private static final int TABLE = 5;

    private static final Pattern WHOLE_MATH = Pattern.compile("^\\$[^$]+\\$$");
    private static final Pattern ITEM = Pattern.compile(
            "^\\s*([\\u2022\\-\\u2013\\u25AA\\u25E6*\\u00B7\\u2192]|\\(?\\d{1,3}[.)]|\\(?[a-zA-Z][.)])(\\s|$)");

    private IndraMarkdown() {
    }

    /**
     * 渲染一页 Markdown（对齐 {@code page_markdown}）。
     *
     * @param boxes         行框 {@code [N][4]}
     * @param texts         行文本
     * @param order         阅读顺序
     * @param cls           块分类 logit {@code [N][10]}
     * @param same          同块 logit {@code [N][N]}
     * @param tab           OrderNet 表格头 logit {@code [3][N][N]}（可空）
     * @param keepFurniture 是否保留页眉页脚
     * @return Markdown
     */
    public static String pageMarkdown(float[][] boxes, List<String> texts, int[] order,
                                      float[][] cls, double[][] same, double[][][] tab,
                                      boolean keepFurniture) {
        List<String> parts = new ArrayList<>();
        for (int[] blk : IndraOrderPost.splitBlocks(order, same)) {
            blockPart(parts, blk, boxes, texts, cls, keepFurniture, tab);
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static void blockPart(List<String> parts, int[] blk, float[][] boxes,
                                  List<String> texts, float[][] cls, boolean keepFurniture,
                                  double[][][] tab) {
        int c = classOf(blk, cls);
        List<String> textsB = new ArrayList<>(blk.length);
        for (int i : blk) {
            textsB.add(texts.get(i));
        }
        if ((c == HEADER || c == FOOTER) && !keepFurniture) {
            return;
        }
        boolean allMath = true;
        for (String t : textsB) {
            if (!WHOLE_MATH.matcher(t.strip()).matches()) {
                allMath = false;
                break;
            }
        }
        if (allMath) {
            List<String> eq = new ArrayList<>();
            for (String t : textsB) {
                String s = t.strip();
                eq.add("$$" + s.substring(1, s.length() - 1) + "$$");
            }
            parts.add(String.join("\n", eq));
            return;
        }
        float[][] sub = subBoxes(boxes, blk);
        if (c == SECTION) {
            parts.add("## " + joinLines(textsB.toArray(new String[0])));
        } else if (c == TABLE && blk.length > 1 && tab != null
                && headsPlausible(sub, subTab(tab, blk, 2))) {
            parts.add(tableHtmlHeads(sub, textsB, subTabAll(tab, blk)));
        } else if (c == TABLE && blk.length > 1) {
            parts.add(tableHtml(sub, textsB));
        } else if (c == LIST) {
            List<List<String>> items = new ArrayList<>();
            for (String t : textsB) {
                if (items.isEmpty() || ITEM.matcher(t).lookingAt()) {
                    List<String> it = new ArrayList<>();
                    it.add(t);
                    items.add(it);
                } else {
                    items.get(items.size() - 1).add(t);
                }
            }
            List<String> rendered = new ArrayList<>();
            for (List<String> it : items) {
                rendered.add(joinLines(it.toArray(new String[0])));
            }
            parts.add(String.join("\n", rendered));
        } else {
            parts.add(joinLines(textsB.toArray(new String[0])));
        }
    }

    // ------------------------------------------------------------------ 表格（几何装配）

    /**
     * 由单元格行按几何位置装配 HTML 表格（对齐 {@code table_html}）。
     *
     * @param boxes 单元格框 {@code [M][4]}
     * @param texts 单元格文本
     * @return HTML
     */
    public static String tableHtml(float[][] boxes, List<String> texts) {
        List<int[]> rows = rowsOf(boxes);
        double spanX = maxOf(boxes, 2) - minOf(boxes, 0);
        int maxRow = 0;
        for (int[] r : rows) {
            maxRow = Math.max(maxRow, r.length);
        }
        if (rows.size() > 2 && rows.get(0).length == 1 && maxRow > 1) {
            int first = rows.get(0)[0];
            double w = boxes[first][2] - boxes[first][0];
            if (w > 0.6 * spanX) {
                List<float[]> keepBoxes = new ArrayList<>();
                List<String> keepTexts = new ArrayList<>();
                for (int k = 0; k < texts.size(); k++) {
                    if (k != first) {
                        keepBoxes.add(boxes[k]);
                        keepTexts.add(texts.get(k));
                    }
                }
                float[][] kb = keepBoxes.toArray(new float[0][]);
                return texts.get(first) + "\n\n" + tableHtml(kb, keepTexts);
            }
        }
        int width = Math.max(1, maxRow);
        List<int[]> full = new ArrayList<>();
        for (int[] r : rows) {
            if (r.length == width) {
                full.add(r);
            }
        }
        rows = mergeWrapped(rows, boxes);
        double[][] bands = new double[width][2];
        for (int c = 0; c < width; c++) {
            double[] x0s = new double[full.size()];
            double[] x1s = new double[full.size()];
            for (int r = 0; r < full.size(); r++) {
                x0s[r] = boxes[full.get(r)[c]][0];
                x1s[r] = boxes[full.get(r)[c]][2];
            }
            bands[c] = new double[]{IndraOrderPost.median(x0s), IndraOrderPost.median(x1s)};
        }
        double[] edges = new double[width + 1];
        edges[0] = Double.NEGATIVE_INFINITY;
        for (int c = 0; c < width - 1; c++) {
            edges[c + 1] = (bands[c][1] + bands[c + 1][0]) / 2.0;
        }
        edges[width] = Double.POSITIVE_INFINITY;

        List<String> out = new ArrayList<>();
        out.add("<table>");
        for (int ri = 0; ri < rows.size(); ri++) {
            int[] r = rows.get(ri);
            String tag = (ri == 0 && rows.size() > 1) ? "th" : "td";
            Integer[] order = new Integer[r.length];
            for (int k = 0; k < r.length; k++) {
                order[k] = r[k];
            }
            Arrays.sort(order, (a, b) -> {
                int cc = Float.compare(boxes[a][1], boxes[b][1]);
                return cc != 0 ? cc : Float.compare(boxes[a][0], boxes[b][0]);
            });
            Map<Integer, int[]> cells = new LinkedHashMap<>(); // col -> {span, idx}
            Map<Integer, String> cellText = new LinkedHashMap<>();
            for (int k : order) {
                double x0 = boxes[k][0];
                double x1 = boxes[k][2];
                int first = searchSortedRight(edges, x0) - 1;
                int last = searchSortedRight(edges, x1) - 1;
                List<Integer> span = new ArrayList<>();
                for (int cc = Math.max(first, 0); cc <= Math.min(last, width - 1); cc++) {
                    if (Math.min(x1, bands[cc][1]) - Math.max(x0, bands[cc][0])
                            > 0.5 * (bands[cc][1] - bands[cc][0])) {
                        span.add(cc);
                    }
                }
                if (span.isEmpty()) {
                    span.add(Math.min(Math.max(first, 0), width - 1));
                }
                int c0 = span.get(0);
                if (!cells.containsKey(c0)) {
                    cells.put(c0, new int[]{span.size()});
                    cellText.put(c0, texts.get(k));
                } else {
                    int prevSpan = cells.get(c0)[0];
                    cells.put(c0, new int[]{Math.max(prevSpan, span.size())});
                    cellText.put(c0, cellText.get(c0) + " " + texts.get(k));
                }
            }
            StringBuilder tr = new StringBuilder("<tr>");
            int cc = 0;
            while (cc < width) {
                if (cells.containsKey(cc)) {
                    int n = cells.get(cc)[0];
                    String attr = n > 1 ? " colspan=\"" + n + "\"" : "";
                    tr.append('<').append(tag).append(attr).append('>')
                            .append(escapeHtml(cellText.get(cc)))
                            .append("</").append(tag).append('>');
                    cc += n;
                } else {
                    tr.append('<').append(tag).append('>').append("</").append(tag).append('>');
                    cc += 1;
                }
            }
            tr.append("</tr>");
            out.add(tr.toString());
        }
        out.add("</table>");
        return String.join("\n", out);
    }

    /** 单元格行分组（按垂直中心），行内自左而右（对齐 {@code _rows}）。 */
    static List<int[]> rowsOf(float[][] boxes) {
        int n = boxes.length;
        List<int[]> rows = new ArrayList<>();
        if (n == 0) {
            return rows;
        }
        double[] cy = new double[n];
        double[] hh = new double[n];
        for (int i = 0; i < n; i++) {
            cy[i] = (boxes[i][1] + boxes[i][3]) / 2.0;
            hh[i] = boxes[i][3] - boxes[i][1];
        }
        double h = IndraOrderPost.median(hh);
        List<List<Integer>> groups = new ArrayList<>();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            int cc = Double.compare(cy[a], cy[b]);
            return cc != 0 ? cc : Integer.compare(a, b);
        });
        for (int i : order) {
            boolean joined = false;
            if (!groups.isEmpty()) {
                List<Integer> last = groups.get(groups.size() - 1);
                double mean = 0;
                for (int k : last) {
                    mean += cy[k];
                }
                mean /= last.size();
                if (Math.abs(cy[i] - mean) < 0.5 * h) {
                    last.add(i);
                    joined = true;
                }
            }
            if (!joined) {
                List<Integer> g = new ArrayList<>();
                g.add(i);
                groups.add(g);
            }
        }
        for (List<Integer> g : groups) {
            Integer[] row = g.toArray(new Integer[0]);
            Arrays.sort(row, (a, b) -> Float.compare(boxes[a][0], boxes[b][0]));
            int[] ri = new int[row.length];
            for (int k = 0; k < row.length; k++) {
                ri[k] = row[k];
            }
            rows.add(ri);
        }
        return rows;
    }

    /** 合并被换行拆开的单元格行（对齐 {@code _merge_wrapped}）。 */
    static List<int[]> mergeWrapped(List<int[]> rows, float[][] boxes) {
        if (rows.size() < 3) {
            return rows;
        }
        double[] hh = new double[boxes.length];
        for (int i = 0; i < boxes.length; i++) {
            hh[i] = boxes[i][3] - boxes[i][1];
        }
        double h = IndraOrderPost.median(hh);
        double[] gaps = new double[rows.size() - 1];
        for (int i = 0; i < rows.size() - 1; i++) {
            int[] cur = rows.get(i);
            int[] nxt = rows.get(i + 1);
            double minNextY0 = Double.MAX_VALUE;
            for (int k : nxt) {
                minNextY0 = Math.min(minNextY0, boxes[k][1]);
            }
            double maxCurY1 = -Double.MAX_VALUE;
            for (int k : cur) {
                maxCurY1 = Math.max(maxCurY1, boxes[k][3]);
            }
            gaps[i] = minNextY0 - maxCurY1;
        }
        double med = IndraOrderPost.median(gaps);
        List<int[]> out = new ArrayList<>();
        out.add(rows.get(0).clone());
        int[] prev = rows.get(0);
        for (int i = 0; i < gaps.length; i++) {
            int[] r = rows.get(i + 1);
            if (gaps[i] < 0.5 * med && gaps[i] < 0.6 * h && r.length < prev.length) {
                int[] lastRow = out.get(out.size() - 1);
                int[] merged = new int[lastRow.length + r.length];
                System.arraycopy(lastRow, 0, merged, 0, lastRow.length);
                System.arraycopy(r, 0, merged, lastRow.length, r.length);
                out.set(out.size() - 1, merged);
            } else {
                out.add(r.clone());
            }
            prev = r;
        }
        return out;
    }

    // ------------------------------------------------------------------ 表格（tab 头装配）

    /**
     * tab 头是否可信：单元格内文本行应横向堆叠；并排的行超过 10% 则判失败（对齐 {@code heads_plausible}）。
     *
     * @param boxes 块内单元框
     * @param cell  same-cell logit {@code [M][M]}
     * @return 是否可信
     */
    static boolean headsPlausible(float[][] boxes, double[][] cell) {
        List<int[]> cells = IndraOrderPost.cluster(cell);
        int bad = 0;
        for (int[] c : cells) {
            if (c.length < 2) {
                continue;
            }
            boolean overlap = false;
            for (int a = 0; a < c.length && !overlap; a++) {
                for (int b = 0; b < c.length; b++) {
                    if (a == b) {
                        continue;
                    }
                    double minOverlap = Math.min(boxes[c[a]][2], boxes[c[b]][2])
                            - Math.max(boxes[c[a]][0], boxes[c[b]][0]);
                    if (minOverlap < 0) {
                        overlap = true;
                        break;
                    }
                }
            }
            if (overlap) {
                bad++;
            }
        }
        return bad <= 0.1 * cells.size();
    }

    /**
     * 由 OrderNet 表格头装配 HTML 表格（对齐 {@code table_html_heads}）。
     *
     * @param boxes 块内单元框 {@code [M][4]}
     * @param texts 单元文本
     * @param tab   块内 tab {@code [3][M][M]}（行/列/单元格 logit）
     * @return HTML
     */
    public static String tableHtmlHeads(float[][] boxes, List<String> texts, double[][][] tab) {
        List<int[]> cells = IndraOrderPost.cluster(tab[2]);
        int m = cells.size();
        double[][] cb = new double[m][4];
        String[] ctext = new String[m];
        for (int i = 0; i < m; i++) {
            int[] c = cells.get(i);
            double x0 = Double.MAX_VALUE;
            double y0 = Double.MAX_VALUE;
            double x1 = -Double.MAX_VALUE;
            double y1 = -Double.MAX_VALUE;
            for (int k : c) {
                x0 = Math.min(x0, boxes[k][0]);
                y0 = Math.min(y0, boxes[k][1]);
                x1 = Math.max(x1, boxes[k][2]);
                y1 = Math.max(y1, boxes[k][3]);
            }
            cb[i] = new double[]{x0, y0, x1, y1};
            Integer[] ord = new Integer[c.length];
            for (int k = 0; k < c.length; k++) {
                ord[k] = c[k];
            }
            Arrays.sort(ord, (a, b) -> {
                int cc = Float.compare(boxes[a][1], boxes[b][1]);
                return cc != 0 ? cc : Float.compare(boxes[a][0], boxes[b][0]);
            });
            StringBuilder sb = new StringBuilder();
            for (int k : ord) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(texts.get(k));
            }
            ctext[i] = sb.toString();
        }

        double[][] rowLogits = cellLogits(tab[0], cells, m);
        double[][] colLogits = cellLogits(tab[1], cells, m);
        List<int[]> rows = sortBy(IndraOrderPost.cluster(rowLogits), cb, 1);
        List<int[]> cols = sortBy(IndraOrderPost.cluster(colLogits), cb, 0);

        int[] rowOf = new int[m];
        int[] colOf = new int[m];
        for (int r = 0; r < rows.size(); r++) {
            for (int c : rows.get(r)) {
                rowOf[c] = r;
            }
        }
        for (int k = 0; k < cols.size(); k++) {
            for (int c : cols.get(k)) {
                colOf[c] = k;
            }
        }
        double[] centers = new double[cols.size()];
        for (int k = 0; k < cols.size(); k++) {
            int[] g = cols.get(k);
            double[] v = new double[g.length];
            for (int i = 0; i < g.length; i++) {
                v[i] = (cb[g[i]][0] + cb[g[i]][2]) / 2.0;
            }
            centers[k] = IndraOrderPost.median(v);
        }
        double[] hh = new double[boxes.length];
        for (int i = 0; i < boxes.length; i++) {
            hh[i] = boxes[i][3] - boxes[i][1];
        }
        double h = IndraOrderPost.median(hh);

        Map<Long, int[]> gridSpan = new LinkedHashMap<>();
        Map<Long, String> gridText = new LinkedHashMap<>();
        for (int c = 0; c < m; c++) {
            int c0 = colOf[c];
            int span = 1;
            while (c0 + span < cols.size()
                    && cb[c][0] - h <= centers[c0 + span]
                    && centers[c0 + span] <= cb[c][2] + h) {
                span++;
            }
            long key = (((long) rowOf[c]) << 32) | (c0 & 0xffffffffL);
            if (gridSpan.containsKey(key)) {
                gridSpan.put(key, new int[]{Math.max(gridSpan.get(key)[0], span)});
                gridText.put(key, gridText.get(key) + " " + ctext[c]);
            } else {
                gridSpan.put(key, new int[]{span});
                gridText.put(key, ctext[c]);
            }
        }
        List<String> out = new ArrayList<>();
        out.add("<table>");
        for (int r = 0; r < rows.size(); r++) {
            String tag = (r == 0 && rows.size() > 1) ? "th" : "td";
            StringBuilder tr = new StringBuilder("<tr>");
            int c = 0;
            while (c < cols.size()) {
                long key = (((long) r) << 32) | (c & 0xffffffffL);
                if (gridSpan.containsKey(key)) {
                    int span = gridSpan.get(key)[0];
                    String attr = span > 1 ? " colspan=\"" + span + "\"" : "";
                    tr.append('<').append(tag).append(attr).append('>')
                            .append(escapeHtml(gridText.get(key)))
                            .append("</").append(tag).append('>');
                    c += span;
                } else {
                    tr.append('<').append(tag).append('>').append("</").append(tag).append('>');
                    c += 1;
                }
            }
            tr.append("</tr>");
            out.add(tr.toString());
        }
        out.add("</table>");
        return String.join("\n", out);
    }

    private static double[][] cellLogits(double[][] logit, List<int[]> cells, int m) {
        double[][] out = new double[m][m];
        for (int a = 0; a < m; a++) {
            for (int b = a + 1; b < m; b++) {
                double sum = 0;
                int cnt = 0;
                for (int i : cells.get(a)) {
                    for (int j : cells.get(b)) {
                        sum += logit[i][j];
                        cnt++;
                    }
                }
                double v = cnt > 0 ? sum / cnt : 0;
                out[a][b] = v;
                out[b][a] = v;
            }
        }
        return out;
    }

    private static List<int[]> sortBy(List<int[]> groups, double[][] cb, int axis) {
        List<int[]> list = new ArrayList<>(groups);
        list.sort((g1, g2) -> {
            double m1 = Double.MAX_VALUE;
            double m2 = Double.MAX_VALUE;
            for (int i : g1) {
                m1 = Math.min(m1, cb[i][axis]);
            }
            for (int i : g2) {
                m2 = Math.min(m2, cb[i][axis]);
            }
            return Double.compare(m1, m2);
        });
        return list;
    }

    // ------------------------------------------------------------------ 工具

    private static int classOf(int[] blk, float[][] cls) {
        double[] sum = new double[IndraOrderPost.BLOCK_CLASSES.length];
        for (int li : blk) {
            for (int c = 0; c < sum.length && c < cls[li].length; c++) {
                sum[c] += cls[li][c];
            }
        }
        int best = 0;
        for (int c = 1; c < sum.length; c++) {
            if (sum[c] > sum[best]) {
                best = c;
            }
        }
        return best;
    }

    private static float[][] subBoxes(float[][] boxes, int[] blk) {
        float[][] out = new float[blk.length][4];
        for (int i = 0; i < blk.length; i++) {
            System.arraycopy(boxes[blk[i]], 0, out[i], 0, 4);
        }
        return out;
    }

    /** 块内单层 tab 子矩阵 {@code [blk][blk]}。 */
    private static double[][] subTab(double[][][] tab, int[] blk, int k) {
        int n = blk.length;
        double[][] out = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                out[i][j] = tab[k][blk[i]][blk[j]];
            }
        }
        return out;
    }

    /** 块内三层 tab 子矩阵 {@code [3][blk][blk]}。 */
    private static double[][][] subTabAll(double[][][] tab, int[] blk) {
        return new double[][][]{subTab(tab, blk, 0), subTab(tab, blk, 1), subTab(tab, blk, 2)};
    }

    private static double minOf(float[][] boxes, int axis) {
        double v = Double.MAX_VALUE;
        for (float[] b : boxes) {
            v = Math.min(v, b[axis]);
        }
        return v;
    }

    private static double maxOf(float[][] boxes, int axis) {
        double v = -Double.MAX_VALUE;
        for (float[] b : boxes) {
            v = Math.max(v, b[axis]);
        }
        return v;
    }

    private static int searchSortedRight(double[] arr, double v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] > v) {
                return i;
            }
        }
        return arr.length;
    }

    /** HTML 转义（仅 {@code &},{@code <},{@code >}；对齐 {@code html.escape(..., quote=False)}）。 */
    static String escapeHtml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 段落拼接：{@code -} 断词合并、CJK 之间不空格（对齐 {@code join_lines}）。
     *
     * @param lines 行文本
     * @return 段落
     */
    public static String joinLines(String[] lines) {
        StringBuilder out = new StringBuilder();
        for (String raw : lines) {
            String t = raw == null ? "" : raw.strip();
            if (t.isEmpty()) {
                continue;
            }
            if (out.length() > 1 && out.charAt(out.length() - 1) == '-'
                    && Character.isLetter(out.charAt(out.length() - 2))
                    && Character.isLetter(t.charAt(0)) && Character.isLowerCase(t.charAt(0))) {
                out.setLength(out.length() - 1);
                out.append(t);
            } else if (out.length() > 0 && isCjk(out.codePointBefore(out.length()))
                    && isCjk(t.codePointAt(0))) {
                out.append(t);
            } else {
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(t);
            }
        }
        return out.toString();
    }

    /** 判断是否为无空格书写的 CJK（Han/假名/CJK 标点/全角）。 */
    static boolean isCjk(int cp) {
        return (cp >= 0x3000 && cp <= 0x30FF)
                || (cp >= 0x3400 && cp <= 0x9FFF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFF00 && cp <= 0xFFEF)
                || (cp >= 0x20000 && cp <= 0x2FA1F);
    }
}
