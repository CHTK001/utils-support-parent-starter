package com.chua.deeplearning.support.onnx.ocr.paddlestructure;

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
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.deeplearning.support.onnx.ocr.entity.OcrBox;
import com.chua.deeplearning.support.onnx.ocr.entity.OcrItem;
import com.chua.deeplearning.support.onnx.ocr.entity.Point;
import com.chua.deeplearning.support.onnx.ocr.entity.TableStructureResult;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;


/**
 * PP-StructureV2                    Translator (ONNX)
 *
 * @author CH
 */
@Slf4j
@Spi("pp_structure_v2")
@SpiDescribe("PP-StructureV2                   ")
public class PpStructureV2Translator implements Translator<Image, TableStructureResult> {

    /** 最大长度 */
    private static final int MAX_LENGTH = 488;
    /** 高度 */
    private int height;
    /** 宽度 */
    private int width;
    /** 缩放系数 */
    private float scale = 1.0f;
    /** X 轴缩放系数 */
    private float xScale;
    /** Y 轴缩放系数 */
    private float yScale;
    /** 词典列表 */
    private List<String> dict;
    /** 起始字符串 */
    private final String begStr = "sos";
    /** 结束字符串 */
    private final String endStr = "eos";
    /** TD 分词列表 */
    private final List<String> tdToken = new ArrayList<>();

    @Override
    public void prepare(TranslatorContext ctx) throws IOException {
        Model model = ctx.getModel();
        try (InputStream is = model.getArtifact("table_structure_dict_ch.txt").openStream()) {
            dict = Utils.readLines(is, false);
            dict.add(0, begStr);
            if (dict.contains("<td>")) {
                dict.remove("<td>");
            }
            if (!dict.contains("<td></td>")) {
                dict.add("<td></td>");
            }
            dict.add(endStr);
        }

        tdToken.add("<td>");
        tdToken.add("<td");
        tdToken.add("<td></td>");
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray img = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        height = input.getHeight();
        width = input.getWidth();

        img = resizeTableImage(img, height, width, MAX_LENGTH);
        img = paddingTableImage(ctx, img, MAX_LENGTH);

        img = img.transpose(2, 0, 1).div(255).flip(0);
        img = NDImageUtils.normalize(
                img, new float[]{0.485f, 0.456f, 0.406f}, new float[]{0.229f, 0.224f, 0.225f});
        img = img.expandDims(0);
        return new NDList(img);
    }

    @Override
    public TableStructureResult processOutput(TranslatorContext ctx, NDList list) {
        NDArray bboxPreds = list.get(0);
        NDArray structureProbs = list.get(1);

        NDArray structureIdx = structureProbs.argMax(2);
        structureProbs = structureProbs.max(new int[]{2});

        List<List<String>> structureBatchList = new ArrayList<>();
        List<List<NDArray>> bboxBatchList = new ArrayList<>();
        List<List<NDArray>> resultScoreList = new ArrayList<>();

        int begIdx = dict.indexOf(begStr);
        int endIdx = dict.indexOf(endStr);

        long batchSize = structureIdx.size(0);
        for (int batchIdx = 0; batchIdx < batchSize; batchIdx++) {
            List<String> structureList = new ArrayList<>();
            List<NDArray> bboxList = new ArrayList<>();
            List<NDArray> scoreList = new ArrayList<>();

            long len = structureIdx.get(batchIdx).size();
            for (int idx = 0; idx < len; idx++) {
                int charIdx = (int) structureIdx.get(batchIdx).get(idx).toLongArray()[0];
                if (idx > 0 && charIdx == endIdx) {
                    break;
                }
                String text = dict.get(charIdx);
                if (tdToken.contains(text)) {
                    NDArray bbox = bboxPreds.get(batchIdx, idx);
                    bboxList.add(bbox);
                }
                structureList.add(text);
                scoreList.add(structureProbs.get(batchIdx, idx));
            }

            structureBatchList.add(structureList);
            bboxBatchList.add(bboxList);
            resultScoreList.add(scoreList);
        }
        List<String> structureStrList = structureBatchList.get(0);
        List<NDArray> bboxList = bboxBatchList.get(0);
        List<NDArray> scoreList = resultScoreList.get(0);

        structureStrList.add(0, "<html>");
        structureStrList.add(1, "<body>");
        structureStrList.add(2, "<table>");
        structureStrList.add("</table>");
        structureStrList.add("</body>");
        structureStrList.add("</html>");

        List<OcrItem> ocrItemList = new ArrayList<>();

        for (int i = 0; i < bboxList.size(); i++) {
            NDArray box = bboxList.get(i);
            float[] arr = new float[4];
            arr[0] = box.get(new NDIndex("0::2")).min().toFloatArray()[0];
            arr[1] = box.get(new NDIndex("1::2")).min().toFloatArray()[0];
            arr[2] = box.get(new NDIndex("0::2")).max().toFloatArray()[0];
            arr[3] = box.get(new NDIndex("1::2")).max().toFloatArray()[0];

            Point topLeft = new Point(arr[0] * xScale * width, arr[1] * yScale * height);
            Point topRight = new Point(arr[2] * xScale * width, arr[1] * yScale * height);
            Point bottomRight = new Point(arr[2] * xScale * width, arr[3] * yScale * height);
            Point bottomLeft = new Point(arr[0] * xScale * width, arr[3] * yScale * height);

            OcrBox ocrBox = new OcrBox(topLeft, topRight, bottomRight, bottomLeft);
            float score = scoreList.get(i).toFloatArray()[0];
            OcrItem item = new OcrItem();
            item.setOcrBox(ocrBox);
            item.setScore(score);
            ocrItemList.add(item);
        }
        return new TableStructureResult(ocrItemList, structureStrList);
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }

    /**
     *                         
     *
     * @param img           
     * @param height        
     * @param width         
     * @param maxLen              
     * @return                   
     */
    private NDArray resizeTableImage(NDArray img, int height, int width, int maxLen) {
        int localMax = Math.max(height, width);
        float ratio = maxLen * 1.0f / localMax;
        int resizeH = (int) (height * ratio);
        int resizeW = (int) (width * ratio);
        scale = ratio;

        if (width > height) {
            xScale = 1f;
            yScale = (float) width / (float) height;
        } else {
            xScale = (float) height / (float) width;
            yScale = 1f;
        }

        img = NDImageUtils.resize(img, resizeW, resizeH);
        return img;
    }

    /**
     *                   
     *
     * @param ctx                      
     * @param img          
     * @param maxLen             
     * @return                   
     */
    private NDArray paddingTableImage(TranslatorContext ctx, NDArray img, int maxLen) {
        NDArray paddingImg = ctx.getNDManager().zeros(new Shape(maxLen, maxLen, 3), DataType.UINT8);
        paddingImg.set(
                new NDIndex("0:" + img.getShape().get(0) + ",0:" + img.getShape().get(1) + ",:"), img);
        return paddingImg;
    }
}


