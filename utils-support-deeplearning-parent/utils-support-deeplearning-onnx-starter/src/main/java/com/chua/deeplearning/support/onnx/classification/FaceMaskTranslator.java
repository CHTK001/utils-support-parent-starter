package com.chua.deeplearning.support.onnx.classification;

import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

/**
 * 口罩检测翻译器（SigLIP2，2 类：Face_Mask Found / Not_Found）。
 */
@Slf4j
public class FaceMaskTranslator implements Translator<Image, Classifications> {

    private static final int INPUT_SIZE = 224;
    private static final List<String> LABELS = Arrays.asList("Face_Mask Found", "Face_Mask Not_Found");

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        BufferedImage img = (BufferedImage) input.getWrappedImage();
        BufferedImage resized = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, INPUT_SIZE, INPUT_SIZE, null);
        g.dispose();

        float[] pixels = new float[3 * INPUT_SIZE * INPUT_SIZE];
        int idx = 0;
        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                int argb = resized.getRGB(x, y);
                float rv = ((argb >> 16) & 0xFF) / 255.0f;
                float gv = ((argb >> 8) & 0xFF) / 255.0f;
                float bv = (argb & 0xFF) / 255.0f;
                pixels[idx] = (rv - 0.5f) / 0.5f;
                pixels[idx + INPUT_SIZE * INPUT_SIZE] = (gv - 0.5f) / 0.5f;
                pixels[idx + 2 * INPUT_SIZE * INPUT_SIZE] = (bv - 0.5f) / 0.5f;
                idx++;
            }
        }
        NDArray array = ctx.getNDManager().create(pixels, new Shape(1, 3, INPUT_SIZE, INPUT_SIZE));
        return new NDList(array);
    }

    @Override
    public Classifications processOutput(TranslatorContext ctx, NDList list) {
        NDArray logits = list.get(0);
        float[] data = logits.toFloatArray();
        float max = Math.max(data[0], data[1]);
        float e0 = (float) Math.exp(data[0] - max);
        float e1 = (float) Math.exp(data[1] - max);
        float sum = e0 + e1;
        List<Double> probs = Arrays.asList((double) (e0 / sum), (double) (e1 / sum));
        return new Classifications(LABELS, probs);
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}