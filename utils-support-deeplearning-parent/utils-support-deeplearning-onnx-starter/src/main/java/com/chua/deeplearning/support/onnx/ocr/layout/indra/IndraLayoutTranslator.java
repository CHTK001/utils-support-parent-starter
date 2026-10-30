package com.chua.deeplearning.support.onnx.ocr.layout.indra;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.translator.ITranslator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * datalab-to/indra 文档版面分析 ONNX 翻译器：图像 → 版面块（10 类 chandra 块标签）。
 *
 * <p>indra 的“版面”并不是“图像 → 区域”的单模型检测器，而是 <b>det + rec + order 三段式</b>：</p>
 * <ol>
 *   <li>{@code det.onnx}（LineNet）：灰度页 → 行墨迹框（{@link IndraLineNet}）；</li>
 *   <li>{@code rec.onnx}（CTC 识别器）：逐行裁剪 → 文本（供 order 的文本特征，同时给出块内文字）；</li>
 *   <li>{@code order.onnx}（OrderNet）：行特征 → 10 类块分类 logit + 同块 logit + 成对顺序 logit + 表格头 logit；</li>
 *   <li>{@code script.onnx} + {@code rec_<分组>.onnx}（可选）：语种分类器把每行路由到对应分组识别器
 *       （当前内嵌 CJK），分类不确定的行由基础识别器与分组识别器都读、取置信度高者；</li>
 *   <li>后处理：平均链接聚类分块、块优先阅读顺序、块标签取整块分类 argmax（{@link IndraOrderPost}）；
 *       Markdown 渲染含 HTML 表格（{@link IndraMarkdown}）。</li>
 * </ol>
 *
 * <p>因此本实现可直接被项目既有管线消费：</p>
 * <ul>
 *   <li>{@code LayoutPipeline.builder().model("indra-layout").build().recognizeSingle(bytes)} —— 返回版面块列表；</li>
 *   <li>{@code ImageDetector.create("indra-layout").detect(bytes)} / {@code LayoutDetector} —— 返回各块标签+外接框。</li>
 * </ul>
 *
 * <p>示例（一页文档 → 版面块）：</p>
 * <pre>{@code
 * List<DetectionInfo> blocks = ImageDetector.create("indra-layout").detect(pngBytes);
 * for (DetectionInfo b : blocks) {
 *     // label 例如 "Section-Header" / "Text" / "Table" / "List-Group" ...
 *     System.out.println(b.label() + " " + b.x() + "," + b.y() + " " + b.width() + "x" + b.height());
 * }
 * }</pre>
 *
 * <p>权重来源（优先级）：类路径内嵌 jar（{@code utils-support-models-onnx-indra-layout}）→ 注入路径 →
 * 模型注册表。核心四份文件 {@code det.onnx / rec.onnx / order.onnx / meta.json} 需在同一目录；
 * 同目录若还有 {@code script.onnx} 与 {@code rec_<分组>.onnx}（如 {@code rec_cjk.onnx}），
 * 自动启用多语种路由。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class IndraLayoutTranslator implements ITranslator<byte[], List<DetectionInfo>> {

    /** 内嵌资源目录前缀（models jar 内）。 */
    private static final String RESOURCE_PREFIX = "vision/layout/indra/";
    /** 模型注册标识（用于注册表兜底路径解析）。 */
    private static final String MODEL_ID = "indra-layout";
    /** 识别批上限，约束内存。 */
    private static final int REC_BATCH = 64;
    /** order 局部插入搜索轮数。 */
    private static final int ORDER_SWEEPS = 4;
    /** 行识别置信度下限，低于该值不出文字（对齐 {@code MIN_CONF}）。 */
    private static final double MIN_CONF = 0.5;
    /** 脚本分类器可信阈值，低于该值基础识别器也读一遍（对齐 {@code ROUTE_SURE}）。 */
    private static final double ROUTE_SURE = 0.95;
    /** 触发二次检测的最小行数（对齐 {@code len(boxes) >= 8}）。 */
    private static final int ADAPTIVE_MIN_LINES = 8;
    /** 行高中位数低于该值（输入像素）即触发二次检测（对齐 {@code ADAPTIVE_MIN_H}）。 */
    private static final double ADAPTIVE_MIN_H = 9.0;
    /** 二次检测的目标行高（输入像素，对齐 {@code ADAPTIVE_TARGET_H}）。 */
    private static final double ADAPTIVE_TARGET_H = 12.0;
    /** 二次检测的长边上限（对齐 {@code ADAPTIVE_MAX_SIDE}）。 */
    private static final int ADAPTIVE_MAX_SIDE = 2048;

    private String modelPath;
    private OrtEnvironment env;
    private OrtSession detSession;
    private OrtSession recSession;
    private OrtSession orderSession;
    private OrtSession scriptSession;
    private List<String> vocab;
    private int detLongSide = 1024;
    private int recWindow = 1;
    /** script.onnx 类别顺序（{@code meta.script_groups}）。 */
    private List<String> scriptGroups = List.of("base", "cjk", "rtl", "world");
    /** meta 中声明的分组词表（{@code vocab_cjk} 等），按分组名索引。 */
    private final Map<String, List<String>> groupVocabDefs = new LinkedHashMap<>();
    /** 已装载的分组识别器及其词表。 */
    private final Map<String, OrtSession> groupSessions = new LinkedHashMap<>();
    private final Map<String, List<String>> groupVocabs = new LinkedHashMap<>();
    private volatile boolean prepared = false;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * 设置模型目录/文件路径（模型注册表在 SPI 实例化后注入）。
     *
     * @param modelPath 模型路径（det.onnx 所在目录或文件）
     */
    public void setModelPath(String modelPath) {
        this.modelPath = modelPath;
    }

    @Override
    public String name() {
        return MODEL_ID;
    }

    /**
     * 版面块（含块内文字），供需要文本的调用方直接使用。
     *
     * @param label      块类别（{@link IndraOrderPost#BLOCK_CLASSES}）
     * @param x          外接框左上角 x
     * @param y          外接框左上角 y
     * @param width      外接框宽
     * @param height     外接框高
     * @param confidence 置信度（整块分类 softmax 概率）
     * @param text       块内文字（按阅读顺序换行连接）
     */
    public record Block(String label, float x, float y, float width, float height,
                        float confidence, String text) {
    }

    @Override
    public List<DetectionInfo> translate(byte[] imageData) {
        List<DetectionInfo> out = new ArrayList<>();
        for (Block b : blocks(imageData)) {
            out.add(new DetectionInfo(b.label(), b.confidence(), b.x(), b.y(), b.width(), b.height()));
        }
        return out;
    }

    /**
     * 分析一页文档，返回版面块（含文字）。
     *
     * @param imageData 文档图像字节
     * @return 版面块（阅读顺序）
     */
    public List<Block> blocks(byte[] imageData) {
        Analysis a = analyze(imageData);
        if (a == null) {
            return Collections.emptyList();
        }
        List<Block> result = new ArrayList<>(a.blocks.size());
        for (int[] blk : a.blocks) {
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            double[] sum = new double[IndraOrderPost.BLOCK_CLASSES.length];
            StringBuilder text = new StringBuilder();
            for (int li : blk) {
                minX = Math.min(minX, a.box[li][0]);
                minY = Math.min(minY, a.box[li][1]);
                maxX = Math.max(maxX, a.box[li][2]);
                maxY = Math.max(maxY, a.box[li][3]);
                for (int c = 0; c < sum.length && c < a.cls[li].length; c++) {
                    sum[c] += a.cls[li][c];
                }
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(a.texts.get(li));
            }
            int bestC = 0;
            for (int c = 1; c < sum.length; c++) {
                if (sum[c] > sum[bestC]) {
                    bestC = c;
                }
            }
            result.add(new Block(IndraOrderPost.BLOCK_CLASSES[bestC],
                    (float) minX, (float) minY,
                    (float) (maxX - minX), (float) (maxY - minY),
                    softmaxMax(sum, bestC), text.toString()));
        }
        return result;
    }

    /**
     * 分析一页文档，返回 Markdown（Section-Header → {@code ## }，表格 → HTML table，
     * 页眉页脚丢弃，列表/段落按行合并）。
     *
     * @param imageData 文档图像字节
     * @return Markdown
     */
    public String markdown(byte[] imageData) {
        Analysis a = analyze(imageData);
        if (a == null) {
            return "";
        }
        return IndraMarkdown.pageMarkdown(a.box, a.texts, a.order, a.cls, a.same, a.tab, false);
    }

    /**
     * 分析一页文档，返回 Markdown（含文章/表格/列表等完整渲染）。
     *
     * @param imageData     文档图像字节
     * @param keepFurniture 是否保留页眉页脚
     * @return Markdown
     */
    public String markdown(byte[] imageData, boolean keepFurniture) {
        Analysis a = analyze(imageData);
        if (a == null) {
            return "";
        }
        return IndraMarkdown.pageMarkdown(a.box, a.texts, a.order, a.cls, a.same, a.tab, keepFurniture);
    }

    // ------------------------------------------------------------------ 流水线

    /** 一次分析的中间结果。 */
    private static final class Analysis {
        float[][] box;
        List<String> texts;
        int[] order;
        float[][] cls;
        double[][] same;
        double[][][] tab;
        List<int[]> blocks;
    }

    /**
     * analyze
     * @param imageData 参数
     * @return 返回值
    */
    private Analysis analyze(byte[] imageData) {
        if (imageData == null || imageData.length == 0) {
            return null;
        }
        lock.lock();
        try {
            ensurePrepared();
            return doAnalyze(imageData);
        } catch (IOException e) {
            throw new IllegalStateException("indra 版面分析失败: " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * do Analyze
     * @param imageData 参数
     * @return 返回值
     * @throws IOException 异常
    */
    private Analysis doAnalyze(byte[] imageData) throws IOException {
        byte[][] gray = IndraImageOps.toGray(imageData);
        int pageH = gray.length;
        int pageW = pageH == 0 ? 0 : gray[0].length;
        if (pageW == 0) {
            return null;
        }
        List<float[]> boxes = detectLines(gray);
        if (boxes.isEmpty()) {
            return null;
        }
        List<String> texts = recognizeLines(gray, boxes);
        float[][] boxArr = new float[boxes.size()][4];
        for (int i = 0; i < boxes.size(); i++) {
            System.arraycopy(boxes.get(i), 0, boxArr[i], 0, 4);
        }
        Analysis a = new Analysis();
        a.box = boxArr;
        a.texts = texts;
        try (OrderOut out = runOrder(IndraOrderPost.lineFeatures(boxArr, pageW, pageH, texts), boxArr.length)) {
            a.order = IndraOrderPost.decodeBlocksOrder(out.a, out.same, boxArr, out.cls);
            a.cls = out.cls;
            a.same = out.same;
            a.tab = out.tab;
        }
        a.blocks = IndraOrderPost.splitBlocks(a.order, a.same);
        return a;
    }

    /**
     * 行检测（对齐 {@code detect}）：常规长边一次检测；若检出 ≥8 行且行高中位数 低于 {@link #ADAPTIVE_MIN_H} 输入像素（小字密排页），按目标行高把长边抬到 {@link #ADAPTIVE_TARGET_H} 对应的尺度再检测一次（上限 {@link #ADAPTIVE_MAX_SIDE}）。
     * @param gray 灰度页
     * @return 行框（原图像素坐标）
     * @throws IOException 异常
    */
    private List<float[]> detectLines(byte[][] gray) throws IOException {
        List<float[]> boxes = runDet(IndraLineNet.pageToInput(gray, detLongSide));
        if (boxes.size() >= ADAPTIVE_MIN_LINES) {
            double[] heights = new double[boxes.size()];
            for (int i = 0; i < boxes.size(); i++) {
                heights[i] = boxes.get(i)[3] - boxes.get(i)[1];
            }
            double medH = IndraOrderPost.median(heights);
            if (medH < ADAPTIVE_MIN_H) {
                int ls = (int) Math.min(ADAPTIVE_MAX_SIDE,
                        detLongSide * ADAPTIVE_TARGET_H / Math.max(medH, 1.0)) / 32 * 32;
                if (ls > detLongSide) {
                    List<float[]> finer = runDet(IndraLineNet.pageToInput(gray, ls));
                    if (!finer.isEmpty()) {
                        boxes = finer;
                    }
                }
            }
        }
        return boxes;
    }

    /**
     * run Det
     * @param in 参数
     * @return 返回值
     * @throws IOException 异常
    */
    private List<float[]> runDet(IndraLineNet.DetInput in) throws IOException {
        long[] shape = {1, 1, in.h(), in.w()};
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(in.ink()), shape);
             OrtSession.Result res = detSession.run(Collections.singletonMap("x", tensor))) {
            float[][][][] out = (float[][][][]) res.get(0).getValue();
            float[][][] plane = out[0];
            int h = plane[0].length;
            int w = plane[0][0].length;
            return IndraLineNet.decode(flatten(plane[0], h, w), flatten(plane[1], h, w),
                    flatten(plane[2], h, w), h, w, in.scale());
        } catch (ai.onnxruntime.OrtException e) {
            throw new IOException("det.onnx 推理失败: " + e.getMessage(), e);
        }
    }

    /**
     * flatten
     * @param h 整数
     * @param plane 参数
     * @param w 整数
     * @return 返回值
    */
    private static float[] flatten(float[][] plane, int h, int w) {
        float[] out = new float[h * w];
        for (int y = 0; y < h; y++) {
            System.arraycopy(plane[y], 0, out, y * w, w);
        }
        return out;
    }

    /**
     * 逐行识别（对齐 {@code recognize}）：{@code script.onnx} 为每行判定脚本分组 （{@code cjk / rtl / world …}），分组识别器读单窗口裁剪，基础识别器读多窗口裁剪； 分类器不确定的行由两者都读、取置信度高者；置信度低于 {@link #MIN_CONF} 的行不出文字 （其行框仍参与版面与阅读顺序）。
     * @param boxes 行框
     * @param gray 灰度页
     * @return 每行文本
     * @throws IOException 异常
    */
    private List<String> recognizeLines(byte[][] gray, List<float[]> boxes) throws IOException {
        int n = boxes.size();
        List<float[][]> band = new ArrayList<>(n);
        for (float[] b : boxes) {
            band.add(IndraRecPreprocess.cropLine(gray, b, 1));
        }
        List<float[][]> tall = band;
        if (recWindow > 1) {
            tall = new ArrayList<>(n);
            for (float[] b : boxes) {
                tall.add(IndraRecPreprocess.cropLine(gray, b, recWindow));
            }
        }
        String[] names = new String[n];
        boolean[] sure = new boolean[n];
        java.util.Arrays.fill(names, "base");
        java.util.Arrays.fill(sure, true);
        if (scriptSession != null && n > 0) {
            classifyScript(band, names, sure);
        }
        List<Integer> baseSel = new ArrayList<>();
        Map<String, List<Integer>> groupSel = new LinkedHashMap<>();
        for (String g : groupSessions.keySet()) {
            groupSel.put(g, new ArrayList<>());
        }
        for (int j = 0; j < n; j++) {
            List<Integer> sel = groupSel.get(names[j]);
            if (sel != null) {
                sel.add(j);
            }
            if (sel == null || !sure[j]) {
                baseSel.add(j);
            }
        }
        double[] bestConf = new double[n];
        java.util.Arrays.fill(bestConf, -1.0);
        String[] bestText = new String[n];
        java.util.Arrays.fill(bestText, "");
        runRecGroup(recSession, vocab, tall, baseSel, bestConf, bestText);
        for (Map.Entry<String, List<Integer>> e : groupSel.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            runRecGroup(groupSessions.get(e.getKey()), groupVocabs.get(e.getKey()),
                    band, e.getValue(), bestConf, bestText);
        }
        return java.util.Arrays.asList(bestText);
    }

    /**
     * 用 {@code script.onnx} 判定每行脚本分组（对齐 {@code line_groups}）。
     * @param band 单窗口行裁剪
     * @param names 输出：每行分组名
     * @param sure 输出：分类器是否可信
     * @return 返回值
     * @throws IOException 异常
    */
    private void classifyScript(List<float[][]> band, String[] names, boolean[] sure) throws IOException {
        for (int start = 0; start < band.size(); start += REC_BATCH) {
            int end = Math.min(band.size(), start + REC_BATCH);
            IndraRecPreprocess.RecBatch batch = IndraRecPreprocess.padBatch(band.subList(start, end));
            int b = batch.widths().length;
            long[] xShape = {b, 1, batch.h(), batch.wm()};
            long[] wShape = {b};
            try (OnnxTensor x = OnnxTensor.createTensor(env, FloatBuffer.wrap(batch.data()), xShape);
                 OnnxTensor widths = OnnxTensor.createTensor(env, LongBuffer.wrap(batch.widths()), wShape)) {
                Map<String, OnnxTensor> feeds = new LinkedHashMap<>();
                feeds.put("x", x);
                feeds.put("widths", widths);
                try (OrtSession.Result res = scriptSession.run(feeds)) {
                    Object val = res.get(0).getValue();
                    for (int k = 0; k < b; k++) {
                        String g;
                        double pk;
                        if (val instanceof float[][] probs) {
                            float[] row = probs[k];
                            int best = 0;
                            for (int c = 1; c < row.length; c++) {
                                if (row[c] > row[best]) {
                                    best = c;
                                }
                            }
                            g = best < scriptGroups.size() ? scriptGroups.get(best) : "base";
                            pk = row[best];
                        } else {
                            double p = ((float[]) val)[k];
                            g = p > 0.5 ? "cjk" : "base";
                            pk = Math.max(p, 1.0 - p);
                        }
                        int j = start + k;
                        names[j] = groupSessions.containsKey(g) ? g : "base";
                        sure[j] = pk > ROUTE_SURE;
                    }
                }
            } catch (ai.onnxruntime.OrtException e) {
                throw new IOException("script.onnx 推理失败: " + e.getMessage(), e);
            }
        }
    }

    /** 用给定识别器读一批行，按置信度高者写入结果。 */
    private void runRecGroup(OrtSession session, List<String> voc, List<float[][]> crops,
                             List<Integer> sel, double[] bestConf, String[] bestText) throws IOException {
        for (int start = 0; start < sel.size(); start += REC_BATCH) {
            int end = Math.min(sel.size(), start + REC_BATCH);
            List<Integer> part = sel.subList(start, end);
            List<float[][]> lines = new ArrayList<>(part.size());
            for (int j : part) {
                lines.add(crops.get(j));
            }
            IndraRecPreprocess.RecBatch batch = IndraRecPreprocess.padBatch(lines);
            RecOut[] outs = runRec(session, batch, voc);
            for (int k = 0; k < part.size(); k++) {
                int j = part.get(k);
                if (outs[k].conf() <= bestConf[j]) {
                    continue;
                }
                bestConf[j] = outs[k].conf();
                bestText[j] = outs[k].conf() >= MIN_CONF ? outs[k].text() : "";
            }
        }
    }

    /**
     * 单行识别结果。
     *
     * @param text 识别文本
     * @param conf 置信度（0-1）
     */
    private record RecOut(String text, double conf) {
    }

    /**
     * 跑一次识别器并做 CTC 贪婪折叠（对齐 {@code recognize} 的 keep 规则）。
     *
     * @param session 识别会话
     * @param batch   批输入
     * @param voc     词表（索引 k-1 对应 id k，0 为 blank）
     * @return 每行文本与置信度（峰值概率均值）
     */
    private RecOut[] runRec(OrtSession session, IndraRecPreprocess.RecBatch batch, List<String> voc)
            throws IOException {
        int b = batch.widths().length;
        long[] xShape = {b, 1, batch.h(), batch.wm()};
        long[] wShape = {b};
        RecOut[] out = new RecOut[b];
        try (OnnxTensor x = OnnxTensor.createTensor(env, FloatBuffer.wrap(batch.data()), xShape);
             OnnxTensor widths = OnnxTensor.createTensor(env, LongBuffer.wrap(batch.widths()), wShape)) {
            Map<String, OnnxTensor> feeds = new LinkedHashMap<>();
            feeds.put("x", x);
            feeds.put("widths", widths);
            try (OrtSession.Result res = session.run(feeds)) {
                long[][] ids = (long[][]) res.get(0).getValue();
                float[][] peaks = (float[][]) res.get(1).getValue();
                long[] lengths = (long[]) res.get(2).getValue();
                for (int i = 0; i < b; i++) {
                    int len = (int) Math.min(lengths[i], ids[i].length);
                    StringBuilder sb = new StringBuilder();
                    double sum = 0;
                    int cnt = 0;
                    long prev = -1;
                    for (int t = 0; t < len; t++) {
                        long id = ids[i][t];
                        boolean keep = id != 0 && id != prev;
                        prev = id;
                        if (!keep) {
                            continue;
                        }
                        sum += peaks[i][t];
                        cnt++;
                        if (id <= voc.size()) {
                            sb.append(voc.get((int) id - 1));
                        }
                    }
                    out[i] = new RecOut(sb.toString().strip(), cnt > 0 ? sum / cnt : 0.0);
                }
            }
        } catch (ai.onnxruntime.OrtException e) {
            throw new IOException("rec.onnx 推理失败: " + e.getMessage(), e);
        }
        return out;
    }

    /** order.onnx 输出容器。 */
    private static final class OrderOut implements AutoCloseable {
        final double[][] a;
        final double[][] same;
        final float[][] cls;
        final double[][][] tab;

        /**
         * Order Out
         * @param a 参数
         * @param cls 参数
         * @param same 参数
         * @param tab 参数
         * @return 返回值
        */
        OrderOut(double[][] a, double[][] same, float[][] cls, double[][][] tab) {
            this.a = a;
            this.same = same;
            this.cls = cls;
            this.tab = tab;
        }

        @Override
        public void close() {
            // 纯内存结构，无需释放
        }
    }

    /**
     * run Order
     * @param feats 参数
     * @param n 整数
     * @return 返回值
     * @throws IOException 异常
    */
    private OrderOut runOrder(float[][] feats, int n) throws IOException {
        int nf = IndraOrderPost.N_FEATS;
        float[] flat = new float[n * nf];
        for (int i = 0; i < n; i++) {
            System.arraycopy(feats[i], 0, flat, i * nf, nf);
        }
        byte[] mask = new byte[n];
        java.util.Arrays.fill(mask, (byte) 1);
        long[] fShape = {1, n, nf};
        long[] mShape = {1, n};
        try (OnnxTensor ft = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), fShape);
             OnnxTensor mt = OnnxTensor.createTensor(env, ByteBuffer.wrap(mask), mShape, OnnxJavaType.BOOL)) {
            Map<String, OnnxTensor> feeds = new LinkedHashMap<>();
            feeds.put("feats", ft);
            feeds.put("mask", mt);
            try (OrtSession.Result res = orderSession.run(feeds)) {
                float[][][] aOut = (float[][][]) res.get(0).getValue();
                float[][][] clsOut = (float[][][]) res.get(1).getValue();
                float[][][] sameOut = (float[][][]) res.get(2).getValue();
                float[][][] tabOut = ((float[][][][]) res.get(3).getValue())[0];
                double[][] a = new double[n][n];
                double[][] same = new double[n][n];
                float[][] cls = new float[n][IndraOrderPost.BLOCK_CLASSES.length];
                double[][][] tab = new double[3][n][n];
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        a[i][j] = aOut[0][i][j];
                        same[i][j] = sameOut[0][i][j];
                        for (int k = 0; k < 3; k++) {
                            tab[k][i][j] = tabOut[k][i][j];
                        }
                    }
                    for (int c = 0; c < cls[i].length && c < clsOut[0][i].length; c++) {
                        cls[i][c] = clsOut[0][i][c];
                    }
                }
                return new OrderOut(a, same, cls, tab);
            }
        } catch (ai.onnxruntime.OrtException e) {
            throw new IOException("order.onnx 推理失败: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 装载

    /**
     * ensure Prepared
     * @return 返回值
     * @throws IOException 异常
    */
    private void ensurePrepared() throws IOException {
        if (prepared) {
            return;
        }
        synchronized (this) {
            if (prepared) {
                return;
            }
            Path det = resolveFile("det.onnx");
            Path rec = resolveFile("rec.onnx");
            Path order = resolveFile("order.onnx");
            Path meta = resolveFile("meta.json");
            if (det == null || rec == null || order == null || meta == null) {
                throw new IllegalStateException("indra 版面分析权重未找到（det/rec/order/meta.json 需同目录），"
                        + "请添加 utils-support-models-onnx-indra-layout 依赖；注入路径=" + modelPath);
            }
            loadMeta(meta);
            env = OrtEnvironment.getEnvironment();
            try {
                OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                // 内存受限部署（8GB 实例 + 7 个 onnx 会话常驻）下压线程并降图优化级别，
                // 避免多会话 native 内存（arena/优化中间图）膨胀触发系统 OOM killer。
                opts.setIntraOpNumThreads(1);
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
                opts.setMemoryPatternOptimization(false);
                detSession = env.createSession(det.toString(), opts);
                recSession = env.createSession(rec.toString(), opts);
                orderSession = env.createSession(order.toString(), opts);
                for (Map.Entry<String, List<String>> e : groupVocabDefs.entrySet()) {
                    Path g = resolveFile("rec_" + e.getKey() + ".onnx");
                    if (g == null) {
                        continue;
                    }
                    groupSessions.put(e.getKey(), env.createSession(g.toString(), opts));
                    groupVocabs.put(e.getKey(), e.getValue());
                }
                Path script = groupSessions.isEmpty() ? null : resolveFile("script.onnx");
                if (script != null) {
                    scriptSession = env.createSession(script.toString(), opts);
                }
            } catch (ai.onnxruntime.OrtException e) {
                throw new IOException("indra ONNX 会话初始化失败: " + e.getMessage(), e);
            }
            prepared = true;
            log.info("[IndraLayoutTranslator] 模型装载完成: det={}, rec={}, order={}, 分组={}, script={}",
                    det, rec, order, groupSessions.keySet(), scriptSession != null);
        }
    }

    /**
     * load Meta
     * @param metaFile 路径
     * @return 返回值
     * @throws IOException 异常
    */
    private void loadMeta(Path metaFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode meta = mapper.readTree(metaFile.toFile());
        detLongSide = meta.path("det_long_side").asInt(1024);
        recWindow = Math.max(1, meta.path("rec_window").asInt(1));
        vocab = parseChars(mapper, meta.path("vocab"));
        // 分组词表（vocab_cjk / vocab_rtl / vocab_world …）与 script.onnx 的类别顺序
        groupVocabDefs.clear();
        java.util.Iterator<Map.Entry<String, JsonNode>> it = meta.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().startsWith("vocab_")) {
                groupVocabDefs.put(e.getKey().substring("vocab_".length()),
                        parseChars(mapper, e.getValue()));
            }
        }
        JsonNode groups = meta.path("script_groups");
        if (groups.isArray() && !groups.isEmpty()) {
            List<String> list = new ArrayList<>(groups.size());
            for (JsonNode g : groups) {
                list.add(g.asText());
            }
            scriptGroups = list;
        }
    }

    /**
     * parse Chars
     * @param mapper 参数
     * @param vocabNode 参数
     * @return 返回值
     * @throws IOException 异常
    */
    private static List<String> parseChars(ObjectMapper mapper, JsonNode vocabNode) throws IOException {
        JsonNode inner = vocabNode.isTextual() ? mapper.readTree(vocabNode.asText()) : vocabNode;
        JsonNode chars = inner.path("chars");
        List<String> list = new ArrayList<>(chars.size());
        for (JsonNode c : chars) {
            list.add(c.asText());
        }
        return list;
    }

    /**
     * 解析单个模型文件：注入目录 → 类路径内嵌 → 注册表目录。
     *
     * @param name 文件名
     * @return 可用文件路径，找不到返回 null
     */
    private Path resolveFile(String name) {
        // 1) 注入目录
        if (modelPath != null && !modelPath.isBlank()) {
            try {
                Path p = Path.of(modelPath);
                Path dir = Files.isDirectory(p) ? p : p.getParent();
                if (dir != null) {
                    Path cand = dir.resolve(name);
                    if (Files.exists(cand)) {
                        return cand;
                    }
                }
            } catch (Exception ignored) {
                // 路径非法，继续兜底
            }
        }
        // 2) 类路径内嵌资源（jar 内解压到缓存目录）
        Path cp = ModelRegistry.resolveClasspathResource(RESOURCE_PREFIX + name);
        if (cp != null && Files.exists(cp)) {
            return cp;
        }
        // 3) 注册表
        try {
            ModelRegistry.discoverAll();
            Path reg = ModelRegistry.resolveModelPath(MODEL_ID);
            if (reg != null) {
                Path dir = Files.isDirectory(reg) ? reg : reg.getParent();
                if (dir != null) {
                    Path cand = dir.resolve(name);
                    if (Files.exists(cand)) {
                        return cand;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[IndraLayoutTranslator] 注册表解析 {} 失败: {}", name, e.getMessage());
        }
        return null;
    }

    /**
     * softmax Max
     * @param idx 整数
     * @param logits 参数
     * @return 返回值
    */
    private static float softmaxMax(double[] logits, int idx) {
        double max = logits[0];
        for (double v : logits) {
            if (v > max) {
                max = v;
            }
        }
        double sum = 0;
        for (double v : logits) {
            sum += Math.exp(v - max);
        }
        return sum > 0 ? (float) (Math.exp(logits[idx] - max) / sum) : 0f;
    }

    /**
     * 关闭 ONNX 会话。
     */
    public void close() {
        synchronized (this) {
            List<OrtSession> all = new ArrayList<>();
            all.add(detSession);
            all.add(recSession);
            all.add(orderSession);
            all.add(scriptSession);
            all.addAll(groupSessions.values());
            for (OrtSession s : all) {
                if (s != null) {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                        // 关闭失败不影响后续
                    }
                }
            }
            detSession = null;
            recSession = null;
            orderSession = null;
            scriptSession = null;
            groupSessions.clear();
            groupVocabs.clear();
            prepared = false;
        }
    }
}
