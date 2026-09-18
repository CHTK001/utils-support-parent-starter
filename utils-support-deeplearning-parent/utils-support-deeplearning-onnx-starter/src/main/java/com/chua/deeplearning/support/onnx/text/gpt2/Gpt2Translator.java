package com.chua.deeplearning.support.onnx.text.gpt2;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.modality.Classifications;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
* GPT-2              Translator   
* <p>
* huggingface Tokenizer + ONNX
* decode                 令牌
* </p>
*
* @author CH
* @since 2025-04-10
 */
@Slf4j
public class Gpt2Translator implements Translator<String, Classifications> {

    /**
    * GPT-2                               128
    */
    private static final int MAX_LENGTH = 128;

    /**
    * huggingface
    */
    private HuggingFaceTokenizer tokenizer;

    @Override
    /** Prepare */
    public void prepare(TranslatorContext ctx) throws IOException {
        Path modelPath = ctx.getModel().getModelPath();
        if (modelPath == null) {
            throw new IOException("Model path is null, cannot load tokenizer");
        }
        Path tokenizerPath = findFile(modelPath, "tokenizer.json");
        if (tokenizerPath == null || !Files.exists(tokenizerPath)) {
            throw new IOException("Cannot find tokenizer.json in path: " + modelPath);
        }
        try {
            tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerPath)
                    .optPadding(true)
                    .optMaxLength(MAX_LENGTH)
                    .build();
            log.debug("[GPT2] Tokenizer loaded: {}", tokenizerPath);
        } catch (Exception e) {
            throw new IOException("Failed to load HuggingFaceTokenizer: " + e.getMessage(), e);
        }
    }

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, String input) {
        if (tokenizer == null) {
            throw new IllegalStateException("Tokenizer not initialized");
        }
        Encoding encoding = tokenizer.encode(input);
        long[] inputIds = encoding.getIds();
        long[] attentionMask = encoding.getAttentionMask();

        NDList ndList = new NDList();
        NDArray idsArray = ctx.getNDManager().create(inputIds).toType(DataType.INT64, false).expandDims(0);
        idsArray.setName("input_ids");
        ndList.add(idsArray);

        NDArray maskArray = ctx.getNDManager().create(attentionMask).toType(DataType.INT64, false).expandDims(0);
        maskArray.setName("attention_mask");
        ndList.add(maskArray);

        return ndList;
    }

    @Override
    /** 处理输出 */
    public Classifications processOutput(TranslatorContext ctx, NDList list) {
        NDArray logits = list.singletonOrThrow();
        if (logits.getShape().dimension() == 2 && logits.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            logits = logits.squeeze(0);
        }
        long[] shape = logits.getShape().getShape();
        int seqLen = (int) shape[0];
        int vocabSize = (int) shape[1];
        NDArray lastLogits = logits.get("{},:,", seqLen - 1);
        float[] scores = lastLogits.toFloatArray();

        List<String> tokens = new ArrayList<>();
        List<Double> probabilities = new ArrayList<>();
        int topK = Math.min(10, vocabSize);
        int[] indices = topKIndices(scores, topK);
        double sum = 0;
        for (int i = 0; i < topK; i++) {
            double prob = Math.exp(scores[indices[i]]);
            sum += prob;
            tokens.add("[" + indices[i] + "]");
            probabilities.add(prob);
        }
        final double normSum = sum;
        List<Double> normalized = probabilities.stream().map(p -> p / normSum).collect(Collectors.toList());
        return new Classifications(tokens, normalized);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }

    /**
    * 模型路径          文件名
    *
    * @param modelPath             
    * @param fileName              
    * @return                        path，       空
    */
    private static Path findFile(Path modelPath, String fileName) {
        Path root = Files.isDirectory(modelPath) ? modelPath : modelPath.getParent();
        if (root == null) {
            return null;
        }
        Path p = root.resolve(fileName);
        if (Files.exists(p)) {
            return p;
        }
        return null;
    }

    /**
    * top-K                          
    *
    * @param arr                 
    * @param k           top-K   
    * @return top-K       
    */
    private static int[] topKIndices(float[] arr, int k) {
        int n = arr.length;
        int[] indices = new int[k];
        float[] values = new float[n];
        System.arraycopy(arr, 0, values, 0, n);
        for (int i = 0; i < k; i++) {
            int maxIdx = 0;
            for (int j = 1; j < n; j++) {
                if (values[j] > values[maxIdx]) {
                    maxIdx = j;
                }
            }
            indices[i] = maxIdx;
            values[maxIdx] = -Float.MAX_VALUE;
        }
        return indices;
    }
}
