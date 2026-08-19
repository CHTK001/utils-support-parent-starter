package com.chua.deeplearning.support.onnx.resolution;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

/**
 * NAFNet ONNX             /            /                     
 *
 * <p>         :                                        384   64                HWC     CHW                       [0, 1]
 *
 * <p>         :           [0, 1]          [0, 255] uint8                                     Image
 *
 * <p>      : <a href="https://github.com/megvii-research/NAFNet">NAFNet</a>
 *
 * @author CH
 * @since 2026-05-09
 */
@Slf4j
public class NafNetTranslator implements Translator<Image, Image> {

    /**
     * NAFNet                                                                 0
     */
    private static final int MIN_SIZE = 384;

    /**
     * NAFNet
     */
    private static final int SIZE_ALIGN = 64;

    /**
     * NAFNet ONNX NDManager                                                    
     */
    private NDManager manager;

    /**
     *                                                      
     */
    private int origWidth;

    /**
     *                                                      
     */
    private int origHeight;

    @Override
    /** Prepare */
    public void prepare(TranslatorContext ctx) {
        this.manager = NDManager.newBaseManager(ctx.getNDManager().getDevice(), "OnnxRuntime");
        if (log.isDebugEnabled()) {
            log.debug("NAFNet ONNX                   ");
        }
    }

    @Override
    /** 处理Input */
    public NDList processInput(TranslatorContext ctx, Image input) {
        this.origWidth = input.getWidth();
        this.origHeight = input.getHeight();

        int targetW = alignSize(origWidth);
        int targetH = alignSize(origHeight);

        NDArray array = input.toNDArray(manager).toType(DataType.FLOAT32, false);

        //             HWC 3D             CHW               NDImageUtils     4D+batch             
        if (targetW != origWidth || targetH != origHeight) {
            array = NDImageUtils.resize(array, targetW, targetH);
        }

        // HWC     CHW                 [0, 1]
        array = array.transpose(2, 0, 1).div(255.0f);

        if (log.isDebugEnabled()) {
            log.debug("NAFNet       : {}x{} -> {}x{}, shape={}", origWidth, origHeight, targetW, targetH, array.getShape());
        }
        return new NDList(array);
    }

    @Override
    /** 处理Output */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray outputImg = list.singletonOrThrow();

        //           [0, 1]              [0, 255]
        outputImg = outputImg.clip(0.0f, 1.0f).mul(255.0f).round().toType(DataType.UINT8, false);

        //           CHW 3D: shape=[C,H,W]
        long outH = outputImg.getShape().get(1);
        long outW = outputImg.getShape().get(2);
        if ((int) outW != origWidth || (int) outH != origHeight) {
            outputImg = NDImageUtils.resize(outputImg, origWidth, origHeight);
        }

        Image img = ImageFactory.getInstance().fromNDArray(outputImg);

        if (log.isDebugEnabled()) {
            log.debug("NAFNet       : {}x{}", img.getWidth(), img.getHeight());
        }

        manager.close();
        return img;
    }

    /**
     *                                         MIN_SIZE          SIZE_ALIGN             
     *
     * @param size                       
     * @return                                
     */
    private static int alignSize(int size) {
        if (size < MIN_SIZE) {
            return MIN_SIZE;
        }
        return ((size + SIZE_ALIGN - 1) / SIZE_ALIGN) * SIZE_ALIGN;
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }
}
