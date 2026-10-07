package com.chua.deeplearning.support.onnx.nlp.lac;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * LAC 中文分词/词性标注/NER（ORT 原生 + numpy 风格 Viterbi）。
 *
 * <p>模型由 jar {@code utils-support-models-onnx-lac} 提供，资源在
 * {@code nlp/lac/} 下：{@code lac_encoder.onnx}（Embedding+BiGRU+FC，
 * 输入 token_ids/length，输出 emission logits）+ {@code lac_crf_transitions.npy}
 * + 新词典 word.dic/tag.dic/q2b.dic。CRF 解码用 Java 实现 Viterbi，BIO 合并对齐
 * Paddle 版 {@code LacTranslator} 输出 {@code String[][] [token,label]}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class LacOnnxTranslator implements ITranslator<String, String[][]>, AutoCloseable {

    /**
     * jar 内资源根目录。
     */
    private static final String RESOURCE_BASE = "nlp/lac/";

    /**
     * encoder 模型文件名。
     */
    private static final String ENCODER_FILE = "lac_encoder.onnx";

    /**
     * CRF 转移矩阵文件名（numpy npy）。
     */
    private static final String TRANSITIONS_FILE = "lac_crf_transitions.npy";

    /**
     * encoder 下载地址（jar 内缺失时回退，GitHub raw）。
     */
    private static final String ENCODER_URL = "https://raw.githubusercontent.com/kniost/LAC-ONNX/main/lac_onnx/lac_encoder.onnx";

    /**
     * encoder 镜像地址（jsDelivr）。
     */
    private static final String ENCODER_MIRROR = "https://cdn.jsdelivr.net/gh/kniost/LAC-ONNX@main/lac_onnx/lac_encoder.onnx";

    /**
     * 词典下载基址。
     */
    private static final String DIC_BASE = "https://raw.githubusercontent.com/kniost/LAC-ONNX/main/lac_onnx/";

    /**
     * 输入最大字符数（超长截断，与显存/GRU 展开保护对应）。
     */
    private static final int MAX_INPUT_LENGTH = 512;

    /**
     * ONNX 运行时环境
     */
    private OrtEnvironment ortEnv;
    /**
     * encoder 会话
     */
    private OrtSession encoderSession;
    /**
     * 词 -> 标识
     */
    private final Map<String, Long> word2Id = Wraps.map();
    /**
     * 标识 -> 标签
     */
    private final Map<Integer, String> id2Tag = Wraps.map();
    /**
     * 全角半角替换
     */
    private final Map<String, String> q2b = Wraps.map();
    /**
     * OOV 标识
     */
    private long oovId;
    /**
     * CRF 转移矩阵 [nTag][nTag]
     */
    private float[][] transitions;
    /**
     * 标签数
     */
    private int numTags;
    /**
     * 是否已加载
     */
    private volatile boolean loaded;

    /**
     * 懒加载模型与词典。
     */
    private synchronized void prepare() throws Exception {
        if (loaded) {
            return;
        }
        Path tmpDir = Files.createTempDirectory("lac-onnx-");
        tmpDir.toFile().deleteOnExit();
        Path modelDir = tmpDir.resolve("lac");
        Files.createDirectories(modelDir);
        try {
            NativeLoader.of("lac-onnx")
                    .from(LacOnnxTranslator.class.getClassLoader())
                    .basePath(RESOURCE_BASE)
                    .toTarget(modelDir)
                    .glob("*")
                    .withMd5(true)
                    .extractOnly(true)
                    // 每次使用新的临时目录，必须禁用按 taskId 的全局缓存，
                    // 否则首次抽取后后续调用直接跳过，目标目录为空
                    .cacheable(false)
                    .load();
        } catch (Throwable e) {
            log.warn("[lac-onnx] NativeLoader 抽取失败，改用 classpath 直拷: {}", e.getMessage());
        }
        // classpath 直拷兜底（NativeLoader 枚举不到时用）
        copyFromClasspath(ENCODER_FILE, modelDir.resolve(ENCODER_FILE));
        copyFromClasspath(TRANSITIONS_FILE, modelDir.resolve(TRANSITIONS_FILE));
        copyFromClasspath("word.dic", modelDir.resolve("word.dic"));
        copyFromClasspath("tag.dic", modelDir.resolve("tag.dic"));
        copyFromClasspath("q2b.dic", modelDir.resolve("q2b.dic"));
        Path encoderPath = modelDir.resolve(ENCODER_FILE);
        Path transPath = modelDir.resolve(TRANSITIONS_FILE);
        Path wordPath = modelDir.resolve("word.dic");
        Path tagPath = modelDir.resolve("tag.dic");
        Path q2bPath = modelDir.resolve("q2b.dic");
        // jar 内缺 encoder 时从 HF/镜像下载到同一目录（词典 jar 内已有则直接用）
        if (!Files.isRegularFile(encoderPath) || Files.size(encoderPath) == 0) {
            download(ENCODER_URL, encoderPath);
            if (!Files.isRegularFile(encoderPath) || Files.size(encoderPath) == 0) {
                download(ENCODER_MIRROR, encoderPath);
            }
        }
        if (!Files.isRegularFile(transPath)) {
            download(DIC_BASE + TRANSITIONS_FILE, transPath);
        }
        if (!Files.isRegularFile(wordPath)) {
            download(DIC_BASE + "word.dic", wordPath);
        }
        if (!Files.isRegularFile(tagPath)) {
            download(DIC_BASE + "tag.dic", tagPath);
        }
        if (!Files.isRegularFile(q2bPath)) {
            download(DIC_BASE + "q2b.dic", q2bPath);
        }
        if (!Files.isRegularFile(encoderPath) || !Files.isRegularFile(transPath)
                || !Files.isRegularFile(wordPath) || !Files.isRegularFile(tagPath)) {
            throw new IllegalArgumentException("lac-onnx 模型资源缺失: " + modelDir);
        }
        loadWordDic(wordPath);
        loadTagDic(tagPath);
        if (Files.isRegularFile(q2bPath)) {
            loadQ2b(q2bPath);
        }
        oovId = word2Id.getOrDefault("OOV", 0L);
        transitions = readNpy2d(transPath);
        numTags = transitions.length;
        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
        this.encoderSession = ortEnv.createSession(encoderPath.toString(), opts);
        log.info("[lac-onnx] 模型加载完成: encoder={} tags={}", encoderPath.getFileName(), numTags);
        loaded = true;
    }

    /**
     * 加载 word.dic（格式 id\tchar）。
     */
    private void loadWordDic(Path path) throws Exception {
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                // 注意：value 可能就是空格本身，不可 strip 整行
                if (line.isEmpty()) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab < 0) {
                    continue;
                }
                String idPart = line.substring(0, tab).trim();
                if (idPart.isEmpty()) {
                    continue;
                }
                long id = Long.parseLong(idPart);
                // value 保留原始字符（含空格），仅去掉行尾换行符（readLine 已处理）
                String ch = line.substring(tab + 1);
                word2Id.put(ch, id);
            }
        }
    }

    /**
     * 加载 tag.dic（格式 id\tlabel）。
     */
    private void loadTagDic(Path path) throws Exception {
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab < 0) {
                    continue;
                }
                int id = Integer.parseInt(line.substring(0, tab).trim());
                id2Tag.put(id, line.substring(tab + 1).trim());
            }
        }
    }

    /**
     * 加载 q2b.dic（格式 全角\t半角）。
     */
    private void loadQ2b(Path path) throws Exception {
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line == null) {
                    continue;
                }
                String[] ws = line.split("\t");
                if (ws.length >= 2) {
                    q2b.put(ws[0], ws[1]);
                } else if (ws.length == 1) {
                    q2b.put(ws[0], "");
                }
            }
        } catch (Exception ignored) {
            // optional
        }
    }

    /**
     * 读取 numpy npy 二维 float 数组（C order，descr '&lt;f4'）。
     */
    private static float[][] readNpy2d(Path path) throws Exception {
        byte[] all = Files.readAllBytes(path);
        // npy 头：magic(6) + ver(2) + hlen(2 LE) + header
        int hlen = (all[8] & 0xFF) | ((all[9] & 0xFF) << 8);
        String header = new String(all, 10, hlen, StandardCharsets.US_ASCII);
        int[] shape = parseShape(header);
        int rows = shape[0];
        int cols = shape[1];
        int off = 10 + hlen;
        float[][] out = new float[rows][cols];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(all, off, all.length - off)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                out[i][j] = bb.getFloat();
            }
        }
        return out;
    }

    /**
     * 解析 npy 头中 shape 元组。
     */
    private static int[] parseShape(String header) {
        int s = header.indexOf('(');
        int e = header.indexOf(')', s);
        String[] parts = header.substring(s + 1, e).split(",");
        List<Integer> dims = Wraps.list();
        for (String p : parts) {
            p = p.trim();
            if (!p.isEmpty()) {
                dims.add(Integer.parseInt(p));
            }
        }
        return new int[]{dims.get(0), dims.get(1)};
    }

    @Override
    /**
     * 名称
     */
    public String name() {
        return "lac-onnx";
    }

    @Override
    /**
     * Translate
     */
    public String[][] translate(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0][0];
        }
        try {
            prepare();
            int rawLen = text.length();
            int n = Math.min(rawLen, MAX_INPUT_LENGTH);
            if (rawLen > MAX_INPUT_LENGTH) {
                // 静默截断会让调用方拿到残缺结果且毫无线索，必须显式告警
                log.warn("[lac-onnx] 输入超长已截断: {} -> {} 字符（尾部 {} 字未处理，建议分段调用）",
                        rawLen, MAX_INPUT_LENGTH, rawLen - MAX_INPUT_LENGTH);
            }
            String input = text.substring(0, n);
            long[] ids = new long[n];
            for (int i = 0; i < n; i++) {
                String ch = String.valueOf(input.charAt(i));
                ch = q2b.getOrDefault(ch, ch);
                ids[i] = word2Id.getOrDefault(ch, oovId);
            }
            long[] length = new long[]{(long) n};
            float[][] emissions;
            try (OnnxTensor tIds = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(ids), new long[]{1, n});
                 OnnxTensor tLen = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(length), new long[]{1});
                 OrtSession.Result r = encoderSession.run(Map.of("token_ids", tIds, "length", tLen))) {
                float[][][] logits = (float[][][]) r.get(0).getValue();
                emissions = logits[0];
            }
            int[] tagIds = viterbi(emissions, n);
            return decodeBio(input, tagIds);
        } catch (Exception e) {
            throw new RuntimeException("[lac-onnx] 分词失败: " + e.getMessage(), e);
        }
    }

    /**
     * Viterbi 解码，返回最优标签 id 序列。
     */
    private int[] viterbi(float[][] emissions, int seqLen) {
        double[] dp = new double[numTags];
        for (int j = 0; j < numTags; j++) {
            dp[j] = emissions[0][j];
        }
        int[][] bp = new int[seqLen][numTags];
        for (int t = 1; t < seqLen; t++) {
            double[] ndp = new double[numTags];
            for (int j = 0; j < numTags; j++) {
                double best = Double.NEGATIVE_INFINITY;
                int arg = 0;
                for (int i = 0; i < numTags; i++) {
                    double s = dp[i] + transitions[i][j];
                    if (s > best) {
                        best = s;
                        arg = i;
                    }
                }
                ndp[j] = best + emissions[t][j];
                bp[t][j] = arg;
            }
            dp = ndp;
        }
        int best = 0;
        for (int j = 1; j < numTags; j++) {
            if (dp[j] > dp[best]) {
                best = j;
            }
        }
        int[] path = new int[seqLen];
        path[seqLen - 1] = best;
        for (int t = seqLen - 1; t > 0; t--) {
            path[t - 1] = bp[t][path[t]];
        }
        return path;
    }

    /**
     * BIO 合并为 [token,label]，对齐上游 LAC-ONNX {@code _decode_bio}：
     * 以 {@code B-} 开新词，{@code *-I} 续接当前词（无论前后缀是否一致），
     * 标签取 {@code raw_tag} 中 {@code -} 最后一节之前缀；缺省 {@code n-B}。
     */
    private String[][] decodeBio(String input, int[] tagIds) {
        List<String> tokens = Wraps.list();
        List<String> labels = Wraps.list();
        int i = 0;
        while (i < input.length() && i < tagIds.length) {
            String rawTag = id2Tag.getOrDefault(tagIds[i], "n-B");
            String label = stripBio(rawTag);
            StringBuilder word = new StringBuilder();
            word.append(input.charAt(i));
            i++;
            while (i < input.length() && i < tagIds.length
                    && id2Tag.getOrDefault(tagIds[i], "n-B").endsWith("-I")) {
                word.append(input.charAt(i));
                i++;
            }
            tokens.add(word.toString());
            labels.add(label);
        }
        String[][] result = new String[tokens.size()][2];
        for (int k = 0; k < tokens.size(); k++) {
            result[k][0] = tokens.get(k);
            result[k][1] = labels.get(k);
        }
        return result;
    }

    /**
     * 取 BIO 标签前缀（{@code B-}/{@code I-} 后缀之前部分，无后缀则原样返回）。
     */
    private static String stripBio(String rawTag) {
        int dash = rawTag.lastIndexOf('-');
        return dash > 0 ? rawTag.substring(0, dash) : rawTag;
    }

    /**
     * classpath 直拷单个文件（目标已存在且非空则跳过）。
     */
    private static void copyFromClasspath(String name, Path target) {
        try {
            if (Files.isRegularFile(target) && Files.size(target) > 0) {
                return;
            }
            try (InputStream in = openResource(name)) {
                if (in == null) {
                    return;
                }
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling(target.getFileName() + ".part");
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("[lac-onnx] classpath 拷贝失败: {} -> {}", name, e.getMessage());
        }
    }

    /**
     * 下载文件（失败吞掉，由调用方校验）。
     */
    private static void download(String url, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("[lac-onnx] 下载失败: {} -> {}", url, e.getMessage());
        }
    }

    /**
     * classpath 兜底读取（jar 内嵌词典直读，NativeLoader 解压失败时用）。
     */
    @SuppressWarnings("unused")
    private static InputStream openResource(String name) {
        ClassLoader loader = LacOnnxTranslator.class.getClassLoader();
        InputStream in = loader.getResourceAsStream(RESOURCE_BASE + name);
        if (in == null) {
            in = loader.getResourceAsStream(name);
        }
        return in;
    }

    @Override
    /**
     * 关闭
     */
    public void close() {
        if (encoderSession != null) {
            try {
                encoderSession.close();
            } catch (Exception ignored) {
            }
            encoderSession = null;
        }
        loaded = false;
    }
}
