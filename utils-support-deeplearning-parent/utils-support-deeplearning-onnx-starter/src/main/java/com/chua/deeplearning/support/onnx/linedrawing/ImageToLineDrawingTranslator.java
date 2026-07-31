package com.chua.deeplearning.support.onnx.linedrawing;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;


/**
 *                            ONNX          Translator
 * <p>
 *        ModelScope        {@code msocoder/image-to-line-drawing-onnx}   
 *                                                                   
 * </p>
 * <p>
 * <ul>
 *     <li>                         RGB                    {@code [-1, 1]}                {@code NCHW}       </li>
 *     <li>                                                                                 </li>
 *     <li>                                  {@code [0, 255]}                                                      </li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 2025-01-30
 */
@Slf4j
public class ImageToLineDrawingTranslator implements Translator<Image, Image> {

    /**
     *                                        
     */
    private static final int INPUT_SIZE = 512;

    /**
     *                      
     */
    private static final float NORMALIZE_MAX = 255.0f;

    /**
     *                         
     */
    private static final float DENORMALIZE_MULTIPLIER = 127.5f;

    /**
     *                                                 
     */
    private int originalWidth;

    /**
     *                                                 
     */
    private int originalHeight;

    /**
     *                                                   {@link NDList}   
     *
     * @param ctx                               {@code NDManager}          
     * @param input                                RGB          
     * @return                                            {@code [1, 3, INPUT_SIZE, INPUT_SIZE]}
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        originalWidth = input.getWidth();
        originalHeight = input.getHeight();

        if (log.isDebugEnabled()) {
            log.debug("[LineDrawing][Translator]                      ,             : {}x{}",
                    originalWidth, originalHeight);
        }

        var manager = ctx.getNDManager();

        //        NDArray   HWC   RGB   
        NDArray array = input.toNDArray(manager, Image.Flag.COLOR);

        //                            
        array = NDImageUtils.resize(array, INPUT_SIZE, INPUT_SIZE, Image.Interpolation.BILINEAR);

        //        float32                 [-1, 1]
        if (!DataType.FLOAT32.equals(array.getDataType())) {
            array = array.toType(DataType.FLOAT32, false);
        }
        array = array.div(NORMALIZE_MAX).mul(2.0f).sub(1.0f);

        // HWC -> CHW
        array = array.transpose(2, 0, 1);

        if (log.isDebugEnabled()) {
            log.debug("[LineDrawing][Translator]                ,             : {}",
                    array.getShape());
        }

        return new NDList(array);
    }

    /**
     *                                                  {@link Image}   
     *
     * @param ctx                    
     * @param list                                                                 {@code [1, C, H, W]}     {@code [C, H, W}
     * @return                                                 
     */
    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        var manager = ctx.getNDManager();
        NDArray output = list.singletonOrThrow();

        if (log.isDebugEnabled()) {
            log.debug("[LineDrawing][Translator]                      ,                   : {}",
                    output.getShape());
        }

        //        batch          NCHW -> CHW
        if (output.getShape().dimension() == 4) {
            output = output.squeeze(0);
        }

        //                                                       
        if (output.getShape().dimension() == 3) {
            long channelSize = output.getShape().get(0);
            if (channelSize == 1L) {
                //             1 x H x W -> H x W x 1
                output = output.squeeze(0).expandDims(-1);
                // BufferedImageFactory                                   3              RGB
                output = output.concat(output, -1).concat(output, -1);
            } else {
                //             C x H x W -> H x W x C
                output = output.transpose(1, 2, 0);
            }
        }

        //                 [0, 255]            
        output = output.add(1.0f).mul(DENORMALIZE_MULTIPLIER);
        output = output.clip(0.0f, NORMALIZE_MAX).toType(DataType.UINT8, false);

        //                      
        output = NDImageUtils.resize(
                output, originalWidth, originalHeight, Image.Interpolation.BILINEAR);

        if (log.isDebugEnabled()) {
            log.debug("[LineDrawing][Translator]                      ,             : {}",
                    output.getShape());
        }

        return ImageFactory.getInstance().fromNDArray(output);
    }

    /**
     *                      
     * <p>
     *              {@link Batchifier#STACK}                                                      
     * </p>
     *
     * @return                   
     */
    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
