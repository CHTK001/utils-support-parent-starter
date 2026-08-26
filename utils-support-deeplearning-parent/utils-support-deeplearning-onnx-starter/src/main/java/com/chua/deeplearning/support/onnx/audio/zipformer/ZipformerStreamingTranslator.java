package com.chua.deeplearning.support.onnx.audio.zipformer;

import com.chua.deeplearning.support.onnx.audio.AudioUtils;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流式 Zipformer 中英双语 ASR 翻译器。
 *
 * <p>基于 transducer 架构, 采用 39 帧分块流式推理:
 * <ol>
 *   <li>Kaldi fbank 80 维特征提取</li>
 *   <li>编码器分块执行 + 35 个状态张量跨块传递</li>
 *   <li>transducer 贪心解码 (encoder_out + decoder_out → joiner → argmax)</li>
 * </ol>
 *
 * @author chua
 * @since 4.0.0.42
 */
public class ZipformerStreamingTranslator implements AutoCloseable {

    private static final int CHUNK_SIZE = 39;
    private static final int DECODE_CHUNK_LEN = 32;
    private static final int NUM_MELS = 80;
    private static final int CONTEXT_SIZE = 2;
    private static final int BLANK_ID = 0;
    private static final int SAMPLE_RATE = 16000;

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private OrtSession encoderSession;
    private OrtSession decoderSession;
    private OrtSession joinerSession;
    private Map<String, OnnxTensor> initialStates;
    private List<String> inputNames;
    private Map<Integer, String> vocab = new HashMap<>();

    /**
     * 从模型目录加载。
     *
     * @param modelDir 模型目录
     * @throws IOException IO 异常
     */
    public void prepare(Path modelDir) throws IOException {
        Path encoderPath = resolveModel(modelDir, "encoder");
        Path decoderPath = resolveModel(modelDir, "decoder");
        Path joinerPath = resolveModel(modelDir, "joiner");

        encoderSession = env.createSession(encoderPath.toString(), new OrtSession.SessionOptions());
        decoderSession = env.createSession(decoderPath.toString(), new OrtSession.SessionOptions());
        joinerSession = env.createSession(joinerPath.toString(), new OrtSession.SessionOptions());

        loadVocab(modelDir);
        buildInitialStates();
    }

    private Path resolveModel(Path dir, String prefix) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith(prefix)
                            && p.getFileName().toString().endsWith(".onnx"))
                    .findFirst()
                    .orElseThrow(() -> new IOException("未找到 " + prefix + " 模型于 " + dir));
        }
    }

    private void loadVocab(Path modelDir) throws IOException {
        Path tokensFile = modelDir.resolve("tokens.txt");
        if (!Files.exists(tokensFile)) {
            try (InputStream is = getClass().getResourceAsStream("/audio/asr/zipformer-zh-en/tokens.txt")) {
                if (is == null) {
                    throw new IOException("未找到 tokens.txt 于 " + modelDir);
                }
                readTokens(is);
            }
            return;
        }
        try (InputStream is = Files.newInputStream(tokensFile)) {
            readTokens(is);
        }
    }

    private void readTokens(InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length >= 2) {
                    try {
                        vocab.put(Integer.parseInt(parts[1]), parts[0]);
                    } catch (NumberFormatException ignored) {
                        // 忽略非数字行
                    }
                }
            }
        }
    }

    private void buildInitialStates() {
        inputNames = new ArrayList<>();
        for (var info : encoderSession.getInputInfo().entrySet()) {
            inputNames.add(info.getKey());
        }
        Collections.sort(inputNames);

        initialStates = new LinkedHashMap<>();
        for (String name : inputNames) {
            if ("x".equals(name)) {
                continue;
            }
            var nodeInfo = encoderSession.getInputInfo().get(name).getInfo();
            long[] dims;
            if (nodeInfo instanceof ai.onnxruntime.TensorInfo tensorInfo) {
                long[] shape = tensorInfo.getShape();
                dims = new long[shape.length];
                for (int i = 0; i < shape.length; i++) {
                    dims[i] = shape[i] > 0 ? shape[i] : 1L;
                }
            } else {
                continue;
            }
            if (name.startsWith("cached_len_")) {
                initialStates.put(name, OnnxTensor.createTensor(env,
                        new long[(int) dims[0]][(int) dims[1]]));
            } else {
                initialStates.put(name, createZeroTensor(name, dims));
            }
        }
    }

    private OnnxTensor createZeroTensor(String name, long[] dims) {
        switch (dims.length) {
            case 2:
                return OnnxTensor.createTensor(env, new float[(int) dims[0]][(int) dims[1]]);
            case 3:
                return OnnxTensor.createTensor(env,
                        new float[(int) dims[0]][(int) dims[1]][(int) dims[2]]);
            case 4:
                float[][][][] t4 = new float[(int) dims[0]][(int) dims[1]]
                        [(int) dims[2]][(int) dims[3]];
                return OnnxTensor.createTensor(env, t4);
            default:
                throw new IllegalStateException("不支持的维度: " + name);
        }
    }

    /**
     * 转写音频文件。
     *
     * @param wavPath WAV 文件路径
     * @return 识别文本
     */
    public String transcribe(Path wavPath) throws Exception {
        float[] samples = AudioUtils.loadMono16k(wavPath);
        float[][] features = new ZipformerFbank().extract(samples);
        float[][] encoderOut = runEncoder(features);
        return greedyDecode(encoderOut);
    }

    private float[][] runEncoder(float[][] features) {
        int totalFrames = features.length;
        int numChunks = Math.max(1, (totalFrames + DECODE_CHUNK_LEN - 1) / DECODE_CHUNK_LEN);

        List<float[]> outputs = new ArrayList<>();
        Map<String, OnnxTensor> states = new LinkedHashMap<>(initialStates);
        List<OnnxTensor> toClose = new ArrayList<>();

        try {
            for (int ci = 0; ci < numChunks; ci++) {
                int start = ci * DECODE_CHUNK_LEN;
                int end = Math.min(totalFrames, start + CHUNK_SIZE);
                int validLen = end - start;

                float[][] chunk = new float[CHUNK_SIZE][NUM_MELS];
                for (int i = 0; i < validLen; i++) {
                    chunk[i] = features[start + i];
                }

                Map<String, OnnxTensor> feed = new HashMap<>();
                feed.put("x", OnnxTensor.createTensor(env, new float[][][]{chunk}));
                feed.putAll(states);

                try (OrtSession.Result result = encoderSession.run(feed)) {
                    float[][][] rawOut = (float[][][]) result.get(0).getValue();
                    float[][] encOut = rawOut[0];
                    for (float[] frame : encOut) {
                        outputs.add(frame);
                    }

                    states.clear();
                    for (int k = 1; k < inputNames.size(); k++) {
                        String inName = inputNames.get(k);
                        String outName = "new_" + inName;
                        var optionalValue = result.get(outName);
                        if (optionalValue.isPresent()) {
                            OnnxTensor src = (OnnxTensor) optionalValue.get();
                            OnnxTensor tensor = copyToTensor(src.getValue());
                            if (tensor != null) {
                                states.put(inName, tensor);
                                toClose.add(tensor);
                            }
                        }
                    }
                }
            }
        } finally {
            for (OnnxTensor t : toClose) {
                t.close();
            }
            closeStates(initialStates);
        }

        return outputs.toArray(new float[0][]);
    }

    private OnnxTensor copyToTensor(Object value) {
        if (value instanceof long[][] longs) {
            return OnnxTensor.createTensor(env, longs);
        }
        if (value instanceof long[] longs) {
            return OnnxTensor.createTensor(env, longs);
        }
        if (value instanceof float[][] floats) {
            return OnnxTensor.createTensor(env, floats);
        }
        if (value instanceof float[][][] floats3) {
            return OnnxTensor.createTensor(env, floats3);
        }
        if (value instanceof float[][][][] floats4) {
            return OnnxTensor.createTensor(env, floats4);
        }
        return null;
    }

    private void closeStates(Map<String, OnnxTensor> states) {
        for (OnnxTensor t : states.values()) {
            if (t != null) {
                t.close();
            }
        }
        states.clear();
    }

    private String greedyDecode(float[][] encoderOut) {
        List<Integer> emitted = new ArrayList<>();
        long[] context = {-1L, BLANK_ID};

        float[] decoderOut = runDecoder(context);
        try {
            for (float[] frame : encoderOut) {
                float[] logit = runJoiner(frame, decoderOut);
                int nextToken = argmax(logit);
                if (nextToken == BLANK_ID) {
                    continue;
                }
                emitted.add(nextToken);
                context[0] = context[1];
                context[1] = nextToken;
                decoderOut = runDecoder(context);
            }
        } finally {
            // decoderOut 由 runDecoder 返回, 无需额外释放
        }

        StringBuilder sb = new StringBuilder();
        for (int token : emitted) {
            if (token > CONTEXT_SIZE) {
                sb.append(vocab.getOrDefault(token, ""));
            }
        }
        return sb.toString();
    }

    private float[] runDecoder(long[] y) {
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, new long[][]{y});
             OrtSession.Result result = decoderSession.run(Collections.singletonMap("y", tensor))) {
            return ((float[][]) result.get(0).getValue())[0];
        } catch (Exception e) {
            throw new RuntimeException("解码器推理失败", e);
        }
    }

    private float[] runJoiner(float[] encFrame, float[] decOut) {
        try (OnnxTensor encTensor = OnnxTensor.createTensor(env,
                     new float[][]{encFrame});
             OnnxTensor decTensor = OnnxTensor.createTensor(env,
                     new float[][]{decOut});
             OrtSession.Result result = joinerSession.run(Map.of(
                     "encoder_out", encTensor,
                     "decoder_out", decTensor))) {
            return ((float[][]) result.get(0).getValue())[0];
        } catch (Exception e) {
            throw new RuntimeException("joiner 推理失败", e);
        }
    }

    private int argmax(float[] arr) {
        int maxIdx = 0;
        float maxVal = arr[0];
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > maxVal) {
                maxVal = arr[i];
                maxIdx = i;
            }
        }
        return maxIdx;
    }

    @Override
    public void close() {
        if (encoderSession != null) {
            encoderSession.close();
        }
        if (decoderSession != null) {
            decoderSession.close();
        }
        if (joinerSession != null) {
            joinerSession.close();
        }
    }
}
