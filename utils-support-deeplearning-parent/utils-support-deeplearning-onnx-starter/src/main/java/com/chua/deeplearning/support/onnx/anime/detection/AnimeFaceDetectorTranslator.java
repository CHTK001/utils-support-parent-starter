package com.chua.deeplearning.support.onnx.anime.detection;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.LetterBoxUtils;
import com.chua.deeplearning.support.utils.NMSUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Anime Face YOLOv8 ONNX                 
 * <p>
 *       anime face detection                            
 *       YOLOv8n                       
 *       bbox + confidence -> "anime_face"                   
 * </p>
 * <p>
 *      : 640x640 RGB ImageNet normalize
 *      : [1, 3, 640, 640] -> [1, 5, 8400]
 *            5 = cx, cy, w, h, face_conf
 * </p>
 * <p>
 *      : g963302/AnimeFace_YOLOv8n (best.onnx)
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class AnimeFaceDetectorTranslator implements Translator<Image, DetectedObjects> {

    private static final String FACE_LABEL = "anime_face";
    private static final int INPUT_SIZE = 640;
    private static final float CONF_THRESHOLD = 0.25f;
    private static final float IOU_THRESHOLD = 0.45f;
    private static final int TOP_K = 300;

    private int imageWidth;
    private int imageHeight;
    private LetterBoxUtils.ResizeResult letterBoxResult;

    public AnimeFaceDetectorTranslator() {
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        imageWidth = input.getWidth();
        imageHeight = input.getHeight();

        NDManager manager = ctx.getNDManager();
        NDArray array = input.toNDArray(manager, Image.Flag.COLOR);
        letterBoxResult = LetterBoxUtils.letterbox(manager, array, INPUT_SIZE, INPUT_SIZE, 114f, LetterBoxUtils.PaddingPosition.CENTER);
        NDArray letterBox = letterBoxResult.image;

        if (!DataType.FLOAT32.equals(letterBox.getDataType())) {
            letterBox = letterBox.toType(DataType.FLOAT32, false);
        }

        letterBox = letterBox.div(255.0f).transpose(2, 0, 1).expandDims(0);
        return new NDList(letterBox);
    }

    @Override
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.singletonOrThrow();

        long[] shape = output.getShape().getShape();
        int numDets = (int) shape[2];
        int numChannels = (int) shape[1];

        output = output.squeeze(0);

        float[] data = output.toFloatArray();

        List<Float> boxList = new ArrayList<>();
        List<Float> scoreList = new ArrayList<>();
        List<Integer> indexList = new ArrayList<>();

        for (int i = 0; i < numDets; i++) {
            int offset = i * numChannels;
            float cx = data[offset];
            float cy = data[offset + 1];
            float w = data[offset + 2];
            float h = data[offset + 3];

            float maxConf = 0f;
            for (int c = 4; c < numChannels; c++) {
                float conf = data[offset + c];
                if (conf > maxConf) {
                    maxConf = conf;
                }
            }

            if (maxConf < CONF_THRESHOLD) {
                continue;
            }

            float x1 = cx - w / 2;
            float y1 = cy - h / 2;
            float x2 = cx + w / 2;
            float y2 = cy + h / 2;

            boxList.add(x1);
            boxList.add(y1);
            boxList.add(x2);
            boxList.add(y2);
            scoreList.add(maxConf);
            indexList.add(i);
        }

        if (boxList.isEmpty()) {
            return new DetectedObjects(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        NDManager manager = ctx.getNDManager();
        NDArray boxes = manager.create(
                boxList.stream().mapToDouble(Float::doubleValue).toArray(),
                new Shape(boxList.size() / 4, 4));
        NDArray scores = manager.create(
                scoreList.stream().mapToDouble(Float::doubleValue).toArray());

        int[] keep = NMSUtils.nms(boxes, scores, IOU_THRESHOLD);

        List<String> names = new ArrayList<>();
        List<Double> probs = new ArrayList<>();
        List<BoundingBox> rects = new ArrayList<>();

        int topK = Math.min(keep.length, TOP_K);
        for (int i = 0; i < topK; i++) {
            int idx = keep[i];
            float x1 = boxList.get(idx * 4);
            float y1 = boxList.get(idx * 4 + 1);
            float x2 = boxList.get(idx * 4 + 2);
            float y2 = boxList.get(idx * 4 + 3);

            float[] mapped = LetterBoxUtils.scaleCoords(
                    INPUT_SIZE, INPUT_SIZE,
                    x1, y1, x2, y2,
                    letterBoxResult, imageWidth, imageHeight);

            float rectX = Math.max(0, mapped[0]) / imageWidth;
            float rectY = Math.max(0, mapped[1]) / imageHeight;
            float rectW = Math.min(imageWidth, mapped[2]) / imageWidth - rectX;
            float rectH = Math.min(imageHeight, mapped[3]) / imageHeight - rectY;

            names.add(FACE_LABEL);
            probs.add((double) scoreList.get(idx * 4 + 3));
            rects.add(new Rectangle(rectX, rectY, rectW, rectH));
        }

        return new DetectedObjects(names, probs, rects);
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}
