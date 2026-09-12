package com.chua.deeplearning.support.onnx.classification;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.common.support.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
   * siglip                 Translator
 *
 * <p>SigLIP        unified image-text ONNX                       CLIP                      
 *                                                                    logits          </p>
 *
 * @author CH
 * @since 2026-04-23
 */
public class SiglipZeroShotClassificationTranslator implements Translator<Image, Classifications> {

    /** 图像尺寸 */
    /** 镜像_大小 */
    private static final int IMAGE_SIZE = 224;
    /** 默认候选列表 */
    /** 默认_candidates */
    private static final List<String> DEFAULT_CANDIDATES = List.of("person", "document", "animal", "vehicle");

    /** 请求的候选列表 */
    /** Requestedcandidates */
    private final List<String> requestedCandidates;
    /** 提示词模板 */
    /** 提示符模板 */
    private final String promptTemplate;

    /** 分词器 */
    /** Tokenizer */
    private HuggingFaceTokenizer tokenizer;
    /** 候选列表 */
    /** Candidates */
    private List<String> candidates = DEFAULT_CANDIDATES;
    /** 候选输入标识 */
    /** Candidate输入标识 */
    private long[][] candidateInputIds = new long[0][];

    /** 创建 siglipzeroshotclassificationtranslator 实例 */
    public SiglipZeroShotClassificationTranslator() {
        this(Collections.emptyMap());
    }

    /**
      * 创建 siglipzeroshotclassificationtranslator 实例
     * @param arguments 参数
     */
    public SiglipZeroShotClassificationTranslator(Map<String, ?> arguments) {
        String rawCandidates = readArgument(arguments, "candidates");
        this.requestedCandidates = parseCandidates(rawCandidates);
        String template = readArgument(arguments, "promptTemplate");
        this.promptTemplate = StringUtils.isNotBlank(template) ? template : "a photo of %s";
    }

    @Override
    /** Prepare */
    public void prepare(@Nonnull TranslatorContext ctx) throws Exception {
        Path modelRoot = resolveModelRoot(ctx.getModel().getModelPath());
        tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(resolveRequiredFile(modelRoot, "tokenizer.json"))
                .build();

        candidates = requestedCandidates.isEmpty() ? DEFAULT_CANDIDATES : requestedCandidates;
        candidateInputIds = buildCandidateInputIds(candidates);
    }

    @Override
    /** 处理输入 */
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        array = NDImageUtils.resize(array, IMAGE_SIZE, IMAGE_SIZE);
        if (!DataType.FLOAT32.equals(array.getDataType())) {
            array = array.toType(DataType.FLOAT32, false);
        }
        array = array.div(255f).sub(0.5f).div(0.5f).transpose(2, 0, 1).expandDims(0);
        array.setName("pixel_values");

        long[] flattened = new long[candidateInputIds.length * candidateInputIds[0].length];
        int offset = 0;
        for (long[] ids : candidateInputIds) {
            System.arraycopy(ids, 0, flattened, offset, ids.length);
            offset += ids.length;
        }
        NDArray inputIds = ctx.getNDManager().create(flattened, new Shape(candidateInputIds.length, candidateInputIds[0].length));
        inputIds.setName("input_ids");
        return new NDList(inputIds, array);
    }

    @Override
    /** 处理输出 */
    public Classifications processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        if (list == null || list.isEmpty() || candidates.isEmpty()) {
            return new Classifications(List.of(), List.of());
        }

        NDArray logits = selectLogits(list, candidates.size());
        if (logits == null) {
            return new Classifications(List.of(), List.of());
        }

        float[] values = logits.toFloatArray();
        if (values.length == 0) {
            return new Classifications(List.of(), List.of());
        }

        if (values.length != candidates.size()) {
            int limit = Math.min(values.length, candidates.size());
            values = Arrays.copyOf(values, limit);
            return new Classifications(
                    candidates.subList(0, limit),
                    toProbabilities(values));
        }
        return new Classifications(candidates, toProbabilities(values));
    }

    @Nullable
    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }

    /**
     * 选择Logits
     *
     * @param list 列表
     * @param candidateCount candidate数量
     * @return 选择logits的结果
     */
    private NDArray selectLogits(NDList list, int candidateCount) {
        for (NDArray array : list) {
            if (array == null) {
                continue;
            }
            if (array.getShape().dimension() == 2 && array.getShape().get(0) == 1 && array.getShape().get(1) == candidateCount) {
                return array.squeeze(0);
            }
            if (array.getShape().dimension() == 2 && array.getShape().get(0) == candidateCount && array.getShape().get(1) == 1) {
                return array.squeeze(1);
            }
            if (array.getShape().dimension() == 1 && array.getShape().get(0) == candidateCount) {
                return array;
            }
        }

        NDArray fallback = list.getFirst();
        if (fallback.getShape().dimension() == 2 && fallback.getShape().get(0) == 1) {
            return fallback.squeeze(0);
        }
        return fallback;
    }

    /**
     * 构建candidate输入标识
     *
     * @param labels 标签
     * @return 构建candidate输入标识的结果
     */
    private long[][] buildCandidateInputIds(List<String> labels) {
        List<long[]> encoded = new ArrayList<>(labels.size());
        int maxLength = 1;
        for (String label : labels) {
            Encoding encoding = tokenizer.encode(formatPrompt(label));
            long[] ids = encoding == null ? null : encoding.getIds();
            if (ids == null || ids.length == 0) {
                ids = new long[]{0L};
            }
            encoded.add(ids);
            maxLength = Math.max(maxLength, ids.length);
        }

        long[][] values = new long[encoded.size()][maxLength];
        for (int i = 0; i < encoded.size(); i++) {
            long[] ids = encoded.get(i);
            System.arraycopy(ids, 0, values[i], 0, ids.length);
        }
        return values;
    }

    /**
     * 转为probabilities
     *
     * @param logits logits
     * @return 转为probabilities的结果
     */
    private List<Double> toProbabilities(float[] logits) {
        double[] softmax = softmax(logits);
        List<Double> probabilities = new ArrayList<>(softmax.length);
        for (double value : softmax) {
            probabilities.add(value);
        }
        return probabilities;
    }

    /**
     * Softmax
     *
     * @param logits logits
     * @return softmax的结果
     */
    private double[] softmax(float[] logits) {
        double max = Double.NEGATIVE_INFINITY;
        for (float logit : logits) {
            max = Math.max(max, logit);
        }
        double[] values = new double[logits.length];
        double sum = 0d;
        for (int i = 0; i < logits.length; i++) {
            values[i] = Math.exp(logits[i] - max);
            sum += values[i];
        }
        if (sum <= 0d || Double.isNaN(sum)) {
            return new double[logits.length];
        }
        for (int i = 0; i < values.length; i++) {
            values[i] /= sum;
        }
        return values;
    }

    /**
     * 格式化提示符
     *
     * @param label 标签
     * @return 格式化提示符的结果
     */
    private String formatPrompt(String label) {
        if (promptTemplate.contains("%s")) {
            return String.format(promptTemplate, label);
        }
        return promptTemplate + label;
    }

    /**
     * 解析Candidates
     *
     * @param rawCandidates rawcandidates
     * @return 解析candidates的结果
     */
    private List<String> parseCandidates(String rawCandidates) {
        if (StringUtils.isBlank(rawCandidates)) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String item : rawCandidates.split("[,|]")) {
            if (StringUtils.isBlank(item)) {
                continue;
            }
            values.add(item.trim());
        }
        return new ArrayList<>(values);
    }

    /**
     * 读取参数
     *
     * @param arguments 参数
     * @param key 键
     * @return 读取参数的结果
     */
    private String readArgument(Map<String, ?> arguments, String key) {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }
        Object value = arguments.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 解析模型根
     *
     * @param modelPath 模型路径
     * @return resolve模型根的结果
     */
    private Path resolveModelRoot(Path modelPath) {
        if (modelPath == null) {
            return Path.of(".");
        }
        Path candidate = Files.isRegularFile(modelPath) ? modelPath.getParent() : modelPath;
        if (candidate != null && Files.exists(candidate.resolve("tokenizer.json"))) {
            return candidate;
        }
        if (candidate != null && "onnx".equalsIgnoreCase(candidate.getFileName().toString()) && candidate.getParent() != null) {
            return candidate.getParent();
        }
        return candidate == null ? Path.of(".") : candidate;
    }

    /**
     * 解析required文件
     *
     * @param root 根
     * @param fileName 文件名称
     * @return resolverequired文件的结果
     */
    private Path resolveRequiredFile(Path root, String fileName) throws IOException {
        Path file = root.resolve(fileName);
        if (Files.exists(file)) {
            return file;
        }
        throw new IOException("          SigLIP             : " + file);
    }
}

