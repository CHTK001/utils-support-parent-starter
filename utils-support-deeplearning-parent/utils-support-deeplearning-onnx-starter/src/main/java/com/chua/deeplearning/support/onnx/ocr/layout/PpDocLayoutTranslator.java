package com.chua.deeplearning.support.onnx.ocr.layout;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;


/**
 * PP-DocLayoutV2/V3        ONNX Translator
 *
 * @author CH
 */
@Slf4j
public class PpDocLayoutTranslator implements Translator<Image, DetectedObjects> {

    /**
     *              
     */
    private static final int INPUT_SIZE = 800;

    /**
     *                                      
     */
    private static final List<String> LABELS = List.of(
            "abstract",
            "algorithm",
            "aside_text",
            "chart",
            "content",
            "display_formula",
            "doc_title",
            "figure_title",
            "footer",
            "footer_image",
            "footnote",
            "formula_number",
            "header",
            "header_image",
            "image",
            "inline_formula",
            "number",
            "paragraph_title",
            "reference",
            "reference_content",
            "seal",
            "table",
            "text",
            "vertical_text",
            "vision_footnote"
    );

    /**
     *                          
     */
    private final Float configuredScoreThreshold;

    /**
     *              
     */
    private float scoreThreshold;

    /**
     *                              
     */
    private int width;

    /**
     *                              
     */
    private int height;

    /**
     *                              
     */
    private boolean lowInformationInput;

    /**
     *              
     */
    public PpDocLayoutTranslator() {
        this(Collections.emptyMap());
    }

    /**
     *              
     *
     * @param arguments                     
     */
    public PpDocLayoutTranslator(Map<String, ?> arguments) {
        this.configuredScoreThreshold = extractThreshold(arguments);
        this.scoreThreshold = 0.5f;
    }

    /**
     *                                                   {@link NDList}   
     *
     * @param ctx TranslatorContext          
     * @param input                       
     * @return NDList               
     */
    @Override
    @Nonnull
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        width = input.getWidth();
        height = input.getHeight();

        NDArray image = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        lowInformationInput = isLowInformationImage(image);
        scoreThreshold = configuredScoreThreshold != null
                ? configuredScoreThreshold
                : resolveDefaultThreshold(ctx.getModel().getModelPath());
        image = NDImageUtils.resize(image, INPUT_SIZE, INPUT_SIZE);
        if (!image.getDataType().equals(DataType.FLOAT32)) {
            image = image.toType(DataType.FLOAT32, false);
        }
        image = image.transpose(2, 0, 1).div(255f);
        image = image.expandDims(0);
        image.setName("image");

        NDArray imShape = ctx.getNDManager().create(new float[][]{{INPUT_SIZE, INPUT_SIZE}});
        imShape.setName("im_shape");

        float scaleY = height <= 0 ? 1f : (float) INPUT_SIZE / height;
        float scaleX = width <= 0 ? 1f : (float) INPUT_SIZE / width;
        NDArray scaleFactor = ctx.getNDManager().create(new float[][]{{scaleY, scaleX}});
        scaleFactor.setName("scale_factor");

        return new NDList(imShape, image, scaleFactor);
    }

    /**
     *                                                  {@link DetectedObjects}   
     *
     * @param ctx TranslatorContext          
     * @param list NDList              
     * @return DetectedObjects          
     */
    @Override
    @Nonnull
    public DetectedObjects processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        if (lowInformationInput) {
            return new DetectedObjects(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
        if (list.isEmpty()) {
            return new DetectedObjects(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }

        NDArray rows = list.get(0);
        if (rows == null || rows.isEmpty()) {
            return new DetectedObjects(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
        if (rows.getShape().dimension() == 3 && rows.getShape().get(0) == 1) {
            rows = rows.squeeze(0);
        }
        if (rows.getShape().dimension() == 1) {
            rows = rows.expandDims(0);
        }

        int rowWidth = (int) rows.getShape().get(rows.getShape().dimension() - 1);
        float[] values = rows.toFloatArray();
        int count = determineCount(list, rows);

        List<String> names = new ArrayList<>();
        List<Double> probabilities = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            int offset = i * rowWidth;
            if (offset + 5 >= values.length) {
                break;
            }

            int classId = Math.max(0, Math.round(values[offset]));
            float score = values[offset + 1];
            if (score < scoreThreshold) {
                continue;
            }

            float x1 = clip(values[offset + 2], 0f, width);
            float y1 = clip(values[offset + 3], 0f, height);
            float x2 = clip(values[offset + 4], 0f, width);
            float y2 = clip(values[offset + 5], 0f, height);
            if (x2 <= x1 || y2 <= y1 || width <= 0 || height <= 0) {
                continue;
            }

            String name = classId < LABELS.size() ? LABELS.get(classId) : "class_" + classId;
            boxes.add(new Rectangle(
                    x1 / width,
                    y1 / height,
                    (x2 - x1) / width,
                    (y2 - y1) / height
            ));
            names.add(name);
            probabilities.add((double) score);
        }

        return new DetectedObjects(names, probabilities, boxes);
    }

    /**
     *                           
     *
     * @return Batchifier          
     */
    @Override
    @Nullable
    public Batchifier getBatchifier() {
        return null;
    }

    private int determineCount(NDList list, NDArray rows) {
        int maxCount = (int) rows.getShape().get(0);
        if (list.size() < 2 || list.get(1) == null || list.get(1).isEmpty()) {
            return maxCount;
        }

        NDArray countArray = list.get(1);
        try {
            long[] values = countArray.toLongArray();
            if (values.length > 0) {
                return (int) Math.min(maxCount, Math.max(0L, values[0]));
            }
        } catch (Exception ignored) {
        }
        try {
            int[] values = countArray.toIntArray();
            if (values.length > 0) {
                return Math.min(maxCount, Math.max(0, values[0]));
            }
        } catch (Exception ignored) {
        }
        return maxCount;
    }

    private Float extractThreshold(Map<String, ?> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }
        Object value = arguments.get("threshold");
        if (value == null) {
            value = arguments.get("scoreThreshold");
        }
        if (value instanceof Number number) {
            return number.floatValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Float.parseFloat(text);
        }
        return null;
    }

    private float resolveDefaultThreshold(Path modelPath) {
        String path = modelPath == null ? "" : modelPath.toString().replace('\\', '/').toLowerCase();
        if (path.contains("pp-doclayoutv3")) {
            return 0.45f;
        }
        return 0.5f;
    }

    private boolean isLowInformationImage(NDArray image) {
        float[] values = image.toType(DataType.FLOAT32, false).toFloatArray();
        if (values.length == 0) {
            return true;
        }
        double sum = 0d;
        for (float value : values) {
            sum += value;
        }
        double mean = sum / values.length;
        double variance = 0d;
        for (float value : values) {
            double diff = value - mean;
            variance += diff * diff;
        }
        variance /= values.length;
        return Math.sqrt(variance) < 3d;
    }

    private float clip(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
