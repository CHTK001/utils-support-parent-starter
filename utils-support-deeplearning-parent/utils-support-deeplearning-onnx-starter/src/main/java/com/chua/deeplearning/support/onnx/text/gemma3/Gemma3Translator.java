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
        this.modelId = modelId;
        this.useGpu = useGpu;
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
                int next = argmax(logits[0][last]);

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
