package com.chua.deeplearning.support.onnx.text.minimind;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * MiniMind 因果语言模型 Translator。
 * <p>
 * MiniMind 是一个基于 Qwen3 架构的小型中文语言模型（~64M 参数），
 * 使用 RMSNorm + RoPE + SwiGLU + GQA（8 attention heads / 4 KV heads）。
 * 模型通过 PyTorch 2.13 + minimind/model/model_minimind.py 导出为 ONNX
 * （opset 14, fp32, dynamic batch + sequence, 单文件 inline weights）。
 * </p>
 * <p>
 * 本 Translator 实现 String → String 的文本生成：
 * <ol>
 *   <li>使用纯 Java BPE tokenizer（{@link MiniMindTokenizer}）分词，绕过 DJL
 *       自带的 Rust tokenizers（与 minimind 新版 tokenizer.json 兼容性差）</li>
 *   <li>通过 ONNX Runtime 直接执行贪婪解码（Greedy Decoding）</li>
 *   <li>逐 token 生成直到遇到 EOS（{@code <|im_end|>}, id=2）或达到最大长度</li>
 *   <li>将生成的 token 解码为文本返回</li>
 * </ol>
 * </p>
 * <p>
 * 模型输入：{@code input_ids} [batch, seq] int64<br>
 * 模型输出：{@code logits} [batch, seq, 6400] float32
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class MiniMindTranslator implements Translator<String, String> {

    /**
     * 最大输入长度
     */
    private static final int MAX_INPUT_LENGTH = 256;

    /**
     * 最大生成 token 数
     */
    private static final int MAX_NEW_TOKENS = 64;

    /**
     * EOS token ID（{@code <|im_end|>}，MiniMind 的对话结束符）
     */
    private static final long EOS_TOKEN_ID = 2L;

    /**
     * BOS token ID（{@code <|im_start|>}）
     */
    private static final long BOS_TOKEN_ID = 1L;

    /**
     * 纯 Java BPE tokenizer
     */
    private MiniMindTokenizer tokenizer;

    /**
     * ONNX Runtime 环境
     */
    private OrtEnvironment ortEnv;

    /**
     * ONNX Runtime 会话
     */
    private OrtSession ortSession;

    /**
     * 当前输入文本（processOutput 间接获取）
     */
    private String currentInput;

    /**
     * 缓存分词结果，避免 generateGreedy 中重复分词
     */
    private int[] cachedIds;

    /**
     * 缓存模型输出名
     */
    private String outputName;

    /**
     * 模型是否需要 attention_mask 输入
     */
    private boolean hasAttentionMask;

    /**
     * 词表大小（首步推理后从 tensor shape 自动获取）
     */
    private int vocabSize = -1;

    @Override
    public void prepare(TranslatorContext ctx) throws IOException {
        Path modelPath = ctx.getModel().getModelPath();
        Path modelRoot = resolveModelRoot(modelPath);

        // 加载 tokenizer —— 先从模型目录查找，找不到则用 NativeLoader 从 classpath 解压
        Path tokenizerPath = findFile(modelRoot, "tokenizer.json");
        if (tokenizerPath == null || !Files.exists(tokenizerPath)) {
            log.debug("[MiniMind] tokenizer.json not found in {}, extracting from classpath via NativeLoader...", modelRoot);
            NativeLoader.of("minimind-resources")
                    .from(MiniMindTranslator.class.getClassLoader())
                    .basePath("models/minimind/")
                    .toTarget(modelRoot)
                    .glob("*")
                    .withMd5(true)
                    .extractOnly(true)
                    .load();
            tokenizerPath = findFile(modelRoot, "tokenizer.json");
        }
        if (tokenizerPath == null || !Files.exists(tokenizerPath)) {
            throw new IOException("MiniMind tokenizer.json not found in: " + modelRoot
                    + " (also not available on classpath)");
        }
        tokenizer = MiniMindTokenizer.load(tokenizerPath);
        log.info("[MiniMind] Tokenizer loaded: {} (vocab_size={})", tokenizerPath, tokenizer.vocabSize());

        // 创建 ORT 会话
        Path onnxPath = findOnnxFile(modelRoot);
        if (onnxPath == null || !Files.exists(onnxPath)) {
            throw new IOException("MiniMind model.onnx not found in: " + modelRoot);
        }
        try {
            ortEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

            int cpuCores = Runtime.getRuntime().availableProcessors();
            int threadCount = Math.min(8, cpuCores);
            opts.setIntraOpNumThreads(threadCount);
            opts.setMemoryPatternOptimization(true);
            opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);

            ortSession = ortEnv.createSession(onnxPath.toString(), opts);

            outputName = ortSession.getOutputNames().iterator().next();
            hasAttentionMask = ortSession.getInputNames().contains("attention_mask");

            log.info("[MiniMind] ORT session created: threads={}, cores={}, model={}", 
                    threadCount, cpuCores, onnxPath);
            log.debug("[MiniMind] Inputs: {}, Outputs: {}",
                    ortSession.getInputNames(), ortSession.getOutputNames());
        } catch (Exception e) {
            throw new IOException("Failed to create ORT session: " + e.getMessage(), e);
        }
    }

    @Override
    public NDList processInput(TranslatorContext ctx, String input) {
        currentInput = input;
        if (tokenizer == null) {
            throw new IllegalStateException("MiniMind tokenizer not initialized");
        }
        cachedIds = tokenizer.encode(input);
        long[] ids = new long[cachedIds.length];
        for (int i = 0; i < cachedIds.length; i++) {
            ids[i] = cachedIds[i];
        }
        long[][] ids2d = new long[1][ids.length];
        System.arraycopy(ids, 0, ids2d[0], 0, ids.length);

        long[][] mask2d = new long[1][ids.length];
        for (int i = 0; i < ids.length; i++) {
            mask2d[0][i] = 1L;
        }

        NDArray idsArray = ctx.getNDManager().create(ids2d);
        idsArray.setName("input_ids");

        NDArray maskArray = ctx.getNDManager().create(mask2d);
        maskArray.setName("attention_mask");

        return new NDList(idsArray, maskArray);
    }

    @Override
    public String processOutput(TranslatorContext ctx, NDList list) {
        if (ortSession == null || tokenizer == null) {
            throw new IllegalStateException("MiniMind translator not initialized");
        }
        return generateGreedy(currentInput);
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }

    /**
     * 贪婪解码生成文本
     */
    private String generateGreedy(String prompt) {
        long startTime = System.currentTimeMillis();
        int generatedCount = 0;
        try {
            int[] inputIds = cachedIds != null ? cachedIds : tokenizer.encode(prompt);
            cachedIds = null;

            // 必要时添加 BOS
            if (inputIds.length == 0 || inputIds[0] != BOS_TOKEN_ID) {
                int[] withBos = new int[inputIds.length + 1];
                withBos[0] = (int) BOS_TOKEN_ID;
                System.arraycopy(inputIds, 0, withBos, 1, inputIds.length);
                inputIds = withBos;
            }

            log.debug("[MiniMind] Input tokens: {} (length={})", inputIds.length, inputIds.length);

            // 预分配 token buffer
            long[] tokenBuffer = new long[MAX_INPUT_LENGTH];
            for (int i = 0; i < inputIds.length; i++) {
                tokenBuffer[i] = inputIds[i];
            }
            int currentLength = inputIds.length;

            float[] lastTokenLogits = new float[vocabSize > 0 ? vocabSize : 6400];
            StringBuilder generatedTokens = new StringBuilder();

            for (int step = 0; step < MAX_NEW_TOKENS; step++) {
                long[][] input2d = new long[1][currentLength];
                System.arraycopy(tokenBuffer, 0, input2d[0], 0, currentLength);
                OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnv, input2d);

                Map<String, OnnxTensor> inputs = new HashMap<>(4);
                inputs.put("input_ids", inputTensor);

                if (hasAttentionMask) {
                    long[][] mask2d = new long[1][currentLength];
                    for (int i = 0; i < currentLength; i++) {
                        mask2d[0][i] = 1L;
                    }
                    OnnxTensor maskTensor = OnnxTensor.createTensor(ortEnv, mask2d);
                    inputs.put("attention_mask", maskTensor);
                }

                try (OrtSession.Result result = ortSession.run(inputs)) {
                    OnnxTensor logitsTensor = (OnnxTensor) result.get(outputName)
                            .orElseThrow(() -> new RuntimeException("Output not found: " + outputName));

                    if (vocabSize < 0) {
                        long[] shape = logitsTensor.getInfo().getShape();
                        vocabSize = (int) shape[shape.length - 1];
                        if (lastTokenLogits.length != vocabSize) {
                            lastTokenLogits = new float[vocabSize];
                        }
                        log.debug("[MiniMind] Vocab size: {}", vocabSize);
                    }

                    FloatBuffer fb = logitsTensor.getFloatBuffer();
                    int offset = (currentLength - 1) * vocabSize;
                    fb.position(offset);
                    fb.get(lastTokenLogits);

                    int nextTokenId = argmax(lastTokenLogits);
                    log.debug("[MiniMind] Step {}: next_token_id={}", step, nextTokenId);

                    if (nextTokenId == EOS_TOKEN_ID) {
                        log.debug("[MiniMind] EOS reached at step {}", step);
                        break;
                    }

                    int[] singleToken = new int[]{nextTokenId};
                    String tokenText = tokenizer.decode(singleToken);
                    generatedTokens.append(tokenText);
                    generatedCount++;

                    tokenBuffer[currentLength] = nextTokenId;
                    currentLength++;

                    if (currentLength >= MAX_INPUT_LENGTH) {
                        log.debug("[MiniMind] Max length reached at step {}", step);
                        break;
                    }
                } finally {
                    for (OnnxTensor t : inputs.values()) {
                        t.close();
                    }
                }
            }

            String resultText = generatedTokens.toString().trim();
            long elapsed = System.currentTimeMillis() - startTime;
            double avgMs = generatedCount > 0 ? (double) elapsed / generatedCount : 0;
            log.info("[MiniMind] Generated {} tokens in {}ms (avg {}ms/token)",
                    generatedCount, elapsed, String.format("%.1f", avgMs));
            log.debug("[MiniMind] Generated text: {}", resultText);
            return resultText;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[MiniMind] Generation failed after {}ms: {}", elapsed, e.getMessage(), e);
            return "";
        }
    }

    /**
     * argmax
     */
    private static int argmax(float[] logits) {
        int maxIdx = 0;
        float maxVal = logits[0];
        for (int i = 1; i < logits.length; i++) {
            if (logits[i] > maxVal) {
                maxVal = logits[i];
                maxIdx = i;
            }
        }
        return maxIdx;
    }

    /**
     * 解析模型根目录
     */
    private static Path resolveModelRoot(Path modelPath) {
        if (modelPath == null) {
            return Path.of(".");
        }
        if (Files.isRegularFile(modelPath)) {
            return modelPath.getParent();
        }
        return modelPath;
    }

    /**
     * 在模型目录中查找指定文件
     */
    private static Path findFile(Path root, String name) {
        if (root == null) {
            return null;
        }
        Path p = root.resolve(name);
        if (Files.exists(p)) {
            return p;
        }
        if (root.getParent() != null) {
            p = root.getParent().resolve(name);
            if (Files.exists(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 查找 ONNX 模型文件
     */
    private static Path findOnnxFile(Path modelRoot) {
        Path onnxPath = modelRoot.resolve("model.onnx");
        if (Files.exists(onnxPath)) {
            return onnxPath;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(modelRoot)) {
            return stream
                    .filter(p -> p.toString().endsWith(".onnx"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
