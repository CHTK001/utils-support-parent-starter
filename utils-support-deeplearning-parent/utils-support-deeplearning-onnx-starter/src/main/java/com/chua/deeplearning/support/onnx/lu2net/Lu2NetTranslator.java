package com.chua.deeplearning.support.onnx.lu2net;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.TranslatorContext;
import ai.djl.translate.Translator;

/** @作者 CH */
public class Lu2NetTranslator implements Translator<Image, Image> {
    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDManager manager = ctx.getNDManager();
        // U²-Net 类模型要求尺寸对齐，统一缩放到 320x320
 // （DJL 镜像.resize 在部分版本为 no-op，ndarray.resize 在 Rust 引擎未实现，故用 Java2D）
        java.awt.image.BufferedImage src = (java.awt.image.BufferedImage) input.getWrappedImage();
        java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(
                320, 320, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.drawImage(src, 0, 0, 320, 320, null);
        } finally {
            graphics.dispose();
        }
        Image resized = ImageFactory.getInstance().fromImage(scaled);
        NDArray array = resized.toNDArray(manager, Image.Flag.COLOR);
 // normalize 转为 [0,1]
        array = array.toType(DataType.FLOAT32, false).div(255.0f);
 // NHWC 转为 NCHW（批量 维由 pipeline 的 Batchifier 统一添加，此处不手动 expanddims）
        array = array.transpose(2, 0, 1);
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray out = list.singletonOrThrow();
 // clip 和 转换 转为 [0,255]
        out = out.clip(0, 1).mul(255).toType(DataType.UINT8, false);
 // 移除 批量 dim
        out = out.squeeze(0);
 // CHW 转为 HWC
        out = out.transpose(1, 2, 0);
        return ImageFactory.getInstance().fromNDArray(out);
    }
}

