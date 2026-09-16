package com.chua.deeplearning.support.onnx.ocr.layout;

import ai.djl.modality.cv.Image;
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
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;


/**
* PP-doclayoutv2/V3        ONNX Translator
*
* @author CH
* @since 4.0.0
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
    * AWT 缩放类型。
     */
    private int scale;

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
    * @param ctx translator上下文
    * @param input                       
    * @return NDList               
     */
    @Override
    @Nonnull
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        width = input.getWidth();
        height = input.getHeight();

        Object wrapped = input.getWrappedImage();
        if (!(wrapped instanceof java.awt.image.BufferedImage bufferedImage)) {
            throw new IllegalArgumentException("不支持的图像类型: " + wrapped.getClass().getName());
        }
        scale = java.awt.image.BufferedImage.SCALE_SMOOTH;
        lowInformationInput = isLowInformationBuffered(bufferedImage);
        scoreThreshold = configuredScoreThreshold != null
                ? configuredScoreThreshold
                : resolveDefaultThreshold(ctx.getModel().getModelPath());

 // 用 AWT 缩放（ONNX Runtime 引擎的 ndarray 不支持 resize）
        java.awt.image.BufferedImage resized = ImageUtils.resize(bufferedImage, INPUT_SIZE, INPUT_SIZE, scale);
        float[] chw = toChwFloats(resized);
        NDArray image = ctx.getNDManager().create(chw, new Shape(1, 3, INPUT_SIZE, INPUT_SIZE));
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
    * @param ctx translator上下文
    * @param list nd列表
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

        NDArray rows = list.getFirst();
        if (rows == null || rows.isEmpty()) {
            return new DetectedObjects(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
        if (rows.getShape().dimension() == 3 && rows.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
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

    /**
    * Determine计算数量
    *
    * @param list 列表
    * @param rows rows
    * @return determine数量的结果
     */
    private int determineCount(NDList list, NDArray rows) {
        int maxCount = (int) rows.getShape().get(0); // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
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

    /**
    * extract阈值
    *
    * @param arguments 参数
    * @return extract阈值的结果
     */
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

    /**
    * 解析默认阈值
    *
    * @param modelPath 模型路径
    * @return resolve默认阈值的结果
     */
    private float resolveDefaultThreshold(Path modelPath) {
        String path = modelPath == null ? "" : modelPath.toString().replace('\\', '/').toLowerCase();
        if (path.contains("pp-doclayoutv3")) {
            return 0.45f;
        }
        return 0.5f;
    }

    /**
    * 是否low信息缓冲
    *
    * @param buf buf
    * @return 是否low信息缓冲的结果
     */
    private boolean isLowInformationBuffered(java.awt.image.BufferedImage buf) {
        int w = buf.getWidth();
        int h = buf.getHeight();
        if (w <= 0 || h <= 0) {
            return true;
        }
        int[] pixels = new int[w * h];
        buf.getRGB(0, 0, w, h, pixels, 0, w);
        if (pixels.length == 0) {
            return true;
        }
        double sum = 0d;
        for (int pixel : pixels) {
            sum += (pixel & 0xFF);
        }
        double mean = sum / pixels.length;
        double variance = 0d;
        for (int pixel : pixels) {
            double diff = (pixel & 0xFF) - mean;
            variance += diff * diff;
        }
        variance /= pixels.length;
        return Math.sqrt(variance) < 3d;
    }

    /**
    * 将 缓冲镜像 转换为 CHW 归一化 float 数组（RGB，除以 255）。
    *
    * @param buf 缓冲镜像
    * @return CHW 数组，长度 3 * H * W
     */
    private float[] toChwFloats(java.awt.image.BufferedImage buf) {
        int w = buf.getWidth();
        int h = buf.getHeight();
        int[] pixels = new int[w * h];
        buf.getRGB(0, 0, w, h, pixels, 0, w);
        float[] chw = new float[3 * w * h];
        int idx = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = pixels[y * w + x];
                chw[idx] = ((pixel >> 16) & 0xFF) / 255f;
                chw[idx + w * h] = ((pixel >> 8) & 0xFF) / 255f;
                chw[idx + 2 * w * h] = (pixel & 0xFF) / 255f;
                idx++;
            }
        }
        return chw;
    }

    /**
    * Clip
    *
    * @param value 值
    * @param min 最小
    * @param max 最大
    * @return clip的结果
     */
    private float clip(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
