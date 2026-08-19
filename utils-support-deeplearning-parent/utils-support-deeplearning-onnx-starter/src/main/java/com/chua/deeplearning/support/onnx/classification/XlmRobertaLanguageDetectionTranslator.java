package com.chua.deeplearning.support.onnx.classification;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.modality.Classifications;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.NDArrayUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * XLM-RoBERTa                      Translator
 * <p>
 * XLM-RoBERTa                        20                      
 *          178                               
 * </p>
 * <p>
 *                        
 * 1.        HuggingFace Tokenizer                      
 * 2.     token IDs                            
 * 3.                                     128       
 * </p>
 * <p>
 *                            
 * -          input_ids (shape: [batch_size, sequence_length], max_length=128)
 * -          attention_mask (shape: [batch_size, sequence_length])
 * -                         logits -> softmax -> Classifications
 * </p>
 *
 * @author CH
 * @since 2026-05-10
 */
@Slf4j
public class XlmRobertaLanguageDetectionTranslator implements Translator<String, Classifications> {

    /**
     *                       128
     */
    private static final int MAX_LENGTH = 128;

    /**
     * XLM-RoBERTa                           20                      
     */
    private static final List<String> LANGUAGES = List.of(
            "japanese", "dutch", "arabic", "polish", "german", "italian",
            "portuguese", "turkish", "spanish", "hindi", "greek", "urdu",
            "bulgarian", "english", "french", "chinese", "russian", "thai",
            "swahili", "vietnamese"
    );

    /**
     * HuggingFace          
     */
    private HuggingFaceTokenizer tokenizer;

    @Override
    /** Prepare */
    public void prepare(@Nonnull TranslatorContext ctx) throws IOException {
        Path modelPath = ctx.getModel().getModelPath();
        if (modelPath == null) {
            throw new IOException("                                  tokenizer");
        }
        Path tokenizerPath = findTokenizerPath(modelPath);
        if (tokenizerPath == null || !Files.exists(tokenizerPath)) {
            throw new IOException("          tokenizer.json               : " + modelPath);
        }
        tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerPath)
                .optPadding(true)
                .optMaxLength(MAX_LENGTH)
                .build();
        log.debug("[XLM-RoBERTa-Lang] Tokenizer             : {}", tokenizerPath);
    }

    @Override
    @Nonnull
    /** 处理Input */
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull String input) {
        if (tokenizer == null) {
            throw new IllegalStateException("HuggingFaceTokenizer             ");
        }
        Encoding encoding = tokenizer.encode(input);
        long[] ids = encoding.getIds();
        long[] mask = encoding.getAttentionMask();

        NDList ndList = new NDList();
        NDArray inputIds = ctx.getNDManager().create(ids).toType(DataType.INT64, false).expandDims(0);
        inputIds.setName("input_ids");
        ndList.add(inputIds);

        NDArray attentionMask = ctx.getNDManager().create(mask).toType(DataType.INT64, false).expandDims(0);
        attentionMask.setName("attention_mask");
        ndList.add(attentionMask);

        return ndList;
    }

    @Override
    @Nonnull
    /** 处理Output */
    public Classifications processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray logits = list.singletonOrThrow();
        if (logits.getShape().dimension() == 2 && logits.getShape().get(0) == 1) {
            logits = logits.squeeze(0);
        }
        float[] logitArr = NDArrayUtils.safeToFloatArray(logits);
        double[] probs = softmax(logitArr);
        List<Double> probabilities = Arrays.stream(probs).boxed().collect(Collectors.toList());
        return new Classifications(LANGUAGES, probabilities);
    }

    @Override
    @Nullable
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }

    /**
     *                        tokenizer.json
     *
     * @param modelPath             
     * @return                        path，       null
     */
    private static Path findTokenizerPath(Path modelPath) {
        Path root = Files.isDirectory(modelPath) ? modelPath : modelPath.getParent();
        if (root == null) {
            return null;
        }
        Path p = root.resolve("tokenizer.json");
        if (Files.exists(p)) {
            return p;
        }
        if (root.getParent() != null) {
            p = root.getParent().resolve("tokenizer.json");
            if (Files.exists(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * softmax                  
     *
     * @param logits                      
     * @return softmax              
     */
    private static double[] softmax(float[] logits) {
        double max = Double.NEGATIVE_INFINITY;
        for (float l : logits) {
            max = Math.max(max, l);
        }
        double sum = 0d;
        double[] exp = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            exp[i] = Math.exp(logits[i] - max);
            sum += exp[i];
        }
        if (sum <= 0d) {
            return new double[logits.length];
        }
        for (int i = 0; i < exp.length; i++) {
            exp[i] /= sum;
        }
        return exp;
    }
}
