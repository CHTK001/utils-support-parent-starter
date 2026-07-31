package com.chua.deeplearning.support.onnx.ocr.extractor;

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
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;


/**
 * PP-OCR                    Translator
 * <p>
 *              Translator                                                                                        
 * </p>
 *
 * @author CH
 */
@Slf4j
public class PpWordExtractorTranslator implements Translator<Image, String> {

    /**
     *                              
     */
    private List<String> table;

    /**
     *                                             
     */
    private final boolean useSpaceChar;

    /**
     *                       PredictorPool                                     
     */
    private final String batchifier;

    /**
     *              
     */
    public PpWordExtractorTranslator() {
        this(Map.of("use_space_char", true));
    }

    /**
     *              
     *
     * @param arguments             
     */
    public PpWordExtractorTranslator(Map<String, ?> arguments) {
        useSpaceChar =
                arguments.containsKey("use_space_char")
                        ? Boolean.parseBoolean(arguments.get("use_space_char").toString())
                        : true;
        batchifier = arguments.containsKey("batchifier")
                ? String.valueOf(arguments.get("batchifier"))
                : "padding";
    }

    /**
     *                                                     
     *
     * @param ctx TranslatorContext          
     * @throws IOException                
     */
    @Override
    public void prepare(TranslatorContext ctx) throws IOException {
        Model model = ctx.getModel();
        try (InputStream is = model.getArtifact("dict.txt").openStream()) {
            table = Utils.readLines(is, true);
            table.add(0, "blank");
            if (useSpaceChar) {
                table.add(" ");
                table.add(" ");
            } else {
                table.add("");
                table.add("");
            }
        }
    }

    /**
     *                                                        
     *
     * @param ctx TranslatorContext          
     * @param list NDList              
     * @return                       
     * @throws IOException                
     */
    @Override
    public String processOutput(TranslatorContext ctx, NDList list) throws IOException {
        StringBuilder sb = new StringBuilder();
        NDArray tokens = list.singletonOrThrow();
        if (tokens.getShape().dimension() == 3 && tokens.getShape().get(0) == 1) {
            tokens = tokens.squeeze(0);
        }

        long[] indices = tokens.argMax(1).toLongArray();
        boolean[] selection = new boolean[indices.length];
        Arrays.fill(selection, true);
        for (int i = 1; i < indices.length; i++) {
            if (indices[i] == indices[i - 1]) {
                selection[i] = false;
            }
        }

        //                
        //        float[] probs = new float[indices.length];
        //        for (int row = 0; row < indices.length; row++) {
        //            NDArray value = tokens.get(0).get(new NDIndex(""+ row +":" + (row + 1) +"," + indices[row] +":" + ( indices[row] + 1)));
        //            probs[row] = value.toFloatArray()[0];
        //        }

        for (int i = 0; i < indices.length; i++) {
            if (selection[i] && indices[i] > 0) {
                int idx = (int) indices[i];
                if (idx < table.size()) {
                    sb.append(table.get(idx));
                }
            }
        }
        return sb.toString();
    }

    /**
     *                                        
     *
     * @param ctx TranslatorContext          
     * @param input                       
     * @return NDList               
     */
    @Override
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
            resizedW = (int) (Math.ceil(imgH * whRatio));
        }
        NDArray resizedImage = NDImageUtils.resize(img, resizedW, imgH);
        resizedImage = resizedImage.transpose(2, 0, 1).toType(DataType.FLOAT32, false);
        resizedImage.divi(255f).subi(0.5f).divi(0.5f);
        NDArray paddingIm = ctx.getNDManager().zeros(new Shape(imgC, imgH, imgW), DataType.FLOAT32);
        paddingIm.set(new NDIndex(":,:,0:" + resizedW), resizedImage);

        paddingIm = paddingIm.flip(0);
        return new NDList(paddingIm);
    }

    /**
     *                           
     *
     * @return Batchifier          
     */
    @Override
    public Batchifier getBatchifier() {
        return Batchifier.fromString(batchifier);
    }
}
