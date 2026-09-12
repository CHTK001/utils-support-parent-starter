package com.chua.deeplearning.support.onnx.colorize;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

/**
 * 图像上色 Translator
 * <p>
 * 基于深度学习的自动上色模型，为黑白照片或灰度图像生成自然的彩色图像。
   * 大多数 ONNX 上色模型（如 deoldify、Colorize）直接输出 RGB 格式，
 * 无需 Lab 色彩空间转换，流程简洁高效。
 * </p>
 * <p>
   * 模型来源：huggingface.co/bluefoxcreation/deoldify-ONNX
 * 架构：U-Net 风格编码器-解码器
   * 输入：镜像（灰度图像）
   * 输出：镜像（彩色 RGB 图像）
 * </p>
 * <p>
 * 输入流程：
 * <ol>
 *   <li>灰度图像归一化到 [0, 1]</li>
 *   <li>ONNX 模型直接预测 RGB 三通道输出</li>
 *   <li>反归一化到 [0, 255]，转为 uint8 图像</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ImageColorizeTranslator implements Translator<Image, Image> {

    @Override
    /** 处理输入 */
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        NDManager manager = ctx.getNDManager();

        // 预处理：确保为灰度 -> 归一化 [0, 1] -> 复制三通道 -> [1, 3, H, W]
 // （嵌入式 deoldify ONNX 的输入为 NCHW 3 通道）
        NDArray gray = input.toNDArray(manager, Image.Flag.GRAYSCALE);
        if (!DataType.FLOAT32.equals(gray.getDataType())) {
            gray = gray.toType(DataType.FLOAT32, false);
        }
        gray = gray.div(255.0f);
        gray = gray.transpose(2, 0, 1);
 // 复制三通道（Rust 引擎未实现 repeat，改用 连接 拼接）
        gray = ai.djl.ndarray.NDArrays.concat(
                new NDList(gray, gray, gray), 0).get(0);
        gray = gray.expandDims(0);

        log.debug("[ImageColorize] Input: gray={}", gray.getShape());
        return new NDList(gray);
    }

    @Override
    /** 处理输出 */
    public Image processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray output = list.singletonOrThrow();
        long[] shape = output.getShape().getShape();
        log.info("[ImageColorize] Output raw shape={} dtype={}", java.util.Arrays.toString(shape), output.getDataType());

        if (shape.length == 4) {
            output = output.squeeze(0);
        }

        // 模型输出：[3, H, W] (RGB)
        // 根据首元素快速判断值域，避免全量扫描
        float firstVal = output.toFloatArray()[0];

        if (firstVal < 0.0f) {
            // [-1, 1] -> [0, 1]
            output = output.add(1.0f).div(2.0f);
        }

        output = output.transpose(1, 2, 0);
        output = output.mul(255.0f).toType(DataType.UINT8, true);

        return ImageFactory.getInstance().fromNDArray(output);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }
}
