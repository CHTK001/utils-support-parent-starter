package com.chua.deeplearning.support.onnx.classification;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * CN-CLIP                 Translator   
 *
 * <p>CN-CLIP        Chinese-CLIP ONNX                       CLIP                      
 *                                                                    logits          </p>
 *
 * <p>        gficcg/clip_cn_vit-onnx              
 *  image encoder + text encoder + vocab.txt                   
 *  ONNX   unnorm_image_features / unnorm_text_features             
 *  L2      cosine * logit_scale  softmax             </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CnClipZeroShotClassificationTranslator implements Translator<Image, Classifications> {

    /** 图像尺寸 */
    private static final int IMAGE_SIZE = 224;
    /** 文本最大长度 */
    private static final int TEXT_MAX_LENGTH = 52;
    /** Logit 缩放系数 */
    private static final float LOGIT_SCALE = 100f;
    /** 默认候选列表 */
    private static final List<String> DEFAULT_CANDIDATES = List.of("person", "document", "animal", "vehicle");

    /** 均值数组 */
    private static final float[] MEAN = new float[]{0.48145466f, 0.45782750f, 0.40821073f};
    /** 标准差数组 */
    private static final float[] STD = new float[]{0.26862954f, 0.26130258f, 0.27577711f};

    /** 请求的候选列表 */
    private final List<String> requestedCandidates;
    /** 提示词模板 */
    private final String promptTemplate;

    /** 分词器 */
    private HuggingFaceTokenizer tokenizer;
    /** 候选列表 */
    private List<String> candidates = DEFAULT_CANDIDATES;

    public CnClipZeroShotClassificationTranslator() {
        this(Collections.emptyMap());
    }

    public CnClipZeroShotClassificationTranslator(Map<String, ?> arguments) {
        String rawCandidates = readArgument(arguments, "candidates");
        this.requestedCandidates = parseCandidates(rawCandidates);
        String template = readArgument(arguments, "promptTemplate");
        this.promptTemplate = StringUtils.isNotBlank(template) ? template : "a photo of %s";
    }

    @Override
    public void prepare(@Nonnull TranslatorContext ctx) throws Exception {
        Path modelRoot = resolveModelRoot(ctx.getModel().getModelPath());
        Path tokPath = resolveFirstExisting(modelRoot,
                "tokenizer.json", "vocab.txt", "tokenizers/vocab.txt");
        tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokPath)
                .build();

        candidates = requestedCandidates.isEmpty() ? DEFAULT_CANDIDATES : requestedCandidates;
    }

    @Override
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        array = NDImageUtils.resize(array, IMAGE_SIZE, IMAGE_SIZE);
        if (!DataType.FLOAT32.equals(array.getDataType())) {
            array = array.toType(DataType.FLOAT32, false);
        }
        array = array.div(255f);
        int h = (int) array.getShape().get(0);
        int w = (int) array.getShape().get(1);
        int c = (int) array.getShape().get(2);
        float[] flat = array.toFloatArray();
        for (int i = 0; i < h; i++) {
            for (int j = 0; j < w; j++) {
                for (int k = 0; k < c; k++) {
                    int idx = (i * w + j) * c + k;
                    flat[idx] = (flat[idx] - MEAN[k]) / STD[k];
                }
            }
        }
        array = ctx.getNDManager().create(flat, new Shape(h, w, c));
        array = array.transpose(2, 0, 1).expandDims(0);
        return new NDList(array);
    }

    @Override
    public Classifications processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray imageEmbeds = list.singletonOrThrow();
        float[] imageVec = normalize(imageEmbeds.squeeze().toFloatArray());

        NDManager manager = ctx.getNDManager();
        List<String> classNames = new ArrayList<>();
        List<Double> probabilities = new ArrayList<>();

        float maxScore = Float.NEGATIVE_INFINITY;
        float[] scores = new float[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            String text = String.format(promptTemplate, candidates.get(i));
            Encoding encoding = tokenizer.encode(text);
            long[] inputIds = truncate(encoding.getIds(), TEXT_MAX_LENGTH);
            long[] attention = new long[inputIds.length];
            Arrays.fill(attention, 1L);
            NDList textInput = new NDList(manager.create(inputIds), manager.create(attention));
            NDList textOutput = ctx.getModel().getBlock()
                    .forward(new ai.djl.training.ParameterStore(), textInput, false);
            float[] textVec = normalize(textOutput.singletonOrThrow().squeeze().toFloatArray());
            scores[i] = cosineSimilarity(imageVec, textVec) * LOGIT_SCALE;
            if (scores[i] > maxScore) {
                maxScore = scores[i];
            }
        }

        float sumExp = 0f;
        float[] expScores = new float[candidates.size()];
        for (int i = 0; i < scores.length; i++) {
            expScores[i] = (float) Math.exp(scores[i] - maxScore);
            sumExp += expScores[i];
        }
        for (int i = 0; i < candidates.size(); i++) {
            classNames.add(candidates.get(i));
            probabilities.add((double) (expScores[i] / sumExp));
        }

        return new Classifications(classNames, probabilities);
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }

    private static float[] normalize(float[] vec) {
        float norm = 0f;
        for (float v : vec) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm == 0f) {
            return vec;
        }
        float[] out = new float[vec.length];
        for (int i = 0; i < vec.length; i++) {
            out[i] = vec[i] / norm;
        }
        return out;
    }

    private static float cosineSimilarity(float[] a, float[] b) {
        float dot = 0f;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    private static long[] truncate(long[] ids, int maxLen) {
        if (ids.length <= maxLen) {
            return ids;
        }
        long[] out = new long[maxLen];
        System.arraycopy(ids, 0, out, 0, maxLen);
        return out;
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

    private static List<String> parseCandidates(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        String[] parts = raw.split("[,\\uff0c;；\\s]+");
        List<String> list = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (!p.isBlank()) {
                list.add(p.trim());
            }
        }
        return list;
    }

    private static String readArgument(Map<String, ?> arguments, String key) {
        if (arguments == null) {
            return null;
        }
        Object val = arguments.get(key);
        return val == null ? null : val.toString();
    }
}
