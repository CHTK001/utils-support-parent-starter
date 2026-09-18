package com.chua.deeplearning.support.onnx.yolo.cls;

import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;


/**
* YOLO                
* <p>
*        YOLO                                     
*                                                    
* <p>
*                
* -                             
* -                                   
* -            Top-K       
* -                                
* <p>
*                   
* 1.                                  
* 2.                      
* 3.              [0, 1]
* 4.           CHW       
* <p>
*                
* <pre>{@code
* //                      224x224   
* List<String> classes = Arrays.asList("cat", "dog", "bird");
* YoloClsTranslator translator = new YoloClsTranslator(classes);
*
* //                      
* YoloClsTranslator translator = new YoloClsTranslator(224, 224, classes, 5);
* }</pre>sses, 5);
* }</pre>
*
* @author CH
* @版本 4.0.0.32
* @since 2025-01-22
 */
@Slf4j
@Spi("yolo_cls")
public class YoloClsTranslator implements Translator<Image, Classifications> {

    /**
    *                   
    */
    private final List<String> classes;

    /**
    *                   
    */
    private final int width;

    /**
    *                   
    */
    private final int height;

    /**
    * Top-K             
    */
    private final int topk;

    /**
    *              -                   
    *
    * @param classes                   
    */
    public YoloClsTranslator() {
        this(defaultClasses(1024));
    }

    /**
    *              -                   
    *
    * @param classes                   
    */
    public YoloClsTranslator(List<String> classes) {
        this(224, 224, classes, 5);
    }

    /**
    *              -                      
    *
    * @param width                     
    * @param height                    
    * @param classes                   
    * @param topk    Top-K             
    */
    public YoloClsTranslator(int width, int height, List<String> classes, int topk) {
        this.width = width;
        this.height = height;
        this.classes = new ArrayList<>(classes);
        this.topk = topk;
        log.info("          YOLO                 -             : {}x{},          : {}, Top-K: {}",
                width, height, classes.size(), topk);
    }

    /**
    *                   
    *
    * @param ctx                     
    * @param input             
    * @return              NDList
    * @throws Exception             
    */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) throws Exception {
        var manager = ctx.getNDManager();
        var array = input.toNDArray(manager, Image.Flag.COLOR);

        //                                  
        array = NDImageUtils.centerCrop(array);

        //                      
        array = NDImageUtils.resize(array, width, height);

        //        float32                 0~1
        array = array.toType(DataType.FLOAT32, false).div(255f);

        // HWC -> CHW
        array = array.transpose(2, 0, 1);

        if (log.isDebugEnabled()) {
            log.debug("                  : shape={}, dtype={}", array.getShape(), array.getDataType());
        }

        return new NDList(array);
    }

    /**
    *                   
    *
    * @param ctx                    
    * @param list              nd列表
    * @return             
    * @throws Exception             
    */
    @Override
    public Classifications processOutput(TranslatorContext ctx, NDList list) throws Exception {
        var probabilitiesNd = list.singletonOrThrow();
        if (probabilitiesNd.getShape().dimension() > 1 && probabilitiesNd.getShape().get(0) == 1) { // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            probabilitiesNd = probabilitiesNd.squeeze(0);
        }

        int classCount = (int) probabilitiesNd.size();
        List<String> outputClasses = classes.size() == classCount
                ? classes
                : defaultClasses(classCount);
        int effectiveTopK = Math.max(1, Math.min(topk, classCount));

        if (log.isDebugEnabled()) {
            log.debug("       shape: {}, dtype: {}, classCount={}",
                    probabilitiesNd.getShape(), probabilitiesNd.getDataType(), classCount);
        }

        //                                   Top-K   
        return new Classifications(outputClasses, probabilitiesNd, effectiveTopK);
    }

    /**
    *                   
    *
    * @return STACK             
    */
    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }

    /**
    *                           
    *
    * @param size                   
    * @return                       
    */
    private static List<String> defaultClasses(int size) {
        return IntStream.range(0, size)
                .mapToObj(index -> "class-" + index)
                .toList();
    }
}
