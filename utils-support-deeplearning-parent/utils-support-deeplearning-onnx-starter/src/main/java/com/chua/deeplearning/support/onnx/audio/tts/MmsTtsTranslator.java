package com.chua.deeplearning.support.onnx.audio.tts;

import com.chua.common.support.utils.NativeLoader;
import lombok.extern.slf4j.Slf4j;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * MMS-TTS（VITS）语音合成器。
 *
 * <p>基于 modelscope {@code Xenova/mms-tts-eng} 的量化 ONNX 模型（model_quantized.onnx）。
 * 输入文本 → 字符级 tokenizer 分词 → ONNX 推理 → waveform 波形 → WAV 字节数组。</p>
 *
 * <p>模型输入：{@code input_ids} [batch, seq] int64 + {@code attention_mask} [batch, seq] int64；
 * 输出：{@code waveform} [batch, n_samples] float32。</p>
 *
 * <p>资源在 jar 内路径：{@code audio/tts/mms-tts-eng/} 下，由 {@link NativeLoader} 解压后加载。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MmsTtsTranslator {

    /**
     * 输出采样率（MMS-TTS 固定 16000Hz）
     */
    private static final int SAMPLE_RATE = 16000;

    /**
     * 默认最大输入长度
     */
    private static final int MAX_INPUT_LENGTH = 512;

    /**
     * classpath 资源根路径
     */
    private static final String RESOURCE_BASE = "audio/tts/mms-tts-eng/";

    /**
     * 模型文件名
     */
    private static final String MODEL_FILE = "model_quantized.onnx";

    /**
     * tokenizer 词表文件名
     */
    private static final String VOCAB_FILE = "tokenizer.json";

    /**
     * 未知 token ID
     */
    private static final int UNK_ID = 38;

    /**
     * 空格在词表中的 token（VITS 用 "_" 表示空格）
     */
    private static final String SPACE_TOKEN = "_";

    /**
     * 模型缓存根目录
     */
    private static final String CACHE_ROOT = "audio/tts/";

    /**
     * 字符 → token ID 映射
     */
    private Map<Character, Integer> charToId;

    private ai.onnxruntime.OrtEnvironment ortEnv;
    private ai.onnxruntime.OrtSession session;
    private volatile boolean prepared;

    /**
     * 构造合成器。
     */
    public MmsTtsTranslator() {
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
        Path modelDir = Path.of(cacheRoot(), CACHE_ROOT, "mms-tts-eng");
        if (!Files.isDirectory(modelDir) || !Files.exists(modelDir.resolve(MODEL_FILE))) {
            NativeLoader.of("mms-tts-resources")
                    .from(MmsTtsTranslator.class.getClassLoader())
                    .basePath(RESOURCE_BASE)
                    .toTarget(modelDir)
                    .glob("*")
                    .withMd5(true)
                    .extractOnly(true)
                    .load();
        }
        Path modelPath = modelDir.resolve(MODEL_FILE);
        if (!Files.exists(modelPath)) {
            throw new IllegalStateException("MMS-TTS 模型资源缺失: " + modelPath);
        }
        loadVocab(modelDir.resolve(VOCAB_FILE));
        ortEnv = ai.onnxruntime.OrtEnvironment.getEnvironment();
        ai.onnxruntime.OrtSession.SessionOptions opts = new ai.onnxruntime.OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        session = ortEnv.createSession(modelPath.toString(), opts);
        log.info("[MMS-TTS] 模型加载完成: {} (vocab={})", modelPath, charToId.size());
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
     * 加载字符级词表。
     *
     * @param vocabPath tokenizer.json 路径
     * @throws Exception 加载异常
     */
    private void loadVocab(Path vocabPath) throws Exception {
        charToId = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(vocabPath)) {
            // tokenizer.json 中 vocab 是 {"k":0,"'":1,...,"<unk>":38}
            String content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int vocabStart = content.indexOf("\"vocab\"");
            if (vocabStart < 0) {
                throw new IllegalStateException("tokenizer.json 缺少 vocab");
            }
            String vocabSection = content.substring(vocabStart + 7);
            int braceEnd = findMatchingBrace(vocabSection);
            String vocabBody = vocabSection.substring(1, braceEnd);
            // 解析 "char": id 对
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*(-?\\d+)")
                    .matcher(vocabBody);
            while (matcher.find()) {
                String token = unescape(matcher.group(1));
                int id = Integer.parseInt(matcher.group(2));
                if (token.length() == 1) {
                    charToId.put(token.charAt(0), id);
                }
            }
        }
        if (charToId.isEmpty()) {
            throw new IllegalStateException("tokenizer.json vocab 解析失败");
        }
    }

    /**
     * 查找匹配的大括号位置。
     *
     * @param s 从 "vocab" 后的字符串
     * @return 匹配大括号的下标（相对于 s）
     */
    private static int findMatchingBrace(String s) {
        int depth = 0;
        boolean inStr = false;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    inStr = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inStr = true;
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return s.length();
    }

    /**
     * 反转义 JSON 字符串。
     *
     * @param s 原始字符串
     * @return 反转义后
     */
    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 文本转 token IDs（字符级）。
     *
     * <p>未知字符跳过（不使用 &lt;unk&gt;，因其 id 超出 embedding 范围）；
     * 空格映射为 {@code _} token。</p>
     *
     * @param text 文本
     * @return token ids
     */
    private long[] encode(String text) {
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                ids.add((long) charToId.getOrDefault('_', UNK_ID));
            } else {
                Integer id = charToId.get(c);
                if (id != null && id != UNK_ID) {
                    ids.add(id.longValue());
                }
            }
            if (ids.size() >= MAX_INPUT_LENGTH) {
                break;
            }
        }
        if (ids.isEmpty()) {
            ids.add((long) charToId.getOrDefault('a', 26));
        }
        long[] result = new long[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            result[i] = ids.get(i);
        }
        return result;
    }

    /**
     * 文本转 WAV 字节。
     *
     * @param text 输入文本
     * @return WAV 音频字节
     */
    public byte[] synthesize(String text) {
        try {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("文本不能为空");
            }
            prepare();
            long[] ids = encode(text);
            long[] mask = new long[ids.length];
            java.util.Arrays.fill(mask, 1L);
            float[] waveform = inferWaveform(ids, mask);
            return toWav(waveform, SAMPLE_RATE);
        } catch (Exception e) {
            throw new RuntimeException("MMS-TTS 合成失败: " + e.getMessage(), e);
        }
    }

    /**
     * ORT 推理获取波形。
     *
     * @param ids  token ids
     * @param mask attention mask
     * @return 波形数据
     * @throws Exception 推理异常
     */
    private float[] inferWaveform(long[] ids, long[] mask) throws Exception {
        long[] shape = new long[]{1, ids.length};
        try (ai.onnxruntime.OnnxTensor tIds = ai.onnxruntime.OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(ids), shape);
             ai.onnxruntime.OnnxTensor tMask = ai.onnxruntime.OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(mask), shape)) {
            Map<String, ai.onnxruntime.OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", tIds);
            inputs.put("attention_mask", tMask);
            try (ai.onnxruntime.OrtSession.Result result = session.run(inputs)) {
                ai.onnxruntime.OnnxTensor wave = (ai.onnxruntime.OnnxTensor) result.get("waveform").get();
                long nSamples = wave.getInfo().getShape()[1];
                float[] samples = wave.getFloatBuffer().array();
                if (samples.length != nSamples) {
                    samples = java.util.Arrays.copyOf(samples, (int) nSamples);
                }
                return samples;
            }
        }
    }

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
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
                log.debug("[MMS-TTS] 关闭 ORT 会话时忽略异常");
            }
            session = null;
        }
        charToId = null;
        prepared = false;
    }
}
