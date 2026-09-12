package com.chua.deeplearning.support.onnx.seg;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import com.chua.deeplearning.support.ai.DetectionConfiguration;

/**
* clipseg 零样本语义分割 Translator
* <p>
* clipseg 是基于 CLIP 的零样本分割模型，通过文本描述直接对图像进行语义分割，
* 无需训练即可分割任意目标类别（如 "a person"、"a car"、"a 树" 等）。
* 适用于需要灵活指定分割类别的场景。
* </p>
* <p>
* 模型来源：huggingface.co/Xenova/clipseg-rd64-refined
* 架构：CNN + CLIP 文本编码器
* 输入：镜像 + 文本描述（通过构造方法传入）
* 输出：镜像（二值分割掩码，255=前景，0=背景）
* </p>
* <p>
* 输入流程：
* <ol>
*   <li>图像 resize 到 352x352，归一化到 [-1, 1]</li>
*   <li>文本描述用 CLIP tokenizer 编码为固定长度 77 的 token IDs</li>
*   <li>ONNX 模型输出分割掩码 logits</li>
*   <li>Sigmoid + 阈值化得到二值掩码</li>
* </ol>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class CLIPSegZeroShotSegmentationTranslator implements Translator<Image, Image> {

    /** 输入尺寸 */
    /** 输入_大小 */
    private static final int INPUT_SIZE = 352;
    /** 最大文本长度 */
    /** 最大_文本_长度 */
    private static final int MAX_TEXT_LENGTH = 77;

    /** 提示词 */
    /** 提示符 */
    private final String prompt;

    /** 分词器 */
    /** Tokenizer */
    private HuggingFaceTokenizer tokenizer;

    /** 外部掩码概率阈值（区间 (0,1)，空 表示未配置时以 logits>0 为界）。 */
    private Float probThreshold;

    /**
    * 创建 Translator（支持外部阈值覆盖）。
    *
    * @param configuration 检测配置（可空）
     */
    public CLIPSegZeroShotSegmentationTranslator(com.chua.deeplearning.support.ai.DetectionConfiguration configuration) {
        this();
        if (null != configuration) {
            float t = configuration.optFloat(com.chua.deeplearning.support.ai.DetectionConfiguration.KEY_THRESHOLD, -1f);
            if (t > 0 && t < 1) {
                this.probThreshold = t;
            }
        }
    }

    /** 创建 clipsegzeroshotsegmentationtranslator 实例 */
    public CLIPSegZeroShotSegmentationTranslator() {
        this("object");
    }

    /**
    * 创建 clipsegzeroshotsegmentationtranslator 实例
    * @param prompt 字符串
    * @param prompt 提示符
     */
    public CLIPSegZeroShotSegmentationTranslator(@Nonnull String prompt) {
        this.prompt = prompt != null ? prompt : "object";
    }

    @Override
    /** Prepare */
    public void prepare(TranslatorContext ctx) throws IOException {
        Path modelRoot = resolveModelRoot(ctx.getModel().getModelPath());
        Path tokenizerPath = findFile(modelRoot, "tokenizer.json");
        if (Files.exists(tokenizerPath)) {
            tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerPath)
                    .optPadding(true)
                    .optMaxLength(MAX_TEXT_LENGTH)
                    .build();
            log.debug("[CLIPSeg] Tokenizer loaded: {}", tokenizerPath);
        } else {
            log.warn("[CLIPSeg] tokenizer.json not found at {}", tokenizerPath);
        }
    }

    @Override
    /** 处理输入 */
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        if (tokenizer == null) {
            throw new IllegalStateException("CLIPSeg tokenizer not initialized");
        }

        NDManager manager = ctx.getNDManager();

        // 图像预处理：resize -> 归一化 [-1, 1] -> CHW -> [1, 3, H, W]
        NDArray array = input.toNDArray(manager, Image.Flag.COLOR);
        array = NDImageUtils.resize(array, INPUT_SIZE, INPUT_SIZE);
        if (!DataType.FLOAT32.equals(array.getDataType())) {
            array = array.toType(DataType.FLOAT32, false);
        }
        array = array.div(255.0f).mul(2.0f).sub(1.0f);
        array = array.transpose(2, 0, 1).expandDims(0);
        array.setName("image");

        // 文本 tokenization：编码 -> 填充/截断到 77
        Encoding encoding = tokenizer.encode(prompt);
        long[] ids = encoding.getIds();
        long[] padded = new long[MAX_TEXT_LENGTH];
        System.arraycopy(ids, 0, padded, 0, Math.min(ids.length, MAX_TEXT_LENGTH));
        NDArray text = manager.create(padded).expandDims(0);
        text.setName("text");

        log.debug("[CLIPSeg] Input: image={}, text='{}'", array.getShape(), prompt);
        return new NDList(array, text);
    }

    @Override
    /** 处理输出 */
    public Image processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray logits = list.singletonOrThrow();

        // logits: [1, 1, 352, 352] -> sigmoid -> threshold -> uint8 mask
        NDArray mask = logits.squeeze(0).squeeze(0); // [H, W]
        mask = mask.gt(probThreshold == null ? 0f
                : (float) Math.log(probThreshold / (1 - probThreshold)))
                .toType(DataType.UINT8, false);
        mask = mask.mul(255);

        return ImageFactory.getInstance().fromNDArray(mask);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }

    /**
    * 解析模型根
    *
    * @param modelPath 模型路径
    * @return resolve模型根的结果
     */
    private static Path resolveModelRoot(Path modelPath) {
        if (modelPath == null) {
            return Path.of(".");
        }
        if (Files.isRegularFile(modelPath)) {
            return modelPath.getParent();
        }
        return modelPath;
    }

    /**
    * 查找文件
    *
    * @param root 根
    * @param name 名称
    * @return find文件的结果
     */
    private static Path findFile(Path root, String name) {
        Path p = root.resolve(name);
        if (Files.exists(p)) {
            return p;
        }
        if (root.getParent() != null) {
            p = root.getParent().resolve(name);
            if (Files.exists(p)) {
                return p;
            }
        }
        return root.resolve(name);
    }
}
