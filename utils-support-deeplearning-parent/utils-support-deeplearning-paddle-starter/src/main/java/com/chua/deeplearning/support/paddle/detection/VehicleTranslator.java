package com.chua.deeplearning.support.paddle.detection;

import ai.djl.Model;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import ai.djl.util.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 飞桨 车辆检测 Translator。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class VehicleTranslator implements Translator<Image, DetectedObjects> {

    /**
     * 原图宽。
     */
    private int width;

    /**
     * 原图高。
     */
    private int height;

    /**
     * 类别名。
     */
    private List<String> className;

    @Override
    /**
     * Prepare
    */
    public void prepare(TranslatorContext ctx) throws IOException {
        Model model = ctx.getModel();
        try (InputStream is = model.getArtifact("label_file.txt").openStream()) {
            className = Utils.readLines(is, true);
        }
    }

    @Override
    /**
     * 处理输出
    */
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        NDArray result = list.singletonOrThrow();
        float[] probabilities = result.get(":,1").toFloatArray();
        List<String> names = new ArrayList<>();
        List<Double> prob = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();
        for (int i = 0; i < probabilities.length; i++) {
            float[] array = result.get(i).toFloatArray();
            int cls = (int) array[0];
            String name = (className != null && cls >= 0 && cls < className.size())
                    ? className.get(cls) : "vehicle";
            names.add(name);
            prob.add((double) probabilities[i]);
            boxes.add(new Rectangle(
                    array[2] / width,
                    array[3] / height,
                    (array[4] - array[2]) / width,
                    (array[5] - array[3]) / height));
        }
        return new DetectedObjects(names, prob, boxes);
    }

    @Override
    /**
     * 处理输入
    */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        array = NDImageUtils.resize(array, 608, 608);
        if (!array.getDataType().equals(DataType.FLOAT32)) {
            array = array.toType(DataType.FLOAT32, false);
        }
        array = array.div(255f);
        NDArray mean = ctx.getNDManager().create(new float[]{0.485f, 0.456f, 0.406f}, new Shape(1, 1, 3));
        NDArray std = ctx.getNDManager().create(new float[]{0.229f, 0.224f, 0.225f}, new Shape(1, 1, 3));
        array = array.sub(mean).div(std).transpose(2, 0, 1).expandDims(0);
        width = input.getWidth();
        height = input.getHeight();
        NDArray imageSize = ctx.getNDManager().create(new int[]{height, width})
                .toType(DataType.FLOAT32, false).expandDims(0);
        return new NDList(array, imageSize);
    }

    @Override
    /**
     * 获取Batchifier
    */
    public Batchifier getBatchifier() {
        return null;
    }
}
