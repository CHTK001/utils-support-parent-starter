package com.chua.deeplearning.support.onnx.text.minimind;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import lombok.extern.slf4j.Slf4j;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * MiniMind 直接 ONNX Runtime 端到端测试（不经过 DJL Predictor）
 * <p>
 * 用法：
 * <pre>
 *   java DirectMiniMindTest "你好" 30
 * </pre>
 * 第一个参数是 prompt，第二个是 max_new_tokens。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DirectMiniMindTest {

    private static final int MAX_INPUT_LENGTH = 256;
    private static final long EOS_TOKEN_ID = 2L;
    private static final long BOS_TOKEN_ID = 1L;

    public static void main(String[] args) throws Exception {
        String prompt = args.length > 0 ? args[0] : "你好";
        int maxNewTokens = args.length > 1 ? Integer.parseInt(args[1]) : 30;

        Path modelDir;
        String envDir = System.getenv("MINIMIND_MODEL_DIR");
        if (envDir != null && Files.isDirectory(Path.of(envDir))) {
            modelDir = Path.of(envDir);
        } else {
            // Use classpath via NativeLoader (jar 资源)
            Path temp = Path.of(System.getProperty("java.io.tmpdir"), "chua-dl-models", "models", "minimind");
            NativeLoader.of("minimind-resources")
                    .from(DirectMiniMindTest.class.getClassLoader())
                    .basePath("models/minimind/")
                    .toTarget(temp)
                    .glob("*")
                    .withMd5(true)
                    .extractOnly(true)
                    .load();
            if (Files.exists(temp.resolve("model.onnx"))) {
                modelDir = temp;
            } else if (Files.isDirectory(Path.of("D:/ch/project/minimind-3"))) {
                modelDir = Path.of("D:/ch/project/minimind-3");
            } else {
                modelDir = temp;
            }
        }
        log.info("model dir: {}", modelDir);

        Path tokenizerPath = modelDir.resolve("tokenizer.json");
        Path onnxPath = modelDir.resolve("model.onnx");
        if (!Files.exists(tokenizerPath) || !Files.exists(onnxPath)) {
            log.error("Missing files in {}: tokenizer={} onnx={}", modelDir, Files.exists(tokenizerPath), Files.exists(onnxPath));
            System.exit(1);
        }

        log.info("[1/3] Loading tokenizer");
        MiniMindTokenizer tokenizer = MiniMindTokenizer.load(tokenizerPath);
        log.info("  vocab_size: {}", tokenizer.vocabSize());

        log.info("[2/3] Creating ORT session");
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        OrtSession session = env.createSession(onnxPath.toString(), opts);
        String outputName = session.getOutputNames().iterator().next();
        boolean hasMask = session.getInputNames().contains("attention_mask");
        log.info("  inputs: {}, output: {}", session.getInputNames(), outputName);

        log.info("[3/3] Generating response (prompt='{}', max_new_tokens={})", prompt, maxNewTokens);
        int[] promptIds = tokenizer.encode(prompt);
        log.info("  prompt tokens: {}", promptIds.length);

        if (promptIds.length == 0 || promptIds[0] != BOS_TOKEN_ID) {
            int[] withBos = new int[promptIds.length + 1];
            withBos[0] = (int) BOS_TOKEN_ID;
            System.arraycopy(promptIds, 0, withBos, 1, promptIds.length);
            promptIds = withBos;
        }

        long[] tokenBuffer = new long[MAX_INPUT_LENGTH];
        for (int i = 0; i < promptIds.length; i++) tokenBuffer[i] = promptIds[i];
        int curLen = promptIds.length;
        StringBuilder out = new StringBuilder();
        int vocabSize = -1;
        float[] logits = new float[6400];

        long start = System.currentTimeMillis();
        for (int step = 0; step < maxNewTokens; step++) {
            long[][] ids2d = new long[1][curLen];
            System.arraycopy(tokenBuffer, 0, ids2d[0], 0, curLen);
            OnnxTensor ids = OnnxTensor.createTensor(env, ids2d);
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", ids);
            if (hasMask) {
                long[][] mask2d = new long[1][curLen];
                java.util.Arrays.fill(mask2d[0], 1L);
                inputs.put("attention_mask", OnnxTensor.createTensor(env, mask2d));
            }
            try (OrtSession.Result r = session.run(inputs)) {
                OnnxTensor logitsT = (OnnxTensor) r.get(outputName).get();
                if (vocabSize < 0) {
                    long[] shape = logitsT.getInfo().getShape();
                    vocabSize = (int) shape[shape.length - 1];
                    if (logits.length != vocabSize) logits = new float[vocabSize];
                    log.info("  vocab_size (from logits): {}", vocabSize);
                }
                FloatBuffer fb = logitsT.getFloatBuffer();
                int offset = (curLen - 1) * vocabSize;
                fb.position(offset);
                fb.get(logits);
                int next = 0; float max = logits[0];
                for (int i = 1; i < logits.length; i++) {
                    if (logits[i] > max) { max = logits[i]; next = i; }
                }
                if (next == EOS_TOKEN_ID) {
                    log.info("  step {}: EOS", step);
                    break;
                }
                String tokenText = tokenizer.decode(new int[]{next});
                out.append(tokenText);
                tokenBuffer[curLen++] = next;
                log.info("  step {}: token_id={} text={}", step, next, tokenText);
            } finally {
                for (OnnxTensor t : inputs.values()) t.close();
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        log.info("==> Done in {}ms", elapsed);
        log.info("==> Input:  {}", prompt);
        log.info("==> Output: {}", out.toString().trim());
    }
}
