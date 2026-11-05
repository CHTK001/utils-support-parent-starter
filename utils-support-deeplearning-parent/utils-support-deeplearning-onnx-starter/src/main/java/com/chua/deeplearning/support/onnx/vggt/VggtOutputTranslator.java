// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.deeplearning.support.onnx.vggt;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;
import java.util.Arrays;


/**
 * VGGT              Translator
 * <p>
 *              {@link VggtOutput}                    Image
 *           3D                             Translator
 *
 * @author CH
 * @since 2024/11/08
 */
@Slf4j
public class VggtOutputTranslator implements Translator<Image, VggtOutput> {

    private static final int INPUT_SIZE = 518;

    /**
     * 处理输入
    */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("原始图像尺寸: {}x{}", input.getWidth(), input.getHeight());
        }

        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);

        //
        array = NDImageUtils.resize(array, INPUT_SIZE, INPUT_SIZE);

        //              [0, 1]
        array = array.div(255.0f);

        // DJL     normalize        CHW
        array = array.transpose(2, 0, 1);

 // 镜像net
        float[] mean = {0.485f, 0.456f, 0.406f};
        float[] std = {0.229f, 0.224f, 0.225f};
        array = NDImageUtils.normalize(array, mean, std).expandDims(0);

        if (log.isDebugEnabled()) {
            log.debug("模型输入: shape={}", array.getShape());
        }
        return new NDList(array);
    }

    /**
     * 处理输出
    */
    @Override
    public VggtOutput processOutput(TranslatorContext ctx, NDList list) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("模型输出 {} 个张量", list.size());
        }

        if (list.isEmpty()) {
            log.warn("模型输出为空");
            return new VggtOutput(new float[0], new long[]{0});
        }

        //                      3D
        NDArray output = list.getFirst();
        long[] outputShape = output.getShape().getShape();

        log.info("VGGT 输出: shape={}, dtype={}",
                output.getShape(), output.getDataType());

        //        3D                 Java
        float[] gaussianData = output.toFloatArray();

        log.info("转 3D 高斯数据完成: {} 个 float 值, 形状: {}",
                gaussianData.length, Arrays.toString(outputShape));

 // vggt输出
        VggtOutput vggtOutput = new VggtOutput(gaussianData, outputShape);

        log.info("3D 高斯结果: {} 个高斯点, 特征维度: {}",
                vggtOutput.getNumGaussians(), vggtOutput.getFeatureDimension());

        return vggtOutput;
    }

    /**
     * 获取Batchifier
    */
    @Override
    public Batchifier getBatchifier() {
        //
        return null;
    }
}