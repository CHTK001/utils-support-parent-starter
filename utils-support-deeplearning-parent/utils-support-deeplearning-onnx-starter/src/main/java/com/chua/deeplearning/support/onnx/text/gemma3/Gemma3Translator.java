package com.chua.deeplearning.support.onnx.text.gemma3;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.SessionOptions;
import com.chua.common.support.env.CudaEnvironmentInstaller;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.ai.DetectionConfiguration;
import com.chua.deeplearning.support.engine.KvCacheDecoder;
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
* 输出 {@code logits}，无 KV 缓存 —— 采用朴素自回归解码：每步前向喂入完整序列，
* 取最后位置 logits 贪心取下一个 令牌，直至 EOS / {@code <end_of_turn>}。
* </p>
* <p>
* 推理流程：gemma-3 对话 模板（{@code <bos><start_of_turn>user\n...<end_of_turn>\n<start_of_turn>model\n}）
* → 原版 tokenizer 编码 → ORT 贪心自回归解码 → 跳过特殊 令牌 解码输出。
* </p>
* <p>
* 模型文件嵌入式存放于 模型-父 模块
* {@code utils-support-models-onnx-gemma-3-270m}
* （类路径: {@code models/gemma-3-270m/}）。
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
    * 最大生成 令牌 数（可用 -Dgemma.最大新令牌=N 覆盖）。
    */
    private static final int MAX_NEW_TOKENS =
            Integer.getInteger("gemma.maxNewTokens", 96);

    /**
    * 最大输入 令牌 数（超出截断，防止长文本 OOM）
    */
    private static final int MAX_INPUT_LENGTH = 2048;

    /**
    * BOS 令牌 标识（{@code <bos>}）
    */
    private static final long BOS_TOKEN_ID = 2L;

    /**
    * EOS 令牌 标识（{@code <eos>}）
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

    private final String modelId; // 模型标识
    private final boolean useGpu; // usegpu

    /** 采样温度（>0 时启用温度采样；0 = 纯贪心） */
    private final float temperature;
    /** 重复惩罚系数（>=1，1 = 不惩罚） */
    private final float repeatPenalty;
    /** top-k 采样候选数（>0 启用，0 = 不限制） */
    private final int topK;

    private HuggingFaceTokenizer tokenizer; // tokenizer
    private OrtEnvironment ortEnv; // ortenv
    private OrtSession session; // 会话
    private volatile boolean initialized; // 初始化

    /**
    * 是否为 KV 缓存 版模型（含 past_键_值/present 输入输出，如 transformers.js 导出）
    */
    private boolean kvCacheModel;

    /** 多轮对话历史（交替存储 用户 / assistant 文本） */
    private final List<String> history = new ArrayList<>();

    /** 多轮对话总结指令 */
    private static final String SUMMARIZE_PROMPT =
            "请用简洁的中文总结我们刚才的整个对话内容，说明用户询问了什么、你给出了什么答复。";

    /**
    * 构造默认模型翻译器。
    */
    public Gemma3Translator() {
        this(DEFAULT_MODEL_ID, false);
    }

    /**
    * 构造翻译器。
    *
    * @param modelId 模型标识（模型registry 注册名）
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
 // gemma-3 对话 模板（与 transformers apply_对话_template 一致）：
        // <bos><start_of_turn>user\n{content}<end_of_turn>\n<start_of_turn>model\n
        String prompt = "<bos><start_of_turn>user\n" + content + "<end_of_turn>\n<start_of_turn>model\n";
        return generate(prompt);
    }

    /**
    * 多轮对话：携带全部历史拼接 对话 模板生成回复，并追加到历史。
    *
    * @param userPrompt 本轮用户输入
    * @return 模型回复
    * @throws Exception 推理异常
    */
    public String chatTurn(String userPrompt) throws Exception {
        prepare();
        String content = userPrompt == null ? "" : userPrompt.trim();
        StringBuilder sb = new StringBuilder("<bos>");
 // 历史轮次：用户 / assistant 交替
        for (int i = 0; i + 1 < history.size(); i += 2) {
            sb.append("<start_of_turn>user\n").append(history.get(i))
                    .append("<end_of_turn>\n<start_of_turn>model\n")
                    .append(history.get(i + 1)).append("<end_of_turn>\n");
        }
        sb.append("<start_of_turn>user\n").append(content).append("<end_of_turn>\n<start_of_turn>model\n");
        String reply = generate(sb.toString());
        history.add(content);
        history.add(reply);
        return reply;
    }

    /**
    * 总结当前多轮对话：以总结指令收尾，复用同一生成链路。
    *
    * @return 对话总结
    * @throws Exception 推理异常
    */
    public String summarize() throws Exception {
        prepare();
        if (history.isEmpty()) {
            return "（暂无对话内容可总结）";
        }
        StringBuilder sb = new StringBuilder("<bos>");
        for (int i = 0; i + 1 < history.size(); i += 2) {
            sb.append("<start_of_turn>user\n").append(history.get(i))
                    .append("<end_of_turn>\n<start_of_turn>model\n")
                    .append(history.get(i + 1)).append("<end_of_turn>\n");
        }
        sb.append("<start_of_turn>user\n").append(SUMMARIZE_PROMPT).append("<end_of_turn>\n<start_of_turn>model\n");
        return generate(sb.toString());
    }

    /**
    * 清空多轮对话历史。
    */
    public void resetHistory() {
        history.clear();
    }

    /**
    * 多轮对话历史条目数（用户+assistant 各计一条；偶数 = 完整轮次 ×2）。
    * @return 历史大小的结果
    */
    public int historySize() {
        return history.size();
    }

    /**
    * 按完整 gemma-3 对话 模板执行生成（模板已含 {@code <bos>} 与 {@code <start_of_turn>} 收尾）。
    * @param fullPrompt 完整提示符
    * @return generate的结果
    */
    private String generate(String fullPrompt) throws Exception {
        Encoding enc = tokenizer.encode(fullPrompt);
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

        if (kvCacheModel) {
 // KV 缓存 版（transformers.js 导出）：每步只传 1 个新 令牌 + 上一步 present，
 // 由公用 kv缓存解码器 管理 past 生命周期，避免重复计算整个上下文
            try (KvCacheDecoder decoder = KvCacheDecoder.of(ortEnv, session)) {
                float[] lastLogits = decoder.step(toLongArray(tokens), ones(tokens.size()));
                while (stepCount < MAX_NEW_TOKENS) {
                    int next = nextToken(lastLogits, tokens, promptLen);
                    if (next == EOS_TOKEN_ID || next == END_OF_TURN_TOKEN_ID) {
                        break;
                    }
                    tokens.add((long) next);
                    stepCount++;
                    if (isDegenerate(tokens, promptLen)) {
                        log.info("[Gemma3] 检测到重复循环，提前终止（已生成 {} tokens）", stepCount);
                        break;
                    }
                    lastLogits = decoder.step(new long[]{next}, ones(decoder.totalSeqLen() + 1));
                }
            }
        } else {
 // 朴素自回归（无 KV 缓存）：每步前向完整序列，取最后位置 logits
            while (stepCount < MAX_NEW_TOKENS) {
                long[] ids = toLongArray(tokens);
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("input_ids", OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(ids), new long[]{1, ids.length}));
                inputs.put("attention_mask", OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(ones(ids.length)), new long[]{1, ids.length}));
                inputs.put("position_ids", OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(range(ids.length)), new long[]{1, ids.length}));

                try (OrtSession.Result result = session.run(inputs)) {
                    float[][][] logits = (float[][][]) result.get(0).getValue(); // [P3C 四十一 豁免] OrtSession.Result 模型输出索引（非 List/Collection）
                    int last = logits[0].length - 1;
                    int next = nextToken(logits[0][last], tokens, promptLen);

                    if (next == EOS_TOKEN_ID || next == END_OF_TURN_TOKEN_ID) {
                        break;
                    }
                    tokens.add((long) next);
                    stepCount++;
                    // 早停：检测到已生成片段出现重复周期（如连续重复或循环）时终止，避免无谓计算
                    if (isDegenerate(tokens, promptLen)) {
                        log.info("[Gemma3] 检测到重复循环，提前终止（已生成 {} tokens）", stepCount);
                        break;
                    }
                }
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
    * 从 logits 选择下一个 令牌。
    * <p>应用重复惩罚（对已生成 token 降权）后，按采样策略选择：
    * temperature&gt;0 时做温度 + top-k 采样，否则纯贪心。</p>
    *
    * @param logits    最后一个位置 logits（词表大小）
    * @param tokens    当前完整 令牌 序列（含 提示符 与已生成）
    * @param promptLen 提示符 长度，仅对 提示符 之后的生成 令牌 施加重复惩罚
    * @return 选中的 令牌 标识
    */
    private int nextToken(float[] logits, List<Long> tokens, int promptLen) {
        float[] scores = logits.clone();
        if (repeatPenalty > 1f) {
 // 对已生成的 令牌 施加重复惩罚（跳过 提示符，避免抑制关键词）
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
    * @return 采样得到的 令牌 标识
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

    /**
    * argmax。
    * @param logits logits
    * @return argmax的结果
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

    /**
    * 检测已生成片段是否陷入退化解（重复/循环），用于提前终止。
    * <p>策略：取最近生成的若干 token，检测是否存在长度 ≥2 的重复周期
    * （如 A A A...、AB AB AB...、ABC ABC... 等典型循环模式）。</p>
    *
    * @param tokens    完整 令牌 序列（含 提示符 与已生成）
    * @param promptLen 提示符 长度
    * @return true 表示已陷入重复，应终止生成
    */
    private static boolean isDegenerate(List<Long> tokens, int promptLen) {
        int gen = tokens.size() - promptLen;
        if (gen < 8) {
            return false;
        }
        int start = tokens.size() - Math.min(gen, 16);
        int window = tokens.size() - start;
 // 检测周期 p（2..窗口/2）：窗口尾部是否由该周期重复构成
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
    * 转为longarray。
    * @param list 列表
    * @return 转为longarray的结果
    */
    private static long[] toLongArray(List<Long> list) {
        long[] r = new long[list.size()];
        for (int i = 0; i < list.size(); i++) {
            r[i] = list.get(i);
        }
        return r;
    }

    /**
    * ones。
    * @param n n
    * @return ones的结果
    */
    private static long[] ones(int n) {
        long[] r = new long[n];
        for (int i = 0; i < n; i++) {
            r[i] = 1L;
        }
        return r;
    }

    /**
    * 范围。
    * @param n n
    * @return 范围的结果
    */
    private static long[] range(int n) {
        long[] r = new long[n];
        for (int i = 0; i < n; i++) {
            r[i] = i;
        }
        return r;
    }

    /**
    * 懒加载：解析模型路径（嵌入式 类路径 资源自动抽取）、加载 tokenizer 与 ORT 会话。
    */
    private synchronized void prepare() throws Exception {
        if (initialized) {
            return;
        }
        Path modelPath = ModelRegistry.resolveModelPath(modelId);
        if (modelPath == null || !Files.exists(modelPath)) {
 // 嵌入式回退：注册表无法定位时，从模型 jar 整目录抽取（含 模型.onnx 与 tokenizer）
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

 // tokenizer.json 不在磁盘时，从 类路径 整目录抽取（模型 jar 内嵌）
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
 // uint8 量化模型：必须保留默认图优化（全部_OPT）。
 // NO_OPT 下量化 matmul 走降级内核，需物化 ~671MB 反量化权重（262144×640 fp32）→ bad allocation，
 // 且输出 令牌 与优化路径不一致；全部_OPT 使用优化内核。
 // 同时关闭 bfcarena：lm_head 量化 matmul 的 ~135MB 临时缓冲在 BFC arena 下分配失败。
        opts.setCPUArenaAllocator(false);
        if (useGpu) {
            // 根据 DetectionConfiguration(useGpu=true) 触发 CUDA 环境检测；
            // 运行库缺失时异步执行 native-cuda 模块脚本安装，结果通过回调输出日志
            try {
                boolean cudaReady = CudaEnvironmentInstaller.ensureCudaRuntime();
                if (!cudaReady) {
                    log.warn("[Gemma3] CUDA 运行库未就绪，已触发异步安装；本次推理可能回退 CPU 或失败");
                }
            } catch (Throwable envEx) {
                log.debug("[Gemma3] CUDA 环境检测跳过: {}", envEx.getMessage());
            }
 // CUDA 提供者：默认图优化 + 默认 arena 策略（gemma 大词表 lm_head 临时缓冲较大，
            // 不宜过度收紧内存限制否则碎片导致 OOM）。gpu_mem_limit 可选，经 -Dgemma.gpuMemLimit=MB 设置。
            try {
                ai.onnxruntime.providers.OrtCUDAProviderOptions cuda =
                        new ai.onnxruntime.providers.OrtCUDAProviderOptions();
                long gpuMemLimit = Long.getLong("gemma.gpuMemLimit", 0L) * 1024L * 1024L;
                if (gpuMemLimit > 0L) {
                    cuda.add("gpu_mem_limit", String.valueOf(gpuMemLimit));
                    log.info("[Gemma3] CUDA provider 已配置 (gpu_mem_limit={}MB)", gpuMemLimit / (1024L * 1024L));
                } else {
                    log.info("[Gemma3] CUDA provider 使用默认配置（未设 gpu_mem_limit）");
                }
                opts.addCUDA(cuda);
            } catch (Exception providerEx) {
                log.warn("[Gemma3] CUDA provider 参数配置失败，回退默认 addCUDA(): {}", providerEx.getMessage());
                opts.addCUDA();
            }
        }
        session = ortEnv.createSession(modelPath.toString(), opts);
 // 探测是否为 KV 缓存 版（transformers.js 导出含 past_键_值 输入）
        kvCacheModel = KvCacheDecoder.isKvCacheModel(session);
        initialized = true;
        log.info("[Gemma3] ORT session ready (gpu={}, vocab={}, kvCache={}) model={}",
                useGpu, VOCAB_SIZE, kvCacheModel, modelPath);
    }

    @Override
    public void close() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignore) {}
        }
        ortEnv = null;
        initialized = false;
    }
}
