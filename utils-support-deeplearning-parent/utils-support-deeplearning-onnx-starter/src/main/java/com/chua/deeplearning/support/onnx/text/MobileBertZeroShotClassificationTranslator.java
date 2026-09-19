package com.chua.deeplearning.support.onnx.text;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * mobilebert MNLI                       Translator
 * <p>
 * 映射("premise" ->             , "假设" ->             )
 *   CONTRADICTION / ENTAILMENT / NEUTRAL                
 * </p>
 * <p>
 *                                                premise                                 
 * "This example 是否 about {标签}."        假设       ENTAILMENT score
 * </p>
 *
 * @author CH
 * @since 2026-05-10
 */
@Slf4j
public class MobileBertZeroShotClassificationTranslator implements Translator<Map<String, String>, Classifications> {

    /**
     *                       128
     */
    private static final int MAX_LENGTH = 128;

    /**
     * MNLI                          
     *   ENTAILMENT / NEUTRAL / CONTRADICTION
     */
    private static final List<String> NLI_LABELS = List.of("ENTAILMENT", "NEUTRAL", "CONTRADICTION");

    /**
     * huggingface
     */
    private HuggingFaceTokenizer tokenizer;

    @Override
    /**
     * Prepare
    */
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
        log.debug("[MobileBERT-MNLI] Tokenizer             : {}", tokenizerPath);
    }

    @Override
    @Nonnull
    /**
     * 处理输入
     *
     * @param ctx ctx
     * @param input 输入
     * @return 处理输入的结果
     */
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Map<String, String> input) {
        if (tokenizer == null) {
            throw new IllegalStateException("HuggingFaceTokenizer             ");
        }
        String premise = input.getOrDefault("premise", "");
        String hypothesis = input.getOrDefault("hypothesis", "");

        Encoding encoding = tokenizer.encode(premise, hypothesis);
        long[] ids = encoding.getIds();
        long[] mask = encoding.getAttentionMask();
        long[] typeIds = encoding.getTypeIds();

        NDList ndList = new NDList();
        NDArray inputIds = ctx.getNDManager().create(ids).toType(DataType.INT64, false).expandDims(0);
        inputIds.setName("input_ids");
        ndList.add(inputIds);

        NDArray attentionMask = ctx.getNDManager().create(mask).toType(DataType.INT64, false).expandDims(0);
        attentionMask.setName("attention_mask");
        ndList.add(attentionMask);

        NDArray tokenTypeIds = ctx.getNDManager().create(typeIds).toType(DataType.INT64, false).expandDims(0);
        tokenTypeIds.setName("token_type_ids");
        ndList.add(tokenTypeIds);

        return ndList;
    }

    @Override
    @Nonnull
    /**
     * 处理输出
     *
     * @param ctx ctx
     * @param list 列表
     * @return 处理输出的结果
     */
    public Classifications processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray logits = list.singletonOrThrow();
        if (logits.getShape().dimension() == 2 && logits.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            logits = logits.squeeze(0);
        }
        float[] logitArr = NDArrayUtils.safeToFloatArray(logits);
        double[] probs = softmax(logitArr);
        List<Double> probabilities = Arrays.stream(probs).boxed().collect(Collectors.toList());
        return new Classifications(NLI_LABELS, probabilities);
    }

    /**
     *                               
     * <p>
     * "This example 是否 about {标签}.",        NLI,
     *     ENTAILMENT score                      
     * zoo模型        Predictor
     * </p>
     *
     * @param predictor                      Predictor
     * @param text                          
     * @param candidateLabels                   
     * @return                                        
     */
    public static Classifications zeroShotClassify(
            ai.djl.inference.Predictor<Map<String, String>, Classifications> predictor,
            String text,
            List<String> candidateLabels) throws Exception {

        List<Double> scores = new ArrayList<>();
        for (String label : candidateLabels) {
            String hypothesis = "This example is about " + label + ".";
            Map<String, String> pair = Map.of("premise", text, "hypothesis", hypothesis);
            Classifications result = predictor.predict(pair);
            // topK(3)              3                            
            var topItems = result.topK(3);
            double entailmentScore = 0.0;
            for (var item : topItems) {
                if ("ENTAILMENT".equals(item.getClassName())) {
                    entailmentScore = item.getProbability();
                    break;
                }
            }
            scores.add(entailmentScore);
        }

        double sum = scores.stream().mapToDouble(d -> d).sum();
        if (sum > 0) {
            scores = scores.stream().map(s -> s / sum).collect(Collectors.toList());
        }
        return new Classifications(candidateLabels, scores);
    }

    @Override
    @Nullable
    /**
     * 获取Batchifier
     *
     * @return 获取batchifier的结果
     */
    public Batchifier getBatchifier() {
        return null;
    }

    /**
     *                        tokenizer.json
     *
     * @param modelPath             
     * @return                        path，       空
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
