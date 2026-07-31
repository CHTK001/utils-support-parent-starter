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
 *  input input_ids [1,52] + attention_mask [1,52]  unnorm_text_features [1,512]           </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CnClipTextFeatureTranslator implements Translator<String, float[]> {

    private static final int TEXT_MAX_LENGTH = 52;

    private HuggingFaceTokenizer tokenizer;

    public CnClipTextFeatureTranslator() {
    }

    @Override
    public void prepare(@Nonnull TranslatorContext ctx) throws Exception {
        Path modelRoot = resolveModelRoot(ctx.getModel().getModelPath());
        Path tokPath = resolveFirstExisting(modelRoot,
                "tokenizer.json", "vocab.txt", "tokenizers/vocab.txt");
        tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokPath)
                .build();
    }

    @Override
    public NDList processInput(TranslatorContext ctx, String input) {
        Encoding encoding = tokenizer.encode(input);
        long[] inputIds = truncate(encoding.getIds(), TEXT_MAX_LENGTH);
        long[] attention = new long[inputIds.length];
        Arrays.fill(attention, 1L);
        return new NDList(
                ctx.getNDManager().create(inputIds).expandDims(0),
                ctx.getNDManager().create(attention).expandDims(0)
        );
    }

    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray textEmbeds = list.singletonOrThrow();
        return textEmbeds.squeeze().toFloatArray();
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }

    private static Path resolveModelRoot(Path modelPath) {
        if (modelPath == null) {
            return Paths.get("models/onnx");
        }
        Path parent = modelPath.getParent();
        return parent != null ? parent : Paths.get("models/onnx");
    }

    private static Path resolveFirstExisting(Path modelRoot, String... names) throws IOException {
        for (String name : names) {
            Path p = modelRoot.resolve(name);
            if (Files.exists(p)) {
                return p;
            }
        }
        throw new IOException("找不到必需文件，尝试: " + Arrays.toString(names) + "，根目录: " + modelRoot);
    }

    private static long[] truncate(long[] ids, int maxLen) {
        if (ids.length <= maxLen) {
            return ids;
        }
        long[] out = new long[maxLen];
        System.arraycopy(ids, 0, out, 0, maxLen);
        return out;
    }
}
