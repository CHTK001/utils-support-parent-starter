package com.chua.deeplearning.support.onnx.text.minimind;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
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
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * MiniMind 因果语言模型 Translator
 * <p>
 * MiniMind 是一个基于 Qwen3 架构的小型中文语言模型（~64M 参数），
 * 支持 RMSNorm + RoPE + SwiGLU + GQA（8 KV heads / 16 attention heads）。
 * 模型通过 PyTorch 2.13 导出为 ONNX（opset 14, fp32, dynamic_axes），
 * 使用 MiniMindOnnxWrapper 包装器禁用 KV cache 后导出。
 * </p>
 * <p>
 * 本 Translator 实现 String -> String 的文本生成：
 * 1. 使用 HuggingFaceTokenizer 对输入文本分词
 * 2. 通过 ONNX Runtime 直接执行贪婪解码（Greedy Decoding）
 * 3. 逐 token 生成直到遇到 EOS（&lt;|im_end|&gt;, id=2）或达到最大长度
 * 4. 将生成的 token 解码为文本返回
 * </p>
 * <p>
 * 模型输入：input_ids [batch, seq], attention_mask [batch, seq]
 * 模型输出：logits [batch, seq, 6400]
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
     * EOS token ID（MiniMind tokenizer 的结束符号）
     */
    private static final long EOS_TOKEN_ID = 2L;

    /**
     * BOS token ID
     */
    private static final long BOS_TOKEN_ID = 1L;

    /**
     * HuggingFace 分词器
     */
    private HuggingFaceTokenizer tokenizer;

    /**
     * ONNX Runtime 环境
     */
    private OrtEnvironment ortEnv;

    /**
     * ONNX Runtime 会话（直接使用，支持多步生成循环）
     */
    private OrtSession ortSession;

    /**
     * 当前输入文本（用于 processOutput 间接获取）
     */
    private String currentInput;

    /**
     * 缓存 processInput 中的分词结果，避免 generateGreedy 中重复分词
     */
    private Encoding cachedEncoding;

    /**
     * 缓存模型输出名，避免每步迭代获取
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
            // 使用 NativeLoader 从 classpath 解压 models/minimind/ 目录下所有附加文件到 modelRoot
            // NativeLoader 自带 MD5 校验和缓存，同一 taskId 只执行一次，已存在的文件会跳过拷贝
            NativeLoader.of("minimind-resources")
                    .from(MiniMindTranslator.class.getClassLoader())
                    .basePath("models/minimind/")
                    .toTarget(modelRoot)
                    .glob("*")
                    .withMd5(true)
                    .extractOnly(true)
                    .load();
            // 解压后重新查找
            tokenizerPath = findFile(modelRoot, "tokenizer.json");
        }
        if (tokenizerPath == null || !Files.exists(tokenizerPath)) {
            throw new IOException("MiniMind tokenizer.json not found in: " + modelRoot
                    + " (also not available on classpath)");
        }
        tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerPath)
                .optPadding(false)
                .optMaxLength(MAX_INPUT_LENGTH)
                .build();
        log.debug("[MiniMind] Tokenizer loaded: {}", tokenizerPath);

        // 创建 ORT 会话（直接使用 ONNX Runtime，支持多步生成循环）
        Path onnxPath = findOnnxFile(modelRoot);
        if (onnxPath == null || !Files.exists(onnxPath)) {
            throw new IOException("MiniMind model.onnx not found in: " + modelRoot);
        }
        try {
            ortEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

            // 显式配置 CPU 线程数，充分利用多核
            int cpuCores = Runtime.getRuntime().availableProcessors();
            int threadCount = Math.min(8, cpuCores);
            opts.setIntraOpNumThreads(threadCount);
            opts.setMemoryPatternOptimization(true);
            opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);

            ortSession = ortEnv.createSession(onnxPath.toString(), opts);

            // 缓存输出名和 attention_mask 标志，避免每步迭代获取
            outputName = ortSession.getOutputNames().iterator().next();
            hasAttentionMask = ortSession.getInputNames().contains("attention_mask");

            log.info("[MiniMind] ORT session created: threads={}, cores={}, output={}", 
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
        // 分词并缓存，generateGreedy 中直接复用，避免重复分词
        cachedEncoding = tokenizer.encode(input);
        long[] inputIds = cachedEncoding.getIds();
        long[] attentionMask = cachedEncoding.getAttentionMask();

        // DJL 的 Predictor 会用此 NDList 校验模型输入签名（input_ids + attention_mask），
        // 实际推理在 processOutput -> generateGreedy 中用 ORT 直接执行，这里只提供结构正确的占位张量
        long[][] ids2d = new long[1][inputIds.length];
        System.arraycopy(inputIds, 0, ids2d[0], 0, inputIds.length);

        long[][] mask2d = new long[1][attentionMask.length];
        System.arraycopy(attentionMask, 0, mask2d[0], 0, attentionMask.length);

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
     * <p>
     * 从输入文本开始，逐 token 预测下一个 token，
     * 直到遇到 EOS 或达到最大生成长度。
     * 直接使用 ORT session 进行多步推理。
     * </p>
     * <p>
     * 性能优化：
     * - 使用 FloatBuffer 直接读取最后一个位置的 logits，避免物化整个 [1, seq, vocab] 三维数组
     * - 预分配 MAX_INPUT_LENGTH 长度的 token buffer，避免每步 arraycopy 扩容
     * - 复用 processInput 中缓存的分词结果，避免重复分词
     * - 缓存 outputName 和 hasAttentionMask，避免每步迭代获取
     * </p>
     *
     * @param prompt 输入文本
     * @return 生成的文本（不包含输入 prompt）
     */
    private String generateGreedy(String prompt) {
        long startTime = System.currentTimeMillis();
        int generatedCount = 0;
        try {
            // 使用缓存的分词结果，避免重复分词
            Encoding encoding = cachedEncoding != null ? cachedEncoding : tokenizer.encode(prompt);
            cachedEncoding = null;
            long[] inputIds = encoding.getIds();

            // 在开头添加 BOS（如果不存在）
            if (inputIds.length == 0 || inputIds[0] != BOS_TOKEN_ID) {
                long[] withBos = new long[inputIds.length + 1];
                withBos[0] = BOS_TOKEN_ID;
                System.arraycopy(inputIds, 0, withBos, 1, inputIds.length);
                inputIds = withBos;
            }

            log.debug("[MiniMind] Input tokens: {} (length={})",
                    Arrays.toString(inputIds), inputIds.length);

            // 预分配 token buffer，避免每步扩容拷贝
            long[] tokenBuffer = new long[MAX_INPUT_LENGTH];
            System.arraycopy(inputIds, 0, tokenBuffer, 0, inputIds.length);
            int currentLength = inputIds.length;

            // 预分配 logits buffer（vocabSize 首步推理后确定）
            float[] lastTokenLogits = new float[vocabSize > 0 ? vocabSize : 6400];

            StringBuilder generatedTokens = new StringBuilder();

            for (int step = 0; step < MAX_NEW_TOKENS; step++) {
                // 从预分配 buffer 构造 2D 输入 [1, currentLength]
                long[][] input2d = new long[1][currentLength];
                System.arraycopy(tokenBuffer, 0, input2d[0], 0, currentLength);
                OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnv, input2d);

                Map<String, OnnxTensor> inputs = new HashMap<>(4);
                inputs.put("input_ids", inputTensor);

                if (hasAttentionMask) {
                    long[][] mask2d = new long[1][currentLength];
                    Arrays.fill(mask2d[0], 1L);
                    OnnxTensor maskTensor = OnnxTensor.createTensor(ortEnv, mask2d);
                    inputs.put("attention_mask", maskTensor);
                }

                // 执行推理
                try (OrtSession.Result result = ortSession.run(inputs)) {
                    OnnxTensor logitsTensor = (OnnxTensor) result.get(outputName)
                            .orElseThrow(() -> new RuntimeException("Output not found: " + outputName));

                    // 首步推理时从 tensor shape 自动获取 vocabSize
                    if (vocabSize < 0) {
                        long[] shape = logitsTensor.getInfo().getShape();
                        vocabSize = (int) shape[shape.length - 1];
                        if (lastTokenLogits.length != vocabSize) {
                            lastTokenLogits = new float[vocabSize];
                        }
                        log.debug("[MiniMind] Vocab size: {}", vocabSize);
                    }

                    // 使用 FloatBuffer 直接读取最后一个位置的 logits
                    // 避免 getValue() 物化整个 [1, seq, vocab] 三维数组（每步可节省数 MB 内存分配）
                    FloatBuffer fb = logitsTensor.getFloatBuffer();
                    int offset = (currentLength - 1) * vocabSize;
                    fb.position(offset);
                    fb.get(lastTokenLogits);

                    // 贪婪选择：argmax
                    int nextTokenId = argmax(lastTokenLogits);
                    log.debug("[MiniMind] Step {}: next_token_id={}", step, nextTokenId);

                    // 检查 EOS
                    if (nextTokenId == EOS_TOKEN_ID) {
                        log.debug("[MiniMind] EOS reached at step {}", step);
                        break;
                    }

                    // 解码当前 token
                    long[] singleToken = new long[]{nextTokenId};
                    String tokenText = tokenizer.decode(singleToken);
                    generatedTokens.append(tokenText);
                    generatedCount++;

                    // 直接写入预分配 buffer，无需扩容拷贝
                    tokenBuffer[currentLength] = nextTokenId;
                    currentLength++;

                    // 检查总长度
                    if (currentLength >= MAX_INPUT_LENGTH) {
                        log.debug("[MiniMind] Max length reached at step {}", step);
                        break;
                    }
                } finally {
                    // 清理 ONNX tensor
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
     * 找到 logits 中最大值的索引
     *
     * @param logits logits 数组
     * @return 最大值的索引
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
     *
     * @param modelPath 模型路径（文件或目录）
     * @return 模型根目录
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
     *
     * @param root 根目录
     * @param name 文件名
     * @return 文件路径，找不到返回 null
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
     *
     * @param modelRoot 模型根目录
     * @return ONNX 文件路径
     */
    private static Path findOnnxFile(Path modelRoot) {
        // 优先查找 model.onnx
        Path onnxPath = modelRoot.resolve("model.onnx");
        if (Files.exists(onnxPath)) {
            return onnxPath;
        }
        // 查找任意 .onnx 文件
        try {
            Path[] found = new Path[1];
            Files.list(modelRoot)
                    .filter(p -> p.toString().endsWith(".onnx"))
                    .findFirst()
                    .ifPresent(p -> found[0] = p);
            return found[0];
        } catch (IOException e) {
            return null;
        }
    }
}
