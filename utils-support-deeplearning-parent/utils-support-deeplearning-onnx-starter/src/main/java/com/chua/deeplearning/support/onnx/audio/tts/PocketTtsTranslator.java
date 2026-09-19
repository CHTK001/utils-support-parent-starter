package com.chua.deeplearning.support.onnx.audio.tts;

import com.chua.common.support.utils.NativeLoader;
import lombok.extern.slf4j.Slf4j;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kyutai Pocket-TTS（100M 流匹配 TTS）嵌入式语音合成器。
 *
 * <p>流水线：文本 → BPE tokenizer（tokenizer.json）→ text_encoder.onnx（文本编码）
 * → 流.onnx（流匹配一致性采样，默认 4 步 Euler）→ mimi_解码器.onnx（Mimi 解码）→ 24khz WAV。</p>
 *
 * <p>零样本声音克隆：提供参考音频 WAV（{@link #synthesize(String, byte[])}），
 * 经 mimi_编码器.onnx 编码为说话人潜变量，作为 流 模型的参考音频输入参与条件生成。
 * mimi_编码器 缺省时回退默认音色（{@link #synthesize(String)}）。</p>
 *
 * <p>模型与张量名通过模型目录下 {@code config.json} 配置（兼容 sherpa-onnx / KevinAHM 等
 * 不同导出包的命名差异），未配置项按 dtype/shape 自动推断。模型约 225MB（int8），
 * 由 NAT加载 从 {@code audio/tts/pocket-tts/} 解压到缓存目录后加载。</p>
 *
 * <p>用法（由 OnnxTextToAudioClient 调度）：
 * <pre>{@code
 *   byte[] wav = new PocketTtsTranslator().synthesize("Hello world");
 *   byte[] wav2 = new PocketTtsTranslator().synthesize("Hello", referenceWavBytes); // 声音克隆
 * }</pre>vBytes); // 声音克隆
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class PocketTtsTranslator {

    /**
     * 输出采样率（Pocket-TTS 固定 24khz）
     */
    private static final int SAMPLE_RATE = 24000;

    /**
     * 流 LM sequence 输入的第三维大小（令牌 嵌入 维度）。
     * <p>Pocket-TTS 的 flow_lm_main.onnx 期望 sequence 形状为 [1, seqLen, 32]，
     * 不同于 latent_dim(8)，此为模型固定超参。</p>
     */
    private static final int SEQUENCE_EMBED_DIM = 32;

    /**
     * 默认最大输入 令牌 数
     */
    private static final int MAX_TEXT_LENGTH = 512;

    /**
     * 类路径 资源根路径
     */
    private static final String RESOURCE_BASE = "audio/tts/pocket-tts/";

    /**
     * 模型缓存根目录（音频/tts/）
     */
    private static final String CACHE_ROOT = "audio/tts/";

    /** ONNX 运行时环境 */
    private ai.onnxruntime.OrtEnvironment ortEnv;
    /** 文本编码器会话 */
    private ai.onnxruntime.OrtSession textEncoderSession;
    /** 流 LM 会话 */
    private ai.onnxruntime.OrtSession flowSession;
    /** Mimi 解码器会话 */
    private ai.onnxruntime.OrtSession mimiDecoderSession;
    /** Mimi 编码器会话 */
    private ai.onnxruntime.OrtSession mimiEncoderSession;
    /** 分词器 */
    private PocketTtsTokenizer tokenizer;

    /**
     * 各模型解析后的输入/输出张量名（prepare 时解析一次，推理复用）
     */
    private String textEncoderInputName;
    /** 文本编码器输出节点名称 */
    private String textEncoderOutputName;
    /** 流 LM x 输入节点名称 */
    private String flowXName;
    /** 流 LM t 时间步输入节点名称 */
    private String flowTName;
    /** 流 LM 空调 嵌入输入节点名称 */
    private String flowEmbName;
    /** 流 LM 掩码输入节点名称 */
    private String flowMaskName;
    /** 流 LM 参考音频输入节点名称 */
    private String flowRefName;
    /** 流 LM 速率 输出节点名称 */
    private String flowVName;
    /** Mimi 解码器输入节点名称 */
    private String mimiInputName;
    /** Mimi 解码器输出节点名称 */
    private String mimiOutputName;
    /** Mimi 编码器输入节点名称 */
    private String mimiEncoderInputName;
    /** Mimi 编码器输出节点名称 */
    private String mimiEncoderOutputName;

    /**
     * 流匹配采样步数（蒸馏一致性模型通常 4 步）
     */
    private int flowSteps = 4;

    /**
     * 潜变量维度（Mimi 为 8）
     */
    private int latentDim = 8;

    /**
     * 每 令牌 估算帧数（时长启发式，按真实模型校准）
     */
    private double framesPerToken = 4.0;

    /** 文本 编码器 条件向量维度 */
    private static final int CONDITIONING_DIM = 1024;
    /** 流 LM 状态 张量数量 */
    private static final int STATE_TENSOR_COUNT = 18;
    /** 最大帧数上限（防 OOM） */
    private static final int MAX_FRAMES = 4096;
    /** 配置.json 扁平化键值对解析正则 */
    private static final Pattern FLATTEN_JSON_KEY_PATTERN =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*");
    /** 最大帧数（可由 配置.json 覆盖） */
    private int maxFrames = MAX_FRAMES;

    /**
     * 参考音频潜变量布局："NCT" = [1, C, T]（Mimi 默认），"NTC" = [1, T, C]（兼容旧导出）
     */
    private String refLatentsLayout = "NCT";

    /** 是否已准备 */
    private volatile boolean prepared;

    /**
     * 构造合成器。
     */
    public PocketTtsTranslator() {
    }

    /**
     * 准备模型（懒加载）。
     *
     * @throws Exception 准备异常
     */
    private synchronized void prepare() throws Exception {
        if (prepared) {
            return;
        }
        Path modelDir = Path.of(cacheRoot(), CACHE_ROOT, "pocket-tts");
        if (!Files.isDirectory(modelDir) || !hasModelFiles(modelDir)) {
            NativeLoader.of("pocket-tts-resources")
                    .from(PocketTtsTranslator.class.getClassLoader())
                    .basePath(RESOURCE_BASE)
                    .toTarget(modelDir)
                    .glob("*")
                    .withMd5(true)
                    .extractOnly(true)
                    .load();
        }
        loadConfig(modelDir.resolve("config.json"));
        loadTokenizer(modelDir.resolve("vocab.json"));
        ortEnv = ai.onnxruntime.OrtEnvironment.getEnvironment();
        ai.onnxruntime.OrtSession.SessionOptions opts = new ai.onnxruntime.OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        textEncoderSession = ortEnv.createSession(modelDir.resolve(textEncoderFile()).toString(), opts);
        flowSession = ortEnv.createSession(modelDir.resolve(flowFile()).toString(), opts);
        mimiDecoderSession = ortEnv.createSession(modelDir.resolve(mimiDecoderFile()).toString(), opts);
 // mimi_编码器 用于零样本声音克隆；缺省（未下载）时回退默认音色
        Path mimiEncoderPath = modelDir.resolve(mimiEncoderFile());
        if (Files.exists(mimiEncoderPath)) {
            mimiEncoderSession = ortEnv.createSession(mimiEncoderPath.toString(), opts);
            log.info("[Pocket-TTS] 声音克隆就绪: {}", mimiEncoderPath);
        } else {
            log.warn("[Pocket-TTS] 未找到 {} ，零样本声音克隆不可用，将使用默认音色（可执行 scripts/fetch-pocket-tts.ps1 获取）", mimiEncoderFile());
        }
        resolveTensorNames();
        log.info("[Pocket-TTS] 模型加载完成: {} (flow_steps={}, latent_dim={})",
                modelDir, flowSteps, latentDim);
        prepared = true;
    }

    /**
     * 模型缓存根目录：优先读系统属性 {@code deeplearning.model.cache-dir}，
     * 未配置时回落 {@code %TEMP%}。
     *
     * @return 缓存根目录
     */
    private static String cacheRoot() {
        String prop = System.getProperty("deeplearning.model.cache-dir");
        return (prop != null && !prop.isBlank()) ? prop.trim() : System.getProperty("java.io.tmpdir");
    }

    /**
     * 是否拥有模型文件
     * @param modelDir 模型dir
     * @return 是否包含模型文件的结果
     */
    private boolean hasModelFiles(Path modelDir) {
        return Files.exists(modelDir.resolve(textEncoderFile()))
                && Files.exists(modelDir.resolve(flowFile()))
                && Files.exists(modelDir.resolve(mimiDecoderFile()));
    }

    /**
     * 解析各模型的输入/输出张量名（prepare 时一次解析，推理阶段复用，避免每次查原生元数据）。
     */
    private void resolveTensorNames() {
        textEncoderInputName = tensorName("text_encoder_input", "tokens", textEncoderSession, true, false);
        textEncoderOutputName = tensorName("text_encoder_output", "text_embeddings", textEncoderSession, false, true);
        // flow LM (lm_flow.int8.onnx) 输入名固定为 c/s/t/x，不依赖 config 推断
        flowXName = "x";
        flowTName = "t";
        flowEmbName = "c";
        flowVName = "flow_dir";
        flowRefName = null; // sherpa-onnx int8 导出无 ref_音频 输入
        mimiInputName = tensorName("mimi_input", "latents", mimiDecoderSession, true, true);
        mimiOutputName = tensorName("mimi_output", "waveform", mimiDecoderSession, false, true);
        if (mimiEncoderSession != null) {
            mimiEncoderInputName = tensorName("mimi_encoder_input", "waveform", mimiEncoderSession, true, true);
            mimiEncoderOutputName = tensorName("mimi_encoder_output", "latents", mimiEncoderSession, false, true);
        }
        flowRefName = resolveOptionalFloatInput("flow_ref_audio", "ref_audio");
        log.info("[Pocket-TTS] 张量名解析: flowX={} flowT={} flowEmb={} flowV={} flowRef={}",
                flowXName, flowTName, flowEmbName, flowVName, flowRefName);
    }

    /**
     * 解析可选的参考音频输入名：配置名存在则用之；否则在 流 输入中
     * 找非 x/t/文本_嵌入/mask 的剩余 float 张量（克隆模式导出特有）。
     *
     * @return 参考音频输入名；不存在返回 空（表示无克隆条件输入）
     * @param configKey 配置键
     * @param def def
     */
    private String resolveOptionalFloatInput(String configKey, String def) {
        String configured = configCache.get(configKey);
        try {
            Map<String, ?> meta = flowSession.getInputInfo();
            if (configured != null && !configured.isBlank() && meta.containsKey(configured)) {
                return configured;
            }
            if (configured != null && !configured.isBlank()) {
                log.warn("[Pocket-TTS] 配置张量名 {} 不存在于模型，尝试推断参考音频输入", configured);
            }
            // 排除已占用输入，找剩余 float 张量
            for (Map.Entry<String, ?> e : meta.entrySet()) {
                String name = e.getKey();
                if (name.equals(flowXName) || name.equals(flowTName)
                        || name.equals(flowEmbName) || name.equals(flowMaskName)) {
                    continue;
                }
                ai.onnxruntime.NodeInfo info = (ai.onnxruntime.NodeInfo) e.getValue();
                ai.onnxruntime.TensorInfo tensorInfo = (ai.onnxruntime.TensorInfo) info.getInfo();
                if (tensorInfo.type == ai.onnxruntime.OnnxJavaType.FLOAT) {
                    log.info("[Pocket-TTS] 推断参考音频输入: {}", name);
                    return name;
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[Pocket-TTS] 解析参考音频输入名异常: {}", e.getMessage());
            return null;
        }
    }

    // ==================== config.json 解析 ====================

    /**
     * 获取 文本 编码器 文件路径
     *
     * @return 文本编码器文件的结果
     */
    private String textEncoderFile() {
        return configStr("model_files.text_encoder", "text_encoder.onnx");
    }

    /**
     * 获取 流 LM 文件路径
     *
     * @return 流文件的结果
     */
    private String flowFile() {
        return configStr("model_files.flow", "flow.onnx");
    }

    /**
     * 获取 mimi 解码器 文件路径
     *
     * @return mimi解码器文件的结果
     */
    private String mimiDecoderFile() {
        return configStr("model_files.mimi_decoder", "mimi_decoder.onnx");
    }

    /**
     * 获取 mimi 编码器 文件路径
     *
     * @return mimi编码器文件的结果
     */
    private String mimiEncoderFile() {
        return configStr("model_files.mimi_encoder", "mimi_encoder.onnx");
    }

    /** 配置.json 扁平化缓存：点路径 → 值 */
    private final Map<String, String> configCache = new LinkedHashMap<>();

    /**
     * 从 配置.json 读取字符串（点路径），未配置时返回默认值。
     *
     * @param dotPath 点路径键（如 "模型_文件.文本_编码器"）
     * @param def     默认值
     * @return 配置值或默认值
     */
    private String configStr(String dotPath, String def) {
        return configCache.getOrDefault(dotPath, def);
    }

    /**
     * 从 配置.json 读取整型（点路径），未配置或格式错误时返回默认值。
     *
     * @param dotPath 点路径键
     * @param def     默认值
     * @return 配置整数值或默认值
     */
    private int configInt(String dotPath, int def) {
        String v = configCache.get(dotPath);
        if (v == null) {
            return def;
        }
        try {
            return (int) Math.round(Double.parseDouble(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * 从 配置.json 读取浮点值（点路径），未配置或格式错误时返回默认值。
     *
     * @param dotPath 点路径键
     * @param def     默认值
     * @return 配置浮点值或默认值
     */
    private double configDouble(String dotPath, double def) {
        String v = configCache.get(dotPath);
        if (v == null) {
            return def;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * 加载并扁平化 配置.json（支持 a.b 点路径）。
     * @param configPath 配置路径
     */
    private void loadConfig(Path configPath) throws Exception {
        if (configPath == null || !Files.exists(configPath)) {
            log.warn("[Pocket-TTS] config.json 不存在，使用默认配置");
            return;
        }
        String content = new String(Files.readAllBytes(configPath), java.nio.charset.StandardCharsets.UTF_8);
        flattenJson("", content, configCache);
        flowSteps = configInt("flow_steps", flowSteps);
        latentDim = configInt("latent_dim", latentDim);
        framesPerToken = configDouble("frames_per_token", framesPerToken);
        maxFrames = configInt("max_frames", maxFrames);
        refLatentsLayout = configStr("ref_latents_layout", refLatentsLayout);
    }

    /**
     * 简易 JSON 扁平化：把嵌套对象转为 {@code parent.key=value} 的点路径映射。
     * 仅支持对象/字符串/数字（配置.json 足够）。
     * @param prefix 前缀
     * @param json json
     * @param out 出
     */
    private static void flattenJson(String prefix, String json, Map<String, String> out) {
        // 逐层解析 { "key": value, "nested": { ... } }
        String body = json.trim();
        if (body.startsWith("{")) {
            body = body.substring(1);
        }
        if (body.endsWith("}")) {
            body = body.substring(0, body.length() - 1);
        }
        Matcher matcher = FLATTEN_JSON_KEY_PATTERN.matcher(body);
        int searchFrom = 0;
        while (matcher.find()) {
            String key = matcher.group(1);
            int valueStart = matcher.end();
            // 找到值的结束位置（对象按括号深度，字符串/数字按逗号）
            int end = findValueEnd(body, valueStart);
            if (end < 0) {
                break;
            }
            String value = body.substring(valueStart, end).trim();
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            if (value.startsWith("{")) {
                flattenJson(fullKey, value, out);
            } else {
                out.put(fullKey, unquote(value));
            }
            searchFrom = end;
            matcher.region(searchFrom, body.length());
        }
    }

    /**
     * find值结束。
     * @param s s
     * @param start 启动
     * @return find值结束的结果
     */
    private static int findValueEnd(String s, int start) {
        if (start >= s.length()) {
            return -1;
        }
        char c0 = s.charAt(start);
        if (c0 == '{') {
            int depth = 0;
            boolean inStr = false;
            for (int i = start; i < s.length(); i++) {
                char c = s.charAt(i);
                if (inStr) {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        inStr = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inStr = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                }
            }
            return s.length();
        }
        boolean inStr = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == ',') {
                return i;
            }
        }
        return s.length();
    }

    /**
     * unquote。
     * @param s s
     * @return unquote的结果
     */
    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return t;
    }

    // ==================== tokenizer ====================

    /**
     * 加载Tokenizer
     * @param tokenizerPath tokenizer路径
     */
    private void loadTokenizer(Path tokenizerPath) throws Exception {
 // 优先尝试 vocab.json（词表格式），兼容 sentencepiece .模型
        Path vocabPath = tokenizerPath.getParent().resolve("vocab.json");
        if (Files.exists(vocabPath)) {
            tokenizer = new PocketTtsTokenizer();
            tokenizer.load(vocabPath);
            log.info("[Pocket-TTS] Tokenizer loaded from vocab.json: {} tokens", tokenizer.vocabSize());
        } else if (tokenizerPath.toString().endsWith(".model") && Files.exists(tokenizerPath)) {
 // sentencepiece .模型 文件：使用 vocab.json 作为备选
            Path fallbackVocab = tokenizerPath.resolveSibling("vocab.json");
            if (Files.exists(fallbackVocab)) {
                tokenizer = new PocketTtsTokenizer();
                tokenizer.load(fallbackVocab);
            } else {
                throw new IllegalStateException("Pocket-TTS vocab.json 缺失: " + fallbackVocab);
            }
        } else {
            throw new IllegalStateException("Pocket-TTS tokenizer 文件缺失: " + tokenizerPath);
        }
    }

    /**
     * 文本转 令牌 ids（BPE）。
     * @param text 文本
     * @return encode的结果
     */
    private long[] encode(String text) {
        return tokenizer.encode(text);
    }

    // ==================== 推理 ====================

    /**
     * 文本转 WAV 字节（默认音色）。
     *
     * @param text 输入文本
     * @return WAV 音频字节
     */
    public byte[] synthesize(String text) {
        return synthesize(text, null);
    }

    /**
     * 文本转 WAV 字节（声音克隆：参考音频字节作为音色源）。
     *
     * @param text        输入文本
     * @param refAudioWav 参考音频 WAV 字节（用于零样本声音克隆）
     * @return WAV 音频字节
     */
    public byte[] voice(String text, byte[] refAudioWav) {
        return synthesize(text, refAudioWav);
    }

    /**
     * 获取当前是否支持声音克隆（mimi_编码器 已加载）。
     *
     * @return true 表示可用参考音频进行声音克隆
     */
    public boolean supportsVoiceClone() {
        return mimiEncoderSession != null && flowRefName != null;
    }

    /**
     * 文本转 WAV 字节（支持零样本声音克隆）。
     *
     * <p>参考音频为 24kHz 单声道 WAV 字节（或任意采样率，自动重采样）；
     * 经 mimi_编码器 编码为说话人潜变量后参与 流 条件生成。
     * 参考音频为空或 mimi_编码器 缺失时使用默认音色。</p>
     *
     * @param text      输入文本
     * @param refAudioWav 参考音频 WAV 字节（可空，克隆音色）
     * @return WAV 音频字节
     */
    public byte[] synthesize(String text, byte[] refAudioWav) {
        try {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("文本不能为空");
            }
            prepare();
            long[] ids = encode(text);
            float[] refLatents = encodeRefAudio(refAudioWav);
            float[] conditioning;
            int textLen;
            if (refLatents != null) {
 // 声音克隆：用参考音频投影特征作为 sequence，获取音色 空调
                conditioning = runTextConditioner(refLatents);
                textLen = ids.length;
            } else {
 // 默认音色：用文本 令牌 标识 作为 sequence
                conditioning = runTextConditioner(encodeToSequence(ids));
                textLen = ids.length;
            }
            float[] latents = runFlowMatching(conditioning, textLen, null);
            float[] waveform = runMimiDecoder(latents);
            return toWav(waveform, SAMPLE_RATE);
        } catch (Exception e) {
            throw new RuntimeException("Pocket-TTS 合成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 运行 lm_main（状态 流 LM）获取文本条件向量。
     * <p>将 token ids（或参考音频投影特征）转为 float sequence [1, seqLen, 32]，
     * 传入 流 LM 获取 空调 [1, 1024] + 初始 状态。</p>
     *
     * @param seqFloat 序列输入（令牌 标识 转 float 或参考音频投影特征）
     * @return conditioning 向量 [1024]
     */
    private float[] runTextConditioner(float[] seqFloat) throws Exception {
        int seqLen = seqFloat.length / SEQUENCE_EMBED_DIM;
        long[] seqShape = new long[]{1, seqLen, SEQUENCE_EMBED_DIM};
 // 空 文本_嵌入：让 LM 从 sequence 中提取说话人信息
        float[] emptyEmb = new float[0];

        try (ai.onnxruntime.OnnxTensor tSeq = ai.onnxruntime.OnnxTensor.createTensor(
                        ortEnv, FloatBuffer.wrap(seqFloat), seqShape);
             ai.onnxruntime.OnnxTensor tEmb = ai.onnxruntime.OnnxTensor.createTensor(
                         ortEnv, FloatBuffer.wrap(emptyEmb), new long[]{1, 0, CONDITIONING_DIM})) {

            Map<String, ai.onnxruntime.OnnxTensor> initInputs = new LinkedHashMap<>();
            initInputs.put("sequence", tSeq);
            initInputs.put("text_embeddings", tEmb);
            for (int i = 0; i < STATE_TENSOR_COUNT; i++) {
                initInputs.put("state_" + i, buildZeroStateTensor(i));
            }

            try (ai.onnxruntime.OrtSession.Result initResult = textEncoderSession.run(initInputs)) {
                ai.onnxruntime.OnnxTensor cond = (ai.onnxruntime.OnnxTensor) initResult.get("conditioning").get();
                FloatBuffer fb = cond.getFloatBuffer();
                float[] conditioning = new float[fb.remaining()];
                fb.get(conditioning);

 // 收集输出 状态 张量（流 LM 的 recurrent 状态，供后续帧使用）
                Map<String, ai.onnxruntime.OnnxTensor> initState = new LinkedHashMap<>();
                for (int i = 0; i < STATE_TENSOR_COUNT; i++) {
                    try {
                        ai.onnxruntime.OnnxTensor outState = (ai.onnxruntime.OnnxTensor) initResult.get("out_state_" + i).get();
                        FloatBuffer sb = outState.getFloatBuffer();
                        float[] stateData = new float[sb.remaining()];
                        sb.get(stateData);
                        initState.put("state_" + i, ai.onnxruntime.OnnxTensor.createTensor(
                                ortEnv, FloatBuffer.wrap(stateData), ((ai.onnxruntime.TensorInfo) outState.getInfo()).getShape()));
                    } catch (Exception e) {
                        log.debug("[Pocket-TTS] out_state_{} 不在输出中，跳过", i);
                    }
                }
                this.flowState = initState;
                log.info("[Pocket-TTS] Conditioning 获取成功: {} dimensions", conditioning.length);
                return conditioning;
            }
        }
    }

    /**
     * 将 令牌 标识 转为 float sequence [1, seqlen, 32]。
     * @param ids 标识
     * @return encode转为sequence的结果
     */
    private float[] encodeToSequence(long[] ids) {
        int seqLen = ids.length;
        float[] seqFloat = new float[seqLen * SEQUENCE_EMBED_DIM];
        for (int i = 0; i < seqLen; i++) {
            float val = (float) ids[i];
            for (int d = 0; d < SEQUENCE_EMBED_DIM; d++) {
                seqFloat[i * SEQUENCE_EMBED_DIM + d] = val;
            }
        }
        return seqFloat;
    }

    /**
     * 构建零初始化 状态 张量。
     * <p>根据模型元数据中的实际 dtype（float32 / int64）创建对应类型的零张量，
     * 形状严格遵循模型定义（包含 0 维的空张量）。</p>
     * @param index 索引
     * @return 构建zero状态tensor的结果
     */
    private ai.onnxruntime.OnnxTensor buildZeroStateTensor(int index) throws Exception {
        try {
            Map<String, ?> meta = textEncoderSession.getInputInfo();
            String name = "state_" + index;
            if (!meta.containsKey(name)) {
                return null;
            }
            ai.onnxruntime.NodeInfo info = (ai.onnxruntime.NodeInfo) meta.get(name);
            ai.onnxruntime.TensorInfo ti = (ai.onnxruntime.TensorInfo) info.getInfo();
            long[] shape = ti.getShape();
 // ONNX Runtime 1.x: tensor信息.类型 是 公共 最终 字段，不是方法
            boolean isFloat = ti.type == ai.onnxruntime.OnnxJavaType.FLOAT;
            // 严格遵循模型形状，包括 0 维张量
            if (shape.length == 1 && shape[0] == 0) {
 // 空张量：用空 缓冲 创建
                if (isFloat) {
                    return ai.onnxruntime.OnnxTensor.createTensor(ortEnv,
                            FloatBuffer.wrap(new float[0]), shape);
                }
                return ai.onnxruntime.OnnxTensor.createTensor(ortEnv,
                        LongBuffer.wrap(new long[0]), shape);
            }
            long total = 1;
            for (long s : shape) {
                total *= s;
            }
            if (isFloat) {
                return ai.onnxruntime.OnnxTensor.createTensor(ortEnv,
                        FloatBuffer.wrap(new float[(int) total]), shape);
            }
            return ai.onnxruntime.OnnxTensor.createTensor(ortEnv,
                    LongBuffer.wrap(new long[(int) total]), shape);
        } catch (Exception e) {
            log.warn("[Pocket-TTS] 构建 state_{} 张量失败: {}", index, e.getMessage());
            return null;
        }
    }

    /** 流 LM 当前 状态（由 运行文本条件 初始化） */
    private Map<String, ai.onnxruntime.OnnxTensor> flowState = new LinkedHashMap<>();

    /**
     * 获取状态tensorshape。
     * @param inName 入名称
     * @return 获取状态tensorshape的结果
     */
    private long[] getStateTensorShape(String inName) {
        try {
            Map<String, ?> meta = textEncoderSession.getInputInfo();
            if (meta.containsKey(inName)) {
                ai.onnxruntime.NodeInfo info = (ai.onnxruntime.NodeInfo) meta.get(inName);
                return ((ai.onnxruntime.TensorInfo) info.getInfo()).getShape();
            }
        } catch (Exception e) {
            log.warn("[Pocket-TTS] 获取 state 张量形状失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 编码参考音频为说话人潜变量（零样本声音克隆）。
     *
     * <p>WAV 字节 → 24kHz 单声道 float 波形 → mimi_encoder.onnx → 1024维特征 → 投影到32维 → 供 decoder 使用。</p>
     *
     * @param refAudioWav 参考音频 WAV 字节；为空或 mimi_编码器 缺失时返回 空（默认音色）
     * @return 32维潜变量（扁平数组，长度 = seqlen * 32）；空 表示使用默认音色
     */
    private float[] encodeRefAudio(byte[] refAudioWav) throws Exception {
        if (refAudioWav == null || refAudioWav.length == 0) {
            return null;
        }
        if (mimiEncoderSession == null) {
            log.debug("[Pocket-TTS] mimi_encoder 缺失，忽略参考音频，使用默认音色");
            return null;
        }
        float[] waveform = decodeWavToFloat(refAudioWav);
        if (waveform.length == 0) {
            log.warn("[Pocket-TTS] 参考音频解码为空，使用默认音色");
            return null;
        }
        long[] shape = new long[]{1, 1, waveform.length};
        try (ai.onnxruntime.OnnxTensor tWave = ai.onnxruntime.OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(waveform), shape)) {
            Map<String, ai.onnxruntime.OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put(mimiEncoderInputName, tWave);
            try (ai.onnxruntime.OrtSession.Result result = mimiEncoderSession.run(inputs)) {
                ai.onnxruntime.OnnxTensor latents = (ai.onnxruntime.OnnxTensor) result.get(mimiEncoderOutputName).get();
                FloatBuffer fb = latents.getFloatBuffer();
                float[] encOut = new float[fb.remaining()];
                fb.get(encOut);
 // mimi_编码器 输出 [批量, 帧, 1024]，投影到 [seqlen, 32]
 // 编码器dim 从模型元数据获取（1024），非 waveform 长度
                int encoderDim = latentDim * 128; // mimi_编码器 固定输出维度 1024
 // 更可靠：从 输出 shape 推断
                long[] encShape = ((ai.onnxruntime.TensorInfo) ((ai.onnxruntime.NodeInfo)
                        mimiEncoderSession.getOutputInfo().get(mimiEncoderOutputName)).getInfo()).getShape();
                if (encShape.length >= 3) {
                    encoderDim = (int) encShape[2];
                }
                int frames = encOut.length / encoderDim;
                int projDim = SEQUENCE_EMBED_DIM;
                float[] projected = new float[frames * projDim];
                int step = encoderDim / projDim;
                for (int f = 0; f < frames; f++) {
                    int offset = f * encoderDim;
                    for (int d = 0; d < projDim; d++) {
                        float sum = 0;
                        for (int k = d; k < encoderDim; k += step) {
                            sum += encOut[offset + k];
                        }
                        projected[f * projDim + d] = sum / step;
                    }
                }
                refLatentsLen = projected.length;
                log.info("[Pocket-TTS] 参考音频编码完成: {} samples -> {} frames x {} dim",
                        waveform.length, frames, projDim);
                return projected;
            }
        }
    }

    /**
     * 流匹配一致性采样：逐帧运行 流 LM，Euler 积分生成潜变量。
     *
     * <p>flow_lm_flow.onnx 接受单帧输入 x [1, 32]，
     * 需逐帧迭代。每步：x += v / 流steps，其中 v 来自 流 模型。</p>
     * @param conditioning 空调
     * @param textLen 文本len
     * @param refLatents reflatents
     * @return 运行流匹配的结果
     */
    private float[] runFlowMatching(float[] conditioning, int textLen, float[] refLatents) throws Exception {
        int frames = (int) Math.max(1, Math.round(textLen * framesPerToken));
        frames = Math.min(frames, maxFrames);
        int totalSize = frames * SEQUENCE_EMBED_DIM;
        float[] x = new float[totalSize];
        Random random = new Random(0);
        for (int i = 0; i < totalSize; i++) {
            x[i] = (float) random.nextGaussian();
        }

        float dt = 1.0f / flowSteps;
 // 逐帧迭代：流_lm_流 只接受 2D 输入 [批量, latent_dim]
        for (int step = 0; step < flowSteps; step++) {
            float s = step * dt; // step 索引 for 流 模型
            float t = (step + 1) * dt; // 时间
            for (int f = 0; f < frames; f++) {
                // 提取单帧 x[f]
                float[] frameX = new float[SEQUENCE_EMBED_DIM];
                System.arraycopy(x, f * SEQUENCE_EMBED_DIM, frameX, 0, SEQUENCE_EMBED_DIM);
                float[] v = runFlowStep(frameX, s, t, conditioning, refLatents);
                // 更新单帧
                for (int d = 0; d < SEQUENCE_EMBED_DIM; d++) {
                    x[f * SEQUENCE_EMBED_DIM + d] += dt * v[d];
                }
            }
        }
        return x;
    }

    /**
     * 运行 流 LM 单帧：返回速度场 v [1, 32]。
     * <p>使用 lm_flow.onnx：输入 c(cond)[1,1024], s(step_idx)[1,1], t(time)[1,1], x(noise)[1,32] → flow_dir[1,32]。</p>
     */
    private float[] runFlowStep(float[] x, float s, float t, float[] conditioning,
                                float[] refLatents) throws Exception {
        long[] xShape = new long[]{1, SEQUENCE_EMBED_DIM};
        long[] sShape = new long[]{1, 1};
        long[] tShape = new long[]{1, 1};
        long[] cShape = new long[]{1, conditioning.length};

        Map<String, ai.onnxruntime.OnnxTensor> inputs = new LinkedHashMap<>();
        try (ai.onnxruntime.OnnxTensor tX = ai.onnxruntime.OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(x), xShape);
             ai.onnxruntime.OnnxTensor tS = ai.onnxruntime.OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(new float[]{s}), sShape);
             ai.onnxruntime.OnnxTensor tT = ai.onnxruntime.OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(new float[]{t}), tShape);
             ai.onnxruntime.OnnxTensor tC = ai.onnxruntime.OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(conditioning), cShape)) {
            inputs.put(flowXName, tX);
            inputs.put("s", tS);
            inputs.put("t", tT);
            inputs.put(flowEmbName, tC);
            try (ai.onnxruntime.OrtSession.Result result = flowSession.run(inputs)) {
                ai.onnxruntime.OnnxTensor vTensor = (ai.onnxruntime.OnnxTensor) result.get(flowVName).get();
                FloatBuffer fb = vTensor.getFloatBuffer();
                float[] out = new float[fb.remaining()];
                fb.get(out);
                return out;
            }
        }
    }

    /**
     * 最近一次参考音频潜变量长度（由 encoderef音频 记录）。
     */
    private int refLatentsLen = 0;

    /**
     * 运行 mimi_解码器.onnx：latents [1, seq_len, 32] → waveform。
     * <p>同时传入零初始化 state 张量，decoder 为自回归模型。</p>
     * @param latents latents
     * @return 运行mimi解码器的结果
     */
    private float[] runMimiDecoder(float[] latents) throws Exception {
        int latentSize = SEQUENCE_EMBED_DIM;
        int frames = latents.length / latentSize;
        long[] shape = new long[]{1, frames, latentSize};
        try (ai.onnxruntime.OnnxTensor tLatents = ai.onnxruntime.OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(latents), shape)) {
            Map<String, ai.onnxruntime.OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put(mimiInputName, tLatents);
 // 注入零初始化 状态 张量
            for (ai.onnxruntime.NodeInfo info : mimiDecoderSession.getInputInfo().values()) {
                ai.onnxruntime.TensorInfo ti = (ai.onnxruntime.TensorInfo) info.getInfo();
                String name = info.getName();
                if (name.equals(mimiInputName)) {
                    continue;
                }
                if (!name.startsWith("state_")) {
                    continue;
                }
                long[] s = ti.getShape();
                if (ti.type == ai.onnxruntime.OnnxJavaType.BOOL) {
                    boolean[] b = new boolean[(int) s[0]];
                    java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(b.length);
                    for (int j = 0; j < b.length; j++) {
                        bb.put((byte) (b[j] ? 1 : 0));
                    }
                    bb.flip();
                    inputs.put(name, ai.onnxruntime.OnnxTensor.createTensor(ortEnv, bb, s, ai.onnxruntime.OnnxJavaType.BOOL));
                } else if (ti.type == ai.onnxruntime.OnnxJavaType.INT64) {
                    long[] l = new long[(int) s[0]];
                    inputs.put(name, ai.onnxruntime.OnnxTensor.createTensor(ortEnv, java.nio.LongBuffer.wrap(l), s));
                } else {
                    long total = 1;
                    for (long dim : s) {
                        total *= dim;
                    }
                    inputs.put(name, ai.onnxruntime.OnnxTensor.createTensor(
                            ortEnv, FloatBuffer.wrap(new float[(int) total]), s));
                }
            }
            try (ai.onnxruntime.OrtSession.Result result = mimiDecoderSession.run(inputs)) {
                ai.onnxruntime.OnnxTensor wave = (ai.onnxruntime.OnnxTensor) result.get(mimiOutputName).get();
                FloatBuffer fb = wave.getFloatBuffer();
                float[] out = new float[fb.remaining()];
                fb.get(out);
                return out;
            }
        }
    }

    /**
     * 解析张量名：优先 配置.json 中的配置名；若会话中不存在该名称，则按
     * dtype（int64 优先 / float 优先）从输入或输出元数据中推断。
     *
     * @param configKey  配置.json 点路径键
     * @param def        默认张量名
     * @param session    ORT 会话
     * @param inputSide  true 查输入 / false 查输出
     * @param preferFloat true 优先 float 张量（文本_嵌入 等）/ false 优先 int64（令牌/mask）
     */
    private String tensorName(String configKey, String def, ai.onnxruntime.OrtSession session,
                              boolean inputSide, boolean preferFloat) {
        String configured = configCache.get(configKey);
        if (configured != null && !configured.isBlank()) {
            try {
                Map<String, ?> meta = inputSide ? session.getInputInfo() : session.getOutputInfo();
                if (meta.containsKey(configured)) {
                    return configured;
                }
                log.warn("[Pocket-TTS] 配置张量名 {} 不存在于模型（{}），改为按 dtype/shape 推断；如音质异常请核对 config.json tensor_names",
                        configured, configKey);
            } catch (Exception e) {
                log.debug("[Pocket-TTS] 张量名 {} 元数据读取异常，按 dtype/shape 推断: {}", configKey, e.getMessage());
                // 忽略元数据异常，走推断
            }
        }
        try {
            Map<String, ?> meta = inputSide ? session.getInputInfo() : session.getOutputInfo();
            for (Map.Entry<String, ?> e : meta.entrySet()) {
                ai.onnxruntime.NodeInfo info = (ai.onnxruntime.NodeInfo) e.getValue();
                ai.onnxruntime.TensorInfo tensorInfo = (ai.onnxruntime.TensorInfo) info.getInfo();
                boolean isFloat = tensorInfo.type == ai.onnxruntime.OnnxJavaType.FLOAT;
                if (preferFloat == isFloat) {
                    return e.getKey();
                }
            }
            // 兜底：第一个
            return meta.keySet().iterator().next();
        } catch (Exception e) {
            return def;
        }
    }

    // ==================== WAV 解码 ====================

    /**
     * WAV 字节解码为 float 波形（-1.0~1.0）：支持任意采样率/声道数，自动重采样到 24khz 单声道。
     *
     * @param wavBytes WAV 字节
     * @return float 波形（单声道 24khz）
     * @throws Exception 解码异常
     */
    private static float[] decodeWavToFloat(byte[] wavBytes) throws Exception {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavBytes))) {
            AudioFormat srcFmt = ais.getFormat();
            // 尽早检查位深度，避免无效 I/O
            int bitsPerSample = srcFmt.getSampleSizeInBits();
            if (bitsPerSample != 16) {
                throw new IllegalArgumentException("参考音频仅支持 16-bit PCM WAV，当前: " + bitsPerSample + "-bit");
            }
 // 按 16-钻头 PCM 读取全部帧（获取帧长度 可能返回 -1，此时按 8KB 分块读）
            long frameLength = ais.getFrameLength();
            int frameSize = srcFmt.getFrameSize();
            byte[] raw;
            if (frameLength > 0) {
                raw = new byte[(int) (frameLength * frameSize)];
                int totalRead = 0;
                while (totalRead < raw.length) {
                    int n = ais.read(raw, totalRead, raw.length - totalRead);
                    if (n < 0) {
                        break;
                    }
                    totalRead += n;
                }
 // raw 可能因 部分 读取 而含尾部零，截断到实际读取长度
                if (totalRead < raw.length) {
                    raw = java.util.Arrays.copyOf(raw, totalRead);
                }
            } else {
                // 帧长度未知，分块读取
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = ais.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
                raw = baos.toByteArray();
            }
            // PCM → float [-1.0, 1.0]
            float[] mono = new float[raw.length / 2];
            for (int i = 0; i < mono.length; i++) {
                int lo = raw[i * 2] & 0xFF;
                int hi = raw[i * 2 + 1];
                mono[i] = (hi << 8 | lo) / 32768.0f;
            }
            // 多声道 → 单声道
            int channels = srcFmt.getChannels();
            if (channels > 1) {
                float[] merged = new float[mono.length / channels];
                for (int i = 0; i < merged.length; i++) {
                    float sum = 0;
                    for (int c = 0; c < channels; c++) {
                        sum += mono[i * channels + c];
                    }
                    merged[i] = sum / channels;
                }
                mono = merged;
            }
 // 重采样到 24khz
            float srcRate = srcFmt.getSampleRate();
            if (srcRate != SAMPLE_RATE && srcRate > 0) {
                int newLen = (int) (mono.length * (long) SAMPLE_RATE / srcRate);
                float[] resampled = new float[newLen];
                for (int i = 0; i < newLen; i++) {
                    double pos = (double) i * srcRate / SAMPLE_RATE;
                    int lo = (int) pos;
                    int hi = Math.min(lo + 1, mono.length - 1);
                    float frac = (float) (pos - lo);
                    resampled[i] = mono[lo] * (1 - frac) + mono[hi] * frac;
                }
                mono = resampled;
            }
            return mono;
        }
    }

    // ==================== WAV 输出 ====================

    /**
     * float 波形转 WAV 字节。
     *
     * @param samples 波形数据
     * @param rate    采样率
     * @return WAV 字节
     * @throws Exception 转换异常
     */
    private static byte[] toWav(float[] samples, int rate) throws Exception {
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            short s = (short) (Math.max(-1.0f, Math.min(1.0f, samples[i])) * Short.MAX_VALUE);
            pcm[i * 2] = (byte) (s & 0xFF);
            pcm[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcm), format, pcm.length / 2);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, baos);
            return baos.toByteArray();
        }
    }

    /**
     * 关闭资源。
     * @param s s
     */
    public void close() {
        closeQuietly(textEncoderSession);
        closeQuietly(flowSession);
        closeQuietly(mimiDecoderSession);
        closeQuietly(mimiEncoderSession);
        textEncoderSession = null;
        flowSession = null;
        mimiDecoderSession = null;
        mimiEncoderSession = null;
        prepared = false;
    }

    /**
     * 关闭Quietly。
     *
     * @param s 方法入参 s
     */
    private static void closeQuietly(ai.onnxruntime.OrtSession s) {
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
                log.debug("[Pocket-TTS] 关闭 ORT 会话时忽略异常");
            }
        }
    }
}
