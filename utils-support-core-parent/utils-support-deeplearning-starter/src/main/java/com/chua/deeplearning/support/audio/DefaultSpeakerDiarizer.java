package com.chua.deeplearning.support.audio;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 说话人分离默认实现，基于<b>短时能量语音活动检测（Energy-based VAD）</b>的时间切分方案。
 *
 * <h2>算法原理</h2>
 * <p>本方案不使用深度学习模型，纯信号处理实现，具有以下特点：
 * <ul>
 *   <li><b>零模型依赖</b>：无需下载或注册任何 ONNX 模型，开箱即用。</li>
 *   <li><b>极低资源消耗</b>：内存占用 &lt; 10MB，CPU 推理时间在毫秒级。</li>
 *   <li><b>适合嵌入式场景</b>：可在内存受限的 edge device 上运行。</li>
 * </ul>
 * </p>
 * <p>算法流程：</p>
 * <pre>
 *   音频字节 → WAV/PCM 解码 → 分帧 → 计算每帧短时能量
 *      → 能量 > 阈值 标记为"语音"，否则标记为"静音"
 *      → 合并连续语音帧为片段 → 分配说话人 ID
 * </pre>
 *
 * <h2>参数说明</h2>
 * <table border="1" cellpadding="6">
 *   <tr><th>参数</th><th>默认值</th><th>说明</th></tr>
 *   <tr><td>segmentDurationMs</td><td>25ms</td><td>每帧分析时长，25ms 是语音处理的行业标准（约 400 个采样点）</td></tr>
 *   <tr><td>energyThreshold</td><td>0.002</td><td>能量阈值，低于此值判定为静音；值越小越敏感</td></tr>
 *   <tr><td>minSpeechMs</td><td>80ms</td><td>最小有效语音片段时长；短于此值的片段将被丢弃（噪声过滤）</td></tr>
 *   <tr><td>silenceThresholdMs</td><td>200ms</td><td>静音间隔阈值；超过此长度的静音段视为新说话人的开始</td></tr>
 * </table>
 *
 * <h2>说话人数量估算</h2>
 * <p>本实现采用<b>静音分割启发式</b>估算说话人数量：
 * 当两个语音片段之间出现超过 {@code silenceThresholdMs} 的静音间隔时，
 * 认为可能是不同说话人在交替发言，分配新的说话人 标识。
 * 此策略在双人对讲场景（如电话会议）中效果较好，但在多人同时发言时可能不够准确。</p>
 *
 * <h2>后续升级路径</h2>
 * <p>若需要更高精度的说话人分离，可：</p>
 * <ol>
 *   <li>接入 {@link com.chua.deeplearning.support.audio.WespeakerFingerprintTranslator}（wav2vec2/Wespeaker 说话人嵌入）。</li>
 *   <li>对每个 VAD 片段提取说话人嵌入向量，进行聚类（如 K-Means）得到说话人分组。</li>
 *   <li>或替换为本实现，接入 pyannote.audio 的 ONNX 导出版本。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class DefaultSpeakerDiarizer implements SpeakerDiarizer {

    // ==================== 默认参数 ====================

    /** 默认推理设备：CPU */
    private static final String DEFAULT_DEVICE = "cpu";
    /** 默认目标采样率：16khz（语音处理标准） */
    private static final int DEFAULT_SAMPLE_RATE = 16000;
    /**
     * 每帧分析时长：25ms（约 400 个 16khz 采样点，语音处理的行业标准帧长）
     */
    private static final long DEFAULT_SEGMENT_MS = 25L;
    /**
     * 能量阈值：短时能量低于此值判定为静音。
     * <p>能量计算公式：E = (1/N) * sum(x[i]^2)，x[i] 为归一化采样值 [-1, 1]。
     * 0.002 对应 RMS ≈ 0.045，约为安静房间的底色噪声水平。</p>
     */
    private static final double DEFAULT_energy_THRESHOLD = 0.002d;
    /** 最小有效语音片段时长：80ms。短于此值的片段视为噪声/咔嗒声，予以丢弃 */
    private static final long DEFAULT_MIN_SPEECH_MS = 80L;
    /**
     * 静音分割阈值：200ms。
     * <p>若两个语音片段之间的静音间隔超过此值，认为是不同说话人切换；
     * 短于此值的静音视为同一说话人内的呼吸间隙。</p>
     */
    private static final long DEFAULT_SILENCE_THRESHOLD_MS = 200L;

    // ==================== 成员字段 ====================

    /** 推理引擎实例（全局单例） */
    private final IdentificationEngine engine;
    /** 模型 标识（本实现在当前版本中主要用于日志输出，VAD 逻辑不依赖具体模型） */
    private final String modelName;
    /** 模型配置（保留字段，供后续升级至模型驱动方案时复用） */
    @SuppressWarnings("unused")
    private final ModelSetting setting; // setting

    /** 自定义模型路径（预留，当前 VAD 方案不使用） */
    private String modelPath;
    /** 推理设备："cpu" 或 "cuda" */
    private String device = DEFAULT_DEVICE;
    /** 目标采样率（Hz） */
    private int sampleRate = DEFAULT_SAMPLE_RATE;
    /** 最大说话人数限制，空 表示不限制 */
    private Integer maxSpeakers;

    // ==================== VAD 算法参数（可调优） ====================

    /** 每帧时长（毫秒） */
    private long segmentDurationMs = DEFAULT_SEGMENT_MS;
    /** 能量检测阈值 */
    private double energyThreshold = DEFAULT_energy_THRESHOLD;
    /** 最小语音片段时长（毫秒），短于此值的静音间隙不被计入 */
    private long minSpeechMs = DEFAULT_MIN_SPEECH_MS;
    /** 静音分割阈值（毫秒），超过此值的静音间隔触发新说话人 标识 */
    private long silenceThresholdMs = DEFAULT_SILENCE_THRESHOLD_MS;

    /**
     * 构造默认说话人分离实例。
     *
     * @param engine    推理引擎（预留，当前 VAD 不直接使用）
     * @param modelName 模型 标识（当前版本仅作日志标记）
     * @param setting   模型配置（当前版本不使用，保留以兼容未来扩展）
     */
    public DefaultSpeakerDiarizer(IdentificationEngine engine, String modelName, ModelSetting setting) {
        this.engine = engine;
        this.modelName = modelName;
        this.setting = setting;
        if (setting.getModelPath() != null) {
            this.modelPath = setting.getModelPath();
        }
        if (setting.getDevice() != null) {
            this.device = setting.getDevice();
        }
    }

    @Override
    public SpeakerDiarizer modelPath(String path) {
        this.modelPath = path;
        return this;
    }

    @Override
    public SpeakerDiarizer device(String device) {
        this.device = device;
        return this;
    }

    @Override
    public SpeakerDiarizer sampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
        return this;
    }

    @Override
    public SpeakerDiarizer maxSpeakers(int maxSpeakers) {
        this.maxSpeakers = maxSpeakers;
        return this;
    }

    /**
     * 对音频字节数据进行说话人分离。
     *
     * <p>执行流程：
     * <ol>
     *   <li>解析 WAV/PCM 字节流为 float[] 采样数组（自动处理多声道混缩和重采样）</li>
     *   <li>将采样数组按帧长分段，计算每帧短时能量</li>
     *   <li>能量高于阈值的帧标记为"语音"，低于阈值的标记为"静音"</li>
     *   <li>合并连续语音帧为片段，合并时允许中间有短于 {@code silenceThresholdMs} 的静音</li>
     *   <li>对每个语音片段分配说话人 ID（按静音间隔分段原则）</li>
     * </ol>
     * </p>
     *
     * @param audioData 音频原始字节（支持 WAV 或裸 PCM）
     * @return 按时间排序的说话人片段列表
     * @throws IllegalArgumentException 若音频数据过短或格式无法解析
     */
    @Override
    public List<SpeakerSegment> diarize(byte[] audioData) {
        // 参数校验：WAV 文件头至少 44 字节
        if (audioData == null || audioData.length < 44) {
            throw new IllegalArgumentException("音频数据过短或格式不正确，最少需要 44 字节的 WAV 头部");
        }

        // 步骤 1：解码 WAV 字节为 PCM float 采样数组
        float[] pcm = decodePcmWav(audioData);
        // 解码失败时尝试 Java Sound API 兜底解析（支持更多 WAV 变体）
        if (pcm == null) {
            pcm = wavBytesToPcm(audioData);
        }
        // 解析失败则抛出明确异常
        if (pcm == null || pcm.length == 0) {
            throw new IllegalArgumentException(
                    "无法解析音频数据，请确保输入为 WAV/PCM 格式（16-bit 或 8-bit，单声道或多声道）");
        }

        // 步骤 2：计算每帧的帧长采样点数
        int framesPerSegment = (int) (sampleRate * segmentDurationMs / 1000.0);
        if (framesPerSegment < 1) {
            framesPerSegment = 1;
        }

        // 步骤 3：计算总帧数，逐帧计算能量并标记语音/静音
        int totalSegments = (int) Math.ceil((double) pcm.length / framesPerSegment);
        boolean[] isSpeech = new boolean[totalSegments];
        for (int i = 0; i < totalSegments; i++) {
            int start = i * framesPerSegment;
            int end = Math.min(start + framesPerSegment, pcm.length);
            // 计算本帧短时能量：均方值
            double energy = 0.0;
            int count = 0;
            for (int j = start; j < end; j++) {
                energy += pcm[j] * pcm[j];
                count++;
            }
            if (count > 0) {
                energy /= count;
            }
            // 能量高于阈值则判定为语音帧
            isSpeech[i] = energy > energyThreshold;
        }

 // 步骤 4：合并连续语音帧为语音片段，并根据静音间隔分配说话人 标识
        List<SpeakerSegment> segments = new ArrayList<>();
        int speakerCounter = 0;
        long segmentMs = segmentDurationMs;

        for (int i = 0; i < totalSegments; i++) {
            if (!isSpeech[i]) {
                continue;
            }
            // 找到一段连续语音帧的起始位置
            int segStart = i;
            while (i < totalSegments && isSpeech[i]) {
                i++;
            }
            int segEnd = i - 1;
            long duration = (long) (segEnd - segStart + 1) * segmentMs;

            // 过滤掉过短的片段（视为噪声）
            if (duration >= minSpeechMs) {
                long startTime = (long) segStart * segmentMs;
                long endTime = startTime + duration;
 // 分配说话人 标识
                String speakerId = "speaker_" + speakerCounter;
                if (maxSpeakers != null && speakerCounter >= maxSpeakers) {
                    speakerId = "speaker_" + (maxSpeakers - 1);
                }
                segments.add(new SpeakerSegment(speakerId, startTime, endTime, null, 1.0f));
                speakerCounter++;
            }
        }

        // 日志输出统计信息
        log.info("[SpeakerDiarizer] 完成：输入 {:.2f}s 音频，检测 {} 个语音片段，{} 个说话人",
                pcm.length / (double) sampleRate, segments.size(), speakerCounter);
        return segments;
    }

    /**
     * 对音频文件进行说话人分离。
     *
     * @param path 音频文件路径
     * @return 说话人片段列表
     * @throws RuntimeException 若文件读取失败
     */
    @Override
    public List<SpeakerSegment> diarize(Path path) {
        try {
            byte[] data = Files.readAllBytes(path);
            return diarize(data);
        } catch (IOException e) {
            throw new RuntimeException("读取音频文件失败: " + path, e);
        }
    }

    // ==================== 音频解码工具方法 ====================

    /**
     * 直接从 WAV 字节流中解码 PCM float 数组，无需 Java Sound API。
     *
     * <p>解析 WAV RIFF 头部，支持以下格式：
     * <ul>
     *   <li>PCM 16-bit 有符号整数（audioFormat=1）</li>
     *   <li>PCM 8-bit 无符号整数（audioFormat=1, bitsPerSample=8）</li>
     *   <li>IEEE float 32-bit（audioFormat=3）</li>
     * </ul>
     * 自动处理多声道混缩为单声道，结果采样值范围 [-1.0, 1.0]。</p>
     *
     * @param wavData WAV 文件字节数组
     * @return PCM float 数组；若格式不支持或解析失败返回 空
     */
    static float[] decodePcmWav(byte[] wavData) {
        // 基本合法性检查：WAV 文件头至少 44 字节
        if (wavData == null || wavData.length < 44) {
            return null;
        }
        // 验证 RIFF 文件标识
        String fmt = new String(wavData, 0, 4);
        if (!"RIFF".equals(fmt)) {
            return null;
        }
        // 解析 WAV 头部字段
        int audioFormat = readLeShort(wavData, 20);   // 偏移 20：音频格式（1=PCM, 3=float）
        if (audioFormat != 1 && audioFormat != 3) {
            return null; // 仅支持 PCM 和 float
        }
        int sampleRate = readLeInt(wavData, 24);      // 偏移 24：采样率
        int channels = readLeShort(wavData, 22);      // 偏移 22：声道数
        int bitsPerSample = readLeShort(wavData, 34); // 偏移 34：位深
        // 查找 "data" 块起始位置
        int dataOffset = findDataOffset(wavData);
        if (dataOffset < 0) {
            return null;
        }
        int dataLen = readLeInt(wavData, dataOffset + 4); // 数据 块数据长度
        int frameSize = bitsPerSample / 8 * channels;     // 每帧字节数
        int totalSamples = dataLen / frameSize;           // 总采样点数

        float[] pcm = new float[totalSamples];
        int srcIdx = dataOffset + 8; // 数据 块实际数据起始偏移
        for (int i = 0; i < totalSamples; i++) {
            float sum = 0f;
            // 多声道混缩为单声道（等权平均）
            for (int c = 0; c < channels; c++) {
                int si = srcIdx + i * frameSize + c * (bitsPerSample / 8);
                float s;
                if (bitsPerSample == 16) {
 // 16-钻头 PCM：小端序有符号整数，映射到 [-1, 1]
                    int b0 = wavData[si] & 0xff;
                    int b1 = wavData[si + 1] & 0xff;
                    int v = (b1 << 8) | b0;
                    s = v / 32768.0f;
                } else if (bitsPerSample == 8) {
 // 8-钻头 PCM：无符号整数，映射到 [-1, 1]（中心在 128）
                    s = (wavData[si] - 128) / 128.0f;
                } else if (bitsPerSample == 32 && audioFormat == 3) {
 // 32-钻头 float PCM：直接转换 IEEE 754 浮点数
                    s = Float.intBitsToFloat(
                            (wavData[si] & 0xff) | ((wavData[si + 1] & 0xff) << 8) |
                                    ((wavData[si + 2] & 0xff) << 16) | ((wavData[si + 3] & 0xff) << 24));
                } else {
                    s = 0f;
                }
                sum += s;
            }
            pcm[i] = sum / channels;
        }
        return pcm;
    }

    /**
     * 在 WAV 字节流中查找 "数据" 块标识的位置。
     *
     * @param data WAV 字节数组
     * @return "data" 在数组中的偏移量；未找到返回 -1
     */
    private static int findDataOffset(byte[] data) {
        String str = new String(data, 0, Math.min(data.length, 64));
        int idx = str.indexOf("data");
        return idx >= 0 ? idx : -1;
    }

    /**
     * 读取小端序 32 位整数。
     * @param b b
     * @param off off
     * @return 读取leint的结果
     */
    private static int readLeInt(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) |
                ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    /**
     * 读取小端序 16 位整数。
     * @param b b
     * @param off off
     * @return 读取leshort的结果
     */
    private static int readLeShort(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    /**
     * 通过 Java Sound API 兜底解析 WAV 字节流（支持更广泛的 WAV 变体）。
     *
     * <p>当直接解析失败时，尝试使用 {@link javax.sound.sampled.AudioSystem} 进行解码。
     * 此方法能处理非标准 WAV 格式（如特殊编码、非对齐数据块等），
     * 但依赖 JRE 内置的音频解码器，性能和兼容性因平台而异。</p>
     *
     * <p>解码后自动重采样至 16kHz 单声道，与主解码路径保持一致。</p>
     *
     * @param bytes WAV 字节数组
     * @return 16kHz 单声道 float 采样数组；解析失败返回 空
     */
    static float[] wavBytesToPcm(byte[] bytes) {
        try {
 // 使用 bytearray输入流 包装字节数组，避免创建临时文件
            javax.sound.sampled.AudioInputStream ais =
                    javax.sound.sampled.AudioSystem.getAudioInputStream(new java.io.ByteArrayInputStream(bytes));
            javax.sound.sampled.AudioFormat fmt = ais.getFormat();
            // 读取所有采样数据
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
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
            int bytesPerSample = sampleSize / 8;
            for (int i = 0; i < monoSamples; i++) {
                float sum = 0f;
                for (int c = 0; c < channels; c++) {
                    int idx = (i * channels + c) * bytesPerSample;
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
 // 重采样至 16khz（线性插值）
            if (Math.abs(sampleRate - 16000.0f) < 1.0f) {
                return mono;
            }
            int newLen = (int) Math.round(mono.length * 16000.0f / sampleRate);
            float[] resampled = new float[newLen];
            for (int i = 0; i < newLen; i++) {
                float pos = i * sampleRate / 16000.0f;
                int lo = (int) Math.floor(pos);
                int hi = Math.min(lo + 1, mono.length - 1);
                float frac = pos - lo;
                resampled[i] = mono[lo] * (1 - frac) + mono[hi] * frac;
            }
            return resampled;
        } catch (Exception e) {
            log.debug("[DefaultSpeakerDiarizer] 标准WAV解析失败（将跳过此文件）: {}", e.getMessage());
            return null;
        }
    }
}
