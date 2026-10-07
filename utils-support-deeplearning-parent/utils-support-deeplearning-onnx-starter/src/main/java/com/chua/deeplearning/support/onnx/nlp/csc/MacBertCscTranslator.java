package com.chua.deeplearning.support.onnx.nlp.csc;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.onnx.GpuHelper;
import com.chua.deeplearning.support.onnx.ModelResourceResolver;
import com.chua.deeplearning.support.onnx.embedding.minilm.MiniLMTokenizer;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * macbert4csc 中文拼写纠错翻译器（BERT MLM：逐位置 argmax + 置信度阈值逐字替换）。
 *
 * <p>忠实复刻 pycorrector {@code MacBertCorrector._predict} / {@code correct_batch}：</p>
 * <ol>
 *   <li>编码为 {@code [CLS] + 单字令牌 + [SEP]}（1 字 = 1 令牌，否则放弃纠错）；</li>
 *   <li>逐位置 argmax 解码，按 HF decode 语义跳过 {@code [CLS]/[SEP]/[PAD]/[UNK]/[MASK]}
 *       并把 {@code ##} 子词合并回前一令牌（{@code " ".join(toks).replace(" ##","").strip().split(' ')}）；</li>
 *   <li>解码条数必须与字符数一致；第 {@code i+1} 位（+1 跳过 [CLS]）softmax 最大概率
 *       ≥ 阈值（默认 0.7，系统属性 {@code deeplearning.csc.threshold}，每次调用读取）
 *       且候选为单字符时才替换第 i 字，否则保留原字；</li>
 *   <li>超 128 字按 128 硬切分段纠错后拼接（pycorrector {@code split_text_into_sentences_by_length}）。</li>
 * </ol>
 *
 * <p>逐位单字符替换保证输出与输入恒等长——在等长逐字替换场景下与 pycorrector
 * {@code get_errors}（difflib 对齐）结果等价，且杜绝长度漂移；任何守卫不满足均原样
 * 返回原文，模型缺失等失败抛出运行时异常，由调用方（如 OCR 管线）决定是否降级。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MacBertCscTranslator implements ITranslator<String, String>, AutoCloseable {

    /**
     * 模型主文件名
     */
    private static final String MODEL_FILE = "model.onnx";

    /**
     * 词表文件名
     */
    private static final String VOCAB_FILE = "vocab.txt";

    /**
     * 单段最大字符数（pycorrector correct_batch 默认 max_length=128，超长硬切）
     */
    private static final int CHUNK_CHARS = 128;

    /**
     * 编码序列上限（max_position_embeddings 含 [CLS]/[SEP]；分段后实际不超过 130）
     */
    private static final int MAX_LEN = 512;

    /**
     * 默认替换置信度阈值（pycorrector 默认 0.7）
     */
    private static final float DEFAULT_THRESHOLD = 0.7f;

    /**
     * 置信度阈值系统属性（运行期可调，大于 1 等价关闭纠错）
     */
    private static final String THRESHOLD_PROPERTY = "deeplearning.csc.threshold";

    /**
     * 嵌入式 jar 资源根目录
     */
    private final String resourceBase;

    /**
     * 模型标识
     */
    private final String modelName;

    private OrtEnvironment ortEnv;
    private OrtSession session;
    private MiniLMTokenizer tokenizer;

    /**
     * id -&gt; token（解码 argmax 输出用）
     */
    private String[] id2token;

    /**
     * 跳过解码的特殊令牌 id：[CLS]/[SEP]/[PAD]/[UNK]/[MASK]
     */
    private Set<Integer> specialIds;

    /**
     * 框架注入的模型路径
     */
    private volatile String injectedPath;

    /**
     * 创建 macbert4csc 纠错翻译器。
     */
    public MacBertCscTranslator() {
        this("nlp/csc/macbert4csc/", "macbert4csc");
    }

    /**
     * 创建 macbert4csc 纠错翻译器。
     *
     * @param resourceBase 嵌入式 jar 资源根目录
     * @param modelName    模型标识
     */
    public MacBertCscTranslator(String resourceBase, String modelName) {
        this.resourceBase = resourceBase;
        this.modelName = modelName;
    }

    /**
     * 注入模型路径（框架懒加载时反射调用；空值忽略）。
     *
     * @param path 模型文件路径
     */
    public void setModelPath(String path) {
        if (path != null && !path.isBlank()) {
            this.injectedPath = path.trim();
        }
    }

    private synchronized void prepare() throws Exception {
        if (session != null) {
            return;
        }
        try {
            ModelRegistry.discoverAll();
        } catch (Exception e) {
            log.debug("[macbert4csc] 注册表发现失败: {}", e.getMessage());
        }
        Path modelPath = resolveModelFile();
        loadVocab(modelPath);
        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        GpuHelper.apply(opts, modelName);
        this.session = ortEnv.createSession(modelPath.toString(), opts);
        log.info("[macbert4csc] ONNX loaded: {} vocab={}", modelPath, id2token.length);
    }

    /**
     * 解析模型文件：注入路径 → modelId 外部目录 → 注册表（classpath 解压缓存）→ classpath 兜底解压。
     *
     * @return 模型文件路径
     * @throws Exception 解析或解压失败
     */
    private Path resolveModelFile() throws Exception {
        Path external = ModelResourceResolver.resolveOnnx(injectedPath, modelName, resourceBase, MODEL_FILE);
        if (external != null) {
            return external;
        }
        Path tmpDir = Files.createTempDirectory("macbert4csc-");
        tmpDir.toFile().deleteOnExit();
        Path modelDir = tmpDir.resolve("csc");
        Files.createDirectories(modelDir);
        NativeLoader.of(modelName)
                .from(MacBertCscTranslator.class.getClassLoader())
                .basePath(resourceBase)
                .toTarget(modelDir)
                .glob("*")
                .withMd5(true)
                .extractOnly(true)
                .load();
        Path modelPath = modelDir.resolve(MODEL_FILE);
        if (!Files.isRegularFile(modelPath)) {
            throw new IllegalArgumentException("CSC 资源缺失（外部目录 / 注册表 / classpath 均未找到）: " + modelName);
        }
        return modelPath;
    }

    /**
     * 加载词表：优先模型同目录 vocab.txt，缺失回退 classpath（注册表缓存只解压单文件）。
     *
     * @param modelPath 模型文件路径
     * @throws Exception 词表缺失或读取失败
     */
    private void loadVocab(Path modelPath) throws Exception {
        Path vocab = modelPath.getParent() == null ? null : modelPath.getParent().resolve(VOCAB_FILE);
        if (vocab != null && Files.isRegularFile(vocab)) {
            initVocab(Files.readAllLines(vocab, StandardCharsets.UTF_8));
            return;
        }
        log.info("[macbert4csc] 模型同目录无 {}，回落 classpath 读取", VOCAB_FILE);
        loadVocabFromClasspath();
    }

    /**
     * 从 classpath 读取词表。
     *
     * @throws Exception 资源缺失或读取失败
     */
    private void loadVocabFromClasspath() throws Exception {
        String resource = resourceBase.endsWith("/")
                ? resourceBase + VOCAB_FILE : resourceBase + "/" + VOCAB_FILE;
        try (InputStream is = MacBertCscTranslator.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                throw new IllegalStateException("CSC 词表缺失（模型同目录与 classpath 均未找到）: " + resource);
            }
            List<String> lines = Wraps.list();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            initVocab(lines);
            log.info("[macbert4csc] vocab 取自 classpath: {} tokens={}", resource, lines.size());
        }
    }

    /**
     * 用词表行初始化分词器、解码表与特殊令牌 id。
     *
     * @param lines 词表行（每行一个令牌，行号即 id）
     */
    private void initVocab(List<String> lines) {
        if (lines.isEmpty()) {
            throw new IllegalStateException("CSC 词表为空: " + modelName);
        }
        id2token = lines.toArray(new String[0]);
        Map<String, Integer> vocab = Wraps.map();
        for (int i = 0; i < lines.size(); i++) {
            vocab.put(lines.get(i), i);
        }
        tokenizer = new MiniLMTokenizer(vocab, true, true);
        Set<Integer> specials = new HashSet<>();
        for (String token : new String[]{
                MiniLMTokenizer.CLS, MiniLMTokenizer.SEP, MiniLMTokenizer.PAD, MiniLMTokenizer.UNK, "[MASK]"}) {
            Integer id = vocab.get(token);
            if (id != null) {
                specials.add(id);
            }
        }
        specialIds = specials;
    }

    @Override
    public String name() {
        return modelName;
    }

    @Override
    public synchronized String translate(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        try {
            prepare();
            float threshold = threshold();
            if (text.length() <= CHUNK_CHARS) {
                return correctChunk(text, threshold);
            }
            StringBuilder out = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i += CHUNK_CHARS) {
                out.append(correctChunk(text.substring(i, Math.min(i + CHUNK_CHARS, text.length())), threshold));
            }
            return out.toString();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("[macbert4csc] 中文纠错失败: " + e.getMessage(), e);
        }
    }

    /**
     * 当前置信度阈值（每次调用读取系统属性，便于运行期调整）。
     *
     * @return 阈值；非法或缺失时回退 0.7
     */
    private static float threshold() {
        String value = System.getProperty(THRESHOLD_PROPERTY);
        if (value == null || value.isBlank()) {
            return DEFAULT_THRESHOLD;
        }
        try {
            float threshold = Float.parseFloat(value.trim());
            if (threshold > 0f) {
                return threshold;
            }
        } catch (NumberFormatException ignore) {
            // 非法值回退默认阈值
        }
        return DEFAULT_THRESHOLD;
    }

    /**
     * 对单段文本（≤128 字）执行纠错。
     *
     * @param sentence  单段文本
     * @param threshold 置信度阈值
     * @return 纠错文本；守卫不满足时原样返回（恒等长）
     * @throws Exception 推理失败
     */
    private String correctChunk(String sentence, float threshold) throws Exception {
        int maxLen = Math.min(MAX_LEN, sentence.length() + 2);
        MiniLMTokenizer.EncodeResult enc = tokenizer.encode(sentence, maxLen);
        // 实际序列长度 = attentionMask 前缀 1 的个数（右侧为 [PAD] 补零）
        int seqLen = 0;
        while (seqLen < enc.attentionMask.length && enc.attentionMask[seqLen] == 1) {
            seqLen++;
        }
        // 守卫①：内容令牌数必须与字符数 1:1（单字单令牌），否则逐位对齐不成立，放弃纠错
        if (seqLen - 2 != sentence.length()) {
            return sentence;
        }
        long[] ids = new long[seqLen];
        long[] mask = new long[seqLen];
        long[] types = new long[seqLen];
        for (int i = 0; i < seqLen; i++) {
            ids[i] = enc.inputIds[i];
            mask[i] = 1;
            types[i] = enc.tokenTypeIds[i];
        }
        int[] pred;
        double[] conf;
        try (OnnxTensor t1 = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(ids), new long[]{1, seqLen});
             OnnxTensor t2 = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(mask), new long[]{1, seqLen});
             OnnxTensor t3 = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(types), new long[]{1, seqLen})) {
            Map<String, OnnxTensor> inputs = Wraps.map();
            inputs.put("input_ids", t1);
            inputs.put("attention_mask", t2);
            inputs.put("token_type_ids", t3);
            try (OrtSession.Result result = session.run(inputs)) {
                var info = result.get("logits");
                Object out = info.isPresent() ? info.get().getValue() : result.get(0).getValue();
                float[][][] logits;
                if (out instanceof float[][][]) {
                    logits = (float[][][]) out;
                } else if (out instanceof float[][][][]) {
                    logits = ((float[][][][]) out)[0];
                } else {
                    throw new IllegalArgumentException("CSC 输出格式不识别: " + out.getClass());
                }
                pred = new int[seqLen];
                conf = new double[seqLen];
                for (int p = 0; p < seqLen; p++) {
                    float[] row = logits[0][p];
                    int best = 0;
                    for (int v = 1; v < row.length; v++) {
                        if (row[v] > row[best]) {
                            best = v;
                        }
                    }
                    // softmax 最大概率 = 1 / Σ exp(x - max)
                    double sum = 0d;
                    double max = row[best];
                    for (float value : row) {
                        sum += Math.exp(value - max);
                    }
                    pred[p] = best;
                    conf[p] = 1d / sum;
                }
            }
        }
        // 守卫②：解码条数必须与字符数一致（跳过特殊令牌并合并 ## 子词后仍逐字对齐）
        List<String> decoded = decode(pred, seqLen);
        if (decoded.size() > sentence.length()) {
            decoded = decoded.subList(0, sentence.length());
        }
        if (decoded.size() != sentence.length()) {
            return sentence;
        }
        // 逐位替换：置信度 ≥ 阈值且候选为单字符才改字，其余保留原字（输出恒等长）
        StringBuilder corrected = new StringBuilder(sentence.length());
        for (int i = 0; i < sentence.length(); i++) {
            String token = decoded.get(i);
            if (i + 1 < conf.length && conf[i + 1] >= threshold && token.length() == 1) {
                corrected.append(token);
            } else {
                corrected.append(sentence.charAt(i));
            }
        }
        return corrected.toString();
    }

    /**
     * 按 HF decode 语义解码预测序列：跳过特殊令牌，合并 {@code ##} 子词后按空格切分。
     *
     * @param pred   逐位置 argmax 预测 id
     * @param seqLen 序列长度
     * @return 切分后的令牌列表（等价 Python {@code decode(...).split(' ')}）
     */
    private List<String> decode(int[] pred, int seqLen) {
        List<String> tokens = Wraps.list();
        for (int p = 0; p < seqLen; p++) {
            int id = pred[p];
            if (id < 0 || id >= id2token.length || specialIds.contains(id)) {
                continue;
            }
            tokens.add(id2token[id]);
        }
        String joined = String.join(" ", tokens).replace(" ##", "").strip();
        List<String> parts = Wraps.list();
        for (String part : joined.split(" ", -1)) {
            parts.add(part);
        }
        return parts;
    }

    /**
     * 关闭会话（幂等；下次 translate 自动重新加载）。
     */
    @Override
    public synchronized void close() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception ignore) {
            // 关闭失败忽略
        }
        session = null;
        ortEnv = null;
        tokenizer = null;
        id2token = null;
        specialIds = null;
    }
}
