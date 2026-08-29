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
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Wespeaker ResNet34 说话人嵌入提取翻译器（纯 ONNX Runtime 实现）。
 *
 * <h2>模型说明</h2>
 * <p>wespeaker-resnet34 是专用于说话人验证的 ResNet34+LM 架构：
 * <ul>
 *   <li><b>输入</b>：16kHz 单声道 PCM float 音频采样数组。</li>
 *   <li><b>输出</b>：512 维 L2 归一化嵌入向量（x-vector）。</li>
 *   <li><b>用途</b>：说话人验证、声纹识别、说话人分离。</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.44
 */
@Slf4j
public class WespeakerEmbeddingTranslator implements ITranslator<byte[], float[]> {

    private OrtEnvironment ortEnv;
    private OrtSession session;
    private String modelPath;
    private int targetSampleRate = 16000;
    private volatile boolean prepared = false;

    @Override
    public String name() {
        return "wespeaker-resnet34";
    }

    @Override
    public float[] translate(byte[] audioData) {
        try {
            ensurePrepared();
            float[] pcm = decodeToPcm(audioData);
            if (pcm == null || pcm.length == 0) {
                throw new IllegalArgumentException("Cannot decode audio data");
            }

            // Truncate to max 30 seconds
            int maxSamples = targetSampleRate * 30;
            if (pcm.length > maxSamples) {
                float[] truncated = new float[maxSamples];
                System.arraycopy(pcm, 0, truncated, 0, maxSamples);
                pcm = truncated;
            }

            // Create input tensor [1, samples]
            long[] shape = {1, pcm.length};
            OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnv,
                    FloatBuffer.wrap(pcm), shape);

            // Run inference
            String inputName = session.getInputNames().iterator().next();
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(inputName, inputTensor);

            try (OrtSession.Result results = session.run(inputs)) {
                // Output shape: [1, 512]
                float[][] output = (float[][]) results.get(0).getValue();
                float[] embedding = output[0];

                // L2 normalize
                double norm = 0.0;
                for (float v : embedding) {
                    norm += v * v;
                }
                norm = Math.sqrt(norm);
                if (norm > 0) {
                    for (int i = 0; i < embedding.length; i++) {
                        embedding[i] = (float) (embedding[i] / norm);
                    }
                }
                return embedding;
            }
        } catch (Exception e) {
            throw new RuntimeException("Wespeaker inference failed", e);
        }
    }

    private void ensurePrepared() throws Exception {
        if (prepared) return;
        synchronized (this) {
            if (prepared) return;
            ortEnv = OrtEnvironment.getEnvironment();
            Path modelFile = resolveModelPath(modelPath);
            if (modelFile == null || !Files.exists(modelFile)) {
                throw new IllegalStateException("Model file not found: " + modelPath);
            }
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            session = ortEnv.createSession(modelFile.toString(), opts);
            prepared = true;
            log.info("[WespeakerEmbedding] model loaded: {}", modelFile);
        }
    }

    private static Path resolveModelPath(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) return null;
        Path abs = Path.of(pathStr);
        if (Files.exists(abs)) return abs;
        try {
            var is = WespeakerEmbeddingTranslator.class.getClassLoader()
                    .getResourceAsStream(pathStr.startsWith("/") ? pathStr.substring(1) : pathStr);
            if (is != null) {
                Path tmp = Files.createTempFile("wespeaker-", ".onnx");
                tmp.toFile().deleteOnExit();
                Files.write(tmp, is.readAllBytes());
                is.close();
                return tmp;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private float[] decodeToPcm(byte[] audioData) {
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(
                    new ByteArrayInputStream(audioData));
            AudioFormat fmt = ais.getFormat();

            if (fmt.getSampleRate() != targetSampleRate) {
                AudioFormat targetFmt = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        targetSampleRate, 16, 1, 2, targetSampleRate, false);
                ais = AudioSystem.getAudioInputStream(targetFmt, ais);
                fmt = targetFmt;
            }

            byte[] allBytes = ais.readAllBytes();
            ais.close();

            int samples = allBytes.length / 2;
            float[] pcm = new float[samples];
            for (int i = 0; i < samples; i++) {
                short s = (short) ((allBytes[i * 2] & 0xFF) | (allBytes[i * 2 + 1] << 8));
                pcm[i] = s / 32768.0f;
            }
            return pcm;
        } catch (Exception e) {
            log.warn("[WespeakerEmbedding] audio decode failed: {}", e.getMessage());
            return null;
        }
    }
}
