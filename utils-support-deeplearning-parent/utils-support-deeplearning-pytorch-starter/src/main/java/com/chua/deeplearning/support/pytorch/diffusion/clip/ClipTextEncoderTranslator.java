package com.chua.deeplearning.support.pytorch.diffusion.clip;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.NoBatchifyTranslator;
import ai.djl.translate.TranslatorContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * CLIP 文本编码器 Translator（Stable Diffusion 条件）。
 * <p>优先本地 tokenizer 目录，否则回退 HuggingFace 模型名。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ClipTextEncoderTranslator implements NoBatchifyTranslator<String, NDList> {

    /**
      * CLIP 最大 令牌 长度。
     */
    private static final int MAX_LENGTH = 77;

    /**
     * 本地 tokenizer 目录（可选）。
     */
    private final String tokenizerPath;

    /**
      * huggingface tokenizer。
     */
    private HuggingFaceTokenizer tokenizer;

    /** 创建 clip文本编码器translator 实例 */
    public ClipTextEncoderTranslator() {
        this(null);
    }

    /**
      * 创建 clip文本编码器translator 实例
     * @param tokenizerPath tokenizer路径
     */
    public ClipTextEncoderTranslator(String tokenizerPath) {
        this.tokenizerPath = tokenizerPath;
    }

    @Override
    /** Prepare */
    public void prepare(TranslatorContext ctx) throws IOException {
        HuggingFaceTokenizer.Builder builder = HuggingFaceTokenizer.builder()
                .optPadding(true)
                .optPadToMaxLength()
                .optMaxLength(MAX_LENGTH)
                .optTruncation(true);
        Path local = resolveTokenizerPath();
        if (local != null && Files.isDirectory(local)) {
            builder.optTokenizerPath(local);
            tokenizer = builder.build();
        } else {
            tokenizer = builder.optTokenizerName("openai/clip-vit-large-patch14").build();
        }
    }

    /**
     * 解析tokenizer路径
     *
     * @return resolvetokenizer路径的结果
     */
    private Path resolveTokenizerPath() {
        if (tokenizerPath != null && !tokenizerPath.isBlank()) {
            Path p = Paths.get(tokenizerPath);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        Path candidate = Paths.get("models", "pytorch", "diffusion", "clip-vit-large-patch14");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        return null;
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, String input) {
        Encoding encoding = tokenizer.encode(input);
        long[] ids = encoding.getIds();
        int[] tokenValues = new int[MAX_LENGTH];
        int copy = Math.min(ids.length, MAX_LENGTH);
        for (int i = 0; i < copy; i++) {
            tokenValues[i] = (int) ids[i];
        }
        NDArray ndArray = ctx.getNDManager().create(new int[][]{tokenValues});
        return new NDList(ndArray);
    }

    @Override
    /** 处理输出 */
    public NDList processOutput(TranslatorContext ctx, NDList list) {
        list.detach();
        return list;
    }
}
