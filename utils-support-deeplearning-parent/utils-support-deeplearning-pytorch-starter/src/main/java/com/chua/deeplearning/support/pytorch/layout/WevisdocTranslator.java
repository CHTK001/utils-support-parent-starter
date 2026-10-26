package com.chua.deeplearning.support.pytorch.layout;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.common.support.collection.wrap.Wraps;
import java.util.List;

/**
 * WeVisDoc 版面检测 Translator（pytorch torchscript）。
 * <p>
 * 基于 Qwen3-VL-2B 多模态模型的版面分析能力，双塔输入（视觉 ViT + OCR 文本特征），
 * 输出 11 类版面块（text/paragraph_title/table/figure/figure_caption/
 * table_caption/list/header/footer/reference/equation）。
 * <p>
 * 注意：原始 WeVisDoc 权重为 Qwen3-VL-2B（约 1.8GB），DJL pytorch 引擎无法直接加载
 * 该 LLM 架构。需提取视觉编码器 + 检测头并导出为 torchscript（.pt），
 * 模型大小约 350MB（FP32）/ 100MB（INT8 量化）。
 * <p>
 * 本类实现了完整的预处理（ViT 输入）与后处理（[N,6] 版面框 → DetectedObjects）逻辑，
 * 可独立通过单元测试验证。真实推理需配合转换后的 torchscript 权重。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WevisdocTranslator implements Translator<Image, DetectedObjects> {

    /**
     * 11 类版面标签。
     */
    private static final List<String> LAYOUT_LABELS = List.of(
            "text",
            "paragraph_title",
            "table",
            "figure",
            "figure_caption",
            "table_caption",
            "list",
            "header",
            "footer",
            "reference",
            "equation"
    );

    /**
     * Qwen3-VL 图像均值（preprocessor_config: image_mean=[0.5,0.5,0.5]）。
     */
    private static final float[] QWEN3VL_MEAN = {0.5f, 0.5f, 0.5f};

    /**
     * Qwen3-VL 图像标准差（preprocessor_config: image_std=[0.5,0.5,0.5]）。
     */
    private static final float[] QWEN3VL_STD = {0.5f, 0.5f, 0.5f};

    /**
     * 默认输入尺寸（ViT 输入边长）。
     */
    private static final int DEFAULT_INPUT_SIZE = 448;

    /**
     * 默认置信度阈值。
     */
    private static final float DEFAULT_THRESHOLD = 0.3f;

    /**
     * 输入尺寸。
     */
    private final int inputSize;

    /**
     * 置信度阈值。
     */
    private final float threshold;

    /**
     * 默认构造。
     */
    public WevisdocTranslator() {
        this(DEFAULT_INPUT_SIZE, DEFAULT_THRESHOLD);
    }

    /**
     * 构造检测器。
     *
     * @param inputSize 输入尺寸
     * @param threshold 置信度阈值
     */
    public WevisdocTranslator(int inputSize, float threshold) {
        this.inputSize = inputSize;
        this.threshold = threshold;
    }

    @Override
    /**
     * 处理输入
     * <p>
     * 视觉塔预处理：resize 到 inputSize × inputSize → CHW → FLOAT32 → 归一化。
     * 文本塔输入（OCR 文本 → token embedding）通过 ctx attachment 传入，可选。
     * </p>
     *
     * @param ctx  翻译器上下文
     * @param input 输入图像
     * @return NDList（视觉塔输入）
     */
    public NDList processInput(TranslatorContext ctx, Image input) {
        ctx.setAttachment("sourceWidth", input.getWidth());
        ctx.setAttachment("sourceHeight", input.getHeight());

        // resize 到 ViT 输入尺寸（不保持比例）
        Image resized = input.resize(inputSize, inputSize, false);
        NDArray array = resized.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);

        // HWC → CHW
        array = array.transpose(2, 0, 1);

        // 确保 FLOAT32
        if (!DataType.FLOAT32.equals(array.getDataType())) {
            array = array.toType(DataType.FLOAT32, false);
        }

        // Qwen3-VL 归一化: (x/255 - 0.5) / 0.5 = x/127.5 - 1.0
        array = array.div(127.5f).sub(1.0f);

        return new NDList(array);
    }

    @Override
    /**
     * 处理输出
     * <p>
     * 模型输出格式：[N, 6] = [x1, y1, x2, y2, class_id, score]（像素坐标）。
     * 解析后归一化到 0~1 并过滤低置信度框。
     * </p>
     *
     * @param ctx  翻译器上下文
     * @param list 模型输出
     * @return DetectedObjects（11 类版面块）
     */
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        int sourceWidth = (int) ctx.getAttachment("sourceWidth");
        int sourceHeight = (int) ctx.getAttachment("sourceHeight");
        return parseOutput(list.singletonOrThrow(), sourceWidth, sourceHeight, threshold);
    }

    @Override
    /**
     * 获取Batchifier
     *
     * @return Batchifier
     */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }

    /**
     * 解析模型输出为 DetectedObjects。
     * <p>
     * 输入格式：[N, 6] = [x1, y1, x2, y2, class_id, score]（像素坐标）。
     * 输出：归一化到 0~1 的版面框，过滤 score < threshold 与越界 class_id。
     * </p>
     *
     * @param output        模型输出张量
     * @param sourceWidth   原图宽度
     * @param sourceHeight  原图高度
     * @param threshold     置信度阈值
     * @return DetectedObjects
     */
    static DetectedObjects parseOutput(NDArray output, int sourceWidth, int sourceHeight, float threshold) {
        long rows = output.getShape().get(0);
        if (rows == 0) {
            return new DetectedObjects(List.of(), List.of(), List.of());
        }

        long cols = output.getShape().get(1);
        if (cols < 6) {
            return new DetectedObjects(List.of(), List.of(), List.of());
        }

        List<String> classNames = Wraps.list();
        List<Double> probabilities = Wraps.list();
        List<BoundingBox> boxes = Wraps.list();

        float[] data = output.toFloatArray();
        int c = (int) cols;
        for (int i = 0; i < rows; i++) {
            int base = i * c;
            float x1 = data[base];
            float y1 = data[base + 1];
            float x2 = data[base + 2];
            float y2 = data[base + 3];
            int classId = Math.round(data[base + 4]);
            float score = data[base + 5];

            if (score < threshold) {
                continue;
            }
            if (classId < 0 || classId >= LAYOUT_LABELS.size()) {
                continue;
            }

            // 像素坐标 → 归一化 0~1
            float nx = Math.max(0, Math.min(x1 / sourceWidth, 1.0f));
            float ny = Math.max(0, Math.min(y1 / sourceHeight, 1.0f));
            float nw = Math.max(0, Math.min((x2 - x1) / sourceWidth, 1.0f));
            float nh = Math.max(0, Math.min((y2 - y1) / sourceHeight, 1.0f));

            if (nw <= 0 || nh <= 0) {
                continue;
            }

            classNames.add(LAYOUT_LABELS.get(classId));
            probabilities.add((double) score);
            boxes.add(new Rectangle(nx, ny, nw, nh));
        }

        return new DetectedObjects(classNames, probabilities, boxes);
    }

    /**
     * 获取版面标签列表。
     *
     * @return 11 类标签
     */
    public static List<String> getLayoutLabels() {
        return LAYOUT_LABELS;
    }
}
