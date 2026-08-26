package com.chua.deeplearning.support.onnx.audio.zipformer;

import com.chua.deeplearning.support.onnx.audio.AudioUtils;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

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

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private OrtSession encoderSession;
    private OrtSession decoderSession;
    private OrtSession joinerSession;
    private Map<String, OnnxTensor> initialStates;
    private List<String> inputNames;
    private final Map<Integer, String> vocab = new HashMap<>();

    /**
     * 从模型目录加载。
     *
     * @param modelDir 模型目录
     * @throws Exception 加载异常
     */
    public void prepare(Path modelDir) throws Exception {
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
            throw new IOException("未找到 tokens.txt 于 " + modelDir);
        }
        try (InputStream is = Files.newInputStream(tokensFile)) {
            readTokens(is);
        }
    }

    private void readTokens(InputStream is) throws IOException {
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
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

    private void buildInitialStates() throws OrtException {
        inputNames = new ArrayList<>(encoderSession.getInputInfo().keySet());
        Collections.sort(inputNames);

        initialStates = new LinkedHashMap<>();
        for (String name : inputNames) {
            if ("x".equals(name)) {
                continue;
            }
            var info = encoderSession.getInputInfo().get(name).getInfo();
            if (!(info instanceof TensorInfo tensorInfo)) {
                continue;
            }
            long[] shape = tensorInfo.getShape();
            long[] dims = new long[shape.length];
            for (int i = 0; i < shape.length; i++) {
                dims[i] = shape[i] > 0 ? shape[i] : 1L;
            }
            OnnxTensor tensor;
            if (name.startsWith("cached_len_")) {
                tensor = createZeroLongTensor(dims);
            } else {
                tensor = createZeroFloatTensor(name, dims);
            }
            initialStates.put(name, tensor);
        }
    }

    private OnnxTensor createZeroLongTensor(long[] dims) throws OrtException {
        Object arr;
        if (dims.length == 2) {
            arr = new long[(int) dims[0]][(int) dims[1]];
        } else if (dims.length == 1) {
            arr = new long[(int) dims[0]];
        } else {
            throw new IllegalStateException("不支持的状态维度: " + dims.length);
        }
        return OnnxTensor.createTensor(env, arr);
    }

    private OnnxTensor createZeroFloatTensor(String name, long[] dims) throws OrtException {
        switch (dims.length) {
            case 2:
                return OnnxTensor.createTensor(env, new float[(int) dims[0]][(int) dims[1]]);
            case 3:
                return OnnxTensor.createTensor(env,
                        new float[(int) dims[0]][(int) dims[1]][(int) dims[2]]);
            case 4:
                return OnnxTensor.createTensor(env,
                        new float[(int) dims[0]][(int) dims[1]]
                                [(int) dims[2]][(int) dims[3]]);
            default:
                throw new IllegalStateException("不支持的状态维度: " + name);
        }
    }

    /**
     * 转写音频文件。
     *
     * @param wavPath WAV 文件路径
     * @return 识别文本
     * @throws Exception 推理异常
     */
    public String transcribe(Path wavPath) throws Exception {
        float[] samples = AudioUtils.loadMono16k(wavPath);
        float[][] features = new ZipformerFbank().extract(samples);
        float[][] encoderOut = runEncoder(features);
        return greedyDecode(encoderOut);
    }

    private float[][] runEncoder(float[][] features) throws OrtException {
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
                feed.put("x", OnnxTensor.createTensor(env,
                        new float[][][]{chunk}));
                feed.putAll(states);

                try (OrtSession.Result result = encoderSession.run(feed)) {
                    float[][][] rawEnc = (float[][][]) result.get("encoder_out").get().getValue();
                    float[][] encOut = rawEnc[0];
                    Collections.addAll(outputs, encOut);

                    states.clear();
                    for (String inName : inputNames) {
                        if ("x".equals(inName)) {
                            continue;
                        }
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

    private void closeStates(Map<String, OnnxTensor> states) {
        for (OnnxTensor t : states.values()) {
            if (t != null) {
                t.close();
            }
        }
        states.clear();
    }

    private OnnxTensor copyToTensor(Object value) throws OrtException {
        if (value instanceof long[] flatLongs) {
            return OnnxTensor.createTensor(env, flatLongs);
        }
        if (value instanceof long[][] matLongs) {
            return OnnxTensor.createTensor(env, matLongs);
        }
        if (value instanceof float[] flatFloats) {
            return OnnxTensor.createTensor(env, flatFloats);
        }
        if (value instanceof float[][] matFloats) {
            return OnnxTensor.createTensor(env, matFloats);
        }
        if (value instanceof float[][][] cubeFloats) {
            return OnnxTensor.createTensor(env, cubeFloats);
        }
        if (value instanceof float[][][][] quadFloats) {
            return OnnxTensor.createTensor(env, quadFloats);
        }
        return null;
    }

    private String greedyDecode(float[][] encoderOut) throws OrtException {
        StringBuilder sb = new StringBuilder();
        long[] context = {-1L, BLANK_ID};

        float[] decoderOut = runDecoder(context);
        for (float[] frame : encoderOut) {
            float[] logit = runJoiner(frame, decoderOut);
            int nextToken = argmax(logit);
            if (nextToken == BLANK_ID) {
                continue;
            }
            context[0] = context[1];
            context[1] = nextToken;
            decoderOut = runDecoder(context);
            if (nextToken > CONTEXT_SIZE) {
                sb.append(vocab.getOrDefault(nextToken, ""));
            }
        }
        return sb.toString();
    }

    private float[] runDecoder(long[] y) throws OrtException {
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, new long[][]{y});
             OrtSession.Result result =
                     decoderSession.run(Collections.singletonMap("y", tensor))) {
            return ((float[][]) result.get(0).getValue())[0];
        }
    }

    private float[] runJoiner(float[] encFrame, float[] decOut) throws OrtException {
        try (OnnxTensor encTensor = OnnxTensor.createTensor(env, new float[][]{encFrame});
             OnnxTensor decTensor = OnnxTensor.createTensor(env, new float[][]{decOut});
             OrtSession.Result result = joinerSession.run(Map.of(
                     "encoder_out", encTensor,
                     "decoder_out", decTensor))) {
            return ((float[][]) result.get(0).getValue())[0];
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
            try {
                encoderSession.close();
            } catch (OrtException ignored) {
                // 忽略关闭异常
            }
        }
        if (decoderSession != null) {
            try {
                decoderSession.close();
            } catch (OrtException ignored) {
                // 忽略关闭异常
            }
        }
        if (joinerSession != null) {
            try {
                joinerSession.close();
            } catch (OrtException ignored) {
                // 忽略关闭异常
            }
        }
    }
}
