package com.chua.deeplearning.support.onnx.audio.tts;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文 VITS TTS 合成器（vits-icefall-zh-aishell3，sherpa-onnx 导出）。
 * <p>
   * 流水线：中文文本 → 逐字查询 lexicon（字 → 音素序列）→ 音素转 令牌 标识
   * （{@code sil ... sil eos} 边界）→ VITS ONNX 单次前向 → 8khz 波形 WAV。
 * </p>
 * <p>
 * 模型约 30MB（fp32），174 个 AISHELL3 说话人，输出采样率 8000Hz（电话音质）。
   * 由 NAT加载 从 {@code audio/tts/vits-icefall-zh/} 解压到缓存目录后加载。
 * </p>
 * <p>
 * 对齐 sherpa-onnx {@code Lexicon::ConvertTextToTokenIdsChinese}：
 * 句首插入 {@code sil}，句末标点处追加 {@code eos} 并分段，整句末尾补
 * {@code sil} + {@code eos}；标点不在词表时用 {@code #0} 占位；OOV 字忽略。
 * </p>
 * <p>
   * ONNX 输入输出（模型.onnx 实测确认）：
 * <ul>
 *   <li>输入 {@code tokens}：(1, T) int64 音素 id 序列</li>
 *   <li>输入 {@code tokens_lens}：(1,) int64</li>
 *   <li>输入 {@code noise_scale}/{@code alpha}/{@code noise_scale_dur}：(1,) float32 采样参数</li>
 *   <li>输入 {@code speaker}：(1,) int64 说话人 id（0~173）</li>
 *   <li>输出 {@code audio}：(1, N) float32 @8kHz 波形</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class VitsTtsTranslator {

    /**
      * 输出采样率（VITS-icefall 固定 8khz）
     */
    private static final int SAMPLE_RATE = 8000;

    /**
      * 类路径 资源根路径
     */
    private static final String RESOURCE_BASE = "audio/tts/vits-icefall-zh/";

    /**
      * 模型缓存根目录（音频/tts/）
     */
    private static final String CACHE_ROOT = "audio/tts/";

    /**
      * 最大音素 令牌 数（防 OOM）
     */
    private static final int MAX_TOKENS = 2000;

    /**
     * 句末标点集合（对标 sherpa-onnx，命中后追加 eos 并分段）
     */
    private static final String SENTENCE_END_PUNCS = "。；！？：”";

    /**
     * 非句末标点集合（直接以 #0 占位，不分段）
     */
    private static final String INLINE_PUNCS = "，、“、";

    /** ONNX 运行时环境 */
    private OrtEnvironment ortEnv;

    /** VITS 会话 */
    private OrtSession session;

    /** 音素 → 标识 映射 */
    private Map<String, Integer> token2id = new LinkedHashMap<>();

    /** 字 → 音素 令牌 标识 列表映射 */
    private Map<String, int[]> word2ids = new LinkedHashMap<>();

    /** 说话人列表 */
    private List<String> speakers = new ArrayList<>();

    /** 是否已准备 */
    private volatile boolean prepared;

    /**
     * 构造合成器。
     */
    public VitsTtsTranslator() {
    }

    /**
      * 说话人名称列表（speakers.txt 顺序，对应 speaker 标识 0~N-1）。
     *
     * @return 说话人名称列表
     */
    public List<String> speakerNames() {
        return speakers;
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
        Path modelDir = Path.of(cacheRoot(), CACHE_ROOT, "vits-icefall-zh");
        if (!Files.isRegularFile(modelDir.resolve("model.onnx"))) {
            NativeLoader.of("vits-icefall-zh-resources")
                    .from(VitsTtsTranslator.class.getClassLoader())
                    .basePath(RESOURCE_BASE)
                    .toTarget(modelDir)
                    .glob("*")
                    .withMd5(true)
                    .extractOnly(true)
                    .load();
        }
        loadTokens(modelDir.resolve("tokens.txt"));
        loadLexicon(modelDir.resolve("lexicon.txt"));
        loadSpeakers(modelDir.resolve("speakers.txt"));
        ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        session = ortEnv.createSession(modelDir.resolve("model.onnx").toString(), opts);
        log.info("[VITS] 模型加载完成: {} (tokens={}, lexicon={}, speakers={})",
                modelDir, token2id.size(), word2ids.size(), speakers.size());
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
      * 加载 令牌.txt（每行 {@code token id}）。
     * @param path 路径
     */
    private void loadTokens(Path path) throws Exception {
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2) {
                token2id.put(parts[0], Integer.parseInt(parts[1]));
            }
        }
    }

    /**
      * 加载 lexicon.txt（每行 {@code 字 音素...}），音素逐一转 令牌 标识。
     * @param path 路径
     */
    private void loadLexicon(Path path) throws Exception {
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            List<Integer> ids = new ArrayList<>();
            boolean valid = true;
            for (int i = 1; i < parts.length; i++) {
                Integer id = token2id.get(parts[i]);
                if (id == null) {
                    valid = false;
                    break;
                }
                ids.add(id);
            }
            if (valid && !ids.isEmpty()) {
                int[] arr = new int[ids.size()];
                for (int i = 0; i < ids.size(); i++) {
                    arr[i] = ids.get(i);
                }
                word2ids.put(parts[0], arr);
            }
        }
    }

    /**
      * 加载 speakers.txt（每行一个说话人 标识，按行序对应 0~N-1）。
     * @param path 路径
     */
    private void loadSpeakers(Path path) throws Exception {
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                speakers.add(line.trim());
            }
        }
    }

    /**
      * 中文文本转音素 令牌 标识 序列（对齐 sherpa-onnx {@code Lexicon}）。
     *
     * @param text 中文文本
     * @return token 标识 序列
     */
    private int[] textToTokenIds(String text) {
        Integer sil = token2id.get("sil");
        Integer eos = token2id.get("eos");
        Integer pad = token2id.get("#0");
        List<Integer> result = new ArrayList<>();
        if (sil != null) {
            result.add(sil);
        }
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            if (SENTENCE_END_PUNCS.indexOf(ch) >= 0 || ".".equals(ch) || ";".equals(ch)
                    || "!".equals(ch) || "?".equals(ch) || "-".equals(ch) || ":".equals(ch)) {
                if (eos != null) {
                    result.add(eos);
                }
                if (i + 1 < text.length() && sil != null) {
                    result.add(sil);
                }
                continue;
            }
            if (INLINE_PUNCS.indexOf(ch) >= 0 || ",".equals(ch)) {
                if (pad != null) {
                    result.add(pad);
                } else if (sil != null) {
                    result.add(sil);
                }
                continue;
            }
            int[] ids = word2ids.get(ch);
            if (ids != null) {
                for (int id : ids) {
                    result.add(id);
                }
            } else {
                log.warn("[VITS] OOV 字忽略: {}", ch);
            }
        }
        if (sil != null) {
            result.add(sil);
        }
        if (eos != null) {
            result.add(eos);
        }
        return toIntArray(result);
    }

    /**
     * 中文文本转 WAV 字节（指定说话人）。
     *
     * @param text     中文文本
     * @param speakerId 说话人 标识（0~173），负数或越界回退 0
     * @return 8kHz WAV 音频字节
     */
    public byte[] synthesize(String text, int speakerId) {
        try {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("文本不能为空");
            }
            prepare();
            int[] tokenIds = textToTokenIds(text);
            if (tokenIds.length == 0) {
                throw new IllegalArgumentException("文本无法转换: " + text);
            }
            if (tokenIds.length > MAX_TOKENS) {
                throw new IllegalArgumentException("文本过长（token 数超过 " + MAX_TOKENS + "）");
            }
            int sid = speakerId >= 0 && speakerId < speakers.size() ? speakerId : 0;
            float[] audio = runInference(tokenIds, sid);
            return toWav(audio);
        } catch (Exception e) {
            throw new RuntimeException("VITS 合成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 运行 VITS ONNX 推理。
     *
     * @param tokenIds 音素 令牌 标识 序列
     * @param sid      说话人 标识
     * @return 8kHz 波形
     */
    private float[] runInference(int[] tokenIds, int sid) throws Exception {
        long[] tokensShape = new long[]{1, tokenIds.length};
        long[] lenShape = new long[]{1};
        try (OnnxTensor tokens = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(toLongArray(tokenIds)), tokensShape);
             OnnxTensor lens = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(new long[]{tokenIds.length}), lenShape);
             OnnxTensor noiseScale = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(new float[]{0.667f}), lenShape);
             OnnxTensor alpha = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(new float[]{1.0f}), lenShape);
             OnnxTensor noiseScaleDur = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(new float[]{1.0f}), lenShape);
             OnnxTensor speaker = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(new long[]{sid}), lenShape)) {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put("tokens", tokens);
            inputs.put("tokens_lens", lens);
            inputs.put("noise_scale", noiseScale);
            inputs.put("alpha", alpha);
            inputs.put("noise_scale_dur", noiseScaleDur);
            inputs.put("speaker", speaker);
            try (OrtSession.Result result = session.run(inputs)) {
                OnnxTensor audioTensor = (OnnxTensor) result.get(0);
                FloatBuffer fb = audioTensor.getFloatBuffer();
                float[] out = new float[fb.remaining()];
                fb.get(out);
                log.info("[VITS] 合成完成: {} token, {} samples ({}s), speaker={}",
                        tokenIds.length, out.length, String.format("%.2f", out.length / (float) SAMPLE_RATE), sid);
                return out;
            }
        }
    }

    /**
      * float 波形转 16-钻头 WAV 字节。
     *
     * @param samples 波形数据
     * @return WAV 字节
     */
    private static byte[] toWav(float[] samples) throws Exception {
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            short s = (short) (Math.max(-1.0f, Math.min(1.0f, samples[i])) * Short.MAX_VALUE);
            pcm[i * 2] = (byte) (s & 0xFF);
            pcm[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcm), format, pcm.length / 2);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, baos);
            return baos.toByteArray();
        }
    }

    /**
      * 列表 转 int 数组。
     * @param list 列表
     * @return 转为intarray的结果
     */
    private static int[] toIntArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    /**
     * int 数组转 long 数组。
     * @param arr arr
     * @return 转为longarray的结果
     */
    private static long[] toLongArray(int[] arr) {
        long[] out = new long[arr.length];
        for (int i = 0; i < arr.length; i++) {
            out[i] = arr[i];
        }
        return out;
    }

    /**
     * 关闭资源。
     */
    public void close() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
                log.debug("[VITS] 关闭 ORT 会话时忽略异常");
            }
            session = null;
        }
        prepared = false;
    }
}