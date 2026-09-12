package com.chua.deeplearning.support.onnx.audio.zipformer;

import com.chua.deeplearning.support.onnx.audio.AudioUtils;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
* 英文离线（非流式）Zipformer ASR 翻译器。
*
* <p>与流式版本不同：整句一次性送入编码器，无分块、无状态传递，
* 因此不存在分块边界的重复问题。解码仍为 transducer 贪心。
*
* @author chua
* @since 4.0.0.42
 */
public class ZipformerEnOfflineTranslator implements AutoCloseable {

    private static final int CONTEXT_SIZE = 2; // 上下文大小
    private static final int BLANK_ID = 0; // BLANK_标识
    private static final int NUM_MELS = 80; // NUM_MELS

    private final OrtEnvironment env = OrtEnvironment.getEnvironment(); // env
    private OrtSession encoderSession; // 编码器会话
    private OrtSession decoderSession; // 解码器会话
    private OrtSession joinerSession; // 连接会话
    private final Map<Integer, String> vocab = new HashMap<>(); // vocab

    /**
    * 从模型目录加载。
    *
    * @param modelDir 含 编码器/解码器/连接 int8 onnx 与 令牌.txt
    * @throws Exception 加载异常
    * @param tokensFile 令牌文件
     /**
      * prepare。
      * @param modelDir 模型dir
      */
     * @param dir dir
     * @param prefix 前缀
     * @return 方法的结果
      * @param tokensFile 令牌文件
     /**
     * prepare。
     * @param modelDir 模型dir
      */
     */
    public void prepare(Path modelDir) throws Exception {
        encoderSession = env.createSession(
                resolve(modelDir, "encoder"), new OrtSession.SessionOptions());
        decoderSession = env.createSession(
                resolve(modelDir, "decoder"), new OrtSession.SessionOptions());
        joinerSession = env.createSession(
                resolve(modelDir, "joiner"), new OrtSession.SessionOptions());
        loadVocab(modelDir.resolve("tokens.txt"));
    }

    private String resolve(Path dir, String prefix) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith(prefix) && n.endsWith(".onnx");
                    })
                    .findFirst()
                    .orElseThrow(() -> new IOException("未找到 " + prefix + " 模型"))
                    .toString();
        }
    }

    private void loadVocab(Path tokensFile) throws IOException {
        try (InputStream is = Files.newInputStream(tokensFile);
             BufferedReader reader =
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

    /**
    * 转写音频文件（英文）。
    *
    * @param wavPath WAV 路径
    * @return 识别文本
    * @throws Exception 推理异常
    * @param encoderOut 编码器出
     /**
      * transcribe。
      * @param wavPath wav路径
      * @return transcribe的结果
      */
     * @param features 特征
      * @param encoderOut 编码器出
     /**
     * transcribe。
     * @param wavPath wav路径
     * @return transcribe的结果
      */
     */
    public String transcribe(Path wavPath) throws Exception {
        float[] samples = AudioUtils.loadMono16k(wavPath);
        float[][] features = new ZipformerFbank().extract(samples);
        float[][] encoderOut = runEncoder(features);
        return greedyDecode(encoderOut);
    }

    private float[][] runEncoder(float[][] features) throws OrtException {
        int frames = features.length;
        try (OnnxTensor x = OnnxTensor.createTensor(env,
                     new float[][][]{features});
             OnnxTensor xLens = OnnxTensor.createTensor(env,
                     new long[]{frames});
             OrtSession.Result result = encoderSession.run(Map.of("x", x, "x_lens", xLens))) {
            float[][][] raw = (float[][][]) result.get(0).getValue();
            return raw[0];
        }
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
            String piece = vocab.get(nextToken);
            if (piece != null && !piece.startsWith("<")) {
                sb.append(decodePiece(piece));
            }
        }
        return sb.toString().trim();
    }

     /**
     * decodepiece。
     * @param piece piece
     * @return decodePiece的结果
      */
     * BPE 词片转可读文本：▁ 还原为空格。
     *
     * @param session 会话
     * @param y y
     * @return decodePiece的结果
     */
    private String decodePiece(String piece) {
        return piece.replace('\u2581', ' ');
    }

    private float[] runDecoder(long[] y) throws OrtException {
        try (OnnxTensor tensor = OnnxTensor.createTensor(env,
                new long[][]{y});
             OrtSession.Result result =
                     decoderSession.run(Collections.singletonMap("y", tensor))) {
            /**
            * 运行连接。
            * @param encFrame enc帧
            * @param decOut dec出
            * @return 运行连接的结果
             */
            return ((float[][]) result.get(0).getValue())[0];
        }
    }

    private float[] runJoiner(float[] encFrame, float[] decOut) throws OrtException {
        try (OnnxTensor encTensor = OnnxTensor.createTensor(env,
                new float[][]{encFrame});
             OnnxTensor decTensor = OnnxTensor.createTensor(env,
                     new float[][]{decOut});
             OrtSession.Result result = joinerSession.run(Map.of(
                     "encoder_out", encTensor,
                     "decoder_out", decTensor))) {
            /**
            * argmax。
            * @param arr arr
            * @return argmax的结果
            * @param session 会话
             */
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
        closeQuietly(encoderSession);
        closeQuietly(decoderSession);
        closeQuietly(joinerSession);
    }

    private void closeQuietly(OrtSession session) {
        if (session != null) {
            try {
                session.close();
            } catch (OrtException ignored) {
                // 忽略关闭异常
            }
        }
    }
}
