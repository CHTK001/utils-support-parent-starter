package com.chua.deeplearning.support.onnx.audio;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 基于 wav2vec2 的音频指纹提取翻译器（纯 ONNX Runtime 实现，无 DJL 依赖）。
 *
 * <h2>模型说明</h2>
 * <p>wav2vec2 是 Facebook AI 提出的自监督语音预训练模型，在本实现中作为<b>音频指纹提取器</b>使用：
 * <ul>
 *   <li><b>输入</b>：16kHz 单声道 PCM float 音频采样数组（长度不限）。</li>
 *   <li><b>输出</b>：ASR head logits [batch, seq_len, vocab_size]，对时间维度做 mean pooling 后得到固定维度向量。</li>
 *   <li><b>维度</b>：取决于模型变体。wav2vec2-base-960h ASR head 输出 32 维 vocab（英文字符+特殊 token）；
 *       wav2vec2-zh 输出约 10k 维中文字符 vocab；backbone-only（无 LM head）模型输出 hidden_dim 维（768/1024）。</li>
 * </ul>
 * </p>
 *
 * <h2>特征提取流程</h2>
 * <pre>
 *   raw PCM (byte[]) → resample to 16kHz mono → float[] samples
 *       → ONNX encoder (wav2vec2 feature extractor + transformer layers)
 *       → last_hidden_state [batch, seq_len, vocab_size]
 *       → mean pooling over time dimension
 *       → flatten → float[] fingerprint (vocab_size)
 * </pre>
 *
 * <h2>支持的模型格式</h2>
 * <ul>
 *   <li>单文件 ONNX：模型已将 feature extractor 和 transformer 合并为一个计算图。</li>
 *   <li>多文件 ONNX：分开的 encoder_model.onnx + decoder_model.onnx（较少见，本实现优先查找单个 onnx 文件）。</li>
 * </ul>
 *
 * <h2>注册示例</h2>
 * <pre>{@code
 *   reg("wav2vec2-zh",
 *       "com.chua.deeplearning.support.onnx.audio.Wav2Vec2FingerprintTranslator",
 *       byte[].class, float[].class,
 *       AudioFingerprinter.class,
 *       "audio/fingerprint/wav2vec2-zh/model.onnx",
 *       "https://huggingface.co/onnx-community/wav2vec2-large-xlsr-53-chinese-zh-cn-ONNX/resolve/main/model.onnx",
 *       false, null);
 * }</pre>
 *
 * <h2>注意事项</h2>
 * <ul>
 *   <li>推理时使用静态形状（static shapes），因此输入音频长度应控制在合理范围内（建议 ≤ 30 秒）。</li>
 *   <li>超出模型最大序列长度时，将对输入进行截断（仅保留前 N 秒）。</li>
 *   <li>模型输出的 hidden state 未做 L2 归一化；归一化由 {@link com.chua.deeplearning.support.audio.DefaultAudioFingerprinter} 负责。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class Wav2Vec2FingerprintTranslator implements ITranslator<byte[], float[]> {

    /** ONNX Runtime 全局环境单例，所有会话共享（线程安全） */
    private OrtEnvironment ortEnv;
    /** ONNX 模型推理会话 */
    private OrtSession session;
    /** 模型特征维度（来自 ONNX 模型结构推断，对 ASR head 而言 = vocab_size） */
    private int hiddenSize = 32;
    /** 模型最大输入序列长度（采样点数） */
    private int maxInputLength = 480000;
    /** 是否已在本实例上完成初始化 */
    private boolean prepared = false;
    /** 模型文件路径 */
    private String modelPath;

    /**
     * 设置模型文件路径（仅供 ModelRegistry 在 SPI 实例化后注入使用）。
     *
     * @param modelPath 模型路径
     */
    public void setModelPath(String modelPath) {
        this.modelPath = modelPath;
    }

    /**
     * 构造翻译器。
     *
     * @param modelPath ONNX 模型文件路径（可为 classpath 资源或文件系统绝对路径）
     */
    public Wav2Vec2FingerprintTranslator(String modelPath) {
        this.modelPath = modelPath;
    }

    /**
     * 无参构造器，供反射实例化使用。
     */
    public Wav2Vec2FingerprintTranslator() {
        this.modelPath = null;
    }

    @Override
    public String name() {
        return "wav2vec2-fingerprint";
    }

    /**
     * 初始化 ONNX 会话并推断模型结构参数。
     *
     * <p>首次调用 {@link #translate(byte[])} 时自动触发初始化（懒加载）。
     * 初始化过程：
     * <ol>
     *   <li>加载 ONNX 模型文件到 {@link OrtSession}</li>
     *   <li>读取第一个输入节点张量的 shape，推断 hidden_size 和 max_input_length</li>
     *   <li>缓存结果供后续推理使用</li>
     * </ol>
     * </p>
     *
     * @throws Exception 若模型加载或结构推断失败
     */
    private void ensurePrepared() throws Exception {
        if (prepared) {
            return;
        }
        synchronized (this) {
            if (prepared) {
                return;
            }
            // 获取 ORT 全局环境
            ortEnv = OrtEnvironment.getEnvironment();
            // 解析模型文件路径
            Path modelFile = resolveModelPath(modelPath);
            if (modelFile == null || !Files.exists(modelFile)) {
                throw new IllegalStateException("模型文件不存在: " + modelPath);
            }
            // 创建推理会话（限制 CPU 线程数以避免内存溢出）
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = ortEnv.createSession(modelFile.toString(), opts);

            // 通过 dummy inference 推断 hidden size 和 max length
            inferModelStructure();
            prepared = true;
            log.info("[Wav2Vec2Fingerprint] 模型加载完成: {} (hidden={}, maxLen={})",
                    modelFile, hiddenSize, maxInputLength);
        }
    }

    /**
     * 通过一次 dummy 推理推断模型结构参数。
     *
     * <p>构造一个全零的 dummy 输入张量，执行一次前向推理，
     * 从输出张量的 shape 中提取 hidden_size 和 max_input_length。</p>
     */
    private void inferModelStructure() {
        try {
            String inputName = session.getInputNames().iterator().next();
            // 构造 dummy 输入：batch=1, seq_len=16000（1 秒音频），全零
            int probeSeq = 16000;
            float[] dummyInput = new float[probeSeq];
            try (OnnxTensor inputTensor = OnnxTensor.createTensor(
                    ortEnv, FloatBuffer.wrap(dummyInput), new long[]{1, probeSeq});
                 OrtSession.Result result = session.run(Map.of(inputName, inputTensor))) {

                ai.onnxruntime.OnnxValue outVal = result.get(0);
                if (outVal instanceof OnnxTensor outTensor) {
                    long[] shape = outTensor.getInfo().getShape();
                    // shape 通常为 [1, seq_len, hidden_size] 或 [1, hidden_size]
                    if (shape.length == 3) {
                        hiddenSize = (int) shape[2];
                        maxInputLength = (int) shape[1];
                    } else if (shape.length == 2) {
                        hiddenSize = (int) shape[1];
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Wav2Vec2Fingerprint] 结构推断失败，使用默认值: {}", e.getMessage());
        }
    }

    /**
     * 解析模型文件路径：优先尝试文件系统，其次尝试 classpath 资源。
     *
     * @param pathStr 模型路径字符串（绝对路径、相对路径或 classpath: 前缀）
     * @return 本地 Path；不存在返回 null
     */
    private static Path resolveModelPath(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            return null;
        }
        // 绝对路径
        Path abs = Path.of(pathStr);
        if (Files.exists(abs)) {
            return abs;
        }
        // classpath 路径（含 classpath: 前缀或裸相对路径）
        String resource = pathStr;
        if (pathStr.startsWith("classpath:")) {
            resource = pathStr.substring("classpath:".length());
        }
        java.net.URL url = Wav2Vec2FingerprintTranslator.class.getClassLoader().getResource(resource);
        if (url != null) {
            try {
                if ("file".equals(url.getProtocol())) {
                    return Path.of(url.toURI());
                }
                // 嵌入式 JAR 资源：复制到临时目录后返回
                Path tmp = Files.createTempFile("wav2vec2-", ".onnx");
                tmp.toFile().deleteOnExit();
                try (var in = url.openStream()) {
                    Files.write(tmp, in.readAllBytes());
                }
                return tmp;
            } catch (Exception ex) {
                // 继续到兜底逻辑
            }
        }
        // 相对当前工作目录
        Path rel = Path.of(System.getProperty("user.dir"), pathStr);
        if (Files.exists(rel)) {
            return rel;
        }
        return null;
    }

    /**
     * 从音频字节数据中提取指纹特征向量。
     *
     * <p>执行流程：
     * <ol>
     *   <li>将 byte[] 解析为 16kHz 单声道 float 采样数组</li>
     *   <li>若采样长度超过模型上限，截断至前 maxInputLength 个采样点</li>
     *   <li>将采样数组 reshape 为 [1, seq_len] 的 ONNX 张量</li>
     *   <li>执行 ONNX 推理，获取 last_hidden_state</li>
     *   <li>对时间维度做全局平均池化，得到 fixed-dim 特征向量</li>
     * </ol>
     * </p>
     *
     * @param audioData 音频原始字节（WAV 或 PCM）
     * @return 特征向量（维度 = hidden_size）
     */
    @Override
    @SuppressWarnings("unchecked")
    public float[] translate(byte[] audioData) {
        try {
            ensurePrepared();
        } catch (Exception e) {
            throw new RuntimeException("模型初始化失败: " + e.getMessage(), e);
        }

        // 步骤 1：解码音频字节为 float[]
        float[] samples = decodeAudioToPcm(audioData);
        if (samples == null || samples.length == 0) {
            throw new IllegalArgumentException("音频解码失败，输入数据为空或格式不支持");
        }

        // 步骤 2：截断至模型最大输入长度
        if (samples.length > maxInputLength) {
            log.warn("[Wav2Vec2Fingerprint] 输入音频过长（{}s），已截断至 {:.2f}s",
                    samples.length / 16000.0, maxInputLength / 16000.0);
            float[] truncated = new float[maxInputLength];
            System.arraycopy(samples, 0, truncated, 0, maxInputLength);
            samples = truncated;
        }

        // 步骤 3：构造 ONNX 输入张量 [1, seq_len]
        float[] inputFlat = new float[samples.length];
        System.arraycopy(samples, 0, inputFlat, 0, samples.length);
        try (OnnxTensor inputTensor = OnnxTensor.createTensor(
                ortEnv, FloatBuffer.wrap(inputFlat), new long[]{1, samples.length})) {

            String inputName = session.getInputNames().iterator().next();
            // 步骤 4：执行推理
            Map<String, OnnxTensor> feed = new HashMap<>();
            feed.put(inputName, inputTensor);
            try (OrtSession.Result result = session.run(feed)) {
                // 步骤 5：从输出张量中提取 hidden state
                ai.onnxruntime.OnnxValue outVal = result.get(0);
                if (!(outVal instanceof OnnxTensor outTensor)) {
                    throw new RuntimeException("模型输出类型不兼容: " + outVal.getClass().getSimpleName());
                }
                long[] shape = outTensor.getInfo().getShape();
                float[] hidden = outTensor.getFloatBuffer().array();

                // 步骤 6：全局平均池化（对时间维度求均值）
                // shape[0]=batch, shape[1]=seq_len, shape[2]=hidden_size
                int seqLen = (int) shape[1];
                int hs = (int) shape[2];
                float[] pooled = new float[hs];
                // hidden 数组 layout: [batch][seq_len][hidden_size] 按行优先展平
                // 即 hidden[i * seqLen * hs + t * hs + h]
                for (int t = 0; t < seqLen; t++) {
                    int base = t * hs;
                    for (int h = 0; h < hs; h++) {
                        pooled[h] += hidden[base + h];
                    }
                }
                // 除以 seqLen 得到均值
                float invSeqLen = 1.0f / seqLen;
                for (int h = 0; h < hs; h++) {
                    pooled[h] *= invSeqLen;
                }
                return pooled;
            }
        } catch (OrtException e) {
            throw new RuntimeException("ONNX 推理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将音频字节数组解码为 16kHz 单声道 float 采样数组。
     *
     * <p>支持以下输入格式：
     * <ul>
     *   <li>WAV 文件（自动解析 RIFF 头，支持 8-bit/16-bit/32-bit PCM 及 float）</li>
     *   <li>裸 PCM 字节（假设为 16-bit 有符号小端，直接转换为 float）</li>
     * </ul>
     * 解码后统一重采样至 16kHz 单声道，采样值范围 [-1.0, 1.0]。</p>
     *
     * @param bytes 音频字节数组
     * @return 16kHz 单声道 float 采样数组；解析失败返回 null
     */
    private static float[] decodeAudioToPcm(byte[] bytes) {
        if (bytes == null || bytes.length < 44) {
            return null;
        }
        // 尝试直接解析 WAV 头（比 Java Sound API 更快）
        String riff = new String(bytes, 0, 4);
        if ("RIFF".equals(riff)) {
            return decodeWavDirect(bytes);
        }
        // 回退到 Java Sound API（支持更多格式）
        return decodeViaJavaSound(bytes);
    }

    /**
     * 直接解析 WAV 文件头并提取 PCM 采样（高性能路径）。
     *
     * @param wavBytes WAV 文件字节
     * @return 16kHz 单声道 float 采样；解析失败返回 null
     */
    private static float[] decodeWavDirect(byte[] wavBytes) {
        try {
            int audioFormat = readLeShort(wavBytes, 20);
            if (audioFormat != 1 && audioFormat != 3) {
                return null;
            }
            int sampleRate = readLeInt(wavBytes, 24);
            int channels = readLeShort(wavBytes, 22);
            int bitsPerSample = readLeShort(wavBytes, 34);
            int dataOffset = findDataChunkOffset(wavBytes);
            if (dataOffset < 0) {
                return null;
            }
            int dataLen = readLeInt(wavBytes, dataOffset + 4);
            int frameSize = bitsPerSample / 8 * channels;
            int totalSamples = dataLen / frameSize;
            float[] pcm = new float[totalSamples];
            int srcIdx = dataOffset + 8;
            for (int i = 0; i < totalSamples; i++) {
                float sum = 0f;
                for (int c = 0; c < channels; c++) {
                    int si = srcIdx + i * frameSize + c * (bitsPerSample / 8);
                    float s;
                    if (bitsPerSample == 16) {
                        int v = (wavBytes[si + 1] << 8) | (wavBytes[si] & 0xff);
                        s = v / 32768.0f;
                    } else if (bitsPerSample == 8) {
                        s = (wavBytes[si] - 128) / 128.0f;
                    } else if (bitsPerSample == 32 && audioFormat == 3) {
                        s = Float.intBitsToFloat(readLeInt(wavBytes, si));
                    } else {
                        s = 0f;
                    }
                    sum += s;
                }
                pcm[i] = sum / channels;
            }
            // 若采样率不是 16kHz，进行重采样
            if (Math.abs(sampleRate - 16000) < 1) {
                return pcm;
            }
            return resample(pcm, sampleRate, 16000);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 通过 Java Sound API 解码音频（通用兜底路径）。
     *
     * @param bytes 音频字节
     * @return 16kHz 单声道 float 采样；失败返回 null
     */
    private static float[] decodeViaJavaSound(byte[] bytes) {
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(bytes));
            AudioFormat fmt = ais.getFormat();
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = ais.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            byte[] raw = baos.toByteArray();
            ais.close();
            return convertToPcmFloat(raw, fmt);
        } catch (Exception e) {
            log.debug("[Wav2Vec2Fingerprint] JavaSound 解码失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将 Java Sound 解码后的原始字节转换为 16kHz 单声道 float 数组。
     *
     * @param raw   原始采样字节
     * @param fmt   音频格式信息
     * @return float[] 采样数组
     */
    private static float[] convertToPcmFloat(byte[] raw, AudioFormat fmt) {
        int sampleRate = (int) fmt.getSampleRate();
        int channels = fmt.getChannels();
        int sampleSize = fmt.getSampleSizeInBits();
        boolean bigEndian = fmt.isBigEndian();
        int totalSamples = raw.length / (sampleSize / 8);
        int monoSamples = totalSamples / channels;
        float[] mono = new float[monoSamples];
        int bps = sampleSize / 8;
        for (int i = 0; i < monoSamples; i++) {
            float sum = 0f;
            for (int c = 0; c < channels; c++) {
                int idx = (i * channels + c) * bps;
                float s;
                if (sampleSize == 16) {
                    int lo = raw[idx] & 0xff;
                    int hi = raw[idx + 1] & 0xff;
                    int v = bigEndian ? ((lo << 8) | hi) : ((hi << 8) | lo);
                    s = (short) v / 32768.0f;
                } else if (sampleSize == 8) {
                    s = (raw[idx] - 128) / 128.0f;
                } else {
                    s = 0f;
                }
                sum += s;
            }
            mono[i] = sum / channels;
        }
        if (Math.abs(sampleRate - 16000) < 1) {
            return mono;
        }
        return resample(mono, sampleRate, 16000);
    }

    /**
     * 线性插值重采样：将音频从源采样率重采样到目标采样率。
     *
     * @param input      原始采样数组
     * @param srcRate    源采样率
     * @param dstRate    目标采样率
     * @return 重采样后的 float 数组
     */
    private static float[] resample(float[] input, int srcRate, int dstRate) {
        int newLen = (int) Math.round((double) input.length * dstRate / srcRate);
        float[] output = new float[newLen];
        for (int i = 0; i < newLen; i++) {
            float pos = i * (float) srcRate / dstRate;
            int lo = (int) Math.floor(pos);
            int hi = Math.min(lo + 1, input.length - 1);
            float frac = pos - lo;
            output[i] = input[lo] * (1 - frac) + input[hi] * frac;
        }
        return output;
    }

    /**
     * 在字节流中查找 "data" 字节的偏移位置（用于 WAV 头解析）。
     *
     * @param bytes WAV 字节数组
     * @return "data" 偏移量；未找到返回 -1
     */
    private static int findDataChunkOffset(byte[] bytes) {
        String header = new String(bytes, 0, Math.min(bytes.length, 128));
        int idx = header.indexOf("data");
        return idx >= 0 ? idx : -1;
    }

    /** 读取小端序 16 位整数 */
    private static int readLeShort(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    /** 读取小端序 32 位整数 */
    private static int readLeInt(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) |
                ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }
}
