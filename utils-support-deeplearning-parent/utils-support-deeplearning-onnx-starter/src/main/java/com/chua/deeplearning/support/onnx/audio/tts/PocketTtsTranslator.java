package com.chua.deeplearning.support.onnx.audio.tts;

import com.chua.common.support.utils.NativeLoader;
import lombok.extern.slf4j.Slf4j;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
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
 * → flow.onnx（流匹配一致性采样，默认 4 步 Euler）→ mimi_decoder.onnx（Mimi 解码）→ 24kHz WAV。</p>
 *
 * <p>零样本声音克隆：提供参考音频 WAV（{@link #synthesize(String, byte[])}），
 * 经 mimi_encoder.onnx 编码为说话人潜变量，作为 flow 模型的参考音频输入参与条件生成。
 * mimi_encoder 缺省时回退默认音色（{@link #synthesize(String)}）。</p>
 *
 * <p>模型与张量名通过模型目录下 {@code config.json} 配置（兼容 sherpa-onnx / KevinAHM 等
 * 不同导出包的命名差异），未配置项按 dtype/shape 自动推断。模型约 225MB（int8），
 * 由 NativeLoader 从 {@code audio/tts/pocket-tts/} 解压到缓存目录后加载。</p>
 *
 * <p>用法（由 OnnxTextToAudioClient 调度）：
 * <pre>{@code
 *   byte[] wav = new PocketTtsTranslator().synthesize("Hello world");
 *   byte[] wav2 = new PocketTtsTranslator().synthesize("Hello", referenceWavBytes); // 声音克隆
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class PocketTtsTranslator {

    /**
     * 输出采样率（Pocket-TTS 固定 24kHz）
     */
    private static final int SAMPLE_RATE = 24000;

    /**
     * 默认最大输入 token 数
     */
    private static final int MAX_TEXT_LENGTH = 512;

    /**
     * classpath 资源根路径
     */
    private static final String RESOURCE_BASE = "audio/tts/pocket-tts/";

    /**
     * 模型缓存根目录（audio/tts/）
     */
    private static final String CACHE_ROOT = "audio/tts/";

    private ai.onnxruntime.OrtEnvironment ortEnv;
    private ai.onnxruntime.OrtSession textEncoderSession;
    private ai.onnxruntime.OrtSession flowSession;
    private ai.onnxruntime.OrtSession mimiDecoderSession;
    private ai.onnxruntime.OrtSession mimiEncoderSession;
    private ai.djl.huggingface.tokenizers.HuggingFaceTokenizer tokenizer;

    /**
     * 各模型解析后的输入/输出张量名（prepare 时解析一次，推理复用）
     */
    private String textEncoderInputName;
    private String textEncoderOutputName;
    private String flowXName;
    private String flowTName;
    private String flowEmbName;
    private String flowMaskName;
    private String flowRefName;
    private String flowVName;
    private String mimiInputName;
    private String mimiOutputName;
    private String mimiEncoderInputName;
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
     * 每 token 估算帧数（时长启发式，按真实模型校准）
     */
    private double framesPerToken = 4.0;

    /**
     * 最大帧数上限（防 OOM）
     */
    private int maxFrames = 4096;

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
        loadConfig(modelDir.resolve("config.json"));
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
        loadTokenizer(modelDir.resolve("tokenizer.json"));
        ortEnv = ai.onnxruntime.OrtEnvironment.getEnvironment();
        ai.onnxruntime.OrtSession.SessionOptions opts = new ai.onnxruntime.OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        textEncoderSession = ortEnv.createSession(modelDir.resolve(textEncoderFile()).toString(), opts);
        flowSession = ortEnv.createSession(modelDir.resolve(flowFile()).toString(), opts);
        mimiDecoderSession = ortEnv.createSession(modelDir.resolve(mimiDecoderFile()).toString(), opts);
        // mimi_encoder 用于零样本声音克隆；缺省（未下载）时回退默认音色
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
        flowXName = tensorName("flow_x", "x", flowSession, true, true);
        flowTName = tensorName("flow_t", "t", flowSession, true, true);
        flowEmbName = tensorName("flow_text_embeddings", "text_embeddings", flowSession, true, true);
        flowMaskName = tensorName("flow_mask", "mask", flowSession, true, false);
        flowVName = tensorName("flow_output", "v", flowSession, false, true);
        mimiInputName = tensorName("mimi_input", "latents", mimiDecoderSession, true, true);
        mimiOutputName = tensorName("mimi_output", "waveform", mimiDecoderSession, false, true);
        if (mimiEncoderSession != null) {
            mimiEncoderInputName = tensorName("mimi_encoder_input", "waveform", mimiEncoderSession, true, true);
            mimiEncoderOutputName = tensorName("mimi_encoder_output", "latents", mimiEncoderSession, false, true);
        }
        // flow 参考音频输入（零样本克隆）：仅当会话确有该输入时启用
        flowRefName = resolveOptionalFloatInput("flow_ref_audio", "ref_audio");
    }

    /**
     * 解析可选的参考音频输入名：配置名存在则用之；否则在 flow 输入中
     * 找非 x/t/text_embeddings/mask 的剩余 float 张量（克隆模式导出特有）。
     *
     * @return 参考音频输入名；不存在返回 null（表示无克隆条件输入）
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
            return null;
        }
    }

    // ==================== config.json 解析 ====================

    private String textEncoderFile() {
        return configStr("model_files.text_encoder", "text_encoder.onnx");
    }

    private String flowFile() {
        return configStr("model_files.flow", "flow.onnx");
    }

    private String mimiDecoderFile() {
        return configStr("model_files.mimi_decoder", "mimi_decoder.onnx");
    }

    private String mimiEncoderFile() {
        return configStr("model_files.mimi_encoder", "mimi_encoder.onnx");
    }

    /**
     * 从 config.json 读取字符串（点路径），未配置时返回默认值。
     */
    private final Map<String, String> configCache = new LinkedHashMap<>();

    private String configStr(String dotPath, String def) {
        return configCache.getOrDefault(dotPath, def);
    }

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
     * 加载并扁平化 config.json（支持 a.b 点路径）。
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
    }

    /**
     * 简易 JSON 扁平化：把嵌套对象转为 {@code parent.key=value} 的点路径映射。
     * 仅支持对象/字符串/数字（config.json 足够）。
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
        Matcher matcher = Pattern.compile("\"([^\"]+)\"\\s*:\\s*").matcher(body);
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

    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return t;
    }

    // ==================== tokenizer ====================

    private void loadTokenizer(Path tokenizerPath) throws Exception {
        if (tokenizerPath == null || !Files.exists(tokenizerPath)) {
            throw new IllegalStateException("Pocket-TTS tokenizer.json 缺失: " + tokenizerPath);
        }
        tokenizer = ai.djl.huggingface.tokenizers.HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerPath)
                .optPadToMaxLength()
                .build();
    }

    /**
     * 文本转 token IDs（BPE）。
     */
    private long[] encode(String text) {
        ai.djl.huggingface.tokenizers.Encoding encoding = tokenizer.encode(text);
        long[] ids = encoding.getIds();
        if (ids.length > MAX_TEXT_LENGTH) {
            long[] trimmed = new long[MAX_TEXT_LENGTH];
            System.arraycopy(ids, 0, trimmed, 0, MAX_TEXT_LENGTH);
            ids = trimmed;
        }
        if (ids.length == 0) {
            throw new IllegalArgumentException("文本分词后为空: " + text);
        }
        return ids;
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
     * 文本转 WAV 字节（支持零样本声音克隆）。
     *
     * <p>参考音频为 24kHz 单声道 WAV 字节（或任意采样率，自动重采样）；
     * 经 mimi_encoder 编码为说话人潜变量后参与 flow 条件生成。
     * 参考音频为空或 mimi_encoder 缺失时使用默认音色。</p>
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
            float[] textEmbeddings = runTextEncoder(ids);
            int textLen = ids.length;
            float[] refLatents = encodeRefAudio(refAudioWav);
            float[] latents = runFlowMatching(textEmbeddings, textLen, refLatents);
            float[] waveform = runMimiDecoder(latents);
            return toWav(waveform, SAMPLE_RATE);
        } catch (Exception e) {
            throw new RuntimeException("Pocket-TTS 合成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 运行 text_encoder.onnx：tokens → text_embeddings [1, T, D]。
     */
    private float[] runTextEncoder(long[] ids) throws Exception {
        long[] shape = new long[]{1, ids.length};
        try (ai.onnxruntime.OnnxTensor tIds = ai.onnxruntime.OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(ids), shape)) {
            Map<String, ai.onnxruntime.OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put(textEncoderInputName, tIds);
            try (ai.onnxruntime.OrtSession.Result result = textEncoderSession.run(inputs)) {
                ai.onnxruntime.OnnxTensor emb = (ai.onnxruntime.OnnxTensor) result.get(textEncoderOutputName).get();
                FloatBuffer fb = emb.getFloatBuffer();
                float[] out = new float[fb.remaining()];
                fb.get(out);
                return out;
            }
        }
    }

    /**
     * 编码参考音频为说话人潜变量（零样本声音克隆）。
     *
     * <p>WAV 字节 → 24kHz 单声道 float 波形 → mimi_encoder.onnx → 潜变量 [1, C, T]。</p>
     *
     * @param refAudioWav 参考音频 WAV 字节；为空或 mimi_encoder 缺失时返回 null（默认音色）
     * @return 说话人潜变量；null 表示使用默认音色
     */
    private float[] encodeRefAudio(byte[] refAudioWav) throws Exception {
        if (refAudioWav == null || refAudioWav.length == 0) {
            return null;
        }
        if (mimiEncoderSession == null || flowRefName == null) {
            log.debug("[Pocket-TTS] mimi_encoder 或 flow ref 输入缺失，忽略参考音频，使用默认音色");
            return null;
        }
        float[] waveform = new float[0];
        long[] shape = new long[]{1, waveform.length};
        try (ai.onnxruntime.OnnxTensor tWave = ai.onnxruntime.OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(waveform), shape)) {
            Map<String, ai.onnxruntime.OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put(mimiEncoderInputName, tWave);
            try (ai.onnxruntime.OrtSession.Result result = mimiEncoderSession.run(inputs)) {
                ai.onnxruntime.OnnxTensor latents = (ai.onnxruntime.OnnxTensor) result.get(mimiEncoderOutputName).get();
                FloatBuffer fb = latents.getFloatBuffer();
                float[] out = new float[fb.remaining()];
                fb.get(out);
                log.info("[Pocket-TTS] 参考音频编码完成: {} samples -> {} latents", waveform.length, out.length);
                return out;
            }
        }
    }

    /**
     * 流匹配一致性采样：x = 高斯噪声 [1, F, latent_dim]，逐步去噪。
     *
     * <p>Euler 近似：t 从 1/flow_steps 到 1，v = flow(x, t, text_embeddings, mask[, ref])，
     * x += v / flow_steps。与 Pocket-TTS 蒸馏一致性采样（4 步）一致。</p>
     */
    private float[] runFlowMatching(float[] textEmbeddings, int textLen, float[] refLatents) throws Exception {
        int frames = (int) Math.max(1, Math.round(textLen * framesPerToken));
        frames = Math.min(frames, maxFrames);
        int size = frames * latentDim;
        float[] x = new float[size];
        Random random = new Random(0);
        for (int i = 0; i < size; i++) {
            x[i] = (float) random.nextGaussian();
        }
        float dt = 1.0f / flowSteps;
        for (int step = 0; step < flowSteps; step++) {
            float t = (step + 1) * dt;
            float[] v = runFlowStep(x, t, textEmbeddings, textLen, frames, refLatents);
            for (int i = 0; i < size; i++) {
                x[i] += dt * v[i];
            }
        }
        return x;
    }

    /**
     * 运行 flow.onnx 单步：返回速度场 v [1, F, latent_dim]。
     */
    private float[] runFlowStep(float[] x, float t, float[] textEmbeddings, int textLen, int frames,
                                float[] refLatents) throws Exception {
        long[] xShape = new long[]{1, frames, latentDim};
        long[] tShape = new long[]{1};
        long[] embShape = new long[]{1, textLen, textEmbeddings.length / textLen};
        long[] maskShape = new long[]{1, textLen};
        long[] mask = new long[textLen];
        java.util.Arrays.fill(mask, 1L);

        Map<String, ai.onnxruntime.OnnxTensor> inputs = new LinkedHashMap<>();
        try (ai.onnxruntime.OnnxTensor tX = ai.onnxruntime.OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(x), xShape);
             ai.onnxruntime.OnnxTensor tT = ai.onnxruntime.OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(new float[]{t}), tShape);
             ai.onnxruntime.OnnxTensor tEmb = ai.onnxruntime.OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(textEmbeddings), embShape);
             ai.onnxruntime.OnnxTensor tMask = ai.onnxruntime.OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(mask), maskShape)) {
            inputs.put(flowXName, tX);
            inputs.put(flowTName, tT);
            inputs.put(flowEmbName, tEmb);
            inputs.put(flowMaskName, tMask);
            // 参考音频潜变量（声音克隆）：shape 沿用 mimi_encoder 输出 [1, C, T]
            try (ai.onnxruntime.OnnxTensor tRef = refLatents != null
                    ? ai.onnxruntime.OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(refLatents), refLatentsShape())
                    : null) {
                if (tRef != null) {
                    inputs.put(flowRefName, tRef);
                }
                try (ai.onnxruntime.OrtSession.Result result = flowSession.run(inputs)) {
                    ai.onnxruntime.OnnxTensor vTensor = (ai.onnxruntime.OnnxTensor) result.get(flowVName).get();
                    FloatBuffer fb = vTensor.getFloatBuffer();
                    float[] out = new float[fb.remaining()];
                    fb.get(out);
                    return out;
                }
            }
        }
    }

    /**
     * 参考音频潜变量 shape：mimi_encoder 输出 [1, C, T]，此处按输出长度推断
     * 为 [1, latentDim, frames]（与 Mimi 编码器输出布局一致）。
     */
    private long[] refLatentsShape() {
        return new long[]{1, refLatentsLen / latentDim, latentDim};
    }

    /**
     * 最近一次参考音频潜变量长度（由 encodeRefAudio 记录）。
     */
    private int refLatentsLen = 0;

    /**
     * 运行 mimi_decoder.onnx：latents → waveform。
     */
    private float[] runMimiDecoder(float[] latents) throws Exception {
        int latentSize = latentDim;
        int frames = latents.length / latentSize;
        long[] shape = new long[]{1, frames, latentSize};
        try (ai.onnxruntime.OnnxTensor tLatents = ai.onnxruntime.OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(latents), shape)) {
            Map<String, ai.onnxruntime.OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put(mimiInputName, tLatents);
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
     * 解析张量名：优先 config.json 中的配置名；若会话中不存在该名称，则按
     * dtype（int64 优先 / float 优先）从输入或输出元数据中推断。
     *
     * @param configKey  config.json 点路径键
     * @param def        默认张量名
     * @param session    ORT 会话
     * @param inputSide  true 查输入 / false 查输出
     * @param preferFloat true 优先 float 张量（text_embeddings 等）/ false 优先 int64（tokens/mask）
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
            } catch (Exception ignored) {
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
     */
    public void close() {
        closeQuietly(textEncoderSession);
        closeQuietly(flowSession);
        closeQuietly(mimiDecoderSession);
        textEncoderSession = null;
        flowSession = null;
        mimiDecoderSession = null;
        prepared = false;
    }

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
