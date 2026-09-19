package com.chua.deeplearning.support.onnx.text.qwen;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 通义千问2.5-Instruct ONNX 因果语言模型（onnxruntime 直连 + KV 缓存 自回归）。
 *
 * <p>模型为 HF decoder-with-past 导出（59 输入：input_ids / attention_mask / position_ids
 * + 28 层 past_键_值），首步传空 缓存，后续步注入上一步的 present KV。
 * 支持 CUDA EP（{@code useGpu}）。资源由 模型registry downloadurl 拉取。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OnnxQwenTranslator implements ITranslator<String, String>, AutoCloseable {

    private final String modelId; // 模型标识
    private final boolean useGpu; // usegpu
    private static final int MAX_NEW_TOKENS = 128; // 最大新令牌

    private final float temperature; // temperature
    private final float repeatPenalty; // repeat罚款
    private final int topK; // topk

    private ai.djl.huggingface.tokenizers.HuggingFaceTokenizer tokenizer; // tokenizer
    private OrtEnvironment ortEnv; // ortenv
    private OrtSession session; // 会话
    private int eosTokenId = -1; // eos令牌标识
    private volatile boolean initialized; // 初始化
    private int kvDim = 128; // kvdim
    /**
     * 隐藏层数（从模型输入动态解析，0.5B=24 / 1.5B=28）
     * @param modelId 模型标识
     * @param useGpu usegpu
     * @param temperature temperature
     * @param repeatPenalty repeat罚款
     * @param topK topk
     */
    private int nLayers = 24;

    /**
     * 构造方法，创建 OnnxQwenTranslator 实例。
     */
    public OnnxQwenTranslator() {
        this("qwen2-1.5b-onnx", false);
    /**
     * onnx通义千问translator。
     * @param modelId 模型id
     * @param useGpu useGpu
     */
    }

    /**
     * 构造方法，创建 OnnxQwenTranslator 实例。
     *
     * @param modelId 模型ID，不允许为 null
     * @param useGpu useGpu（布尔开关）
     */
    public OnnxQwenTranslator(String modelId, boolean useGpu) {
        this(modelId, useGpu, 0.7f, 1.2f, 40);
    }

    /**
     * 构造方法，创建 OnnxQwenTranslator 实例。
     *
     * @param modelId 模型ID，不允许为 null
     * @param useGpu useGpu（布尔开关）
     * @param temperature 方法入参 temperature
     * @param repeatPenalty 方法入参 repeatPenalty
     * @param topK 顶部K，不允许为 null
     */
    public OnnxQwenTranslator(String modelId, boolean useGpu, float temperature, float repeatPenalty, int topK) {
        this.modelId = modelId;
        this.useGpu = useGpu;
        this.temperature = temperature;
        this.repeatPenalty = repeatPenalty;
        this.topK = topK;
    }

    /**
     * 从 URL 下载资源到模型目录（若不存在），供外部权重 / tokenizer 补充下载。
     * @param dir dir
     * @param fileName 文件名称
     * @param url url
     */
    private static void downloadIfMissing(Path dir, String fileName, String url) throws Exception {
        if (dir == null) {
            return;
        }
        Path target = dir.resolve(fileName);
        if (Files.exists(target) && Files.size(target) > 0) {
            return;
        }
        Files.createDirectories(dir);
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(600000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                int code = conn.getResponseCode();
                if (code != 200) {
                    throw new java.io.IOException("HTTP " + code);
                }
                try (java.io.InputStream in = conn.getInputStream()) {
                    Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                conn.disconnect();
                log.info("[QwenOnnx] 已下载 {} -> {} ({}}", fileName, target, Files.size(target));
                return;
            } catch (Exception e) {
                last = e;
                log.warn("[QwenOnnx] 下载 {} 失败(第{}次): {}", fileName, attempt + 1, e.getMessage());
                try {
                    Files.deleteIfExists(target);
                } catch (Exception ignore) {
                }
                Thread.sleep(3000L * (attempt + 1));
            }
        }
        throw last != null ? last : new java.io.IOException("下载失败: " + url);
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
            throw new RuntimeException("[QwenOnnx] 推理失败: " + e.getMessage(), e);
        }
    }

    /**
     * prepare。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    private synchronized void prepare() throws Exception {
        if (initialized) {
            return;
        }
        Path modelPath = ModelRegistry.resolveModelPath(modelId);
        if (modelPath == null || !Files.exists(modelPath)) {
            throw new IllegalStateException("模型文件不存在: " + modelPath);
        }
        Path dir = modelPath.getParent();
        Path tokPath = dir != null ? dir.resolve("tokenizer.json") : null;
        if (tokPath == null || !Files.exists(tokPath)) {
            downloadIfMissing(dir, "tokenizer.json",
                    "https://hf-mirror.com/onnx-community/Qwen2.5-1.5B-Instruct/resolve/main/tokenizer.json");
            tokPath = dir != null ? dir.resolve("tokenizer.json") : null;
        }
        if (tokPath == null || !Files.exists(tokPath)) {
            throw new IllegalStateException("tokenizer.json 不存在: " + tokPath);
        }
        tokenizer = ai.djl.huggingface.tokenizers.HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokPath)
                .optMaxLength(8192)
                .build();
        eosTokenId = resolveTokenId("<|im_end|>");
        if (eosTokenId < 0) {
            eosTokenId = resolveTokenId("<|endoftext|>");
        }
        log.info("[QwenOnnx] tokenizer loaded (DJL), eos={}", eosTokenId);

        // 检查外部权重：单文件模型（内嵌权重）无 .data；骨架模型（如 fp16）引用 <model>.data 需一并下载。
        // 通用策略：若同目录存在 <model名>.data 引用但文件缺失，则提示用户补充（多文件模型不宜自动猜 URL）。
        Path dataFile = dir != null ? dir.resolve(modelPath.getFileName().toString() + ".data") : null;
        if (dataFile != null && Files.exists(dataFile)) {
            log.info("[QwenOnnx] 外部权重已就绪: {}", dataFile);
        }

        ortEnv = OrtEnvironment.getEnvironment();
        SessionOptions opts = new SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        // 该模型在 Java ORT 图优化阶段 bad allocation，需禁用优化（NO_OPT）
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
        log.info("[QwenOnnx] ORT session ready (gpu={}, kvDim={}, layers={}) model={}", useGpu, kvDim, nLayers, modelPath);
    }

    /**
     * 对话。
     * @param userPrompt 用户提示符
     * @return 对话的结果
     */
    public String chat(String userPrompt) throws Exception {
        prepare();
        String chat = "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n"
                + "<|im_start|>user\n" + (userPrompt == null ? "" : userPrompt) + "<|im_end|>\n"
                + "<|im_start|>assistant\n";
        long[] ids = tokenizer.encode(chat).getIds();
        StringBuilder out = new StringBuilder();
        java.util.List<Long> tokens = new java.util.ArrayList<>();
        for (long id : ids) {
            tokens.add(id);
        }
        int promptLen = ids.length;

        long[] inputIds = ids;
        long[] attMask = ones(ids.length);
        long[] posIds = range(ids.length);

        Map<String, OnnxTensor> past = new HashMap<>();
        boolean first = true;
        int generated = 0;
        long totalSteps = ids.length;

        while (generated < MAX_NEW_TOKENS) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(inputIds), new long[]{1, inputIds.length}));
            inputs.put("attention_mask", OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(attMask), new long[]{1, attMask.length}));
            inputs.put("position_ids", OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(posIds), new long[]{1, posIds.length}));
            if (!first) {
                inputs.putAll(past);
            } else {
                inputs.putAll(emptyPast());
            }

            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] logits = (float[][][]) result.get(0).getValue();
                int last = logits[0].length - 1;
                int next = nextToken(logits[0][last], tokens, promptLen);

                if (isEos(next)) {
                    for (OnnxTensor t : past.values()) {
                        try {
                            t.close();
                        } catch (Exception ignore) {
                        }
                    }
                    break;
                }
                String tokText = tokenizer.decode(new long[]{next});
                if (tokText.contains("<|im_end|>") || tokText.contains("<|endoftext|>")) {
                    for (OnnxTensor t : past.values()) {
                        try {
                            t.close();
                        } catch (Exception ignore) {
                        }
                    }
                    break;
                }
                out.append(tokText);
                tokens.add((long) next);
                generated++;

                if (isDegenerate(tokens, promptLen)) {
                    log.info("[QwenOnnx] 检测到退化重复，提前终止 step={}", generated);
                    break;
                }

                // 收集 present -> 新 past（仅在被裁剪前）
                // position_ids：新 token 的位置 = 当前已处理总长度（0-indexed）
                long nextPos = totalSteps;
                for (OnnxTensor t : past.values()) {
                    try {
                        t.close();
                    } catch (Exception ignore) {
                    }
                }
                past = collectPast(result);
                inputIds = new long[]{next};
                attMask = new long[]{1L};
                posIds = new long[]{nextPos};
                totalSteps++;
                first = false;
            }
        }
        for (OnnxTensor t : past.values()) {
            try {
                t.close();
            } catch (Exception ignore) {
            }
        }
        return out.toString().trim();
    }

    /**
     * 空past。
     * @return 空past的结果
     */
    private Map<String, OnnxTensor> emptyPast() throws Exception {
        Map<String, OnnxTensor> m = new HashMap<>();
        for (int layer = 0; layer < nLayers; layer++) {
            long[] shape = new long[]{1, 2, 0, kvDim};
            float[] empty = new float[0];
            m.put("past_key_values." + layer + ".key",
                    OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(empty), shape));
            m.put("past_key_values." + layer + ".value",
                    OnnxTensor.createTensor(ortEnv, java.nio.FloatBuffer.wrap(empty), shape));
        }
        return m;
    }

    /**
     * collectpast。
     * @param result 结果
     * @return collectPast的结果
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

    /**
     * 是否eos。
     * @param id 标识
     * @return 是否eos的结果
     */
    private boolean isEos(int id) {
        return eosTokenId >= 0 && id == eosTokenId;
    }

    /**
     * 检测已生成片段是否陷入退化解（重复/循环），用于提前终止。
     * @param logits logits
     * @param tokens 令牌
     * @param promptLen 提示符len
     * @param n n
     * @param token 令牌
     * @return 是否degenerate的结果
     */
    private static boolean isDegenerate(java.util.List<Long> tokens, int promptLen) {
        int gen = tokens.size() - promptLen;
        if (gen < 8) {
            return false;
        }
        int start = tokens.size() - Math.min(gen, 16);
        int window = tokens.size() - start;
        for (int p = 2; p <= window / 2; p++) {
            if (window % p != 0) {
                continue;
            }
            boolean repeat = true;
            for (int i = start + p; i < tokens.size(); i++) {
                if (!tokens.get(i).equals(tokens.get(i - p))) {
                    repeat = false;
                    break;
                }
            }
            if (repeat) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析令牌ID。
     *
     * @param token 令牌，不允许为 null
     * @return 结果数值
     */
    private int resolveTokenId(String token) {
        try {
            long[] ids = tokenizer.encode(token).getIds();
            return ids != null && ids.length == 1 ? (int) ids[0] : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * ones。
     *
     * @param n 方法入参 n
     * @return 结果值
     */
    private static long[] ones(int n) {
        long[] r = new long[n];
        for (int i = 0; i < n; i++) {
            r[i] = 1L;
        }
        return r;
    /**
     * 范围。
     * @param n n
     * @return 范围的结果
     */
    }

    /**
     * range。
     *
     * @param n 方法入参 n
     * @return 结果值
     */
    private static long[] range(int n) {
        long[] r = new long[n];
        for (int i = 0; i < n; i++) {
            r[i] = i;
        }
        return r;
    /**
     * 下一个令牌。
     * @param logits logits
     * @param tokens 令牌
     * @param promptLen 提示符len
     * @return 下一个令牌的结果
     */
    }

    /**
     * 下一个令牌。
     *
     * @param logits 方法入参 logits
     * @param tokens 方法入参 tokens
     * @param promptLen 提示词Len，不允许为 null
     * @return 结果数值
     */
    private int nextToken(float[] logits, java.util.List<Long> tokens, int promptLen) {
        float[] scores = logits.clone();
        if (repeatPenalty > 1f) {
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
    /**
     * 样本。
     * @param scores scores
     * @param temp temp
     * @param k k
     * @return 样本的结果
     * @param logits logits
     */
    }

    /**
     * sample。
     *
     * @param scores 方法入参 scores
     * @param temp 方法入参 temp
     * @param k 方法入参 k
     * @return 结果数值
     */
    private int sample(float[] scores, float temp, int k) {
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
        double r = new java.util.Random().nextDouble() * sum;
        double cumulative = 0.0;
        for (int i = 0; i < scores.length; i++) {
            cumulative += probs[i];
            if (r < cumulative) {
                return i;
            }
        }
        return argmax(scores);
    }

    /**
     * argmax。
     *
     * @param logits 方法入参 logits
     * @return 结果数值
     */
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

    @Override
    public void close() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignore) {
            }
        }
        ortEnv = null;
        initialized = false;
    }
}
