package com.chua.deeplearning.support.onnx.owlv2;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.ai.DetectionConfiguration;
import com.chua.common.support.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * owlv2                                   Translator
 *
 * @author CH
 * @since 4.0.0
 */
@Slf4j
public class Owlv2ZeroShotDetectorTranslator implements Translator<Image, DetectedObjects> {

    /** JSON 对象映射器 */
    /** 对象_映射器 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** 中文匹配正则 */
    /** Chinese_模式 */
    private static final Pattern CHINESE_PATTERN = Pattern.compile(".*[\\u4e00-\\u9fff].*");
    /** 默认候选列表 */
    /** 默认_candidates */
    private static final List<String> DEFAULT_CANDIDATES = List.of("person", "flower", "dog", "car");
    /** 默认阈值 */
    /** 默认_阈值 */
    private static final double DEFAULT_THRESHOLD = 0.10d;
    /** 默认 NMS 阈值 */
    /** 默认_nms_阈值 */
    private static final double DEFAULT_NMS_THRESHOLD = 0.50d;
    /** 默认低信息方差 */
    /** 默认_low_信息_variance */
    private static final double DEFAULT_LOW_INFO_VARIANCE = 25d;
    /** 中文转英文映射表 */
    private static final Map<String, String> CHINESE_TO_ENGLISH;

    static {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("   ", "person");
        mapping.put("      ", "woman");
        mapping.put("      ", "man");
        mapping.put("   ", "flower");
        mapping.put("   ", "dog");
        mapping.put("   ", "cat");
        mapping.put("      ", "car");
        mapping.put("         ", "bicycle");
        CHINESE_TO_ENGLISH = Collections.unmodifiableMap(mapping);
    }

    /** 阈值 */
    private final double threshold;
    /** NMS 阈值 */
    /** NMS阈值 */
    private final double nmsThreshold;
    /** 请求的候选列表 */
    /** Requestedcandidates */
    private final List<String> requestedCandidates;

    /** 分词器 */
    /** Tokenizer */
    private HuggingFaceTokenizer tokenizer;
    /** 候选输入标识 */
    /** Candidate输入标识 */
    private long[][] candidateInputIds = new long[0][];
    /** 候选注意力掩码 */
    /** Candidateattentionmasks */
    private long[][] candidateAttentionMasks = new long[0][];
    /** 候选输出标签 */
    /** Candidate输出标签 */
    private List<String> candidateOutputLabels = DEFAULT_CANDIDATES;
    /** 候选模型标签 */
    /** Candidate模型标签 */
    private List<String> candidateModelLabels = DEFAULT_CANDIDATES;
    /** 输入宽度 */
    private int inputWidth = 960;
    /** 输入高度 */
    private int inputHeight = 960;
    /** 图像均值数组 */
    /** 图片mean */
    private float[] imageMean = {0.48145466f, 0.4578275f, 0.40821073f};
    /** 图像标准差数组 */
    /** 图片STD */
    private float[] imageStd = {0.26862954f, 0.26130258f, 0.27577711f};
    /** 重缩放系数 */
    /** Rescale系数 */
    private float rescaleFactor = 1f / 255f;

    /** 是否为低信息图像 */
    /** lowinformation图片 */
    private boolean lowInformationImage;
    /** 原始宽度 */
    /** 原始宽度 */
    private int originalWidth;
    /** 原始高度 */
    /** 原始高度 */
    private int originalHeight;
    /** 缩放比例 */
    /** Resize比例尺 */
    private double resizeScale = 1d;
    /** X 轴填充值 */
    /** PADX坐标 */
    private int padX;
    /** Y 轴填充值 */
    /** PADY坐标 */
    private int padY;

    /** 创建 Owlv2zeroshotdetectortranslator 实例 */
    public Owlv2ZeroShotDetectorTranslator() {
        this(DetectionConfiguration.DEFAULT);
    }

    /**
     * 创建 Owlv2zeroshotdetectortranslator 实例
     * @param configuration 配置
     */
    public Owlv2ZeroShotDetectorTranslator(DetectionConfiguration configuration) {
        DetectionConfiguration cfg = configuration == null ? DetectionConfiguration.DEFAULT : configuration;
        this.threshold = readDouble(cfg.systemOption(), "threshold", DEFAULT_THRESHOLD);
        this.nmsThreshold = readDouble(cfg.systemOption(), "iouThreshold", DEFAULT_NMS_THRESHOLD);
        this.requestedCandidates = parseCandidates(readArgument(cfg.systemOption(), "candidates"));
    }

    @Override
    /** Prepare */
    public void prepare(@Nonnull TranslatorContext ctx) throws Exception {
        Path modelRoot = resolveModelRoot(ctx.getModel().getModelPath());
        tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(resolveRequiredFile(modelRoot, "tokenizer.json"))
                .optPadding(true)
                .build();

        loadPreprocessorConfig(resolveRequiredFile(modelRoot, "preprocessor_config.json"));
        resolveCandidates();
        buildTextInputs();
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
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        BufferedImage original = (BufferedImage) input.getWrappedImage();
        originalWidth = original.getWidth();
        originalHeight = original.getHeight();
        lowInformationImage = isLowInformationImage(original);

        NDArray inputIds = ctx.getNDManager().create(candidateInputIds);
        inputIds.setName("input_ids");
        NDArray attentionMask = ctx.getNDManager().create(candidateAttentionMasks);
        attentionMask.setName("attention_mask");

        NDArray pixelValues;
        if (lowInformationImage) {
            pixelValues = ctx.getNDManager().zeros(new Shape(1, 3, inputHeight, inputWidth), DataType.FLOAT32);
        } else {
            BufferedImage prepared = letterbox(original);
            Image paddedImage = ImageFactory.getInstance().fromImage(prepared);
            NDArray array = paddedImage.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
            if (!DataType.FLOAT32.equals(array.getDataType())) {
                array = array.toType(DataType.FLOAT32, false);
            }
            array = array.transpose(2, 0, 1).mul(rescaleFactor);
            NDArray mean = ctx.getNDManager().create(imageMean).reshape(3, 1, 1);
            NDArray std = ctx.getNDManager().create(imageStd).reshape(3, 1, 1);
            pixelValues = array.sub(mean).div(std).expandDims(0);
        }
        pixelValues.setName("pixel_values");

        return new NDList(inputIds, pixelValues, attentionMask);
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
    public DetectedObjects processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        if (lowInformationImage) {
            return emptyDetections();
        }
        if (list.size() < 2) {
            throw new IllegalStateException("OWLv2                                         logits     pred_boxes");
        }

        NDArray logitsArray = list.getFirst();
        NDArray boxesArray = list.get(1);
        if (logitsArray.getShape().dimension() == 3 && logitsArray.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            logitsArray = logitsArray.squeeze(0);
        }
        if (boxesArray.getShape().dimension() == 3 && boxesArray.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            boxesArray = boxesArray.squeeze(0);
        }

        long numBoxes = logitsArray.getShape().get(0); // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
        int numQueries = (int) logitsArray.getShape().get(1);
        if (numBoxes <= 0 || numQueries <= 0) {
            return emptyDetections();
        }

        List<DetectionCandidate> merged = new ArrayList<>();
        double maxScore = 0d;
        for (int queryIndex = 0; queryIndex < Math.min(numQueries, candidateOutputLabels.size()); queryIndex++) {
            List<DetectionCandidate> perClass = new ArrayList<>();
            String label = candidateOutputLabels.get(queryIndex);
            for (int boxIndex = 0; boxIndex < numBoxes; boxIndex++) {
                double score = sigmoid(logitsArray.getFloat(boxIndex, queryIndex));
                maxScore = Math.max(maxScore, score);
                if (score < threshold) {
                    continue;
                }

                Rectangle rectangle = decodeRectangle(boxesArray, boxIndex);
                if (rectangle == null || rectangle.getWidth() <= 0d || rectangle.getHeight() <= 0d) {
                    continue;
                }
                perClass.add(new DetectionCandidate(label, score, rectangle));
            }
            merged.addAll(applyNms(perClass));
        }

        if (merged.isEmpty()) {
            return emptyDetections();
        }

        merged.sort(Comparator.comparingDouble(DetectionCandidate::score).reversed());
        List<String> names = new ArrayList<>(merged.size());
        List<Double> probabilities = new ArrayList<>(merged.size());
        List<BoundingBox> boxes = new ArrayList<>(merged.size());
        for (DetectionCandidate candidate : merged) {
            names.add(candidate.label());
            probabilities.add(candidate.score());
            boxes.add(candidate.rectangle());
        }
        return new DetectedObjects(names, probabilities, boxes);
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
     * 加载preprocessor配置
     *
     * @param configPath 配置路径
     */
    private void loadPreprocessorConfig(Path configPath) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(configPath.toFile());
        JsonNode size = root.path("size");
        inputHeight = size.path("height").asInt(960);
        inputWidth = size.path("width").asInt(960);
        imageMean = readFloatArray(root.path("image_mean"), imageMean);
        imageStd = readFloatArray(root.path("image_std"), imageStd);
        rescaleFactor = (float) root.path("rescale_factor").asDouble(1d / 255d);
    }

    /** 解析Candidates */
    private void resolveCandidates() {
        List<String> source = requestedCandidates.isEmpty() ? DEFAULT_CANDIDATES : requestedCandidates;
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (String raw : source) {
            String label = raw.trim();
            if (label.isEmpty()) {
                continue;
            }
            String detectionLabel = normalizeCandidate(label);
            normalized.putIfAbsent(detectionLabel, label);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("OWLv2                      ");
        }
        candidateModelLabels = new ArrayList<>(normalized.keySet());
        candidateOutputLabels = new ArrayList<>(normalized.values());
    }

    /**
     * normalizecandidate
     *
     * @param label 标签
     * @return normalizeCandidate的结果
     */
    private String normalizeCandidate(String label) {
        if (!CHINESE_PATTERN.matcher(label).matches()) {
            return label.trim().toLowerCase(Locale.ROOT);
        }
        String mapped = CHINESE_TO_ENGLISH.get(label.trim());
        if (mapped == null) {
            throw new IllegalArgumentException("OWLv2                                        : " + label);
        }
        return mapped;
    }

    /** 构建文本输入 */
    private void buildTextInputs() {
        List<long[]> idsList = new ArrayList<>(candidateModelLabels.size());
        int maxLength = 1;
        for (String candidate : candidateModelLabels) {
            Encoding encoding = tokenizer.encode(formatPrompt(candidate));
            long[] ids = encoding.getIds();
            if (ids == null || ids.length == 0) {
                throw new IllegalStateException("OWLv2                      : " + candidate);
            }
            idsList.add(ids);
            maxLength = Math.max(maxLength, ids.length);
        }

        candidateInputIds = new long[candidateModelLabels.size()][maxLength];
        candidateAttentionMasks = new long[candidateModelLabels.size()][maxLength];
        for (int index = 0; index < idsList.size(); index++) {
            long[] ids = idsList.get(index);
            System.arraycopy(ids, 0, candidateInputIds[index], 0, ids.length);
            Arrays.fill(candidateAttentionMasks[index], 0L);
            Arrays.fill(candidateAttentionMasks[index], 0, ids.length, 1L);
        }
    }

    /**
     * Letterbox
     *
     * @param image 镜像
     * @return letterbox的结果
     */
    private BufferedImage letterbox(BufferedImage image) {
        double widthScale = inputWidth / (double) image.getWidth();
        double heightScale = inputHeight / (double) image.getHeight();
        resizeScale = Math.min(widthScale, heightScale);
        int resizedWidth = Math.max(1, (int) Math.round(image.getWidth() * resizeScale));
        int resizedHeight = Math.max(1, (int) Math.round(image.getHeight() * resizeScale));
        padX = (inputWidth - resizedWidth) / 2;
        padY = (inputHeight - resizedHeight) / 2;

        BufferedImage canvas = new BufferedImage(inputWidth, inputHeight, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, inputWidth, inputHeight);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, padX, padY, resizedWidth, resizedHeight, null);
        }
finally {
            graphics.dispose();
        }
        return canvas;
    }

    /**
     * 解码Rectangle
     *
     * @param boxesArray boxesarray
     * @param boxIndex box索引
     * @return decodeRectangle的结果
     */
    private Rectangle decodeRectangle(NDArray boxesArray, int boxIndex) {
        double centerX = boxesArray.getFloat(boxIndex, 0) * inputWidth;
        double centerY = boxesArray.getFloat(boxIndex, 1) * inputHeight;
        double width = boxesArray.getFloat(boxIndex, 2) * inputWidth;
        double height = boxesArray.getFloat(boxIndex, 3) * inputHeight;

        double x1 = centerX - width / 2d;
        double y1 = centerY - height / 2d;
        double x2 = centerX + width / 2d;
        double y2 = centerY + height / 2d;

        x1 = (x1 - padX) / resizeScale;
        y1 = (y1 - padY) / resizeScale;
        x2 = (x2 - padX) / resizeScale;
        y2 = (y2 - padY) / resizeScale;

        x1 = clip(x1, 0d, originalWidth);
        y1 = clip(y1, 0d, originalHeight);
        x2 = clip(x2, 0d, originalWidth);
        y2 = clip(y2, 0d, originalHeight);
        if (x2 <= x1 || y2 <= y1) {
            return null;
        }

        return new Rectangle(
                x1 / originalWidth,
                y1 / originalHeight,
                (x2 - x1) / originalWidth,
                (y2 - y1) / originalHeight
       );
    }

    /**
     * 应用Nms
     *
     * @param candidates candidates
     * @return applyNms的结果
     */
    private List<DetectionCandidate> applyNms(List<DetectionCandidate> candidates) {
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }
        candidates.sort(Comparator.comparingDouble(DetectionCandidate::score).reversed());
        List<DetectionCandidate> kept = new ArrayList<>();
        for (DetectionCandidate candidate : candidates) {
            boolean keep = true;
            for (DetectionCandidate existing : kept) {
                if (calculateIoU(candidate.rectangle(), existing.rectangle()) >= nmsThreshold) {
                    keep = false;
                    break;
                }
            }
            if (keep) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    /**
     * calculateiou
     *
     * @param first 第一个
     * @param second second
     * @return calculateIoU的结果
     */
    private double calculateIoU(Rectangle first, Rectangle second) {
        double x1 = Math.max(first.getX(), second.getX());
        double y1 = Math.max(first.getY(), second.getY());
        double x2 = Math.min(first.getX() + first.getWidth(), second.getX() + second.getWidth());
        double y2 = Math.min(first.getY() + first.getHeight(), second.getY() + second.getHeight());
        double intersection = Math.max(0d, x2 - x1) * Math.max(0d, y2 - y1);
        double union = first.getWidth() * first.getHeight() + second.getWidth() * second.getHeight() - intersection;
        return union <= 0d ? 0d : intersection / union;
    }

    /**
     * 是否low信息镜像
     *
     * @param image 镜像
     * @return 是否low信息镜像的结果
     */
    private boolean isLowInformationImage(BufferedImage image) {
        long samples = 0L;
        double sum = 0d;
        double sumSquares = 0d;
        int stepX = Math.max(1, image.getWidth() / 64);
        int stepY = Math.max(1, image.getHeight() / 64);
        for (int y = 0; y < image.getHeight(); y += stepY) {
            for (int x = 0; x < image.getWidth(); x += stepX) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                double gray = 0.299d * red + 0.587d * green + 0.114d * blue;
                samples++;
                sum += gray;
                sumSquares += gray * gray;
            }
        }
        if (samples == 0L) {
            return true;
        }
        double mean = sum / samples;
        double variance = (sumSquares / samples) - mean * mean;
        return variance < DEFAULT_LOW_INFO_VARIANCE;
    }

    /**
     * 读取floatarray
     *
     * @param node 节点
     * @param defaults 默认
     * @return 读取floatarray的结果
     */
    private float[] readFloatArray(JsonNode node, float[] defaults) {
        if (node == null || !node.isArray() || node.size() != defaults.length) {
            return defaults;
        }
        float[] values = new float[defaults.length];
        for (int index = 0; index < defaults.length; index++) {
            values[index] = (float) node.get(index).asDouble(defaults[index]);
        }
        return values;
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
     * 读取Double
     *
     * @param arguments 参数
     * @param key 键
     * @param defaultValue 默认值
     * @return 读取double的结果
     */
    private double readDouble(Map<String, ?> arguments, String key, double defaultValue) {
        String value = readArgument(arguments, key);
        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /**
     * 格式化提示符
     *
     * @param candidate candidate
     * @return 格式化提示符的结果
     */
    private String formatPrompt(String candidate) {
        return candidate;
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
        if (Files.isRegularFile(modelPath)) {
            return modelPath.getParent().getParent();
        }
        if (Files.isDirectory(modelPath)) {
            Path fileName = modelPath.getFileName();
            if (fileName != null && "onnx".equalsIgnoreCase(fileName.toString()) && modelPath.getParent() != null) {
                return modelPath.getParent();
            }
        }
        return modelPath;
    }

    /**
     * 解析required文件
     *
     * @param root 根
     * @param name 名称
     * @return resolverequired文件的结果
     */
    private Path resolveRequiredFile(Path root, String name) throws IOException {
        Path file = root.resolve(name);
        if (Files.exists(file)) {
            return file;
        }
        throw new IOException("          OWLv2             : " + file);
    }

    /**
     * Sigmoid
     *
     * @param value 值
     * @return sigmoid的结果
     */
    private double sigmoid(double value) {
        if (value >= 0d) {
            double exp = Math.exp(-value);
            return 1d / (1d + exp);
        }
        double exp = Math.exp(value);
        return exp / (1d + exp);
    }

    /**
     * Clip
     *
     * @param value 值
     * @param min 最小
     * @param max 最大
     * @return clip的结果
     */
    private double clip(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 空detections
     *
     * @return 空detections的结果
     */
    private DetectedObjects emptyDetections() {
        return new DetectedObjects(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    /**
     * detectioncandidate
     *
     * @param label 标签
     * @param score score
     * @param rectangle rectangle
     * @return DetectionCandidate的结果
     */
    private record DetectionCandidate(String label, double score, Rectangle rectangle) {
    }
}

