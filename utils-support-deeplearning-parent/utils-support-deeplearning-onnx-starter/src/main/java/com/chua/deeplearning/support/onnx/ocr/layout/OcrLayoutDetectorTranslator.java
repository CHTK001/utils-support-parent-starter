package com.chua.deeplearning.support.onnx.ocr.layout;

import ai.djl.Model;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrays;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
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
import java.util.ArrayList;
import java.util.List;


/**
 * OCR                                       OCR                                 
 *
 * @author CH
 * @since 2023-10-10
 */
@Slf4j
public class OcrLayoutDetectorTranslator implements Translator<Image, DetectedObjects> {

    /**
     *                                      
     */
    private List<String> table;

    /**
     *                                                                
     */
    private NDArray scaleFactor;

    /**
     *                                                          (height, width)
     */
    private Shape inputShape;

    /**
     *                                        inputShape      
     */
    private Shape oriShape;

    /**
     *                                                                                                    
     */
    private int[] strides = new int[]{8, 16, 32, 64};

    /**
     *                                                                                           
     */
    private float scoreThreshold = 0.6f;

    /**
     *                                                             IOU                                 
     */
    private float nmsThreshold = 0.5f;

    /**
     * NMS                                                         NMS                  
     */
    private int nmsTopK = 1000;

    /**
     *                                                                                  
     */
    private int keepTopK = 100;

    /**
     *                                                 
     */
    private int width;

    /**
     *                                                 
     */
    private int height;

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
        }
    }

    /**
     *                                                   {@link NDList}   
     *
     * @param ctx TranslatorContext          
     * @param input                       
     * @return NDList               
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray img = input.toNDArray(ctx.getNDManager());
        width = input.getWidth();
        height = input.getHeight();

        //                      608x800                           
        img = NDImageUtils.resize(img, 608, 800);
        //                   (HWC to CHW)                        [0,1]
        img = img.transpose(2, 0, 1).div(255);
        //                               ImageNet                                    
        img = NDImageUtils.normalize(
                img, new float[]{0.485f, 0.456f, 0.406f}, new float[]{0.229f, 0.224f, 0.225f});
        //                                              [N, C, H, W]
        img = img.expandDims(0);

        //                    (im_scale_y, im_scale_x)                     
        scaleFactor = ctx.getNDManager().create(new float[]{800f / height, 608f / width});
        inputShape = new Shape(800, 608);
        oriShape = inputShape;

        return new NDList(img);
    }

    /**
     *                                                  {@link DetectedObjects}   
     *
     * @param ctx TranslatorContext          
     * @param list NDList              
     * @return DetectedObjects          
     */
    @Override
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        try (NDManager manager =
                     NDManager.newBaseManager(ctx.getNDManager().getDevice(), "PyTorch")) {

            //                                           4               4                           
            NDList rawBoxes = list.subNDList(4);
            //                                                                   
            NDList scores = list;
            scores.removeAll(rawBoxes);

            //                                                       reg_max      
            int dimension = rawBoxes.get(0).getShape().dimension();
            int regMax = (int) (rawBoxes.get(0).getShape().get(dimension - 1) / 4 - 1);
            //                                              [class_id, score, x1, y1, x2, y2]
            NDArray outBoxesList = manager.zeros(new Shape(0, 6));

            //                            
            List<BoundingBox> boxesList = new ArrayList<>();
            List<String> namesList = new ArrayList<>();
            List<Double> probsList = new ArrayList<>();

            //                                                                   
            NDArray bboxes = manager.zeros(new Shape(0, 4));
            NDArray confidences = manager.zeros(new Shape(0, scores.get(0).getShape().get(2)));

            //                                     4         
            for (int i = 0; i < scores.size(); i++) {
                NDArray boxDistribute = rawBoxes.get(i);
                boxDistribute = boxDistribute.squeeze();
                NDArray score = scores.get(i);
                score = score.squeeze(0);
                int stride = strides[i];

                //                                  
                float fmH = inputShape.get(0) / (float) stride;
                float fmW = inputShape.get(1) / (float) stride;

                //                   
                NDArray hRange = manager.arange(fmH);
                NDArray wRange = manager.arange(fmW);
                wRange = wRange.reshape(1, wRange.size());

                //                      
                NDArray ww = manager.zeros(new Shape(0, wRange.size()));
                for (int j = 0; j < hRange.size(); j++) {
                    ww = ww.concat(wRange, 0);
                }

                hRange = hRange.reshape(hRange.size(), 1);

                //                      
                NDArray hh = manager.zeros(new Shape(hRange.size(), 0));
                for (int j = 0; j < wRange.size(); j++) {
                    hh = hh.concat(hRange, 1);
                }

                //                      
                NDArray ctRow = hh.flatten().add(0.5f).mul(stride);
                NDArray ctCol = ww.flatten().add(0.5f).mul(stride);
                ctRow = ctRow.reshape(ctRow.size(), 1);
                ctCol = ctCol.reshape(ctCol.size(), 1);

                //                            
                NDArray center = ctCol.concat(ctRow, 1).concat(ctCol, 1).concat(ctRow, 1);

                //                                  
                NDArray regRange = manager.arange(regMax + 1);
                NDArray boxDistance = boxDistribute.reshape(-1, regMax + 1);
                boxDistance = boxDistance.softmax(1);
                boxDistance = boxDistance.mul(regRange.expandDims(0));
                boxDistance = boxDistance.sum(new int[]{1}).reshape(-1, 4);
                boxDistance = boxDistance.mul(stride);

                //       Top K         
                NDArray topkIdx = score.max(new int[]{1}).argSort(0, false);
                topkIdx = topkIdx.get(new NDIndex(":" + this.nmsTopK));
                center = center.get(topkIdx);
                score = score.get(topkIdx);
                boxDistance = boxDistance.get(topkIdx);

                //                               +                                                
                NDArray decodeBox = center
                        .add(manager.create(new int[]{-1, -1, 1, 1}).mul(boxDistance));
                bboxes = bboxes.concat(decodeBox, 0);
                confidences = confidences.concat(score, 0);
            }

            //                         
            NDArray pickedBoxProbs = manager.zeros(new Shape(0, 5));
            ArrayList<Integer> pickedLabels = new ArrayList<>();

            //                            NMS      
            for (int classIndex = 0; classIndex < confidences.getShape().get(1); classIndex++) {
                NDArray probs = confidences.get(new NDIndex(":," + classIndex));
                NDArray mask = probs.gt(this.scoreThreshold);
                probs = probs.get(mask);

                //                                                          
                if (probs.getShape().get(0) == 0) {
                    continue;
                }

                NDArray subsetBoxes = bboxes.get(mask);
                NDArray boxProbs = subsetBoxes.concat(probs.reshape(-1, 1), 1);
                boxProbs = hardNms(manager, boxProbs, this.nmsThreshold, this.keepTopK, 200);

                pickedBoxProbs = pickedBoxProbs.concat(boxProbs);
                for (int i = 0; i < boxProbs.size(0); i++) {
                    pickedLabels.add(classIndex);
                }
            }

            //                                     
            if (pickedBoxProbs.size() == 0) {
                //                      
            } else {
                //                            
                NDArray wb = warpBoxes(manager, pickedBoxProbs.get(new NDIndex(":, :4")));
                pickedBoxProbs.set(new NDIndex(":, :4"), wb);

                NDArray imScale = scaleFactor.flip(0).concat(scaleFactor.flip(0));

                pickedBoxProbs.set(
                        new NDIndex(":, :4"),
                        pickedBoxProbs.get(new NDIndex(":, :4")).div(imScale));

                //                                        
                float[] arr = new float[pickedLabels.size()];
                for (int i = 0; i < pickedLabels.size(); i++) {
                    arr[i] = pickedLabels.get(i);
                }

                int rows = pickedLabels.size();
                NDArray labels = manager.create(arr).reshape(rows, 1);
                NDArray pickedBoxProb1 = pickedBoxProbs.get(new NDIndex(":, 4")).reshape(rows, 1);
                NDArray pickedBoxProb2 = pickedBoxProbs.get(new NDIndex(":, :4"));
                NDArray outBoxes = labels.concat(pickedBoxProb1, 1).concat(pickedBoxProb2, 1);
                outBoxesList = outBoxesList.concat(outBoxes);
            }

            //                                  
            for (int i = 0; i < outBoxesList.size(0); i++) {
                NDArray dt = outBoxesList.get(i);
                float[] array = dt.toFloatArray();
                int clsId = (int) array[0];
                double score = array[1];
                String name = table.get(clsId);

                //                            
                float x = array[2] / width;
                float y = array[3] / height;
                float w = (array[4] - array[2]) / width;
                float h = (array[5] - array[3]) / height;

                Rectangle rect = new Rectangle(x, y, w, h);
                boxesList.add(rect);
                namesList.add(name);
                probsList.add(score);
            }

            return new DetectedObjects(namesList, probsList, boxesList);
        }
    }

    /**
     *                                                                            
     *
     * @param manager NDManager               NDArray      
     * @param boxes                                    [x1, y1, x2, y2]
     * @return                      
     */
    private NDArray warpBoxes(NDManager manager, NDArray boxes) {
        int width = (int) oriShape.get(1);
        int height = (int) oriShape.get(0);
        int n = (int) boxes.size(0);

        if (n > 0) {
            //                                              
            NDArray xy = manager.ones(new Shape(n * 4, 3));
            NDArray box1 = boxes.get(new NDIndex(":,0")).reshape(n, 1);
            NDArray box2 = boxes.get(new NDIndex(":,3")).reshape(n, 1);
            NDArray box3 = boxes.get(new NDIndex(":,2")).reshape(n, 1);
            NDArray box4 = boxes.get(new NDIndex(":,1")).reshape(n, 1);
            boxes = boxes.concat(box1, 1).concat(box2, 1).concat(box3, 1).concat(box4, 1);
            boxes = boxes.reshape(n * 4, 2);
            xy.set(new NDIndex(":, :2"), boxes);

            xy = xy.get(new NDIndex(":, :2")).div(xy.get(new NDIndex(":, 2:3"))).reshape(n, 8);

            //                               min/max               
            NDArray xy0 = xy.get(new NDIndex(":,0")).reshape(n, 1);
            NDArray xy2 = xy.get(new NDIndex(":,2")).reshape(n, 1);
            NDArray xy4 = xy.get(new NDIndex(":,4")).reshape(n, 1);
            NDArray xy6 = xy.get(new NDIndex(":,6")).reshape(n, 1);
            NDArray x = xy0.concat(xy2, 1).concat(xy4, 1).concat(xy6, 1);

            NDArray xy1 = xy.get(new NDIndex(":,1")).reshape(n, 1);
            NDArray xy3 = xy.get(new NDIndex(":,3")).reshape(n, 1);
            NDArray xy5 = xy.get(new NDIndex(":,5")).reshape(n, 1);
            NDArray xy7 = xy.get(new NDIndex(":,7")).reshape(n, 1);
            NDArray y = xy1.concat(xy3, 1).concat(xy5, 1).concat(xy7, 1);
            xy = x.min(new int[]{1}).concat(y.min(new int[]{1}))
                    .concat(x.max(new int[]{1})).concat(y.max(new int[]{1})).reshape(4, n).transpose();

            //                                              
            xy.set(new NDIndex(":,0"), xy.get(new NDIndex(":,0")).clip(0, width));
            xy.set(new NDIndex(":,2"), xy.get(new NDIndex(":,2")).clip(0, width));
            xy.set(new NDIndex(":,1"), xy.get(new NDIndex(":,1")).clip(0, height));
            xy.set(new NDIndex(":,3"), xy.get(new NDIndex(":,3")).clip(0, height));

            return xy;
        } else {
            return boxes;
        }
    }

    /**
     *                                                          
     *
     * @param manager        NDManager               NDArray      
     * @param boxScores                                    [x1, y1, x2, y2]
     * @param iouThreshold   IOU                                             
     * @param topK                                         
     * @param candidateSize                                                     
     * @return       NMS                     
     */
    private NDArray hardNms(NDManager manager, NDArray boxScores, float iouThreshold, int topK, int candidateSize) {
        NDArray scores = boxScores.get(new NDIndex(":, -1"));
        NDArray boxes = boxScores.get(new NDIndex(":, :-1"));
        NDArray indexes = scores.argSort();

        //                                                                            
        if (candidateSize < indexes.size()) {
            indexes = indexes.get(new NDIndex((-candidateSize) + ":"));
        }

        NDArray picked = manager.zeros(new Shape(0), DataType.INT64);

        //                      
        while (indexes.size() > 0) {
            NDArray current = indexes.get(new NDIndex("-1")).reshape(1);
            picked = picked.concat(current, 0);

            //                                                                      
            if (topK == picked.size() || indexes.size() == 1) {
                break;
            }

            NDArray currentBox = boxes.get(current);
            indexes = indexes.get(new NDIndex(":-1"));
            NDArray restBoxes = boxes.get(indexes);
            NDArray iou = iouOf(restBoxes, currentBox.expandDims(0));
            iou = iou.squeeze();

            if (iou.getShape().dimension() == 0) {
                iou = iou.reshape(1);
            }

            NDArray cutOff = iou.lte(iouThreshold);
            indexes = indexes.get(cutOff);
        }

        return boxScores.get(picked);
    }

    /**
     *                                     IOU                                                
     *
     * @param boxes0                                     [x1, y1, x2, y2]
     * @param boxes1                                     [x1, y1, x2, y2]
     * @return IOU               [0,1]      
     */
    private NDArray iouOf(NDArray boxes0, NDArray boxes1) {
        NDArray overlapLeftTop = NDArrays.maximum(
                boxes0.get(new NDIndex("..., :2")), boxes1.get(new NDIndex("..., :2")));
        NDArray overlapRightBottom = NDArrays.minimum(
                boxes0.get(new NDIndex("..., 2:")), boxes1.get(new NDIndex("..., 2:")));
        NDArray overlapArea = areaOf(overlapLeftTop, overlapRightBottom);
        NDArray area0 = areaOf(boxes0.get(new NDIndex("..., :2")), boxes0.get(new NDIndex("..., 2:")));
        NDArray area1 = areaOf(boxes1.get(new NDIndex("..., :2")), boxes1.get(new NDIndex("..., 2:")));
        return overlapArea.div(area0.add(area1).sub(overlapArea).add(Math.exp(-5)));
    }

    /**
     *                                                                      
     *
     * @param leftTop                       [x1, y1]
     * @param rightBottom                   [x2, y2]
     * @return          
     */
    private NDArray areaOf(NDArray leftTop, NDArray rightBottom) {
        NDArray hw = rightBottom.sub(leftTop).clip(0.0f, Float.MAX_VALUE);
        return hw.get(new NDIndex("..., 0")).mul(hw.get(new NDIndex("..., 1")));
    }

    /**
     *                           
     *
     * @return Batchifier          
     */
    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}
