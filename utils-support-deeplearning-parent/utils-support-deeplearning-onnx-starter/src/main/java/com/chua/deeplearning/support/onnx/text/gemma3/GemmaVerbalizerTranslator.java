package com.chua.deeplearning.support.onnx.text.gemma3;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemma-3-270M 乌克兰语 TTS 文本规范化（verbalizer）Translator。
 * <p>
 * 模型来源：{@code skypro1111/gemma-3-270m-uk-verbalizer}（Gemma3ForCausalLM，18 层，
 * 1 KV head × head_dim 256，词表 38651，FP32 ONNX，带 KV cache 线性解码）。
 * 作用：把书面乌克兰语转换为"如何发音"的口语形式 —— 数字、日期、时间、金额、单位、
 * 缩写、代码、电话、IBAN、域名、邮箱、罗马数字、拉丁文夹杂等全部展开，是 TTS 合成
 * 流水线中喂给 TTS 前的最后一步预处理。
 * </p>
 * <p>
 * 推理流程：chat 模板（{@code <bos><start_of_turn>user\n...<end_of_turn>\n<start_of_turn>model\n}）
 * → HF tokenizer 编码 → ORT 贪心自回归解码（复用 KV cache，避免重复计算前序 token）→
 * 跳过特殊 token 解码输出。与作者参考实现一致：greedy、最多 192 个新 token、
 * 屏蔽含数字的 token（bad_words_ids 等价物，防止模型输出数字而不是口语词）。
 * </p>
 * <p>
 * 模型文件嵌入式存放于 models-parent 模块
 * {@code utils-support-models-onnx-gemma-3-270m-uk-verbalizer}
 * （classpath: {@code models/gemma-3-270m-uk-verbalizer/}）。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class GemmaVerbalizerTranslator implements ITranslator<String, String>, AutoCloseable {

    /**
     * 默认模型标识
     */
    private static final String DEFAULT_MODEL_ID = "gemma-3-270m-uk-verbalizer";

    /**
     * 最大生成 token 数（作者参考实现 max_new_tokens=192）
     */
    private static final int MAX_NEW_TOKENS = 192;

    /**
     * 最大输入 token 数（超出截断，防止长文本 OOM）
     */
    private static final int MAX_INPUT_LENGTH = 2048;

    /**
     * KV cache 头数（gemma 每层单 KV 头，past shape 第 2 维 = 1）
     */
    private static final int KV_HEADS = 1;

    /**
     * EOS token id（{@code <eos>}）
     */
    private static final long EOS_TOKEN_ID = 1L;

    /**
     * {@code <end_of_turn>} token id（对话结束符）
     */
    private static final long END_OF_TURN_TOKEN_ID = 7L;

    private final String modelId;
    private final boolean useGpu;

    private HuggingFaceTokenizer tokenizer;
    private OrtEnvironment ortEnv;
    private OrtSession session;
    private volatile boolean initialized;

    /**
     * KV 维度（从模型输入动态解析，270M = 256）
     */
    private int kvDim = 256;

    /**
     * 隐藏层数（从模型输入动态解析，270M = 18）
     */
    private int nLayers = 18;

    /**
     * 构造默认模型翻译器。
     */
    public GemmaVerbalizerTranslator() {
        this(DEFAULT_MODEL_ID, false);
    }

    /**
     * 构造翻译器。
     *
     * @param modelId 模型标识（ModelRegistry 注册名）
     * @param useGpu  是否启用 CUDA 执行提供程序
     */
    public GemmaVerbalizerTranslator(String modelId, boolean useGpu) {
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
            throw new RuntimeException("[GemmaVerbalizer] 推理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行乌克兰语文本规范化。
     *
     * @param userPrompt 书面乌克兰语文本
     * @return 口语发音形式的乌克兰语文本
     * @throws Exception 推理异常
     */
    public String chat(String userPrompt) throws Exception {
        prepare();
        String content = userPrompt == null ? "" : userPrompt.trim();
        // 与 README 参考实现一致：apply_chat_template(..., add_generation_prompt=True)
        // 渲染为 <bos><start_of_turn>user\n{content}<end_of_turn>\n<start_of_turn>model\n
        String prompt = "<bos><start_of_turn>user\n" + content + "<end_of_turn>\n<start_of_turn>model\n";

        Encoding enc = tokenizer.encode(prompt);
        long[] ids = enc.getIds();
        if (ids.length == 0) {
            return "";
        }
        if (ids.length > MAX_INPUT_LENGTH) {
            long[] cut = new long[MAX_INPUT_LENGTH];
            System.arraycopy(ids, 0, cut, 0, MAX_INPUT_LENGTH);
            ids = cut;
            log.warn("[GemmaVerbalizer] 输入超过 {} token，已截断", MAX_INPUT_LENGTH);
        }
        long[] attMask = ones(ids.length);

        Map<String, OnnxTensor> past = new HashMap<>();
        List<Long> generated = new ArrayList<>();
        // 已处理的 token 总数（即 KV cache 中的 past_sequence_length）
        long totalSeen = ids.length;
        int stepCount = 0;
        long start = System.currentTimeMillis();

        while (stepCount < MAX_NEW_TOKENS) {
            // 图约束：attention_mask 长度 = past_sequence_length + sequence_length，
            // 即当前 KV cache 长度 + 本次输入长度，且全部为 1
            attMask = ones((int) totalSeen);
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(ids), new long[]{1, ids.length}));
            inputs.put("attention_mask", OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(attMask), new long[]{1, attMask.length}));
            inputs.putAll(stepCount == 0 ? emptyPast() : past);

            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] logits = (float[][][]) result.get(0).getValue();
                int last = logits[0].length - 1;
                int next = argmaxBlockingDigits(logits[0][last]);

                if (next == EOS_TOKEN_ID || next == END_OF_TURN_TOKEN_ID) {
                    break;
                }
                generated.add((long) next);
                stepCount++;

                // 收集 present -> 新 past（先释放旧 past）
                for (OnnxTensor t : past.values()) {
                    try { t.close(); } catch (Exception ignore) {}
                }
                past = collectPast(result);

                ids = new long[]{next};
                totalSeen++;
            }
        }
        for (OnnxTensor t : past.values()) {
            try { t.close(); } catch (Exception ignore) {}
        }

        long[] outIds = generated.stream().mapToLong(Long::longValue).toArray();
        String text = tokenizer.decode(outIds, true).trim();
        long elapsed = System.currentTimeMillis() - start;
        log.info("[GemmaVerbalizer] 生成 {} tokens 耗时 {}ms", stepCount, elapsed);
        log.debug("[GemmaVerbalizer] 规范化结果: {}", text);
        return text;
    }

    /**
     * 贪心 argmax，但屏蔽含数字字符的 token（等价作者参考实现的 bad_words_ids，
     * 保证输出是口语词而非数字）。
     */
    private int argmaxBlockingDigits(float[] logits) {
        int best = argmax(logits);
        // 最多尝试屏蔽若干次：数字 token 在词表中占比小，通常 1~2 次即可命中非数字
        for (int attempt = 0; attempt < 100; attempt++) {
            String text = tokenizer.decode(new long[]{best}, true);
            if (!containsDigit(text)) {
                return best;
            }
            logits[best] = Float.NEGATIVE_INFINITY;
            best = argmax(logits);
        }
        return best;
    }

    private static boolean containsDigit(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
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

    /**
     * 懒加载：解析模型路径（嵌入式 classpath 资源自动抽取）、加载 tokenizer 与 ORT 会话。
     */
    private synchronized void prepare() throws Exception {
        if (initialized) {
            return;
        }
        Path modelPath = ModelRegistry.resolveModelPath(modelId);
        if (modelPath == null || !Files.exists(modelPath)) {
            throw new IllegalStateException("模型文件不存在: " + modelPath
                    + "，请确认已引入 utils-support-models-onnx-gemma-3-270m-uk-verbalizer 模块");
        }
        Path dir = modelPath.getParent();

        // tokenizer.json 不在磁盘时，从 classpath 整目录抽取（模型 jar 内嵌）
        Path tokPath = dir != null ? dir.resolve("tokenizer.json") : null;
        if (tokPath == null || !Files.exists(tokPath)) {
            log.debug("[GemmaVerbalizer] tokenizer.json not found in {}, extracting from classpath...", dir);
            NativeLoader.of("gemma-3-270m-uk-verbalizer-resources")
                    .from(GemmaVerbalizerTranslator.class.getClassLoader())
                    .basePath("models/gemma-3-270m-uk-verbalizer/")
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
        tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokPath)
                .optMaxLength(MAX_INPUT_LENGTH)
                .build();
        log.info("[GemmaVerbalizer] tokenizer loaded: {}", tokPath);

        ortEnv = OrtEnvironment.getEnvironment();
        SessionOptions opts = new SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        // 该模型在 Java ORT 图优化阶段易 bad allocation，禁用优化（NO_OPT）
        opts.setOptimizationLevel(SessionOptions.OptLevel.NO_OPT);
        // 关闭 CPU arena：反量化嵌入权重需大块连续内存，默认 BFC arena 无法分配
        opts.setCPUArenaAllocator(false);
        if (useGpu) {
            opts.addCUDA();
        }
        session = ortEnv.createSession(modelPath.toString(), opts);
        initialized = true;

        // 读 KV 维度 + 动态层数
        try {
            int maxLayer = 0;
            for (ai.onnxruntime.NodeInfo ni : session.getInputInfo().values()) {
                String nm = ni.getName();
                if (nm.startsWith("past_key_values.") && nm.endsWith(".key")) {
                    String mid = nm.substring("past_key_values.".length(), nm.indexOf(".key"));
                    try {
                        int layer = Integer.parseInt(mid);
                        if (layer > maxLayer) {
                            maxLayer = layer;
                        }
                    } catch (NumberFormatException ignore) {
                    }
                    ai.onnxruntime.TensorInfo ti = (ai.onnxruntime.TensorInfo) ni.getInfo();
                    long[] s = ti.getShape();
                    if (s != null && s.length == 4) {
                        kvDim = (int) s[3];
                    }
                }
            }
            nLayers = maxLayer + 1;
        } catch (Exception ignore) {
        }
        log.info("[GemmaVerbalizer] ORT session ready (gpu={}, kvDim={}, layers={}) model={}", useGpu, kvDim, nLayers, modelPath);
    }

    /**
     * 构造空 past（首步用，shape [1, kvHeads, 0, kvDim]）。
     */
    private Map<String, OnnxTensor> emptyPast() throws Exception {
        Map<String, OnnxTensor> m = new HashMap<>();
        long[] shape = new long[]{1, KV_HEADS, 0, kvDim};
        float[] empty = new float[0];
        for (int layer = 0; layer < nLayers; layer++) {
            m.put("past_key_values." + layer + ".key",
                    OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(empty), shape));
            m.put("past_key_values." + layer + ".value",
                    OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(empty), shape));
        }
        return m;
    }

    /**
     * 从推理结果收集 present -> 新 past。
     */
    private Map<String, OnnxTensor> collectPast(OrtSession.Result result) throws Exception {
        Map<String, OnnxTensor> m = new HashMap<>();
        for (int layer = 0; layer < nLayers; layer++) {
            float[][][][] k = (float[][][][]) result.get("present." + layer + ".key").orElseThrow().getValue();
            float[][][][] v = (float[][][][]) result.get("present." + layer + ".value").orElseThrow().getValue();
            m.put("past_key_values." + layer + ".key", OnnxTensor.createTensor(ortEnv, k));
            m.put("past_key_values." + layer + ".value", OnnxTensor.createTensor(ortEnv, v));
        }
        return m;
    }

    private static long[] ones(int n) {
        long[] r = new long[n];
        for (int i = 0; i < n; i++) {
            r[i] = 1L;
        }
        return r;
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
