package com.chua.deeplearning.support.onnx.vision.clip;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.NDArrayUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
   * vit-H-14 (Chinese-CLIP) 中文文本特征提取 Translator。
 *
 * <p>基于 Chinese-CLIP ViT-H-14 的文本编码器（RoBERTa-wwm-ext-large-chinese）：
 * 输入中文文本，输出 1024 维文本特征向量，可与图像特征向量计算余弦相似度。</p>
 *
 * <p>流程：文本 → HuggingFace Tokenizer 分词 → input_ids → DJL ONNX 推理 → 1024 维特征向量。</p>
 *
 * <p>模型输入：input_ids [batch, 52] int64</p>
 * <p>模型输出：unnorm_text_features [batch, 1024] float32</p>
 *
 * <p>与 {@link VitH14OnnxTranslator} 配合使用，实现中文图文检索：</p>
 * <pre>
 *   float[] imageFeat = vitH14Image.translate(image);
 *   float[] textFeat = vitH14Text.translate("一只猫");
 *   float similarity = VitH14OnnxTranslator.cosineSimilarity(imageFeat, textFeat);
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class VitH14TextTranslator implements Translator<String, float[]> {

    /** 文本最大长度（Chinese-CLIP roberta） */
    private static final int TEXT_MAX_LENGTH = 52;

    /** 分词器 */
    private HuggingFaceTokenizer tokenizer;

    /**
     * 构造文本特征提取器
     */
    public VitH14TextTranslator() {
    }

    /**
     * 准备模型：加载 Tokenizer。
     *
     * @param ctx 翻译上下文
     * @throws Exception 准备异常
     */
    @Override
    public void prepare(@Nonnull TranslatorContext ctx) throws Exception {
        Path modelRoot = resolveModelRoot(ctx.getModel().getModelPath());
        Path tokPath = resolveFirstExisting(modelRoot,
                "tokenizer.json", "vocab.txt", "tokenizers/vocab.txt");
        tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokPath)
                .build();
        log.info("[ViT-H-14 Text] Tokenizer 加载完成: {}", tokPath);
    }

    /**
      * 处理输入：文本 → 令牌 ids [1, 52]。
     *
     * <p>使用 HuggingFace Tokenizer 分词，截断到 TEXT_MAX_LENGTH。</p>
     *
     * @param ctx   翻译上下文
     * @param input 中文文本
     * @return NDList 包含 输入_标识 [1, 52]
     */
    @Override
    @Nonnull
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull String input) {
        if (tokenizer == null) {
            throw new IllegalStateException("Tokenizer 未初始化，请先调用 prepare()");
        }
        Encoding encoding = tokenizer.encode(input);
        long[] inputIds = truncate(encoding.getIds(), TEXT_MAX_LENGTH);
        return new NDList(ctx.getNDManager().create(inputIds).expandDims(0));
    }

    /**
      * 处理输出：unnorm_文本_特征 → float[]。
     *
     * @param ctx  翻译上下文
     * @param list nd列表 包含 文本_特征 [1, 1024]
     * @return 1024 维特征向量
     */
    @Override
    @Nonnull
    public float[] processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray textEmbeds = list.singletonOrThrow();
        // 去除 batch 维度 [1, 1024] -> [1024]
        if (textEmbeds.getShape().dimension() > 1 && textEmbeds.getShape().get(0) == 1) {
            textEmbeds = textEmbeds.squeeze(0);
        }
        return NDArrayUtils.safeToFloatArray(textEmbeds);
    }

    @Override
    @Nullable
    /**
     * 获取batchifier。
     * @return 获取batchifier的结果
     */
    public Batchifier getBatchifier() {
        return null;
    }

    /**
     * 计算两个特征向量的余弦相似度（便捷静态方法）。
     *
     * @param a 特征向量 A
     * @param b 特征向量 B
     * @return 余弦相似度 [-1, 1]
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("向量维度不匹配: " + a.length + " vs " + b.length);
        }
        float dot = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (float) (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 解析 模型根
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
     * 解析 第一个existing
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
     * 截断 令牌 ids 到指定长度
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
