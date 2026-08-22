package com.chua.deeplearning.support.onnx.audio;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.deeplearning.support.audio.SpeakerSegment;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 wespeaker-resnet34 的说话人嵌入提取翻译器（纯 ONNX Runtime 实现）。
 *
 * <h2>模型说明</h2>
 * <p>本翻译器使用 Wespeaker ResNet34-LM 模型，该模型将一段语音（通常 2~10 秒）
 * 映射为固定维度的<b>说话人嵌入向量（x-vector）</b>，用于说话人验证和聚类。</p>
 * <ul>
 *   <li><b>输入</b>：16kHz 单声道 PCM float 音频采样数组。</li>
 *   <li><b>输出</b>：512 维说话人嵌入向量（L2 归一化后）。</li>
 *   <li><b>推荐输入时长</b>：2~10 秒；过短（&lt; 0.5s）会导致嵌入质量下降。</li>
 * </ul>
 *
 * <h2>在说话人分离管线中的角色</h2>
 * <p>本翻译器不直接完成说话人分离，而是作为<b>嵌入提取组件</b>，
 * 配合 {@code DefaultSpeakerDiarizer}（VAD 时间切分）构成完整流水线：</p>
 * <pre>
 *   Step 1: DefaultSpeakerDiarizer.diarize(audioBytes)
 *           → 按能量 VAD 切分为多个语音片段（SpeakerSegment[]）
 *
 *   Step 2: 对每个片段调用本翻译器提取说话人嵌入
 *           → float[] embedding (512维)
 *
 *   Step 3: 对所有片段的嵌入进行聚类（K-Means）
 *           → 将不同片段映射到相同说话人 ID
 *
 *   Step 4: 合并同一说话人的连续片段
 *           → 最终 SpeakerSegment 列表（含正确说话人分组）
 * </pre>
 *
 * <h2>注册示例</h2>
 * <pre>{@code
 *   reg("wespeaker-resnet34",
 *       "com.chua.deeplearning.support.onnx.audio.WespeakerEmbeddingTranslator",
 *       byte[].class, float[].class,
 *       com.chua.deeplearning.support.audio.AudioFingerprinter.class,
 *       "audio/speaker/wespeaker-resnet34/model.onnx",
 *       "https://huggingface.co/CV333333/wespeaker-voxceleb-resnet34-LM-onnx/resolve/main/model.onnx",
 *       false, "model.onnx");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class WespeakerEmbeddingTranslator implements ITranslator<byte[], float[]> {

    /** Wespeaker 模型期望的输入长度（16kHz × 10s = 160000 采样点，不足则左侧补零） */
    private static final int EXPECTED_INPUT_LEN = 160000;
    /** 输出嵌入维度 */
    private static final int EMBEDDING_DIM = 512;

    private OrtEnvironment ortEnv;
    private OrtSession session;
    private boolean prepared = false;
    private final String modelPath;

    public WespeakerEmbeddingTranslator(String modelPath) {
        this.modelPath = modelPath;
    }

    public WespeakerEmbeddingTranslator() {
        this.modelPath = null;
    }

    @Override
    public String name() {
        return "wespeaker-embedding";
    }

    private void ensurePrepared() throws Exception {
        if (prepared) {
            return;
        }
        synchronized (this) {
            if (prepared) {
                return;
            }
            ortEnv = OrtEnvironment.getEnvironment();
            Path modelFile = resolveModelPath(modelPath);
            if (modelFile == null || !Files.exists(modelFile)) {
                throw new IllegalStateException("模型文件不存在: " + modelPath);
            }
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = ortEnv.createSession(modelFile.toString(), opts);
            prepared = true;
            log.info("[WespeakerEmbedding] 模型加载完成: {}", modelFile);
        }
    }

    /**
     * 从音频字节中提取说话人嵌入向量。
     *
     * <p>处理流程：
     * <ol>
     *   <li>解码音频字节为 16kHz 单声道 float 采样</li>
     *   <li>若采样长度不足 EXPECTED_INPUT_LEN，在左侧（前端）补零</li>
     *   <li>若超出，截断至 EXPECTED_INPUT_LEN</li>
     *   <li>执行 ONNX 推理，得到 512 维嵌入向量</li>
     *   <li>对输出做 L2 归一化</li>
     * </ol>
     * </p>
     *
     * @param audioData 音频字节（WAV 或 PCM）
     * @return 512 维 L2 归一化说话人嵌入向量
     */
    @Override
    @SuppressWarnings("unchecked")
    public float[] translate(byte[] audioData) {
        try {
            ensurePrepared();
        } catch (Exception e) {
            throw new RuntimeException("模型初始化失败: " + e.getMessage(), e);
        }

        // 解码音频
        float[] samples = decodeAudioToPcm(audioData);
        if (samples == null || samples.length == 0) {
            throw new IllegalArgumentException("音频解码失败");
        }

        // 调整至期望长度（不足补零，超出截断）
        float[] input = padOrTruncate(samples, EXPECTED_INPUT_LEN);

        try (OnnxTensor inputTensor = OnnxTensor.createTensor(
                ortEnv, FloatBuffer.wrap(input), new long[]{1, input.length})) {
            String inputName = session.getInputNames().iterator().next();
            Map<String, OnnxTensor> feed = new HashMap<>();
            feed.put(inputName, inputTensor);
            try (OrtSession.Result result = session.run(feed)) {
                ai.onnxruntime.OnnxValue outVal = result.get(0);
                if (!(outVal instanceof OnnxTensor outTensor)) {
                    throw new RuntimeException("模型输出类型不兼容: " + outVal.getClass().getSimpleName());
                }
                float[] embedding = outTensor.getFloatBuffer().array();
                // Wespeaker 模型输出通常已归一化，但再做一次确保
                return l2Normalize(embedding);
            }
        } catch (OrtException e) {
            throw new RuntimeException("ONNX 推理失败: " + e.getMessage(), e);
        }
    }

    // ==================== 私有工具方法 ====================

    /**
     * 将采样数组调整至 targetLen 长度：不足则在左侧补零，超出则截断右侧。
     */
    private static float[] padOrTruncate(float[] src, int targetLen) {
        if (src.length == targetLen) {
            return src;
        }
        if (src.length >= targetLen) {
            // 截断：取最后 targetLen 个采样（保留尾部，符合模型期望的最近语音）
            float[] truncated = new float[targetLen];
            System.arraycopy(src, src.length - targetLen, truncated, 0, targetLen);
            return truncated;
        }
        // 补零：在左侧（开头）补零，保持语音在尾部
        float[] padded = new float[targetLen];
        int copyStart = targetLen - src.length;
        System.arraycopy(src, 0, padded, copyStart, src.length);
        return padded;
    }

    private static float[] l2Normalize(float[] vec) {
        float norm = 0f;
        for (float v : vec) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm < 1e-8f) {
            return vec;
        }
        float[] result = new float[vec.length];
        for (int i = 0; i < vec.length; i++) {
            result[i] = vec[i] / norm;
        }
        return result;
    }

    private static float[] decodeAudioToPcm(byte[] bytes) {
        if (bytes == null || bytes.length < 44) {
            return null;
        }
        if ("RIFF".equals(new String(bytes, 0, 4))) {
            return decodeWavDirect(bytes);
        }
        return decodeViaJavaSound(bytes);
    }

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
            if (Math.abs(sampleRate - 16000) < 1) {
                return pcm;
            }
            return resample(pcm, sampleRate, 16000);
        } catch (Exception e) {
            return null;
        }
    }

    private static float[] decodeViaJavaSound(byte[] bytes) {
        try {
            var ais = javax.sound.sampled.AudioSystem.getAudioInputStream(new ByteArrayInputStream(bytes));
            var fmt = ais.getFormat();
            byte[] buf = new byte[8192];
            var baos = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = ais.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            byte[] raw = baos.toByteArray();
            ais.close();
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
        } catch (Exception e) {
            return null;
        }
    }

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

    private static int findDataChunkOffset(byte[] bytes) {
        String header = new String(bytes, 0, Math.min(bytes.length, 128));
        int idx = header.indexOf("data");
        return idx >= 0 ? idx : -1;
    }

    private static int readLeShort(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static int readLeInt(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) |
                ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static Path resolveModelPath(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            return null;
        }
        Path abs = Path.of(pathStr);
        if (Files.exists(abs)) {
            return abs;
        }
        if (pathStr.startsWith("classpath:")) {
            String resource = pathStr.substring("classpath:".length());
            java.net.URL url = WespeakerEmbeddingTranslator.class.getClassLoader().getResource(resource);
            if (url != null && "file".equals(url.getProtocol())) {
                return Path.of(url.getPath());
            }
        }
        Path rel = Path.of(System.getProperty("user.dir"), pathStr);
        if (Files.exists(rel)) {
            return rel;
        }
        return null;
    }
}
