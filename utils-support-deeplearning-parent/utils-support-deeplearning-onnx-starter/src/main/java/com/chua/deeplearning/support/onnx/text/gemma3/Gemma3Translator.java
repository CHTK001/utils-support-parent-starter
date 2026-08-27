package com.chua.deeplearning.support.onnx.text.gemma3;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.ai.DetectionConfiguration;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/**
 * Gemma-3-270M 中文通用语言模型 Translator。
 * <p>
 * 模型来源：Google Gemma-3-270M（原版，词表 262144 完整多语言词表，含中文），
 * uint8 量化 ONNX 导出（约 437MB）。输入 {@code input_ids / attention_mask / position_ids}，
 * 输出 {@code logits}，无 KV cache —— 采用朴素自回归解码：每步前向喂入完整序列，
 * 取最后位置 logits 贪心取下一个 token，直至 EOS / {@code <end_of_turn>}。
 * </p>
 * <p>
 * 推理流程：gemma-3 chat 模板（{@code <bos><start_of_turn>user\n...<end_of_turn>\n<start_of_turn>model\n}）
 * → 原版 tokenizer 编码 → ORT 贪心自回归解码 → 跳过特殊 token 解码输出。
 * </p>
 * <p>
 * 模型文件嵌入式存放于 models-parent 模块
 * {@code utils-support-models-onnx-gemma-3-270m}
 * （classpath: {@code models/gemma-3-270m/}）。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class Gemma3Translator implements ITranslator<String, String>, AutoCloseable {

    /**
     * 默认模型标识
     */
    private static final String DEFAULT_MODEL_ID = "gemma-3-270m";

    /**
     * 最大生成 token 数
     */
    private static final int MAX_NEW_TOKENS = 128;

    /**
     * 最大输入 token 数（超出截断，防止长文本 OOM）
     */
    private static final int MAX_INPUT_LENGTH = 2048;

    /**
     * BOS token id（{@code <bos>}）
     */
    private static final long BOS_TOKEN_ID = 2L;

    /**
     * EOS token id（{@code <eos>}）
     */
    private static final long EOS_TOKEN_ID = 1L;

    /**
     * {@code <end_of_turn>} token id（对话结束符）
     */
    private static final long END_OF_TURN_TOKEN_ID = 106L;

    /**
     * 词表大小（原版 gemma-3-270m = 262144）
     */
    private static final int VOCAB_SIZE = 262144;

    private final String modelId;
    private final boolean useGpu;

    /** 采样温度（>0 时启用温度采样；0 = 纯贪心） */
    private final float temperature;
    /** 重复惩罚系数（>=1，1 = 不惩罚） */
    private final float repeatPenalty;
    /** top-k 采样候选数（>0 启用，0 = 不限制） */
    private final int topK;

    private HuggingFaceTokenizer tokenizer;
    private OrtEnvironment ortEnv;
    private OrtSession session;
    private volatile boolean initialized;

    /**
     * 构造默认模型翻译器。
     */
    public Gemma3Translator() {
        this(DEFAULT_MODEL_ID, false);
    }

    /**
     * 构造翻译器。
     *
     * @param modelId 模型标识（ModelRegistry 注册名）
     * @param useGpu  是否启用 CUDA 执行提供程序
     */
    public Gemma3Translator(String modelId, boolean useGpu) {
        this(modelId, useGpu, 0f, 1.2f, 40);
    }

    /**
     * 构造翻译器（完整参数）。
     *
     * @param modelId       模型标识
     * @param useGpu        是否启用 CUDA 执行提供程序
     * @param temperature   采样温度（&gt;0 启用温度采样，0 = 纯贪心）
     * @param repeatPenalty 重复惩罚系数（&gt;=1，1 = 不惩罚）
     * @param topK          top-k 采样候选数（&gt;0 启用）
     */
    public Gemma3Translator(String modelId, boolean useGpu, float temperature, float repeatPenalty, int topK) {
        this.modelId = modelId;
        this.useGpu = useGpu;
        this.temperature = temperature;
        this.repeatPenalty = repeatPenalty;
        this.topK = topK;
    }

    /**
     * 基于 {@link DetectionConfiguration} 构造翻译器。
     * <p>读取 {@code deviceIsGpu()} 决定是否启用 CUDA，并从 {@code systemOption()} 读取
     * {@code temperature} / {@code repeatPenalty} / {@code topK} 采样参数。</p>
     *
     * @param configuration 推理配置，可空
     */
    public Gemma3Translator(DetectionConfiguration configuration) {
        this(
                configuration == null ? DEFAULT_MODEL_ID : configuration.loadModelName() != null ? configuration.loadModelName() : DEFAULT_MODEL_ID,
                configuration != null && configuration.deviceIsGpu(),
                configuration == null ? 0f : configuration.optFloat("temperature", 0f),
                configuration == null ? 1.2f : configuration.optFloat("repeatPenalty", 1.2f),
                configuration == null ? 40 : (int) configuration.optFloat("topK", 40f)
        );
    }

    @Override
    public String name() {
        return modelId;
    }

    @Override
    public String translate(String input) {
        try {
            return chat(input);
        } catch (Exception e) {
            throw new RuntimeException("[Gemma3] 推理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行中文对话/文本生成。
     *
     * @param userPrompt 用户输入
     * @return 模型回复
     * @throws Exception 推理异常
     */
    public String chat(String userPrompt) throws Exception {
        prepare();
        String content = userPrompt == null ? "" : userPrompt.trim();
        // gemma-3 chat 模板（与 transformers apply_chat_template 一致）：
        // <bos><start_of_turn>user\n{content}<end_of_turn>\n<start_of_turn>model\n
        String prompt = "<bos><start_of_turn>user\n" + content + "<end_of_turn>\n<start_of_turn>model\n";

        Encoding enc = tokenizer.encode(prompt);
        long[] promptIds = enc.getIds();
        if (promptIds.length == 0) {
            return "";
        }
        if (promptIds.length > MAX_INPUT_LENGTH) {
            long[] cut = new long[MAX_INPUT_LENGTH];
            System.arraycopy(promptIds, 0, cut, 0, MAX_INPUT_LENGTH);
            promptIds = cut;
            log.warn("[Gemma3] 输入超过 {} token，已截断", MAX_INPUT_LENGTH);
        }

        List<Long> tokens = new ArrayList<>();
        for (long id : promptIds) {
            tokens.add(id);
        }
        int promptLen = tokens.size();
        int stepCount = 0;
        long start = System.currentTimeMillis();

        while (stepCount < MAX_NEW_TOKENS) {
            long[] ids = toLongArray(tokens);
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(ids), new long[]{1, ids.length}));
            inputs.put("attention_mask", OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(ones(ids.length)), new long[]{1, ids.length}));
            inputs.put("position_ids", OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(range(ids.length)), new long[]{1, ids.length}));

            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] logits = (float[][][]) result.get(0).getValue();
                int last = logits[0].length - 1;
                int next = nextToken(logits[0][last], tokens, promptLen);

                if (next == EOS_TOKEN_ID || next == END_OF_TURN_TOKEN_ID) {
                    break;
                }
                tokens.add((long) next);
                stepCount++;
            }
        }

        long[] genIds = new long[tokens.size() - promptLen];
        for (int i = 0; i < genIds.length; i++) {
            genIds[i] = tokens.get(promptLen + i);
        }
        String text = tokenizer.decode(genIds, true).trim();
        long elapsed = System.currentTimeMillis() - start;
        log.info("[Gemma3] 生成 {} tokens 耗时 {}ms", stepCount, elapsed);
        log.debug("[Gemma3] 回复: {}", text);
        return text;
    }

    /**
     * 从 logits 选择下一个 token。
     * <p>应用重复惩罚（对已生成 token 降权）后，按采样策略选择：
     * temperature&gt;0 时做温度 + top-k 采样，否则纯贪心。</p>
     *
     * @param logits    最后一个位置 logits（词表大小）
     * @param tokens    当前完整 token 序列（含 prompt 与已生成）
     * @param promptLen prompt 长度，仅对 prompt 之后的生成 token 施加重复惩罚
     * @return 选中的 token id
     */
    private int nextToken(float[] logits, List<Long> tokens, int promptLen) {
        float[] scores = logits.clone();
        if (repeatPenalty > 1f) {
            // 对已生成的 token 施加重复惩罚（跳过 prompt，避免抑制关键词）
            for (int i = promptLen; i < tokens.size(); i++) {
                int id = tokens.get(i).intValue();
                if (id < 0 || id >= scores.length) {
                    continue;
                }
                if (scores[id] > 0) {
                    scores[id] /= repeatPenalty;
                } else {
                    scores[id] *= repeatPenalty;
                }
            }
        }

        if (temperature > 0f) {
            return sample(scores, temperature, topK);
        }
        return argmax(scores);
    }

    /**
     * 温度 + top-k 采样。
     *
     * @param scores 已施加惩罚的 logits
     * @param temp   温度（&gt;0）
     * @param k      top-k 候选数（&gt;0）
     * @return 采样得到的 token id
     */
    private int sample(float[] scores, float temp, int k) {
        // softmax(score / temp)
        float max = Float.NEGATIVE_INFINITY;
        for (float s : scores) {
            if (s > max) {
                max = s;
            }
        }
        double sum = 0.0;
        double[] probs = new double[scores.length];
        for (int i = 0; i < scores.length; i++) {
            probs[i] = Math.exp((scores[i] - max) / temp);
            sum += probs[i];
        }
        // top-k 截断（保留概率最大的 k 个，其余置 0）
        if (k > 0 && k < scores.length) {
            int[] order = java.util.stream.IntStream.range(0, scores.length)
                    .boxed()
                    .sorted((a, b) -> Double.compare(probs[b], probs[a]))
                    .mapToInt(Integer::intValue)
                    .toArray();
            double kept = 0.0;
            for (int i = 0; i < k; i++) {
                kept += probs[order[i]];
            }
            for (int i = k; i < order.length; i++) {
                probs[order[i]] = 0.0;
            }
            if (kept > 0.0) {
                sum = kept;
            }
        }
        // 归一化后按概率抽样
        double r = new Random().nextDouble() * sum;
        double cumulative = 0.0;
        for (int i = 0; i < scores.length; i++) {
            cumulative += probs[i];
            if (r < cumulative) {
                return i;
            }
        }
        return argmax(scores);
    }

    private static int argmax(float[] logits) {
        int best = 0;
        float max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < logits.length; i++) {
            if (logits[i] > max) {
                max = logits[i];
                best = i;
            }
        }
        return best;
    }

    private static long[] toLongArray(List<Long> list) {
        long[] r = new long[list.size()];
        for (int i = 0; i < list.size(); i++) {
            r[i] = list.get(i);
        }
        return r;
    }

    private static long[] ones(int n) {
        long[] r = new long[n];
        for (int i = 0; i < n; i++) {
            r[i] = 1L;
        }
        return r;
    }

    private static long[] range(int n) {
        long[] r = new long[n];
        for (int i = 0; i < n; i++) {
            r[i] = i;
        }
        return r;
    }

    /**
     * 懒加载：解析模型路径（嵌入式 classpath 资源自动抽取）、加载 tokenizer 与 ORT 会话。
     */
    private synchronized void prepare() throws Exception {
        if (initialized) {
            return;
        }
        Path modelPath = ModelRegistry.resolveModelPath(modelId);
        if (modelPath == null || !Files.exists(modelPath)) {
            // 嵌入式回退：注册表无法定位时，从模型 jar 整目录抽取（含 model.onnx 与 tokenizer）
            Path base = Paths.get(System.getProperty("java.io.tmpdir"), "chua-models", modelId);
            log.info("[Gemma3] registry 未命中({})，从 classpath 抽取到 {}", modelPath, base);
            NativeLoader.of(modelId + "-resources")
                    .from(Gemma3Translator.class.getClassLoader())
                    .basePath("models/" + modelId + "/")
                    .toTarget(base)
                    .glob("*")
                    .withMd5(true)
                    .extractOnly(true)
                    .load();
            modelPath = base.resolve("model.onnx");
        }
        if (modelPath == null || !Files.exists(modelPath)) {
            throw new IllegalStateException("模型文件不存在: " + modelPath
                    + "，请确认已引入 utils-support-models-onnx-gemma-3-270m 模块");
        }
        Path dir = modelPath.getParent();

        // tokenizer.json 不在磁盘时，从 classpath 整目录抽取（模型 jar 内嵌）
        Path tokPath = dir != null ? dir.resolve("tokenizer.json") : null;
        if (tokPath == null || !Files.exists(tokPath)) {
            log.debug("[Gemma3] tokenizer.json not found in {}, extracting from classpath...", dir);
            NativeLoader.of("gemma-3-270m-resources")
                    .from(Gemma3Translator.class.getClassLoader())
                    .basePath("models/gemma-3-270m/")
                    .toTarget(dir)
                    .glob("*")
                    .withMd5(true)
                    .extractOnly(true)
                    .load();
            tokPath = dir != null ? dir.resolve("tokenizer.json") : null;
        }
        if (tokPath == null || !Files.exists(tokPath)) {
            throw new IllegalStateException("tokenizer.json 不存在: " + tokPath);
        }
        // 关闭自动添加特殊 token：模板已显式包含 <bos>，避免双重 BOS
        tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokPath)
                .optAddSpecialTokens(false)
                .optMaxLength(MAX_INPUT_LENGTH)
                .build();
        log.info("[Gemma3] tokenizer loaded: {}", tokPath);

        ortEnv = OrtEnvironment.getEnvironment();
        SessionOptions opts = new SessionOptions();
        // 线程数可用 -Dgemma.intraop=N 覆盖（低内存环境调小可显著降低原生内存峰值）
        int intraOp = Integer.getInteger("gemma.intraop", Math.min(8, Runtime.getRuntime().availableProcessors()));
        opts.setIntraOpNumThreads(intraOp);
        // uint8 量化模型：必须保留默认图优化（ALL_OPT）。
        // NO_OPT 下量化 MatMul 走降级内核，需物化 ~671MB 反量化权重（262144×640 fp32）→ bad allocation，
        // 且输出 token 与优化路径不一致；ALL_OPT 使用优化内核。
        // 同时关闭 BFCArena：lm_head 量化 MatMul 的 ~135MB 临时缓冲在 BFC arena 下分配失败。
        opts.setCPUArenaAllocator(false);
        if (useGpu) {
            opts.addCUDA();
        }
        session = ortEnv.createSession(modelPath.toString(), opts);
        initialized = true;
        log.info("[Gemma3] ORT session ready (gpu={}, vocab={}) model={}", useGpu, VOCAB_SIZE, modelPath);
    }

    @Override
    public void close() {
        if (session != null) {
            try { session.close(); } catch (Exception ignore) {}
        }
        ortEnv = null;
        initialized = false;
    }
}
