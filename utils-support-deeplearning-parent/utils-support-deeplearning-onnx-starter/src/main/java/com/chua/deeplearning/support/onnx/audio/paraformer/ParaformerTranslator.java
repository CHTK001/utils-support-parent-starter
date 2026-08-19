package com.chua.deeplearning.support.onnx.audio.paraformer;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import lombok.extern.slf4j.Slf4j;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Paraformer ONNX Translator（音频文件 → 转写文本）。
 * <p>
 * 流程：WAV(16k int16) → kaldi fbank(80) → LFR(560) → CMVN → ONNX 单次推理 →
 * greedy search(按帧 argmax，遇 EOS 停止) → tokens 解码。
 * </p>
 * <p>
 * ONNX 模型输入输出（已按 model.int8.onnx 实测确认）：
 * <ul>
 *   <li>输入 {@code speech}：(1, T, 560) float32</li>
 *   <li>输入 {@code speech_lengths}：(1,) int64</li>
 *   <li>输出 {@code logits}：(1, T, 8359) float32</li>
 *   <li>输出 {@code token_num}：(1,) int64</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ParaformerTranslator {

    /** 采样率 */
    private static final int SAMPLE_RATE = 16000;

    /** 输入名：语音特征 */
    private static final String INPUT_SPEECH = "speech";

    /** 输入名：特征帧数 */
    private static final String INPUT_SPEECH_LENGTHS = "speech_lengths";

    /** 特征维度（LFR 后 560 = 80×7） */
    private static final int FEATURE_DIM = 560;

    /** 模型 metadata key：vocab size */
    private static final String META_VOCAB_SIZE = "vocab_size";

    /** 模型 metadata key：LFR 窗口 */
    private static final String META_LFR_WINDOW_SIZE = "lfr_window_size";

    /** 模型 metadata key：LFR 步长 */
    private static final String META_LFR_WINDOW_SHIFT = "lfr_window_shift";

    /** 模型 metadata key：CMVN 负均值 */
    private static final String META_NEG_MEAN = "neg_mean";

    /** 模型 metadata key：CMVN 逆标准差 */
    private static final String META_INV_STDDEV = "inv_stddev";

    /** ONNX 运行时环境 */
    private OrtEnvironment ortEnv;

    /** ONNX 会话 */
    private OrtSession session;

    /** 特征提取器 */
    private ParaformerFbankExtractor extractor;

    /** 词表 */
    private ParaformerTokenizer tokenizer;

    /** 是否已准备 */
    private boolean prepared;

    /**
     * 加载模型与词表。
     *
     * @param modelDir 模型目录（含 model.int8.onnx、tokens.txt）
     * @throws Exception 加载失败
     */
    public void prepare(Path modelDir) throws Exception {
        Path modelPath = findOnnx(modelDir);
        Path tokensPath = modelDir.resolve("tokens.txt");
        if (modelPath == null || Files.notExists(tokensPath)) {
            throw new IOException("Paraformer resources not found in: " + modelDir);
        }

        this.tokenizer = ParaformerTokenizer.load(tokensPath);
        this.extractor = new ParaformerFbankExtractor();

        try {
            this.ortEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
            this.session = ortEnv.createSession(modelPath.toString(), opts);
            readMetadata();
            log.info("[Paraformer] ONNX loaded: model={} vocab={} eos={}",
                    modelPath, tokenizer.vocabSize(), tokenizer.eosId());
            prepared = true;
        } catch (Exception e) {
            throw new IOException("Failed to create ORT session: " + e.getMessage(), e);
        }
    }

    /**
     * 读取 ONNX metadata：LFR 参数与 CMVN 向量。
     *
     * @throws Exception 读取失败
     */
    private void readMetadata() throws Exception {
        Map<String, String> meta = session.getMetadata().getCustomMetadata();
        if (meta.containsKey(META_NEG_MEAN) && meta.containsKey(META_INV_STDDEV)) {
            float[] negMean = parseFloats(meta.get(META_NEG_MEAN));
            float[] invStddev = parseFloats(meta.get(META_INV_STDDEV));
            extractor.setCmvn(negMean, invStddev);
        }
    }

    /**
     * 解析逗号分隔的 float 数组。
     *
     * @param value 逗号分隔字符串
     * @return float 数组
     */
    private static float[] parseFloats(String value) {
        String[] parts = value.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i].trim());
        }
        return result;
    }

    /**
     * 查找模型目录下的 ONNX 文件。
     *
     * @param dir 模型目录
     * @return ONNX 文件路径，找不到返回 null
     * @throws IOException 列目录失败
     */
    private static Path findOnnx(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".onnx"))
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * 转写音频。
     *
     * @param audioPath 音频文件路径
     * @return 识别文本
     * @throws Exception 处理失败
     */
    public String transcribe(Path audioPath) throws Exception {
        if (!prepared) {
            throw new IllegalStateException("ParaformerTranslator not prepared");
        }
        float[] samples = loadAudio(audioPath);
        log.info("[Paraformer] audio samples={} duration={}s",
                samples.length, String.format("%.2f", samples.length / (float) SAMPLE_RATE));

        // 提取特征
        float[] features = extractor.extract(samples);
        int inputFrames = features.length / FEATURE_DIM;
        log.info("[Paraformer] features frames={} dim={}", inputFrames, FEATURE_DIM);
        if (inputFrames == 0) {
            return "";
        }

        // ONNX 推理
        float[] logits;
        int seqLen;
        try (OnnxTensor speech = OnnxTensor.createTensor(ortEnv,
                        FloatBuffer.wrap(features), new long[]{1, inputFrames, FEATURE_DIM});
             OnnxTensor lengths = OnnxTensor.createTensor(ortEnv,
                        IntBuffer.wrap(new int[]{inputFrames}), new long[]{1});
             OrtSession.Result result = session.run(Map.of(
                     INPUT_SPEECH, speech, INPUT_SPEECH_LENGTHS, lengths))) {
            OnnxTensor logitsTensor = (OnnxTensor) result.get(0);
            logits = logitsTensor.getFloatBuffer().array();
            long[] shape = logitsTensor.getInfo().getShape();
            seqLen = (int) shape[1];
        }

        // greedy search
        int vocabSize = tokenizer.vocabSize();
        int eosId = tokenizer.eosId();
        List<Integer> tokenIds = new ArrayList<>();
        for (int k = 0; k < seqLen; k++) {
            int offset = k * vocabSize;
            int maxIdx = 0;
            float maxVal = logits[offset];
            for (int v = 1; v < vocabSize; v++) {
                float val = logits[offset + v];
                if (val > maxVal) {
                    maxVal = val;
                    maxIdx = v;
                }
            }
            if (maxIdx == eosId) {
                break;
            }
            tokenIds.add(maxIdx);
        }
        log.info("[Paraformer] generated {} tokens", tokenIds.size());
        return tokenizer.decode(tokenIds);
    }

    /**
     * 加载音频为 16kHz 单声道 int16 范围样本（约 ±32768）。
     *
     * @param path 音频文件路径
     * @return 样本数组
     * @throws Exception 读取失败
     */
    public static float[] loadAudio(Path path) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new File(path.toUri()))) {
            AudioFormat fmt = in.getFormat();
            float sampleRate = fmt.getSampleRate();
            int channels = fmt.getChannels();
            int sampleSizeInBits = fmt.getSampleSizeInBits();
            boolean bigEndian = fmt.isBigEndian();

            byte[] bytes;
            try (var baos = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    baos.write(buf, 0, n);
                }
                bytes = baos.toByteArray();
            }

            int bytesPerSample = sampleSizeInBits / 8;
            int totalSamples = bytes.length / bytesPerSample;
            int monoSamples = totalSamples / channels;
            float[] mono = new float[monoSamples];
            for (int i = 0; i < monoSamples; i++) {
                float sum = 0;
                for (int c = 0; c < channels; c++) {
                    int idx = (i * channels + c) * bytesPerSample;
                    float s;
                    if (sampleSizeInBits == 16) {
                        int lo = bytes[idx] & 0xff;
                        int hi = bytes[idx + 1];
                        int v = bigEndian ? ((lo << 8) | hi) : ((hi << 8) | lo);
                        s = (short) v;
                    } else if (sampleSizeInBits == 8) {
                        s = (bytes[idx] - 128) * 256.0f;
                    } else if (sampleSizeInBits == 32 && fmt.getEncoding() == AudioFormat.Encoding.PCM_FLOAT) {
                        int v = java.nio.ByteBuffer.wrap(bytes, idx, 4)
                                .order(bigEndian ? java.nio.ByteOrder.BIG_ENDIAN : java.nio.ByteOrder.LITTLE_ENDIAN)
                                .getInt();
                        s = Float.intBitsToFloat(v) * 32768.0f;
                    } else {
                        s = 0;
                    }
                    sum += s;
                }
                mono[i] = sum / channels;
            }

            if (Math.abs(sampleRate - SAMPLE_RATE) < 1.0f) {
                return mono;
            }
            int newLen = (int) Math.round(mono.length * SAMPLE_RATE / sampleRate);
            float[] resampled = new float[newLen];
            for (int i = 0; i < newLen; i++) {
                float srcPos = i * sampleRate / SAMPLE_RATE;
                int lo = (int) Math.floor(srcPos);
                int hi = Math.min(lo + 1, mono.length - 1);
                float frac = srcPos - lo;
                resampled[i] = mono[lo] * (1 - frac) + mono[hi] * frac;
            }
            return resampled;
        }
    }
}