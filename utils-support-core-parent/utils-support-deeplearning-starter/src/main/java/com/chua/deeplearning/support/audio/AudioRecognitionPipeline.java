package com.chua.deeplearning.support.audio;

import com.chua.common.support.ai.audio.VirtualClient;
import com.chua.common.support.utils.MathUtils;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 音频识别管线，聚合说话人分离（Diarization）与语音识别（ASR）的完整流程。
 *
 * <h2>处理流水线</h2>
 * <pre>
 *   Step 1 [VAD 时间切分]
 *     Input:  原始音频字节 (byte[])
 *     Output: List&lt;SpeakerSegment&gt;  — 按能量阈值切分的语音片段
 *
 *   Step 2 [说话人嵌入提取]（可选）
 *     Input:  各语音片段的音频字节
 *     Model:  wespeaker-resnet34 / wav2vec2-zh-fingerprint
 *     Output: float[][] embeddings  — 每个片段一个 512 维向量
 *
 *   Step 3 [说话人聚类]（可选）
 *     Algorithm:  K-Means（基于余弦距离）
 *     Output:     String[] assignments  — 每个片段归属的说话人 ID（"speaker_0" ~ "speaker_K-1"）
 *
 *   Step 4 [ASR 转写]（可选）
 *     Model:  whisper-tiny / paraformer-zh-small
 *     Output: String[] transcripts  — 每个片段的转写文本
 *
 *   Step 5 [片段合并]
 *     合并连续同说话人的片段，生成最终 SpeakerSegment 列表
 * </pre>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 *   AudioRecognitionPipeline pipeline = AudioRecognitionPipeline.builder()
 *       .vadModel("energy-vad")
 *       .speakerEmbeddingModel("wespeaker-resnet34")
 *       .asrModel("whisper-tiny")
 *       .maxSpeakers(3)
 *       .build();
 *
 *   List<SpeakerSegment> result = pipeline.recognize(Path.of("meeting.wav"));
 *
 *   for (SpeakerSegment seg : result) {
 *       System.out.printf("%s  %.1fs-%.1fs  %s%n",
 *               seg.speakerId(),
 *               seg.startTimeMs() / 1000.0,
 *               seg.endTimeMs() / 1000.0,
 *               seg.transcript());
 *   }
 * }</pre>
 *
 * <h2>可插拔设计</h2>
 * <ul>
 *   <li>所有步骤均可独立跳过（传入 {@code null}），管线会自动降级。</li>
 *   <li>Step 2/3 用于说话人分组，若仅需时间切分可仅配置 Step 1。</li>
 *   <li>Step 4 用于文字转录，若仅需说话人分段不配置 ASR 模型即可。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class AudioRecognitionPipeline {

    /** 说话人嵌入模型 ID，可为 null（跳过嵌入/聚类步骤） */
    private final String speakerEmbeddingModel;
    /** ASR 语音识别模型 ID，可为 null（跳过转写步骤） */
    private final String asrModel;
    /** 最大说话人数，null 表示不限制 */
    private final Integer maxSpeakers;
    /** 聚类最小片段数，少于该值的碎片将被合并到相邻片段 */
    private final int minSegmentMs;
    /** 推理引擎实例 */
    private final IdentificationEngine engine;
    /** 音频识别管线回调 */
    private AudioRecognitionPipelineCallback callback;

    /**
     * 私有构造，通过 {@link Builder} 创建实例。
     */
    private AudioRecognitionPipeline(Builder builder) {
        this.speakerEmbeddingModel = builder.speakerEmbeddingModel;
        this.asrModel = builder.asrModel;
        this.maxSpeakers = builder.maxSpeakers;
        this.minSegmentMs = builder.minSegmentMs;
        this.engine = AbstractIdentificationEngine.getInstance();
    }

    /**
     * 创建构建器。
     *
     * @return Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 对音频字节执行完整的说话人分离 + 语音识别管线。
     *
     * <p>若配置了说话人嵌入模型，则额外执行 K-Means 聚类以区分不同说话人；
     * 若配置了 ASR 模型，则对每个片段执行语音转写。</p>
     *
     * @param audioData 音频原始字节（WAV/PCM）
     * @return 按时间排序的最终说话人片段列表
     */
    public List<SpeakerSegment> recognize(byte[] audioData) {
        long t0 = System.currentTimeMillis();
        AudioRecognitionContext ctx = AudioRecognitionContext.builder()
                .rawAudio(audioData)
                .build();

        try {
            // Step 1: VAD 时间切分
            List<SpeakerSegment> vadResult = performVad(audioData);
            ctx.setVadSegments(vadResult);
            log.info("[AudioPipeline] Step1 VAD 切分完成：{} 个语音片段", vadResult.size());
            if (callback != null) {
                callback.onVad(vadResult);
            }

            if (vadResult.isEmpty()) {
                ctx.setElapsedMs(System.currentTimeMillis() - t0);
                return List.of();
            }

            // Step 2: 说话人嵌入提取（可选）
            if (speakerEmbeddingModel != null) {
                float[][] embeddings = extractSpeakerEmbeddings(audioData, vadResult);
                ctx.setSpeakerEmbeddings(embeddings);
                if (callback != null) {
                    callback.onEmbedding(embeddings);
                }

                // Step 3: K-Means 聚类
                String[] assignments = kMeansCluster(embeddings, vadResult.size());
                ctx.setSpeakerAssignments(assignments);
                log.info("[AudioPipeline] Step2/3 说话人聚类完成：{} 个说话人",
                        distinctAssignments(assignments).size());
                if (callback != null) {
                    callback.onCluster(assignments);
                }
            } else {
                // 无嵌入模型时，每个 VAD 片段单独分配一个说话人 ID
                String[] assignments = new String[vadResult.size()];
                for (int i = 0; i < vadResult.size(); i++) {
                    assignments[i] = "speaker_" + i;
                }
                ctx.setSpeakerAssignments(assignments);
            }

            // Step 4: ASR 转写（可选）
            if (asrModel != null) {
                String[] transcripts = transcribeSegments(audioData, vadResult);
                ctx.setTranscripts(transcripts);
                log.info("[AudioPipeline] Step4 ASR 转写完成：{} 个片段已转写", transcripts.length);
                if (callback != null) {
                    callback.onTranscribe(transcripts);
                }
            }

            // Step 5: 合并连续同说话人片段
            List<SpeakerSegment> finalResult = mergeAdjacentSegments(ctx);
            ctx.setFinalSegments(finalResult);

            ctx.setElapsedMs(System.currentTimeMillis() - t0);
            log.info("[AudioPipeline] 管线完成：共 {}ms，输出 {} 个最终片段",
                    ctx.getElapsedMs(), finalResult.size());
            if (callback != null) {
                callback.onComplete(finalResult, ctx.getElapsedMs());
            }
            return finalResult;

        } catch (Exception e) {
            log.error("[AudioPipeline] 管线执行失败: {}", e.getMessage(), e);
            throw new RuntimeException("音频识别管线失败: " + e.getMessage(), e);
        }
    }

    /**
     * 对音频文件执行完整管线。
     *
     * @param path 音频文件路径
     * @return 最终说话人片段列表
     */
    public List<SpeakerSegment> recognize(java.nio.file.Path path) {
        try {
            byte[] data = java.nio.file.Files.readAllBytes(path);
            return recognize(data);
        } catch (java.io.IOException e) {
            throw new RuntimeException("读取音频文件失败: " + path, e);
        }
    }

    /**
     * 设置音频识别管线回调。
     *
     * @param callback 回调实例
     */
    public void setCallback(AudioRecognitionPipelineCallback callback) {
        this.callback = callback;
    }

    /**
     * 获取音频识别管线回调。
     *
     * @return 回调实例，可能为 null
     */
    public AudioRecognitionPipelineCallback callback() {
        return this.callback;
    }

    // ==================== Step 1: VAD 时间切分 ====================

    /**
     * 执行 VAD 时间切分。
     *
     * @param audioData 音频字节
     * @return 初步语音片段列表
     */
    private List<SpeakerSegment> performVad(byte[] audioData) {
        var diarizer = new DefaultSpeakerDiarizer(engine, "energy-vad",
                com.chua.deeplearning.support.config.ModelSetting.builder().build());
        if (maxSpeakers != null) {
            diarizer.maxSpeakers(maxSpeakers);
        }
        return diarizer.diarize(audioData);
    }

    // ==================== Step 2: 说话人嵌入提取 ====================

    /**
     * 对每个 VAD 片段提取说话人嵌入向量。
     *
     * <p>从原始音频中按片段时间戳裁剪出各段音频，调用嵌入模型提取特征向量。</p>
     *
     * @param audioData    原始音频字节
     * @param vadSegments  VAD 切分结果
     * @return 二维数组 embeddings[i] 对应 vadSegments.get(i) 的嵌入向量
     */
    private float[][] extractSpeakerEmbeddings(byte[] audioData, List<SpeakerSegment> vadSegments) {
        float[] pcm = DefaultSpeakerDiarizer.decodePcmWav(audioData);
        if (pcm == null) {
            pcm = DefaultSpeakerDiarizer.wavBytesToPcm(audioData);
        }
        if (pcm == null || pcm.length == 0) {
            log.warn("[AudioPipeline] 无法解码音频，跳过说话人嵌入提取");
            return new float[0][];
        }

        ITranslator<byte[], float[]> embedTranslator =
                (ITranslator<byte[], float[]>) engine.get(speakerEmbeddingModel, ITranslator.class);
        if (embedTranslator == null) {
            log.warn("[AudioPipeline] 说话人嵌入模型未注册: {}，跳过嵌入提取", speakerEmbeddingModel);
            return new float[0][];
        }

        int n = vadSegments.size();
        float[][] embeddings = new float[n][];
        for (int i = 0; i < n; i++) {
            SpeakerSegment seg = vadSegments.get(i);
            long startSample = seg.startTimeMs() * 16000L / 1000L;
            long endSample = seg.endTimeMs() * 16000L / 1000L;
            if (startSample >= pcm.length) {
                embeddings[i] = new float[0];
                continue;
            }
            int len = (int) Math.min(endSample - startSample, pcm.length - startSample);
            if (len <= 0) {
                embeddings[i] = new float[0];
                continue;
            }
            float[] segmentPcm = Arrays.copyOfRange(pcm, (int) startSample, (int) startSample + len);
            try {
                // 转换为 byte[] 委托给翻译器（翻译器内部处理解码）
                byte[] segmentBytes = pcmToWavBytes(segmentPcm, 16000);
                embeddings[i] = embedTranslator.translate(segmentBytes);
            } catch (Exception e) {
                log.warn("[AudioPipeline] 片段 {} 嵌入提取失败: {}", i, e.getMessage());
                embeddings[i] = new float[0];
            }
        }
        return embeddings;
    }

    // ==================== Step 3: K-Means 聚类 ====================

    /**
     * 对说话人嵌入向量执行 K-Means 聚类。
     *
     * <p>使用余弦距离作为相似度度量，迭代更新聚类中心直至收敛。</p>
     *
     * @param embeddings  嵌入向量数组
     * @param segmentCount 片段总数（可能与 embeddings 行数不同，以 embeddings 为准）
     * @return 每个片段归属的说话人 ID 数组
     */
    private String[] kMeansCluster(float[][] embeddings, int segmentCount) {
        int n = embeddings.length;
        if (n == 0) {
            return new String[0];
        }

        // 过滤空向量
        List<Integer> validIndices = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (embeddings[i] != null && embeddings[i].length > 0) {
                validIndices.add(i);
            }
        }
        if (validIndices.isEmpty()) {
            return new String[n];
        }

        // 确定聚类数 K
        int K = maxSpeakers != null ? Math.min(maxSpeakers, validIndices.size()) : validIndices.size();
        K = Math.max(1, K);

        // 初始化聚类中心（随机选取 K 个有效向量）
        int dim = embeddings[validIndices.get(0)].length;
        float[][] centers = new float[K][dim];
        java.util.Random rand = new java.util.Random(42);
        List<Integer> shuffled = new ArrayList<>(validIndices);
        Collections.shuffle(shuffled, rand);
        for (int k = 0; k < K && k < shuffled.size(); k++) {
            System.arraycopy(embeddings[shuffled.get(k)], 0, centers[k], 0, dim);
            l2NormalizeInPlace(centers[k]);
        }

        // 迭代聚类
        String[] assignments = new String[n];
        for (int iter = 0; iter < 20; iter++) {
            boolean changed = false;
            // 分配阶段
            for (int i = 0; i < n; i++) {
                if (embeddings[i] == null || embeddings[i].length == 0) {
                    assignments[i] = "speaker_noise";
                    continue;
                }
                int bestK = argmaxCosine(embeddings[i], centers);
                String newLabel = "speaker_" + bestK;
                if (!newLabel.equals(assignments[i])) {
                    changed = true;
                }
                assignments[i] = newLabel;
            }
            if (!changed) break;
            // 更新中心
            for (int k = 0; k < K; k++) {
                float[] sum = new float[dim];
                int count = 0;
                for (int i = 0; i < n; i++) {
                    if (assignments[i] != null && assignments[i].equals("speaker_" + k)
                            && embeddings[i] != null && embeddings[i].length == dim) {
                        for (int d = 0; d < dim; d++) {
                            sum[d] += embeddings[i][d];
                        }
                        count++;
                    }
                }
                if (count > 0) {
                    for (int d = 0; d < dim; d++) {
                        centers[k][d] = sum[d] / count;
                    }
                    l2NormalizeInPlace(centers[k]);
                }
            }
        }
        return assignments;
    }

    /**
     * 返回出现过的不同说话人 ID 集合。
     */
    private java.util.Set<String> distinctAssignments(String[] assignments) {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        for (String a : assignments) {
            if (a != null) set.add(a);
        }
        return set;
    }

    /**
     * 对浮点向量做 L2 归一化（原地修改）。
     */
    private static void l2NormalizeInPlace(float[] vec) {
        float norm = 0f;
        for (float v : vec) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm < 1e-8f) return;
        for (int i = 0; i < vec.length; i++) vec[i] /= norm;
    }

    /**
     * 计算向量与各中心点的余弦相似度，返回最大相似度对应的索引。
     */
    private static int argmaxCosine(float[] vec, float[][] centers) {
        l2NormalizeInPlace(vec);
        int bestK = 0;
        float bestScore = -2f;
        for (int k = 0; k < centers.length; k++) {
            float score = MathUtils.cosineSimilarity(vec, centers[k]);
            if (score > bestScore) {
                bestScore = score;
                bestK = k;
            }
        }
        return bestK;
    }


    // ==================== Step 4: ASR 转写 ====================

    /**
     * 对各 VAD 片段执行 ASR 语音转写。
     *
     * @param audioData   原始音频字节
     * @param vadSegments VAD 切分结果
     * @return 各片段的转录文本数组（与 vadSegments 一一对应）
     */
    private String[] transcribeSegments(byte[] audioData, List<SpeakerSegment> vadSegments) {
        ITranslator<byte[], String> asrTranslator =
                (ITranslator<byte[], String>) engine.get(asrModel, ITranslator.class);
        float[] pcm = DefaultSpeakerDiarizer.decodePcmWav(audioData);
        if (pcm == null) {
            pcm = DefaultSpeakerDiarizer.wavBytesToPcm(audioData);
        }
        if (asrTranslator != null) {
            // 旧路径：IdentificationEngine ITanslator（whisper/paraformer/moonshine 等）
            return transcribeViaTranslator(asrTranslator, pcm, vadSegments);
        }
        // 新路径：VirtualClient SPI（SenseVoice、zipformer 等实现 VirtualClient 的模型）
        return transcribeViaAudioClient(vadSegments, pcm);
    }

    private String[] transcribeViaTranslator(ITranslator<byte[], String> asrTranslator,
                                              float[] pcm, List<SpeakerSegment> vadSegments) {
        String[] transcripts = new String[vadSegments.size()];
        for (int i = 0; i < vadSegments.size(); i++) {
            SpeakerSegment seg = vadSegments.get(i);
            long startSample = seg.startTimeMs() * 16000L / 1000L;
            long endSample = seg.endTimeMs() * 16000L / 1000L;
            if (startSample >= (pcm != null ? pcm.length : 0)) {
                transcripts[i] = null;
                continue;
            }
            int len = (int) Math.min(endSample - startSample,
                    (pcm != null ? pcm.length : 0) - startSample);
            if (len <= 0) {
                transcripts[i] = null;
                continue;
            }
            try {
                byte[] segmentBytes = pcmToWavBytes(
                        Arrays.copyOfRange(pcm, (int) startSample, (int) startSample + len), 16000);
                transcripts[i] = asrTranslator.translate(segmentBytes);
            } catch (Exception e) {
                log.warn("[AudioPipeline] 片段 {} ASR 转写失败: {}", i, e.getMessage());
                transcripts[i] = null;
            }
        }
        return transcripts;
    }

    private String[] transcribeViaAudioClient(List<SpeakerSegment> vadSegments, float[] pcm) {
        VirtualClient client;
        try {
            client = VirtualClient.create(asrModel, "");
        } catch (Exception e) {
            log.warn("[AudioPipeline] VirtualClient 创建失败 {}: {}", asrModel, e.getMessage());
            return new String[vadSegments.size()];
        }
        try {
            String[] transcripts = new String[vadSegments.size()];
            for (int i = 0; i < vadSegments.size(); i++) {
                SpeakerSegment seg = vadSegments.get(i);
                long startSample = seg.startTimeMs() * 16000L / 1000L;
                long endSample = seg.endTimeMs() * 16000L / 1000L;
                if (pcm == null || startSample >= pcm.length) {
                    transcripts[i] = null;
                    continue;
                }
                int len = (int) Math.min(endSample - startSample, pcm.length - startSample);
                if (len <= 0) {
                    transcripts[i] = null;
                    continue;
                }
                try {
                    byte[] segBytes = pcmToWavBytes(
                            Arrays.copyOfRange(pcm, (int) startSample, (int) startSample + len), 16000);
                    Path tmp = Files.createTempFile("asr-pipe-", ".wav");
                    try {
                        Files.write(tmp, segBytes);
                        try {
                            transcripts[i] = client.transcribe(tmp);
                        } finally {
                            Files.deleteIfExists(tmp);
                        }
                    } catch (Exception ex) {
                        Files.deleteIfExists(tmp);
                        throw ex;
                    }
                } catch (Exception e) {
                    log.warn("[AudioPipeline] 片段 {} ASR 失败 {}: {}", i, asrModel, e.getMessage());
                    transcripts[i] = null;
                }
            }
            return transcripts;
        } finally {
            if (client != null) {
                try { client.close(); } catch (Exception ignore) {}
            }
        }
    }

    // ==================== Step 5: 片段合并 ====================

    /**
     * 合并连续同说话人的 VAD 片段，回填 ASR 文本。
     *
     * <p>合并规则：
     * <ul>
     *   <li>相邻片段若说话人 ID 相同，且间隔不超过 {@code minSegmentMs}，则合并</li>
     *   <li>合并后取首个片段的 speakerId，拼接所有子片段的 transcript</li>
     * </ul>
     * </p>
     *
     * @param ctx 音频识别上下文
     * @return 合并后的最终片段列表
     */
    private List<SpeakerSegment> mergeAdjacentSegments(AudioRecognitionContext ctx) {
        List<SpeakerSegment> vadSegs = ctx.getVadSegments();
        String[] assignments = ctx.getSpeakerAssignments();
        String[] transcripts = ctx.getTranscripts();

        if (vadSegs == null || vadSegs.isEmpty()) {
            return List.of();
        }

        List<SpeakerSegment> merged = new ArrayList<>();
        int n = vadSegs.size();
        int i = 0;
        while (i < n) {
            SpeakerSegment current = vadSegs.get(i);
            String speakerId = assignments != null && i < assignments.length ? assignments[i] : current.speakerId();
            long mergeStart = current.startTimeMs();
            long mergeEnd = current.endTimeMs();
            StringBuilder textBuilder = new StringBuilder();
            if (transcripts != null && i < transcripts.length && transcripts[i] != null) {
                textBuilder.append(transcripts[i]);
            }
            int j = i + 1;
            while (j < n) {
                SpeakerSegment next = vadSegs.get(j);
                String nextSpeaker = assignments != null && j < assignments.length ? assignments[j] : next.speakerId();
                long gap = next.startTimeMs() - mergeEnd;
                boolean sameSpeaker = speakerId.equals(nextSpeaker);
                boolean closeEnough = gap <= minSegmentMs;
                if (sameSpeaker && closeEnough) {
                    mergeEnd = next.endTimeMs();
                    if (transcripts != null && j < transcripts.length && transcripts[j] != null) {
                        textBuilder.append(" ").append(transcripts[j]);
                    }
                    j++;
                } else {
                    break;
                }
            }
            merged.add(new SpeakerSegment(speakerId, mergeStart, mergeEnd,
                    textBuilder.toString().trim(), current.confidence()));
            i = j;
        }
        return merged;
    }

    // ==================== PCM → WAV 字节转换 ====================

    /**
     * 将 float 采样数组编码为 16-bit PCM WAV 字节数组，供翻译器消费。
     *
     * @param samples  float 采样数组
     * @param sampleRate 采样率
     * @return WAV 字节数组
     */
    private static byte[] pcmToWavBytes(float[] samples, int sampleRate) {
        int numChannels = 1;
        int bitsPerSample = 16;
        int byteRate = sampleRate * numChannels * bitsPerSample / 8;
        int blockAlign = numChannels * bitsPerSample / 8;
        int dataSize = samples.length * blockAlign;
        int bufferSize = 44 + dataSize;
        byte[] wav = new byte[bufferSize];
        // RIFF 头
        writeBytes(wav, 0, "RIFF".getBytes());
        writeInt(wav, 4, bufferSize - 8);
        writeBytes(wav, 8, "WAVE".getBytes());
        // fmt 子块
        writeBytes(wav, 12, "fmt ".getBytes());
        writeInt(wav, 16, 16);          // Subchunk1Size
        writeShort(wav, 20, (short) 1);         // AudioFormat (PCM)
        writeShort(wav, 22, (short) numChannels);
        writeInt(wav, 24, sampleRate);
        writeInt(wav, 28, byteRate);
        writeShort(wav, 32, (short) blockAlign);
        writeShort(wav, 34, (short) bitsPerSample);
        // data 子块
        writeBytes(wav, 36, "data".getBytes());
        writeInt(wav, 40, dataSize);
        // 写入采样数据（float → 16-bit PCM，小端序）
        int offset = 44;
        for (float s : samples) {
            int val = (int) (Math.max(-1.0f, Math.min(1.0f, s)) * 32767);
            wav[offset++] = (byte) (val & 0xff);
            wav[offset++] = (byte) ((val >> 8) & 0xff);
        }
        return wav;
    }

    private static void writeBytes(byte[] buf, int off, byte[] src) {
        System.arraycopy(src, 0, buf, off, src.length);
    }

    private static void writeInt(byte[] buf, int off, int val) {
        buf[off] = (byte) (val & 0xff);
        buf[off + 1] = (byte) ((val >> 8) & 0xff);
        buf[off + 2] = (byte) ((val >> 16) & 0xff);
        buf[off + 3] = (byte) ((val >> 24) & 0xff);
    }

    private static void writeShort(byte[] buf, int off, short val) {
        buf[off] = (byte) (val & 0xff);
        buf[off + 1] = (byte) ((val >> 8) & 0xff);
    }

    // ==================== Builder ====================

    /**
     * 链式构建器。
     */
    public static class Builder {
        /** 说话人嵌入模型 ID，null 则跳过嵌入/聚类步骤 */
        private String speakerEmbeddingModel;
        /** ASR 语音识别模型 ID，null 则跳过转写步骤 */
        private String asrModel;
        /** 最大说话人数，null 表示不限制 */
        private Integer maxSpeakers;
        /** 合并相邻同说话人片段的最大间隔（毫秒），默认 500ms */
        private int minSegmentMs = 500;

        /**
         * 设置说话人嵌入模型 ID（如 "wespeaker-resnet34"）。
         * 不设置则跳过说话人聚类，每个 VAD 片段单独分配一个 ID。
         */
        public Builder speakerEmbeddingModel(String speakerEmbeddingModel) {
            this.speakerEmbeddingModel = speakerEmbeddingModel;
            return this;
        }

        /**
     * 设置 ASR 语音识别模型 ID（如 "whisper-tiny"、"paraformer-zh-small"、"sensevoice"）。
     * 支持 IdentificationEngine 注册的 ITranslator（旧路径）和 VirtualClient SPI（新路径）。
     * 不设置则不执行转写，最终片段的 transcript 字段为空。
         */
        public Builder asrModel(String asrModel) {
            this.asrModel = asrModel;
            return this;
        }

        /**
         * 设置最大说话人数上限。
         */
        public Builder maxSpeakers(Integer maxSpeakers) {
            this.maxSpeakers = maxSpeakers;
            return this;
        }

        /**
         * 设置合并相邻同说话人片段的最大静音间隔（毫秒）。
         * 默认 500ms，即两个同说话人片段之间若有 &lt;= 500ms 静音则合并。
         */
        public Builder minSegmentMs(int minSegmentMs) {
            this.minSegmentMs = minSegmentMs;
            return this;
        }

        /**
         * 构建管线实例。
         */
        public AudioRecognitionPipeline build() {
            return new AudioRecognitionPipeline(this);
        }
    }
}

