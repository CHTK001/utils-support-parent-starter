package com.chua.deeplearning.support.onnx.yolo.v11.translator;

import ai.djl.modality.cv.Image;
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
import java.util.List;
import java.util.stream.IntStream;


/**
 * yolov11 OBB
 * <p>
 * yolov11                               OBB - Oriented Bounding Box
 *                                                                                              
 * <p>
 *                
 * -                                       +        +          
 * -            probiou                 NMS
 * -                          
 * -                                
 * -     letterbox
 * <p>
 *                
 * [cx, cy, w, h, 类_scores..., angle]
 * - cx, cy:                
 * - w, h:                
 * - 类_scores:
 * - angle:                         
 * <p>
 *                
 * ```Java
 * //                   
 * 列表<String> 类 = Arrays.as列表("文本", "vehicle");
 * Yolo11oddtranslator translator = 新 Yolo11oddtranslator(类);
 *
 * //                
 * Yolo11oddtranslator translator = 新 Yolo11oddtranslator(640, 0.25f, 0.45f, 类);
 * ```
 *
 * @author CH
 * @since 2025-01-22
 */
@Slf4j
@Spi("yolo11_obb")
public class Yolo11OddTranslator implements Translator<Image, ObbResult> {

    /**
     *                      
     */
    private final int maxBoxes;

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
     *              -                   
     *
     * @param classes                   
     */
    public Yolo11OddTranslator() {
        this(defaultClasses(1024));
    }

    /**
     * 创建 Yolo11oddtranslator 实例
     * @param classes classes
     */
    public Yolo11OddTranslator(List<String> classes) {
        this(1024, 1024, 0.25f, 0.45f, classes, 8400);
    }

    /**
     *              -                
     *
     * @param width                          
     * @param height                         
     * @param threshold                   
     * @param nmsThreshold NMS       
     * @param classes                        
     */
    public Yolo11OddTranslator(int width, int height, float threshold, float nmsThreshold, List<String> classes) {
        this(width, height, threshold, nmsThreshold, classes, 8400);
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
    public Yolo11OddTranslator(int width, int height, float threshold, float nmsThreshold,
                                List<String> classes, int maxBoxes) {
        this.width = width;
        this.height = height;
        this.threshold = threshold;
        this.nmsThreshold = nmsThreshold;
        this.classes = new ArrayList<>(classes);
        this.maxBoxes = maxBoxes;
        log.info("          YOLOv11 OBB                 -             : {}x{},       : {}, NMS: {},          : {},             : {}",
                 width, height, threshold, nmsThreshold, classes.size(), maxBoxes);
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

 // letterbox
        var letterBoxResult = LetterBoxUtils.letterbox(manager, array, width, height, 114f,
                                                       LetterBoxUtils.PaddingPosition.CENTER);
        array = letterBoxResult.image;

        //        float32                 0~1
        // 55f); // HWC
        // HWC -> CHW
        array = array.toType(DataType.FLOAT32, false).div(255f);
        array = array.transpose(2, 0, 1);

        ctx.setAttachment("width", input.getWidth());
        ctx.setAttachment("height", input.getHeight());
        ctx.setAttachment("processedWidth", width);
        ctx.setAttachment("processedHeight", height);
        ctx.setAttachment("scale", letterBoxResult.r);
        ctx.setAttachment("left", letterBoxResult.left);
        ctx.setAttachment("top", letterBoxResult.top);

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
     * @param left                  padding
     * @param top                   padding
     * @param list                       
     * @return OBB             
     */
    private ObbResult processFromBoxOutput(int imageWidth, int imageHeight, int processedWidth, int processedHeight,
                                           float scale, int left, int top, NDList list) {
 // [cx, cy, w, h, 类_scores..., angle]
        var rawResult = list.getFirst();
        var reshapedResult = rawResult.transpose();
        var shape = reshapedResult.getShape();
        var buf = reshapedResult.toFloatArray();
        var numberRows = Math.toIntExact(shape.get(0)); // [P3C 四十一 豁免] 张量形状维度下标（Shape 维度数组，非集合首元素）
        var nClasses = Math.toIntExact(shape.get(1));

        //                   
        var rotatedBoxes = new ArrayList<YoloRotatedBox>();

        //                                                       
        for (int i = numberRows - 1; i >= Math.max(0, numberRows - maxBoxes); i--) {
            var index = i * nClasses;

            //                      
            var maxClassProb = -1f;
            var maxIndex = -1;
            // 1; c++) { //           4          -1     angle
            for (int c = 4; c < nClasses; c++) {
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
                var angle = buf[index + nClasses - 1];

                cx = (cx - left) / scale;
                cy = (cy - top) / scale;
                w = w / scale;
                h = h / scale;

                var rotatedBox = new YoloRotatedBox(cx, cy, w, h, angle, classes.get(maxIndex), maxClassProb);
                rotatedBoxes.add(rotatedBox);
            }
        }

        //           NMS
        var rotatedBoxList = rotatedNMS(rotatedBoxes, nmsThreshold);

        log.info("          {}                NMS    : {}   ", rotatedBoxList.size(), rotatedBoxes.size());

        return new ObbResult(rotatedBoxList);
    }

    /**
     * NMS          probiou
     *
     * @param boxes                       
     * @param iouThreshold iou
     * @return                         
     */
    private List<YoloRotatedBox> rotatedNMS(List<YoloRotatedBox> boxes, double iouThreshold) {
        var keep = new ArrayList<YoloRotatedBox>();
        var removed = new boolean[boxes.size()];

        //                      
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

                //                          NMS
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
     * @param size 大小
     */
    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }

    /**
     * defaultClasses。
     *
     * @param size 大小，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    private static List<String> defaultClasses(int size) {
        return IntStream.range(0, size)
                .mapToObj(index -> "obb-" + index)
                .toList();
    }
}


