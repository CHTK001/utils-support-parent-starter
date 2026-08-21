package com.chua.deeplearning.support.onnx.text.qwen;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.training.ParameterStore;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.onnx.text.minimind.MiniMindTokenizer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Qwen2.5-Instruct ONNX 因果语言模型 Translator（DJL ONNX 引擎 + 纯 Java BPE）。
 *
 * <p>处理流程：
 * <ol>
 *   <li>用 Qwen2 tokenizer（{@link MiniMindTokenizer} 兼容 byte-level BPE）分词</li>
 *   <li>按 Qwen chat 模板包装输入：
 *       {@code <|im_start|>user\n{prompt}<|im_end|>\n<|im_start|>assistant\n}</li>
 *   <li>通过 DJL block.forward 自回归生成，直到 EOS（{@code <|im_end|>}）或达到最大长度</li>
 * </ol>
 *
 * <p>资源：model.onnx（fp32）+ tokenizer.json，均由 ModelRegistry downloadUrl 拉取。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OnnxQwenTranslator implements Translator<String, String> {

    /** 最大输入长度 */
    private static final int MAX_INPUT_LENGTH = 512;
    /** 最大生成 token 数 */
    private static final int MAX_NEW_TOKENS = 128;

    private MiniMindTokenizer tokenizer;
    private String currentInput;
    private int[] cachedIds;
    private final ParameterStore parameterStore = new ParameterStore();

    @Override
    /** Prepare */
    public void prepare(TranslatorContext ctx) throws IOException {
        Path modelPath = ctx.getModel().getModelPath();
        Path modelRoot = resolveModelRoot(modelPath);

        Path tokenizerPath = findFile(modelRoot, "tokenizer.json");
        if (tokenizerPath == null || !Files.exists(tokenizerPath)) {
            NativeLoader.of("qwen2-onnx-resources")
                    .from(OnnxQwenTranslator.class.getClassLoader())
                    .basePath("nlp/llm/qwen2.5-1.5b/")
                    .toTarget(modelRoot)
                    .glob("tokenizer.json")
                    .withMd5(true)
                    .extractOnly(true)
                    .load();
            tokenizerPath = findFile(modelRoot, "tokenizer.json");
        }
        if (tokenizerPath == null || !Files.exists(tokenizerPath)) {
            throw new IOException("Qwen2 tokenizer.json not found in: " + modelRoot);
        }
        tokenizer = MiniMindTokenizer.load(tokenizerPath);
        log.info("[QwenOnnx] Tokenizer loaded: {} (vocab_size={})", tokenizerPath, tokenizer.vocabSize());
    }

    @Override
    /** 处理Input */
    public NDList processInput(TranslatorContext ctx, String input) {
        currentInput = input;
        if (tokenizer == null) {
            throw new IllegalStateException("Qwen2 translator not initialized");
        }
        // 使用 Qwen chat 模板
        String chat = "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n"
                + "<|im_start|>user\n" + (input == null ? "" : input) + "<|im_end|>\n"
                + "<|im_start|>assistant\n";
        cachedIds = tokenizer.encode(chat);
        if (cachedIds.length > MAX_INPUT_LENGTH) {
            int[] trimmed = new int[MAX_INPUT_LENGTH];
            System.arraycopy(cachedIds, 0, trimmed, 0, MAX_INPUT_LENGTH);
            cachedIds = trimmed;
        }
        long[] ids = new long[cachedIds.length];
        for (int i = 0; i < cachedIds.length; i++) {
            ids[i] = cachedIds[i];
        }
        long[][] ids2d = new long[1][ids.length];
        System.arraycopy(ids, 0, ids2d[0], 0, ids.length);

        NDArray idsArray = ctx.getNDManager().create(ids2d);
        idsArray.setName("input_ids");
        return new NDList(idsArray);
    }

    @Override
    /** 处理Output */
    public String processOutput(TranslatorContext ctx, NDList list) {
        if (tokenizer == null) {
            throw new IllegalStateException("Qwen2 translator not initialized");
        }
        return generateGreedy(ctx);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }

    /** 贪婪解码自回归生成 */
    private String generateGreedy(TranslatorContext ctx) {
        StringBuilder out = new StringBuilder();
        try {
            List<Long> tokenList = new ArrayList<>();
            for (int id : cachedIds) {
                tokenList.add((long) id);
            }

            int vocabSize = -1;
            float[] lastLogits = null;

            for (int step = 0; step < MAX_NEW_TOKENS; step++) {
                long[] ids = new long[tokenList.size()];
                for (int i = 0; i < tokenList.size(); i++) {
                    ids[i] = tokenList.get(i);
                }
                long[][] ids2d = new long[1][ids.length];
                System.arraycopy(ids, 0, ids2d[0], 0, ids.length);
                NDArray idsArray = ctx.getNDManager().create(ids2d);
                idsArray.setName("input_ids");

                NDList output = ctx.getModel().getBlock().forward(parameterStore, new NDList(idsArray), false);
                NDArray logits = output.singletonOrThrow();

                if (vocabSize < 0) {
                    long[] shape = logits.getShape().getShape();
                    vocabSize = (int) shape[shape.length - 1];
                    lastLogits = new float[vocabSize];
                }

                int seqLen = ids.length;
                int offset = (seqLen - 1) * vocabSize;
                float[] allLogits = logits.toFloatArray();
                System.arraycopy(allLogits, offset, lastLogits, 0, vocabSize);

                int nextTokenId = argmax(lastLogits);
                if (isEos(nextTokenId)) {
                    break;
                }
                String tokenText = tokenizer.decode(new int[]{nextTokenId});
                if (tokenText.contains("<|im_end|>")) {
                    break;
                }
                out.append(tokenText);
                tokenList.add((long) nextTokenId);
                if (tokenList.size() >= MAX_INPUT_LENGTH) {
                    break;
                }
            }
            return out.toString().trim();
        } catch (Exception e) {
            log.warn("[QwenOnnx] 生成失败: {}", e.getMessage());
            return out.toString();
        }
    }

    /** EOS 判定：Qwen 的 `<|im_end|>` 是 added token，id 由 tokenizer 决定 */
    private boolean isEos(int tokenId) {
        Integer imEnd = tokenizer.addedTokenId("<|im_end|>");
        if (imEnd != null && imEnd >= 0 && tokenId == imEnd) {
            return true;
        }
        Integer endOfText = tokenizer.addedTokenId("<|endoftext|>");
        if (endOfText != null && endOfText >= 0 && tokenId == endOfText) {
            return true;
        }
        return false;
    }

    /** 取概率最大的 token */
    private static int argmax(float[] logits) {
        int best = 0;
        float max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < logits.length; i++) {
            if (logits[i] > max) {
                max = logits[i];
                best = i;
            }
        }
        return best;
    }

    /** 解析模型根目录（DJL ModelPath 可能是文件或目录） */
    private static Path resolveModelRoot(Path modelPath) {
        if (modelPath == null) {
            return null;
        }
        if (Files.isDirectory(modelPath)) {
            return modelPath;
        }
        return modelPath.getParent();
    }

    /** 在目录中查找目标文件 */
    private static Path findFile(Path dir, String name) {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        try {
            return Files.walk(dir)
                    .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().equals(name))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}