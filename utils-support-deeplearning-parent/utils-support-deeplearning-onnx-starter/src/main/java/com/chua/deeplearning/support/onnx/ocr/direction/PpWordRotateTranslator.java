package com.chua.deeplearning.support.onnx.ocr.direction;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.index.NDIndex;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.Map;


/**
 * OCR              Translator
 * <p>
 *        SmartJavaAI     PpWordRotateTranslator          
 *                                        0      180         
 * </p>
 *
 * @author CH
 * @since 2025-01-20
 */
@Slf4j
public class PpWordRotateTranslator implements Translator<Image, DirectionInfo> {

    /**
     * OCR                                   
     */
    private static final List<String> CLASSES = Arrays.asList("No Rotate", "Rotate");

    /**
     *                              
     */
    private static final int DEFAULT_RESIZE_WIDTH = 192;

    /**
     *                              
     */
    private static final int DEFAULT_RESIZE_HEIGHT = 48;

    /**
     *                          
     */
    private final String batchifier;

    /**
     *                              
     */
    private final int resizeHeight;

    /**
     *                              
     */
    private final int resizeWidth;

    /**
     *              
     *
     * @param arguments             
     */
    public PpWordRotateTranslator(Map<String, ?> arguments) {
        batchifier = arguments.containsKey("batchifier")
                ? arguments.get("batchifier").toString()
                : "padding";

        resizeWidth = arguments.containsKey("resizeWidth")
                ? (Integer) arguments.get("resizeWidth")
                : DEFAULT_RESIZE_WIDTH;

        resizeHeight = arguments.containsKey("resizeHeight")
                ? (Integer) arguments.get("resizeHeight")
                : DEFAULT_RESIZE_HEIGHT;
    }

    /**
     *                   
     */
    public PpWordRotateTranslator() {
        this(Map.of());
    }

    /**
     *                                                        
     *
     * @param ctx TranslatorContext          
     * @param list NDList              
     * @return DirectionInfo             
     */
    @Override
    public DirectionInfo processOutput(TranslatorContext ctx, NDList list) {
        NDArray prob = list.singletonOrThrow();
        float[] res = prob.toFloatArray();
        int maxIndex = 0;
        if (res[1] > res[0]) {
            maxIndex = 1;
        }

        return new DirectionInfo(CLASSES.get(maxIndex), (double) res[maxIndex]);
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
        NDArray img = input.toNDArray(ctx.getNDManager());
        int imgC = 3;
        int imgH = resizeHeight;
        int imgW = resizeWidth;

        NDArray array = ctx.getNDManager().zeros(new Shape(imgC, imgH, imgW));

        int h = input.getHeight();
        int w = input.getWidth();
        int resizedW = 0;

        float ratio = (float) w / (float) h;
        if (Math.ceil(imgH * ratio) > imgW) {
            resizedW = imgW;
        } else {
            resizedW = (int) (Math.ceil(imgH * ratio));
        }

        img = NDImageUtils.resize(img, resizedW, imgH);

        NDArray normalized = NDImageUtils.toTensor(img).sub(0.5F).div(0.5F);
        array.set(new NDIndex(":,:,0:" + resizedW), normalized);

        return new NDList(array);
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
