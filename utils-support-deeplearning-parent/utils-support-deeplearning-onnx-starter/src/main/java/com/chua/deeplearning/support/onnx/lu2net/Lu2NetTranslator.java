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

/** @author CH */
public class Lu2NetTranslator implements Translator<Image, Image> {
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDManager manager = ctx.getNDManager();
        NDArray array = input.toNDArray(manager, Image.Flag.COLOR);
        // normalize to [0,1]
        array = array.toType(DataType.FLOAT32, false).div(255.0f);
        // NHWC to NCHW, assume input shape [1, 3, H, W]
        array = array.transpose(2, 0, 1).expandDims(0);
        return new NDList(array);
    }

    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray out = list.singletonOrThrow();
        // clip and convert to [0,255]
        out = out.clip(0, 1).mul(255).toType(DataType.UINT8, false);
        // remove batch dim
        out = out.squeeze(0);
        // CHW to HWC
        out = out.transpose(1, 2, 0);
        return ImageFactory.getInstance().fromNDArray(out);
    }
}

