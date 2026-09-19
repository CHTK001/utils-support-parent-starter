package com.chua.deeplearning.support.onnx.clip;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * CN-CLIP                 Translator   
 *
 * <p>CN-CLIP        Chinese-CLIP ViT-B/16 text encoder ONNX                       
 * 输入 输入_标识 [1,52] + attention_mask [1,52]  unnorm_文本_特征 [1,512]           </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CnClipTextFeatureTranslator implements Translator<String, float[]> {

    /** 文本最大长度 */
    /** 文本_最大_长度 */
    private static final int TEXT_MAX_LENGTH = 52;

    /** 分词器 */
    /** Tokenizer */
    private HuggingFaceTokenizer tokenizer;

    /** 创建 cnclip文本特征translator 实例 */
    public CnClipTextFeatureTranslator() {
    }

    @Override
    /** Prepare */
    public void prepare(@Nonnull TranslatorContext ctx) throws Exception {
        Path modelRoot = resolveModelRoot(ctx.getModel().getModelPath());
        Path tokPath = resolveFirstExisting(modelRoot,
                "tokenizer.json", "vocab.txt", "tokenizers/vocab.txt");
        tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokPath)
                .build();
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, String input) {
        Encoding encoding = tokenizer.encode(input);
        long[] inputIds = truncate(encoding.getIds(), TEXT_MAX_LENGTH);
        return new NDList(ctx.getNDManager().create(inputIds).expandDims(0));
    }

    @Override
    /** 处理输出 */
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray textEmbeds = list.singletonOrThrow();
        return textEmbeds.squeeze().toFloatArray();
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }

    /**
     * 解析模型根
     *
     * @param modelPath 模型路径
     * @return resolve模型根的结果
     */
    private static Path resolveModelRoot(Path modelPath) {
        if (modelPath == null) {
            return Paths.get("models/onnx");
        }
        Path parent = modelPath.getParent();
        return parent != null ? parent : Paths.get("models/onnx");
    }

    /**
     * 解析第一个existing
     *
     * @param modelRoot 模型根
     * @param names 名称
     * @return resolve第一个existing的结果
     */
    private static Path resolveFirstExisting(Path modelRoot, String... names) throws IOException {
        for (String name : names) {
            Path p = modelRoot.resolve(name);
            if (Files.exists(p)) {
                return p;
            }
        }
        throw new IOException("找不到必需文件，尝试: " + Arrays.toString(names) + "，根目录: " + modelRoot);
    }

    /**
     * Truncate
     *
     * @param ids 标识
     * @param maxLen 最大len
     * @return truncate的结果
     */
    private static long[] truncate(long[] ids, int maxLen) {
        long[] out = new long[maxLen];
        System.arraycopy(ids, 0, out, 0, Math.min(ids.length, maxLen));
        return out;
    }
}
