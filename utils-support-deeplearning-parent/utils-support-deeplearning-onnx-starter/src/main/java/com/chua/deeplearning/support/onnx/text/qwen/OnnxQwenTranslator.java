package com.chua.deeplearning.support.onnx.text.qwen;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.onnx.text.minimind.MiniMindTokenizer;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Qwen2.5-Instruct ONNX 因果语言模型（onnxruntime 直连 + KV cache 自回归）。
 *
 * <p>模型为 HF decoder-with-past 导出（59 输入：input_ids / attention_mask / position_ids
 * + 28 层 past_key_values），首步传空 cache，后续步注入上一步的 present KV。
 * 支持 CUDA EP（{@code useGpu}）。资源由 ModelRegistry downloadUrl 拉取。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OnnxQwenTranslator implements ITranslator<String, String>, AutoCloseable {

    private final String modelId;
    private final boolean useGpu;
    private static final int MAX_NEW_TOKENS = 128;

    private MiniMindTokenizer tokenizer;
    private OrtEnvironment ortEnv;
    private OrtSession session;
    private volatile boolean initialized;
    private int kvDim = 128;
    /** 隐藏层数（从模型输入动态解析，0.5B=24 / 1.5B=28） */
    private int nLayers = 24;

    public OnnxQwenTranslator() {
        this("qwen2-0.5b-onnx", false);
    }

    public OnnxQwenTranslator(String modelId, boolean useGpu) {
        this.modelId = modelId;
        this.useGpu = useGpu;
    }

    /**
     * 从 URL 下载资源到模型目录（若不存在），供外部权重 / tokenizer 补充下载。
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
                try { Files.deleteIfExists(target); } catch (Exception ignore) {}
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
        tokenizer = MiniMindTokenizer.load(tokPath);
        log.info("[QwenOnnx] tokenizer loaded, vocab={}", tokenizer.vocabSize());

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

    public String chat(String userPrompt) throws Exception {
        prepare();
        String chat = "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n"
                + "<|im_start|>user\n" + (userPrompt == null ? "" : userPrompt) + "<|im_end|>\n"
                + "<|im_start|>assistant\n";
        int[] ids = tokenizer.encode(chat);
        StringBuilder out = new StringBuilder();

        long[] inputIds = toLong(ids);
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
                int next = argmax(logits[0][last]);

                if (isEos(next)) {
                    for (OnnxTensor t : past.values()) { try { t.close(); } catch (Exception ignore) {} }
                    break;
                }
                String tokText = tokenizer.decode(new int[]{next});
                if (tokText.contains("<|im_end|>") || tokText.contains("<|endoftext|>")) {
                    for (OnnxTensor t : past.values()) { try { t.close(); } catch (Exception ignore) {} }
                    break;
                }
                out.append(tokText);
                generated++;

                // 收集 present -> 新 past（仅在被裁剪前）
                // position_ids：新 token 的位置 = 当前已处理总长度（0-indexed）
                long nextPos = totalSteps;
                for (OnnxTensor t : past.values()) { try { t.close(); } catch (Exception ignore) {} }
                past = collectPast(result);
                inputIds = new long[]{next};
                attMask = new long[]{1L};
                posIds = new long[]{nextPos};
                totalSteps++;
                first = false;
            }
        }
        for (OnnxTensor t : past.values()) { try { t.close(); } catch (Exception ignore) {} }
        return out.toString().trim();
    }

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

    private boolean isEos(int id) {
        Integer imEnd = tokenizer.addedTokenId("<|im_end|>");
        if (imEnd != null && imEnd >= 0 && id == imEnd) {
            return true;
        }
        Integer eot = tokenizer.addedTokenId("<|endoftext|>");
        return eot != null && eot >= 0 && id == eot;
    }

    private static long[] toLong(int[] arr) {
        long[] r = new long[arr.length];
        for (int i = 0; i < arr.length; i++) {
            r[i] = arr[i];
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
            try { session.close(); } catch (Exception ignore) {}
        }
        ortEnv = null;
        initialized = false;
    }
}