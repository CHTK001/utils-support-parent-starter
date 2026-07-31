package com.chua.deeplearning.support.onnx.dino;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import ai.djl.util.JsonUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.deeplearning.support.ai.DetectionConfiguration;
import com.chua.deeplearning.support.utils.NDArrayUtils;
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
import java.util.*;

/**
 * Grounding DINO Tiny ONNX                 
 * <p>
 * Grounding DINO                                  
 *       open-vocabulary object detection                               
 *       text prompt -> bounding box + category                       
 * </p>
 * <p>
 *      : 800x1333 (letterbox resize)  RGB  ImageNet normalize
 *      : input_ids, attention_mask, pixel_values
 *      : logits [num_queries, num_classes], pred_boxes [num_queries, 4]
 *      : DetectedObjects (text prompt -> category)
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class GroundingDinoTranslator implements Translator<Image, DetectedObjects> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final double DEFAULT_THRESHOLD = 0.35d;
    private static final double DEFAULT_NMS_THRESHOLD = 0.50d;
    private static final int DEFAULT_INPUT_SIZE = 800;

    private final double threshold;
    private final double nmsThreshold;
    private final List<String> requestedCandidates;

    private ai.djl.huggingface.tokenizers.HuggingFaceTokenizer tokenizer;
    private long[][] candidateInputIds;
    private long[][] candidateAttentionMasks;
    private List<String> candidateOutputLabels;

    private int inputHeight = DEFAULT_INPUT_SIZE;
    private int inputWidth = DEFAULT_INPUT_SIZE;
    private float[] imageMean = {0.485f, 0.456f, 0.406f};
    private float[] imageStd = {0.229f, 0.224f, 0.225f};
    private float rescaleFactor = 1f / 255f;

    private int originalWidth;
    private int originalHeight;
    private double resizeScale = 1d;
    private int padX;
    private int padY;

    public GroundingDinoTranslator() {
        this(DetectionConfiguration.DEFAULT);
    }

    public GroundingDinoTranslator(DetectionConfiguration configuration) {
        DetectionConfiguration cfg = configuration == null ? DetectionConfiguration.DEFAULT : configuration;
        this.threshold = readDouble(cfg.systemOption(), "threshold", DEFAULT_THRESHOLD);
        this.nmsThreshold = readDouble(cfg.systemOption(), "iouThreshold", DEFAULT_NMS_THRESHOLD);
        this.requestedCandidates = parseCandidates(readArgument(cfg.systemOption(), "candidates"));
    }

    @Override
    public void prepare(@Nonnull TranslatorContext ctx) throws Exception {
        Path modelRoot = resolveModelRoot(ctx.getModel().getModelPath());
        Path tokenizerPath = resolveRequiredFile(modelRoot, "tokenizer.json");
        tokenizer = ai.djl.huggingface.tokenizers.HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerPath)
                .optPadding(true)
                .build();

        Path preprocessorConfig = modelRoot.resolve("preprocessor_config.json");
        if (Files.exists(preprocessorConfig)) {
            loadPreprocessorConfig(preprocessorConfig);
        }

        resolveCandidates();
        buildTextInputs();
    }

    @Override
    @Nonnull
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        originalWidth = input.getWidth();
        originalHeight = input.getHeight();

        NDManager manager = ctx.getNDManager();

        long[][] batchedIds = new long[1][];
        long[][] batchedMasks = new long[1][];
        batchedIds[0] = candidateInputIds[0];
        batchedMasks[0] = candidateAttentionMasks[0];

        NDArray inputIds = manager.create(batchedIds);
        inputIds.setName("input_ids");
        NDArray attentionMask = manager.create(batchedMasks);
        attentionMask.setName("attention_mask");

        BufferedImage prepared = letterbox((BufferedImage) input.getWrappedImage());
        Image paddedImage = ImageFactory.getInstance().fromImage(prepared);
        NDArray array = paddedImage.toNDArray(manager, Image.Flag.COLOR);
        if (!DataType.FLOAT32.equals(array.getDataType())) {
            array = array.toType(DataType.FLOAT32, false);
        }
        array = array.transpose(2, 0, 1).mul(rescaleFactor);
        NDArray mean = manager.create(imageMean).reshape(3, 1, 1);
        NDArray std = manager.create(imageStd).reshape(3, 1, 1);
        NDArray pixelValues = array.sub(mean).div(std).expandDims(0);
        pixelValues.setName("pixel_values");

        return new NDList(inputIds, pixelValues, attentionMask);
    }

    @Override
    @Nonnull
    public DetectedObjects processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        if (list.size() < 2) {
            return emptyDetections();
        }

        NDArray logitsArray = list.get(0);
        NDArray boxesArray = list.get(1);

        if (logitsArray.getShape().dimension() == 3 && logitsArray.getShape().get(0) == 1) {
            logitsArray = logitsArray.squeeze(0);
        }
        if (boxesArray.getShape().dimension() == 3 && boxesArray.getShape().get(0) == 1) {
            boxesArray = boxesArray.squeeze(0);
        }

        long numBoxes = logitsArray.getShape().get(0);
        int numClasses = candidateOutputLabels.size();
        if (numBoxes <= 0 || numClasses <= 0) {
            return emptyDetections();
        }

        List<DetectionCandidate> merged = new ArrayList<>();
        for (int queryIndex = 0; queryIndex < numBoxes; queryIndex++) {
            double maxClassScore = Double.NEGATIVE_INFINITY;
            int bestClass = 0;
            for (int c = 0; c < numClasses; c++) {
                double score = sigmoid(logitsArray.getFloat(queryIndex, c));
                if (score > maxClassScore) {
                    maxClassScore = score;
                    bestClass = c;
                }
            }
            if (maxClassScore < threshold) {
                continue;
            }

            Rectangle rectangle = decodeRectangle(boxesArray, queryIndex);
            if (rectangle == null || rectangle.getWidth() <= 0d || rectangle.getHeight() <= 0d) {
                continue;
            }
            merged.add(new DetectionCandidate(candidateOutputLabels.get(bestClass), maxClassScore, rectangle));
        }

        if (merged.isEmpty()) {
            return emptyDetections();
        }

        merged.sort(Comparator.comparingDouble(DetectionCandidate::score).reversed());
        List<DetectionCandidate> kept = new ArrayList<>();
        for (DetectionCandidate candidate : merged) {
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

        List<String> names = new ArrayList<>(kept.size());
        List<Double> probabilities = new ArrayList<>(kept.size());
        List<BoundingBox> boxes = new ArrayList<>(kept.size());
        for (DetectionCandidate candidate : kept) {
            names.add(candidate.label());
            probabilities.add(candidate.score());
            boxes.add(candidate.rectangle());
        }
        return new DetectedObjects(names, probabilities, boxes);
    }

    @Override
    @Nullable
    public Batchifier getBatchifier() {
        return null;
    }

    private void loadPreprocessorConfig(Path configPath) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(configPath.toFile());
        JsonNode size = root.path("size");
        inputHeight = size.path("height").asInt(DEFAULT_INPUT_SIZE);
        inputWidth = size.path("width").asInt(DEFAULT_INPUT_SIZE);
        imageMean = readFloatArray(root.path("image_mean"), imageMean);
        imageStd = readFloatArray(root.path("image_std"), imageStd);
        rescaleFactor = (float) root.path("rescale_factor").asDouble(1d / 255d);
    }

    private void resolveCandidates() {
        List<String> source = requestedCandidates.isEmpty() ? List.of("person", "flower", "dog", "car") : requestedCandidates;
        candidateOutputLabels = new ArrayList<>();
        for (String raw : source) {
            if (StringUtils.isNotBlank(raw)) {
                candidateOutputLabels.add(raw.trim());
            }
        }
        if (candidateOutputLabels.isEmpty()) {
            throw new IllegalArgumentException("Grounding DINO          ");
        }
    }

    private void buildTextInputs() throws Exception {
        List<long[]> idsList = new ArrayList<>();
        int maxLength = 0;
        for (String candidate : candidateOutputLabels) {
            String prompt = candidate + " .";
            Encoding encoding = tokenizer.encode(prompt);
            long[] ids = encoding.getIds();
            if (ids == null || ids.length == 0) {
                throw new IllegalStateException("Grounding DINO tokenizer: " + candidate);
            }
            idsList.add(ids);
            maxLength = Math.max(maxLength, ids.length);
        }

        candidateInputIds = new long[1][maxLength];
        candidateAttentionMasks = new long[1][maxLength];
        long[] ids = idsList.get(0);
        System.arraycopy(ids, 0, candidateInputIds[0], 0, ids.length);
        Arrays.fill(candidateAttentionMasks[0], 0, ids.length, 1L);
        for (int i = ids.length; i < maxLength; i++) {
            candidateInputIds[0][i] = 0L;
            candidateAttentionMasks[0][i] = 0L;
        }
    }

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
        } finally {
            graphics.dispose();
        }
        return canvas;
    }

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

    private double calculateIoU(Rectangle first, Rectangle second) {
        double x1 = Math.max(first.getX(), second.getX());
        double y1 = Math.max(first.getY(), second.getY());
        double x2 = Math.min(first.getX() + first.getWidth(), second.getX() + second.getWidth());
        double y2 = Math.min(first.getY() + first.getHeight(), second.getY() + second.getHeight());
        double intersection = Math.max(0d, x2 - x1) * Math.max(0d, y2 - y1);
        double union = first.getWidth() * first.getHeight() + second.getWidth() * second.getHeight() - intersection;
        return union <= 0d ? 0d : intersection / union;
    }

    private double sigmoid(double value) {
        if (value >= 0d) {
            double exp = Math.exp(-value);
            return 1d / (1d + exp);
        }
        double exp = Math.exp(value);
        return exp / (1d + exp);
    }

    private double clip(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

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

    private List<String> parseCandidates(String rawCandidates) {
        if (StringUtils.isBlank(rawCandidates)) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String item : rawCandidates.split("[,|]")) {
            if (StringUtils.isNotBlank(item)) {
                values.add(item.trim());
            }
        }
        return new ArrayList<>(values);
    }

    private String readArgument(Map<String, ?> arguments, String key) {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }
        Object value = arguments.get(key);
        return value == null ? null : String.valueOf(value);
    }

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

    private Path resolveRequiredFile(Path root, String name) throws IOException {
        Path file = root.resolve(name);
        if (Files.exists(file)) {
            return file;
        }
        throw new IOException("          Grounding DINO            : " + file);
    }

    private DetectedObjects emptyDetections() {
        return new DetectedObjects(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    private record DetectionCandidate(String label, double score, Rectangle rectangle) {
    }
}
