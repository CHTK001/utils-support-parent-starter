package com.chua.deeplearning.support.onnx.clip;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

/**
 * CN-CLIP 图像编码 Translator。
 *
 * <p>输入 {@code [1,3,224,224]} float32（CLIP 归一化），输出图像特征 {@code float[]}，
 * 供「以图搜图」等需要图像语义向量的场景使用。</p>
 *
 * <p><b>预处理与后处理一律避开依赖引擎实现的 NDArray 算子</b>：运行环境中 NDArray 可能落在
 * {@code ai.djl.engine.rust.RsNDArrayEx}（来自 tokenizers）或 OnnxRuntime 的 NDArrayAdapter 上，
 * 前者几乎未实现图像算子，后者需借助「替代引擎」转换而当前类路径不存在，二者都会直接抛
 * {@code UnsupportedOperationException: Not implemented}。因此：</p>
 * <ul>
 *   <li>缩放走 DJL {@link Image} API（BufferedImage 实现，与引擎无关），不使用
 *       {@code NDImageUtils.resize} / {@code centerCrop}</li>
 *   <li>归一化与 NCHW 排布在 Java 侧完成，不使用 {@code transpose} / {@code div} /
 *       {@code normalize} / {@code expandDims}</li>
 *   <li>输出不使用 {@code squeeze} / {@code reshape}，直接取扁平结果；
 *       {@code [1,N]} 与 {@code [N]} 对余弦相似度等价</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CnClipImageFeatureTranslator implements Translator<Image, float[]> {

    /**
     * 图像尺寸
     */
    private static final int IMAGE_SIZE = 224;
    /**
     * 均值数组
     */
    private static final float[] MEAN = new float[]{0.48145466f, 0.45782750f, 0.40821073f};
    /**
     * 标准差数组
     */
    private static final float[] STD = new float[]{0.26862954f, 0.26130258f, 0.27577711f};

    @Override
    /**
     * 处理输入
     *
     * @param ctx   翻译器上下文
     * @param input 输入图像
     * @return NCHW 输入张量 {@code [1,3,224,224]}
     */
    public NDList processInput(TranslatorContext ctx, Image input) {
        Image resized = input.resize(IMAGE_SIZE, IMAGE_SIZE, false);
        NDArray hwc = resized.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        if (!DataType.FLOAT32.equals(hwc.getDataType())) {
            hwc = hwc.toType(DataType.FLOAT32, false);
        }
        float[] pixels = hwc.toFloatArray();
        int h = resized.getHeight();
        int w = resized.getWidth();
        float[] nchw = new float[3 * h * w];
        int planeSize = h * w;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int hwcIndex = (y * w + x) * 3;
                int spatial = y * w + x;
                for (int c = 0; c < 3; c++) {
                    float v = pixels[hwcIndex + c];
                    if (v > 1f) {
                        v = v / 255f;
                    }
                    nchw[c * planeSize + spatial] = (v - MEAN[c]) / STD[c];
                }
            }
        }
        NDArray array = ctx.getNDManager().create(nchw, new Shape(1, 3, h, w));
        return new NDList(array);
    }

    @Override
    /**
     * 处理输出
     *
     * @param ctx  翻译器上下文
     * @param list 模型输出
     * @return 图像特征向量
     */
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.getFirst();
        if (!DataType.FLOAT32.equals(output.getDataType())) {
            output = output.toType(DataType.FLOAT32, false);
        }
        return output.toFloatArray();
    }

    @Override
    /**
     * 获取Batchifier
     *
     * @return 批处理器
     */
    public Batchifier getBatchifier() {
        return null;
    }
}
