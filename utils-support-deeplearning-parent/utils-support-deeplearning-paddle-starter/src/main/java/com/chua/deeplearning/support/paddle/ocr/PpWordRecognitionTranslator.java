package com.chua.deeplearning.support.paddle.ocr;

import ai.djl.Model;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.index.NDIndex;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import ai.djl.util.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

/**
 * PaddleOCR 文字识别 Translator。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PpWordRecognitionTranslator implements Translator<Image, String> {

    /**
     * 是否使用空格字符。
     */
    private final boolean useSpaceChar;

    /**
     * 字符表。
     */
    private List<String> table;

    /** 创建 PpWordRecognitionTranslator 实例 */
    public PpWordRecognitionTranslator() {
        this(false);
    }

    /**
     * 创建 PpWordRecognitionTranslator 实例
     * @param useSpaceChar useSpaceChar
     */
    public PpWordRecognitionTranslator(boolean useSpaceChar) {
        this.useSpaceChar = useSpaceChar;
    }

    @Override
    /** Prepare */
    public void prepare(TranslatorContext ctx) throws IOException {
        Model model = ctx.getModel();
        try (InputStream is = openVocabulary(model)) {
            table = Utils.readLines(is, true);
            table.add(0, "blank");
            if (useSpaceChar) {
                table.add(" ");
            } else {
                table.add("");
            }
        }
    }

    /** 打开Vocabulary */
    private InputStream openVocabulary(Model model) throws IOException {
        String[] candidates = {
                "ppocr_keys_v1.txt",
                "keys.txt",
                "dict.txt",
                "vocab.txt",
                "en_dict.txt"
        };
        for (String name : candidates) {
            try {
                return model.getArtifact(name).openStream();
            } catch (Exception ignored) {
                // try next
            }
        }
        throw new IOException("OCR 字典未找到，请将 keys 文件放入模型目录");
    }

    @Override
    /** 处理Output */
    public String processOutput(TranslatorContext ctx, NDList list) {
        StringBuilder sb = new StringBuilder();
        NDArray tokens = list.singletonOrThrow();
        long[] indices = tokens.get(0).argMax(1).toLongArray();
        boolean[] selection = new boolean[indices.length];
        Arrays.fill(selection, true);
        for (int i = 1; i < indices.length; i++) {
            if (indices[i] == indices[i - 1]) {
                selection[i] = false;
            }
        }
        long lastIdx = 0;
        for (int i = 0; i < indices.length; i++) {
            if (selection[i] && indices[i] > 0 && !(i > 0 && indices[i] == lastIdx)) {
                int idx = (int) indices[i];
                if (idx >= 0 && idx < table.size()) {
                    sb.append(table.get(idx));
                }
                lastIdx = indices[i];
            }
        }
        return sb.toString();
    }

    @Override
    /** 处理Input */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray img = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        int imgC = 3;
        int imgH = 48;
        int imgW = 320;
        float maxWhRatio = (float) imgW / (float) imgH;
        int h = input.getHeight();
        int w = input.getWidth();
        float whRatio = (float) w / (float) h;
        maxWhRatio = Math.max(maxWhRatio, whRatio);
        imgW = (int) (imgH * maxWhRatio);
        int resizedW;
        if (Math.ceil(imgH * whRatio) > imgW) {
            resizedW = imgW;
        } else {
            resizedW = (int) Math.ceil(imgH * whRatio);
        }
        NDArray resizedImage = NDImageUtils.resize(img, resizedW, imgH);
        resizedImage = resizedImage.transpose(2, 0, 1).toType(DataType.FLOAT32, false);
        resizedImage = resizedImage.div(255f).sub(0.5f).div(0.5f);
        NDArray paddingIm = ctx.getNDManager().zeros(new Shape(imgC, imgH, imgW), DataType.FLOAT32);
        paddingIm.set(new NDIndex(":,:,0:" + resizedW), resizedImage);
        paddingIm = paddingIm.flip(0).expandDims(0);
        return new NDList(paddingIm);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return null;
    }
}
