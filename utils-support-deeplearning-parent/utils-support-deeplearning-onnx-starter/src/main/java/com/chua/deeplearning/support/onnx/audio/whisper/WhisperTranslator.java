package com.chua.deeplearning.support.onnx.audio.whisper;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import lombok.extern.slf4j.Slf4j;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Whisper ONNX Translator（音频文件 → 转写文本）。
 * <p>
 * 简化实现：每步 feed 1 个 token（不做 KV cache 优化），
 * 始终 use_cache_branch=False（optimum 导出 decoder 的
 * encoder KV cache shape bug workaround）。
 * </p>
 *
 * 流程：WAV → mel → encoder → decoder greedy → token ids → text
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class WhisperTranslator {

    /** 采样率 */
    /** Sample_rate */
    private static final int SAMPLE_RATE = 16000;
    /** 梅尔滤波器组数量 */
    /** N_mels */
    private static final int N_MELS = 80;
    /** 帧数量 */
    /** N_frames */
    private static final int N_FRAMES = 3000;
    /** 编码器序列输出索引 */
    /** Enc_seq_out */
    private static final int ENC_SEQ_OUT = 1500;
    /** 隐藏层维度 */
    /** Hidden_size */
    private static final int HIDDEN_SIZE = 384;
    /** 层数量 */
    /** N_layers */
    private static final int N_LAYERS = 4;
    /** 注意力头数量 */
    /** N_heads */
    private static final int N_HEADS = 6;
    /** 注意力头维度 */
    /** Head_dim */
    private static final int HEAD_DIM = 64;

    /** 梅尔特征提取器 */
    /** MELextractor */
    private WhisperMelExtractor melExtractor;
    /** 分词器 */
    /** Tokenizer */
    private WhisperTokenizer tokenizer;
    /** ONNX 运行时环境 */
    /** ORTENV */
    private OrtEnvironment ortEnv;
    /** 编码器会话 */
    private OrtSession encoderSession;
    /** 解码器会话 */
    private OrtSession decoderSession;

    /** Prepare */
    public void prepare(Path modelDir) throws Exception {
        Path onnxDir = modelDir.resolve("onnx");
        Path encoderPath = Files.isDirectory(onnxDir) ? findOnnx(onnxDir, "encoder_model") : null;
        Path decoderPath = Files.isDirectory(onnxDir) ? findOnnx(onnxDir, "decoder_model") : null;
        Path vocabPath = modelDir.resolve("vocab.json");

        if (encoderPath == null || decoderPath == null || Files.notExists(vocabPath)) {
            extractFromJar(modelDir);
            onnxDir = modelDir.resolve("onnx");
            encoderPath = Files.isDirectory(onnxDir) ? findOnnx(onnxDir, "encoder_model") : null;
            decoderPath = Files.isDirectory(onnxDir) ? findOnnx(onnxDir, "decoder_model") : null;
        }
        if (encoderPath == null || decoderPath == null) {
            throw new IllegalStateException("无法定位 encoder/decoder ONNX: " + onnxDir);
        }
        if (!Files.exists(encoderPath) || !Files.exists(decoderPath) || !Files.exists(vocabPath)) {
            throw new IOException("Whisper resources not found in: " + modelDir);
        }

        this.melExtractor = new WhisperMelExtractor();
        this.tokenizer = WhisperTokenizer.load(vocabPath, vocabPath.resolveSibling("tokenizer.json"));

        try {
            this.ortEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
            this.encoderSession = ortEnv.createSession(encoderPath.toString(), opts);
            this.decoderSession = ortEnv.createSession(decoderPath.toString(), opts);
            log.info("[Whisper] ONNX loaded: encoder={} decoder={} vocab={}",
                    encoderPath, decoderPath, tokenizer.vocabSize());
        } catch (Exception e) {
            throw new IOException("Failed to create ORT session: " + e.getMessage(), e);
        }
    }

    /** 查找Onnx */
    private static Path findOnnx(Path dir, String prefix) throws IOException {
        try (var stream = Files.list(dir)) {
            Path result = stream
                    .filter(p -> p.getFileName().toString().startsWith(prefix) && p.toString().endsWith(".onnx"))
                    .findFirst()
                    .orElse(null);
            log.info("[Whisper] findOnnx dir={} prefix={} -> {}", dir, prefix, result);
            return result;
        }
    }

    /**
     * 从 classpath jar 内按 audio/asr/whisper-tiny/ 路径解压到 modelDir。
     * 保留子目录结构（onnx/encoder_model_quantized.onnx 等）。
     */
    private static void extractFromJar(Path modelDir) throws Exception {
        final String basePath = "audio/asr/whisper-tiny";
        ClassLoader cl = WhisperTranslator.class.getClassLoader();
        java.util.Enumeration<java.net.URL> resources = cl.getResources(basePath);
        int extracted = 0;
        while (resources.hasMoreElements()) {
            java.net.URL url = resources.nextElement();
            if ("file".equals(url.getProtocol())) {
                // dev mode: 直接从 filesystem 拷贝
                Path src = Path.of(url.toURI());
                Files.createDirectories(modelDir);
                try (var stream = Files.walk(src)) {
                    stream.forEach(p -> {
                        try {
                            if (Files.isDirectory(p)) {
                                Files.createDirectories(modelDir.resolve(src.relativize(p).toString()));
                            } else {
                                Path dest = modelDir.resolve(src.relativize(p).toString());
                                Files.createDirectories(dest.getParent());
                                Files.copy(p, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
                extracted++;
            } else if ("jar".equals(url.getProtocol())) {
                // jar 模式
                String urlPath = url.getPath();
                String jarPath = urlPath.substring(5, urlPath.indexOf("!"));
                String entryPrefix = urlPath.substring(urlPath.indexOf("!") + 2);
                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(java.net.URLDecoder.decode(jarPath, "UTF-8"))) {
                    java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        java.util.jar.JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (!name.startsWith(entryPrefix + "/") && !name.startsWith(entryPrefix)) continue;
                        String rel = name.substring(entryPrefix.length());
                        if (rel.startsWith("/")) rel = rel.substring(1);
                        if (rel.isEmpty()) continue;
                        Path dest = modelDir.resolve(rel);
                        if (entry.isDirectory()) {
                            Files.createDirectories(dest);
                        } else {
                            Files.createDirectories(dest.getParent());
                            try (var in = jar.getInputStream(entry)) {
                                Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                        extracted++;
                    }
                }
            }
        }
        if (extracted == 0) {
            throw new IOException("No resources found at classpath:" + basePath);
        }
    }

    /** Transcribe */
    public String transcribe(Path audioPath) throws Exception {
        long start = System.currentTimeMillis();
        log.info("[Whisper] loadAudio start");
        float[] audio = loadAudio(audioPath);
        log.info("[Whisper] audio samples={} duration={}s", audio.length, String.format("%.2f", audio.length / (float) SAMPLE_RATE));
        log.info("[Whisper] mel extract start");
        try {
            float[][] mel = melExtractor.extract(audio);
            log.info("[Whisper] mel shape={}x{}", mel.length, mel[0].length);
            return doTranscribe(mel);
        } catch (Throwable t) {
            log.error("[Whisper] mel/encoder failed: {}", t.getMessage(), t);
            throw t;
        }
    }

    /** DoTranscribe */
    private String doTranscribe(float[][] mel) throws Exception {

        // encoder input: (1, 80, 3000) flat
        float[] melFlat = new float[1 * N_MELS * N_FRAMES];
        for (int m = 0; m < N_MELS; m++) {
            System.arraycopy(mel[m], 0, melFlat, m * N_FRAMES, N_FRAMES);
        }
        float[] encoderHidden;
        try (OnnxTensor ft = OnnxTensor.createTensor(ortEnv,
                FloatBuffer.wrap(melFlat), new long[]{1, N_MELS, N_FRAMES});
             OrtSession.Result r = encoderSession.run(Map.of("input_features", ft))) {
            ai.onnxruntime.OnnxValue encVal = r.get(0);
            OnnxTensor encOut = (OnnxTensor) encVal;
            encoderHidden = encOut.getFloatBuffer().array();
            long[] shape = encOut.getInfo().getShape();
            log.info("[Whisper] encoder hidden shape={}x{}x{}", shape[0], shape[1], shape[2]);
        }

        int[] generated = greedyDecode(encoderHidden);
        log.info("[Whisper] generated {} tokens: {}", generated.length, generated);
        return tokenizer.decode(generated);
    }

    /** Greedy解码（非自回归模式：每次扩展输入序列重新推理） */
    private int[] greedyDecode(float[] encoderHidden) {
        List<Integer> tokens = new ArrayList<>();
        tokens.add(WhisperTokenizer.SOT);
        tokens.add(WhisperTokenizer.NOTIMESTAMPS);

        int maxNew = 100;
        int eot = WhisperTokenizer.EOT;
        int vocabSize = tokenizer.vocabSize();

        for (int step = 0; step < maxNew; step++) {
            long[] ids = new long[tokens.size()];
            for (int i = 0; i < tokens.size(); i++) ids[i] = tokens.get(i);

            Map<String, OnnxTensor> feed = new HashMap<>();
            try {
                feed.put("input_ids", OnnxTensor.createTensor(ortEnv,
                        java.nio.LongBuffer.wrap(ids), new long[]{1, ids.length}));
                feed.put("encoder_hidden_states", OnnxTensor.createTensor(ortEnv,
                        FloatBuffer.wrap(encoderHidden),
                        new long[]{1, ENC_SEQ_OUT, HIDDEN_SIZE}));

                try (OrtSession.Result r = decoderSession.run(feed)) {
                    ai.onnxruntime.OnnxValue logitsValue = r.get(0);
                    float[] logits;
                    long[] shape;
                    if (logitsValue instanceof OnnxTensor) {
                        OnnxTensor logitsT = (OnnxTensor) logitsValue;
                        logits = logitsT.getFloatBuffer().array();
                        shape = logitsT.getInfo().getShape();
                    } else {
                        log.error("[Whisper] logits is not OnnxTensor: {}", logitsValue.getClass());
                        break;
                    }
                    int seqLen = (int) shape[1];
                    int offset = (seqLen - 1) * vocabSize;
                    int nextToken = argmax(logits, offset, vocabSize);
                    if (nextToken == eot) {
                        log.debug("[Whisper] EOS at step {}", step);
                        break;
                    }
                    tokens.add(nextToken);
                    if (step < 5) {
                        log.debug("[Whisper] step {} token={} ({})", step, nextToken, tokenizer.idToToken(nextToken));
                    }
                } finally {
                    for (OnnxTensor t : feed.values()) t.close();
                }
            } catch (Exception e) {
                log.error("[Whisper] decoder error at step {}: {}", step, e.getMessage(), e);
                break;
            }
        }
        return tokens.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Argmax */
    private static int argmax(float[] arr, int offset, int length) {
        int idx = 0;
        float max = arr[offset];
        for (int i = 1; i < length; i++) {
            float v = arr[offset + i];
            if (v > max) {
                max = v;
                idx = i;
            }
        }
        return idx;
    }

    /** 加载Audio */
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
                while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
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
                        s = (short) v / 32768.0f;
                    } else if (sampleSizeInBits == 8) {
                        s = (bytes[idx] - 128) / 128.0f;
                    } else if (sampleSizeInBits == 32 && fmt.getEncoding() == AudioFormat.Encoding.PCM_FLOAT) {
                        int v = java.nio.ByteBuffer.wrap(bytes, idx, 4)
                                .order(bigEndian ? java.nio.ByteOrder.BIG_ENDIAN : java.nio.ByteOrder.LITTLE_ENDIAN)
                                .getInt();
                        s = Float.intBitsToFloat(v);
                    } else {
                        s = 0;
                    }
                    sum += s;
                }
                mono[i] = sum / channels;
            }

            if (Math.abs(sampleRate - 16000.0f) < 1.0f) return mono;
            int newLen = (int) Math.round(mono.length * 16000.0f / sampleRate);
            float[] resampled = new float[newLen];
            for (int i = 0; i < newLen; i++) {
                float srcPos = i * sampleRate / 16000.0f;
                int lo = (int) Math.floor(srcPos);
                int hi = Math.min(lo + 1, mono.length - 1);
                float frac = srcPos - lo;
                resampled[i] = mono[lo] * (1 - frac) + mono[hi] * frac;
            }
            return resampled;
        }
    }
}