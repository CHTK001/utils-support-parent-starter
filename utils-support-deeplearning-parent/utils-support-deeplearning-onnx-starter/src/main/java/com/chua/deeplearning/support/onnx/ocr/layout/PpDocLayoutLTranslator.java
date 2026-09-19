package com.chua.deeplearning.support.onnx.ocr.layout;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
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
 * PP-doclayout-L / PP-doclayout_plus-L  ONNX Translator
 *
 * <p>文档版面分析（Layout Detection），RT-DETR-L 架构，DETR 输出格式
 * {@code [class_id, score, x1, y1, x2, y2]} 行 + count 输出。
 * 根据模型路径自动识别模型规格：</p>
 * <ul>
 *     <li>{@code pp_doc_layout_l}      ：PP-DocLayout-L，输入 640×640，23 类，mAP 90.4%</li>
 *     <li>{@code pp_doc_layout_plus_l} ：PP-DocLayout_plus-L，输入 800×800，21 类，mAP 83.2%</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class PpDocLayoutLTranslator implements Translator<Image, DetectedObjects>,
        com.chua.deeplearning.support.engine.DetectionConfigurable {

    /**
     * 默认输入尺寸（640）。
     */
    private static final int DEFAULT_INPUT_SIZE = 640;

    /**
     * PP-doclayout-L 输入尺寸。
     */
    private static final int INPUT_SIZE_L = 640;

    /**
     * PP-doclayout_plus-L 输入尺寸。
     */
    private static final int INPUT_SIZE_PLUS_L = 800;

    /**
     * PP-doclayout-L 标签（23 类，与 PaddleOCR 推理.yml 一致）。
     */
    private static final List<String> LABELS_L = List.of(
            "paragraph_title",
            "image",
            "text",
            "number",
            "abstract",
            "content",
            "figure_title",
            "formula",
            "table",
            "table_title",
            "reference",
            "doc_title",
            "footnote",
            "header",
            "algorithm",
            "footer",
            "seal",
            "chart_title",
            "chart",
            "formula_number",
            "header_image",
            "footer_image",
            "aside_text"
    );

    /**
     * PP-doclayout_plus-L 标签（21 类，与 PaddleOCR 推理.yml 一致）。
     */
    private static final List<String> LABELS_PLUS_L = List.of(
            "paragraph_title",
            "image",
            "text",
            "number",
            "abstract",
            "content",
            "figure_title",
            "formula",
            "table",
            "reference",
            "doc_title",
            "footnote",
            "header",
            "algorithm",
            "footer",
            "seal",
            "chart",
            "formula_number",
            "aside_text",
            "reference_content",
            "table_title"
    );

    /**
     * 配置的分值阈值（构造参数或 configure 注入）。
     */
    private Float configuredScoreThreshold;

    /**
     * 动态分值阈值。
     */
    private float scoreThreshold;

    /**
     * 输入尺寸。
     */
    private int inputSize;

    /**
     * 类别标签列表。
     */
    private List<String> labels;

    /**
     * 原图宽度。
     */
    private int width;

    /**
     * 原图高度。
     */
    private int height;

    /**
     * AWT 缩放类型。
     */
    private int scale;

    /**
     * 是否为低信息量输入。
     */
    private boolean lowInformationInput;

    /**
     * 构造器。
     */
    public PpDocLayoutLTranslator() {
        this(Collections.emptyMap());
    }

    /**
     * 构造器。
     *
     * @param arguments 配置参数
     */
    public PpDocLayoutLTranslator(Map<String, ?> arguments) {
        this.configuredScoreThreshold = extractThreshold(arguments);
        this.scoreThreshold = 0.5f;
    }

    /**
     * 注入运行参数（阈值 等）。
     *
     * <p>由 {@link com.chua.deeplearning.support.engine.AbstractIdentificationEngine#get(String, Class, Map)}
     * 在门面调用时注入，覆盖模型默认阈值。</p>
     *
     * @param options 运行参数（阈值 / score阈值 等）
     */
    @Override
    public void configure(Map<String, Object> options) {
        if (options == null || options.isEmpty()) {
            return;
        }
        Float threshold = extractThreshold(options);
        if (threshold != null) {
            this.configuredScoreThreshold = threshold;
            this.scoreThreshold = threshold;
            log.debug("[deeplearning-engine] PP-DocLayout 阈值更新为: {}", threshold);
        }
    }

    /**
     * 将 缓冲镜像 处理为模型输入 nd列表。
     *
     * @param ctx   translator上下文
     * @param input 输入图像
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

        resolveModelConfig(ctx.getModel().getModelPath());

        scale = java.awt.image.BufferedImage.SCALE_SMOOTH;
        lowInformationInput = isLowInformationBuffered(bufferedImage);
        scoreThreshold = configuredScoreThreshold != null
                ? configuredScoreThreshold
                : resolveDefaultThreshold(ctx.getModel().getModelPath());

        float[] chw = toChwFloatsOpenCv(bufferedImage, inputSize);
        NDArray image = ctx.getNDManager().create(chw, new Shape(1, 3, inputSize, inputSize));
        image.setName("image");

        NDArray imShape = ctx.getNDManager().create(new float[][]{{inputSize, inputSize}});
        imShape.setName("im_shape");

        float scaleY = height <= 0 ? 1f : (float) inputSize / height;
        float scaleX = width <= 0 ? 1f : (float) inputSize / width;
        NDArray scaleFactor = ctx.getNDManager().create(new float[][]{{scaleY, scaleX}});
        scaleFactor.setName("scale_factor");

        return new NDList(imShape, image, scaleFactor);
    }

    /**
     * 解析模型输出为 detected对象。
     *
     * @param ctx  translator上下文
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

            String name = classId < labels.size() ? labels.get(classId) : "class_" + classId;
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
     * 获取 Batchifier。
     *
     * @return Batchifier
     */
    @Override
    @Nullable
    public Batchifier getBatchifier() {
        return null;
    }

    /**
     * 根据模型路径解析输入尺寸与标签列表。
     *
     * @param modelPath 模型路径
     */
    private void resolveModelConfig(Path modelPath) {
        String path = modelPath == null ? "" : modelPath.toString().replace('\\', '/').toLowerCase();
        if (path.contains("pp_doc_layout_plus_l") || path.contains("pp-doclayout-plus-l")) {
            this.inputSize = INPUT_SIZE_PLUS_L;
            this.labels = LABELS_PLUS_L;
        } else {
            this.inputSize = INPUT_SIZE_L;
            this.labels = LABELS_L;
        }
    }

    /**
     * 确定检测数量。
     *
     * @param list nd列表
     * @param rows 检测行
     * @return 检测数量
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
     * 从参数中提取阈值。
     *
     * @param arguments 参数
     * @return 阈值或 空
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
     * 解析默认阈值。
     *
     * @param modelPath 模型路径
     * @return 阈值
     */
    private float resolveDefaultThreshold(Path modelPath) {
        String path = modelPath == null ? "" : modelPath.toString().replace('\\', '/').toLowerCase();
        if (path.contains("pp_doc_layout_plus_l") || path.contains("pp-doclayout-plus-l")) {
            return 0.45f;
        }
        return 0.5f;
    }

    /**
     * 判断是否为低信息量图像。
     *
     * @param buf 图像
     * @return 是否为低信息量
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
     * 将 缓冲镜像 经 打开cv 缩放并转为 CHW 归一化 float 数组。
     *
     * <p>直接走 OpenCV Mat 缩放（INTER_CUBIC）并在 float 域提取像素，
     * 避免 缓冲镜像 往返的 8-钻头 量化损失，与 Python cv2.resize 路径一致。</p>
     *
     * @param buf  缓冲镜像
     * @param size 目标尺寸
     * @return CHW 数组，长度 3 * 大小 * 大小
     */
    private float[] toChwFloatsOpenCv(java.awt.image.BufferedImage buf, int size) {
        org.opencv.core.Mat src = ImageUtils.toMat(buf);
        try {
            if (src.empty()) {
                return toChwFloats(ImageUtils.resize(buf, size, size, scale));
            }
            org.opencv.core.Mat resized = new org.opencv.core.Mat();
            org.opencv.imgproc.Imgproc.resize(src, resized,
                    new org.opencv.core.Size(size, size), 0, 0, org.opencv.imgproc.Imgproc.INTER_CUBIC);
            try {
                float[] chw = new float[3 * size * size];
                int idx = 0;
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        double[] bgr = resized.get(y, x);
                        chw[idx] = (float) bgr[2] / 255f;
                        chw[idx + size * size] = (float) bgr[1] / 255f;
                        chw[idx + 2 * size * size] = (float) bgr[0] / 255f;
                        idx++;
                    }
                }
                return chw;
            } finally {
                resized.release();
            }
        } finally {
            src.release();
        }
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
     * 裁剪值到区间。
     *
     * @param value 值
     * @param min   最小值
     * @param max   最大值
     * @return 裁剪后的值
     */
    private float clip(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
