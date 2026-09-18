package com.chua.deeplearning.support.onnx.yolo.v26.translator;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.utils.LetterBoxUtils;
import com.chua.deeplearning.support.onnx.yolo.obb.ObbResult;
import com.chua.deeplearning.support.onnx.yolo.obb.YoloRotatedBox;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
* YOLO26 OBB                
* <p>
*              YOLO26                      OBB - Oriented Bounding Box                  
*                 {@link ObbResult}                               +        +             
* <p>
*                            
* - [cx, cy, w, h, 类_scores..., angle]
* <p>
*          
* -                                      [1, 特征, boxes]     [1, boxes, 特征]
* -                       probiou              NMS
*
* @author CH
* @since 2026/01/28
 */
@Slf4j
@Spi("yolo26_obb")
public class Yolo26ObbTranslator implements Translator<Image, ObbResult> {

    /**
    *                   
    */
    private static final int DEFAULT_SIZE = 640;

    /**
    *                      
    */
    private static final float DEFAULT_THRESHOLD = 0.25f;

    /**
    *        NMS       
    */
    private static final float DEFAULT_NMS_THRESHOLD = 0.45f;

    /**
    *                            
    */
    private static final int DEFAULT_MAX_BOXES = 8400;

    /**
    *                
    */
    private final float threshold;

    /**
    * NMS       
    */
    private final float nmsThreshold;

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
    *                      
    */
    private final int maxBoxes;

    /**
    *                                           
    */
    public Yolo26ObbTranslator() {
        this(DEFAULT_SIZE, DEFAULT_SIZE, DEFAULT_THRESHOLD, DEFAULT_NMS_THRESHOLD, Arrays.asList("object"), DEFAULT_MAX_BOXES);
    }

    /**
    *                               
    *
    * @param classes                   
    */
    public Yolo26ObbTranslator(List<String> classes) {
        this(DEFAULT_SIZE, DEFAULT_SIZE, DEFAULT_THRESHOLD, DEFAULT_NMS_THRESHOLD, classes, DEFAULT_MAX_BOXES);
    }

    /**
    *                                  
    *
    * @param size                                       
    * @param threshold                   
    * @param nmsThreshold NMS       
    * @param classes                        
    */
    public Yolo26ObbTranslator(int size, float threshold, float nmsThreshold, List<String> classes) {
        this(size, size, threshold, nmsThreshold, classes, DEFAULT_MAX_BOXES);
    }

    /**
    *                   
    *
    * @param width                          
    * @param height                         
    * @param threshold                   
    * @param nmsThreshold NMS       
    * @param classes                        
    * @param maxBoxes                          
    */
    public Yolo26ObbTranslator(int width, int height, float threshold, float nmsThreshold, List<String> classes, int maxBoxes) {
        this.width = width;
        this.height = height;
        this.threshold = threshold;
        this.nmsThreshold = nmsThreshold;
        this.classes = new ArrayList<>(classes);
        this.maxBoxes = maxBoxes;
        log.info("          YOLO26 OBB                 -             : {}x{},       : {}, NMS: {},          : {},             : {}",
                width, height, threshold, nmsThreshold, this.classes.size(), maxBoxes);
    }

    /**
    *                   
    *
    * @param ctx                     
    * @param input             
    * @return              NDList
    */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        var manager = ctx.getNDManager();
        var array = input.toNDArray(manager, Image.Flag.COLOR);

        var letterBoxResult = LetterBoxUtils.letterbox(manager, array, width, height, 114f,
                LetterBoxUtils.PaddingPosition.CENTER);
        array = letterBoxResult.image;

        array = array.toType(DataType.FLOAT32, false).div(255f);
        array = array.transpose(2, 0, 1);

        ctx.setAttachment("width", input.getWidth());
        ctx.setAttachment("height", input.getHeight());
        ctx.setAttachment("processedWidth", width);
        ctx.setAttachment("processedHeight", height);
        ctx.setAttachment("scale", letterBoxResult.r);
        ctx.setAttachment("left", letterBoxResult.left);
        ctx.setAttachment("top", letterBoxResult.top);

        return new NDList(array);
    }

    /**
    *                   
    *
    * @param ctx                    
    * @param list              nd列表
    * @return OBB             
    */
    @Override
    public ObbResult processOutput(TranslatorContext ctx, NDList list) {
        var imageWidth = (Integer) ctx.getAttachment("width");
        var imageHeight = (Integer) ctx.getAttachment("height");
        var processedWidth = (Integer) ctx.getAttachment("processedWidth");
        var processedHeight = (Integer) ctx.getAttachment("processedHeight");
        var scale = (Float) ctx.getAttachment("scale");
        var left = (Integer) ctx.getAttachment("left");
        var top = (Integer) ctx.getAttachment("top");

        return processFromBoxOutput(imageWidth, imageHeight, processedWidth, processedHeight, scale, left, top, list);
    }

    /**
    *     Box                         
    *
    * @param imageWidth                        
    * @param imageHeight                       
    * @param processedWidth                          
    * @param processedHeight                         
    * @param scale                       
    * @param left                   padding
    * @param top                    padding
    * @param list                        
    * @return OBB             
    */
    private ObbResult processFromBoxOutput(int imageWidth, int imageHeight, int processedWidth, int processedHeight,
                                          float scale, int left, int top, NDList list) {
        var rawResult = list.getFirst();
        var reshaped = reshapeToBoxesFirst(rawResult);
        var shape = reshaped.getShape();
        var buf = reshaped.toFloatArray();

        var numberRows = Math.toIntExact(shape.get(0)); // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
        var nFeatures = Math.toIntExact(shape.get(1));

        var rotatedBoxes = new ArrayList<YoloRotatedBox>();
        for (int i = numberRows - 1; i >= Math.max(0, numberRows - maxBoxes); i--) {
            var index = i * nFeatures;

            var maxClassProb = -1f;
            var maxIndex = -1;
            for (int c = 4; c < nFeatures - 1; c++) {
                var classProb = buf[index + c];
                if (classProb > maxClassProb) {
                    maxClassProb = classProb;
                    maxIndex = c - 4;
                }
            }

            if (maxClassProb > threshold && maxIndex >= 0 && maxIndex < classes.size()) {
                var cx = buf[index];
                var cy = buf[index + 1];
                var w = buf[index + 2];
                var h = buf[index + 3];
                var angle = buf[index + nFeatures - 1];

                cx = (cx - left) / scale;
                cy = (cy - top) / scale;
                w = w / scale;
                h = h / scale;

                rotatedBoxes.add(new YoloRotatedBox(cx, cy, w, h, angle, classes.get(maxIndex), maxClassProb));
            }
        }

        var rotatedBoxList = rotatedNms(rotatedBoxes, nmsThreshold);
        log.info("          {}                NMS    : {}   ", rotatedBoxList.size(), rotatedBoxes.size());
        return new ObbResult(rotatedBoxList);
    }

    /**
    * reshape     [boxes, 特征]          [1, 特征, boxes]     [1, boxes, 特征]
    *
    * @param rawResult             
    * @return [boxes, 特征]
    */
    private NDArray reshapeToBoxesFirst(NDArray rawResult) {
        var shape = rawResult.getShape();
        if (shape.dimension() == 2) {
 // [boxes, 特征]     [特征, boxes]
            var boxes = shape.get(0); // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
            var features = shape.get(1);
            if (features < boxes) {
                return rawResult;
            }
            return rawResult.transpose();
        }

        if (shape.dimension() != 3) {
            throw new IllegalArgumentException("YOLO26 OBB                      : " + shape);
        }

        // [1, a, b]
        var dim1 = shape.get(1);
        var dim2 = shape.get(2);
        if (dim1 > dim2) {
            // [1, features, boxes] -> [boxes, features]
            return rawResult.squeeze(0).transpose();
        }

 // [1, boxes, 特征]
        return rawResult.squeeze(0);
    }

    /**
    * NMS          probiou
    *
    * @param boxes                       
    * @param iouThreshold iou
    * @return                         
    */
    private List<YoloRotatedBox> rotatedNms(List<YoloRotatedBox> boxes, double iouThreshold) {
        var keep = new ArrayList<YoloRotatedBox>();
        var removed = new boolean[boxes.size()];

        var sortedIndices = new ArrayList<Integer>();
        for (int i = 0; i < boxes.size(); i++) {
            sortedIndices.add(i);
        }
        sortedIndices.sort((i1, i2) -> Float.compare(boxes.get(i2).getScore(), boxes.get(i1).getScore()));

        for (var idx : sortedIndices) {
            if (removed[idx]) {
                continue;
            }

            var ibox = boxes.get(idx);
            keep.add(ibox);

            for (int j = 0; j < boxes.size(); j++) {
                if (j == idx || removed[j]) {
                    continue;
                }

                var jbox = boxes.get(j);
                if (!ibox.getClassName().equals(jbox.getClassName())) {
                    continue;
                }

                var iou = YoloRotatedBox.probiou(ibox, jbox, 1e-7);
                if (iou > iouThreshold - 1e-7) {
                    removed[j] = true;
                }
            }
        }

        return keep;
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
}

