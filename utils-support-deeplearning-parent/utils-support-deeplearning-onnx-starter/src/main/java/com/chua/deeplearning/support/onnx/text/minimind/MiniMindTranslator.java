package com.chua.deeplearning.support.onnx.text.minimind;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.training.ParameterStore;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.common.support.utils.NativeLoader;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * minimind 因果语言模型 Translator。
 * <p>
 * minimind 是一个基于 通义千问3 架构的小型中文语言模型（~64M 参数），
 * 使用 rmsnorm + rope + swiglu + GQA（8 attention heads / 4 KV heads）。
 * 模型通过 pytorch 2.13 + minimind/模型/模型_minimind.py 导出为 ONNX
 * （opset 14, fp32, dynamic 批量 + sequence, 单文件 inline 权重）。
 * </p>
 * <p>
 * 本 Translator 实现 字符串 → 字符串 的文本生成：
 * <ol>
 *   <li>使用纯 Java BPE tokenizer（{@link MiniMindTokenizer}）分词，绕过 DJL
 *       自带的 Rust tokenizers（与 minimind 新版 tokenizer.json 兼容性差）</li>
 *   <li>通过 DJL {@code ctx.getModel().getBlock().forward(...)} 走标准 ONNX 推理，
 * 每次一步（自回归生成），直到遇到 EOS（{@code <|im_end|>}, 标识=2）或达到最大长度</li>
 *   <li>将生成的 token 解码为文本返回</li>
 * </ol>
 * </p>
 * <p>
 * 模型输入：{@code input_ids} [批量, seq] int64 + （可选）{@code attention_mask}<br>
 * 模型输出：{@code logits} [批量, seq, 6400] float32
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
     * 最大生成 令牌 数
     */
    private static final int MAX_NEW_TOKENS = 64;

    /**
     * EOS 令牌 标识（{@code <|im_end|>}，minimind 的对话结束符）
     */
    private static final long EOS_TOKEN_ID = 2L;

    /**
     * BOS 令牌 标识（{@code <|im_start|>}）
     */
    private static final long BOS_TOKEN_ID = 1L;

    /**
     * 纯 Java BPE tokenizer
     */
    private MiniMindTokenizer tokenizer;

    /**
     * 当前输入文本
     */
    private String currentInput;

    /**
     * 缓存分词结果
     */
    private int[] cachedIds;

    /**
     * DJL 参数存储（用于 block.远期）
     */
    private final ParameterStore parameterStore = new ParameterStore();

    /**
     * 模型是否需要 attention_mask 输入
     */
    private boolean hasAttentionMask;

    @Override
    /**
     * Prepare
    */
    public void prepare(TranslatorContext ctx) throws IOException {
        Path modelPath = ctx.getModel().getModelPath();
        Path modelRoot = resolveModelRoot(modelPath);

        // 加载 tokenizer
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

        // 探测模型输入签名
        Path onnxPath = findOnnxFile(modelRoot);
        if (onnxPath == null || !Files.exists(onnxPath)) {
            throw new IOException("MiniMind model.onnx not found in: " + modelRoot);
        }
 // DJL 严格匹配 ONNX 输入数量：minimind 导出时只有 输入_标识（无 attention_mask）
        hasAttentionMask = false;
        log.info("[MiniMind] ORT session ready (via DJL block): model={}", onnxPath);
    }

    @Override
    /**
     * 处理输入
    */
    public NDList processInput(TranslatorContext ctx, String input) {
        currentInput = input;
        if (tokenizer == null) {
            throw new IllegalStateException("MiniMind tokenizer not initialized");
        }
        cachedIds = tokenizer.encode(input);

        // 必要时加 BOS
        if (cachedIds.length == 0 || cachedIds[0] != BOS_TOKEN_ID) {
            int[] withBos = new int[cachedIds.length + 1];
            withBos[0] = (int) BOS_TOKEN_ID;
            System.arraycopy(cachedIds, 0, withBos, 1, cachedIds.length);
            cachedIds = withBos;
        }

        long[] ids = new long[cachedIds.length];
        for (int i = 0; i < cachedIds.length; i++) {
            ids[i] = cachedIds[i];
        }

        long[][] ids2d = new long[1][ids.length];
        System.arraycopy(ids, 0, ids2d[0], 0, ids.length);

        // DJL NDManager.create(long[][]) 会按 long 创建，shape=[1, len]
        NDArray idsArray = ctx.getNDManager().create(ids2d);
        idsArray.setName("input_ids");

 // ONNX 实际只需要 输入_标识，但 DJL 要求所有 输入 都在 nd列表
        if (hasAttentionMask) {
            long[][] mask2d = new long[1][ids.length];
            for (int i = 0; i < ids.length; i++) {
                mask2d[0][i] = 1L;
            }
            NDArray maskArray = ctx.getNDManager().create(mask2d);
            maskArray.setName("attention_mask");
            return new NDList(idsArray, maskArray);
        }
        return new NDList(idsArray);
    }

    @Override
    /**
     * 处理输出
    */
    public String processOutput(TranslatorContext ctx, NDList list) {
        if (tokenizer == null) {
            throw new IllegalStateException("MiniMind translator not initialized");
        }
        return generateGreedy(ctx);
    }

    @Override
    /**
     * 获取Batchifier
    */
    public Batchifier getBatchifier() {
        return null;
    }

    /**
     * 贪婪解码生成文本。
     * <p>
     * 使用 DJL block.远期 多次自回归调用。
     * </p>
     * @param ctx ctx
     * @return generateGreedy的结果
     */
    private String generateGreedy(TranslatorContext ctx) {
        long startTime = System.currentTimeMillis();
        int generatedCount = 0;
        StringBuilder out = new StringBuilder();
        try {
            List<Long> tokenList = new ArrayList<>();
            for (int id : cachedIds) {
                tokenList.add((long) id);
            }

            int vocabSize = -1;
            float[] lastLogits = new float[6400];

            for (int step = 0; step < MAX_NEW_TOKENS; step++) {
                long[] ids = new long[tokenList.size()];
                for (int i = 0; i < tokenList.size(); i++) {
                    ids[i] = tokenList.get(i);
                }
                long[][] ids2d = new long[1][ids.length];
                System.arraycopy(ids, 0, ids2d[0], 0, ids.length);

                NDArray idsArray = ctx.getNDManager().create(ids2d);
                idsArray.setName("input_ids");

                NDList inputs;
                if (hasAttentionMask) {
                    long[][] mask2d = new long[1][ids.length];
                    for (int i = 0; i < ids.length; i++) {
                        mask2d[0][i] = 1L;
                    }
                    NDArray maskArray = ctx.getNDManager().create(mask2d);
                    maskArray.setName("attention_mask");
                    inputs = new NDList(idsArray, maskArray);
                } else {
                    inputs = new NDList(idsArray);
                }

 // DJL block 远期
                NDList output = ctx.getModel().getBlock().forward(parameterStore, inputs, false);

                NDArray logits = output.singletonOrThrow();

                if (vocabSize < 0) {
                    long[] shape = logits.getShape().getShape();
                    vocabSize = (int) shape[shape.length - 1];
                    if (lastLogits.length != vocabSize) {
                        lastLogits = new float[vocabSize];
                    }
                    log.debug("[MiniMind] Vocab size: {}", vocabSize);
                }

                // 取最后一个位置的 logits
                int seqLen = ids.length;
                int offset = (seqLen - 1) * vocabSize;
                float[] allLogits = logits.toFloatArray();
                System.arraycopy(allLogits, offset, lastLogits, 0, vocabSize);

                int nextTokenId = argmax(lastLogits);
                log.debug("[MiniMind] Step {}: next_token_id={}", step, nextTokenId);

                if (nextTokenId == EOS_TOKEN_ID) {
                    log.debug("[MiniMind] EOS reached at step {}", step);
                    break;
                }

                String tokenText = tokenizer.decode(new int[]{nextTokenId});
                out.append(tokenText);
                generatedCount++;
                tokenList.add((long) nextTokenId);

                if (tokenList.size() >= MAX_INPUT_LENGTH) {
                    log.debug("[MiniMind] Max length reached at step {}", step);
                    break;
                }
            }

            String resultText = out.toString().trim();
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
     * @param logits logits
     * @return argmax的结果
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
     * @param modelPath 模型路径
     * @return resolve模型根的结果
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
     * @param root 根
     * @param name 名称
     * @return find文件的结果
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
     * @param modelRoot 模型根
     * @return findonnx文件的结果
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
