package com.chua.deeplearning.support.onnx.seg;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.feature.FeatureExtractor;
import lombok.extern.slf4j.Slf4j;

/**
* SAM vit-H 编码器
* <p>
* : vietanhdev/segment-anything-onnx-模型
* sam_vit_h_4b8939.压缩  编码器.onnx
* </p>
* <p>
* : 1024x1024 BGR  镜像net normalize
* : float[256]  镜像 嵌入 (全局 avg 游泳池 从 [256,64,64])
* </p>
* <p>
* : SAM 解码器
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class SamImageEncoderTranslator implements Translator<Image, float[]> {

    /** 输入尺寸 */
    /** 输入_大小 */
    private static final int INPUT_SIZE = 1024;
    /** 均值数组 */
    /** Mean */
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    /** 标准差数组 */
    /** STD */
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDManager manager = ctx.getNDManager();
        NDArray array = input.toNDArray(manager, Image.Flag.COLOR);

        array = NDImageUtils.resize(array, INPUT_SIZE, INPUT_SIZE, Image.Interpolation.BICUBIC);

        if (!DataType.FLOAT32.equals(array.getDataType())) {
            array = array.toType(DataType.FLOAT32, false);
        }

        array = array.transpose(2, 0, 1).div(255.0f);

        NDArray mean = manager.create(MEAN, new ai.djl.ndarray.types.Shape(3, 1, 1));
        NDArray std = manager.create(STD, new ai.djl.ndarray.types.Shape(3, 1, 1));
        array = array.sub(mean).div(std);

        array = array.expandDims(0);
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        NDArray embedding = list.singletonOrThrow();

        if (embedding.getShape().dimension() == 4 && embedding.getShape().get(0) == 1) {
            embedding = embedding.squeeze(0);
        }

        long[] shape = embedding.getShape().getShape();
        if (shape.length == 3) {
            embedding = embedding.mean(new int[]{1, 2});
        } else if (shape.length == 4) {
            embedding = embedding.squeeze(0).mean(new int[]{1, 2});
        } else {
            embedding = embedding.flatten();
        }

        return embedding.toFloatArray();
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
