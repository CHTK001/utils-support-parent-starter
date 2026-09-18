package com.chua.deeplearning.support.onnx.idcard;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.translate.Translator;
import com.chua.deeplearning.support.idcard.CnIdCardParser;
import com.chua.deeplearning.support.idcard.CnIdCardResult;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.common.support.reflection.ReflectUtils;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
* 中国居民身份证识别门面
*
* <p>组合 YOLOv8 身份证检测 + PaddleOCR 文字识别 + 结构化解析，
* 从身份证图片中提取姓名、身份证号、地址等字段。</p>
*
* @author CH
* @since 4.0.0.43
* @param detectModel detect模型
* @param recModel rec模型
 */
@Slf4j
public class CnIdCardRecognizer {

    private static final CnIdCardParser PARSER = new CnIdCardParser(); // PARSER

    private final String detectModel; // detect模型
    /**
    * cnid卡片recognizer。
    * @param detectModel detect模型
    * @param recModel rec模型
    */
    private final String recModel;

    /**
     * 构造方法，创建 CnIDCardRecognizer 实例。
     */
    public CnIdCardRecognizer() {
        this("id-card-detect", "paddleocrv6-rec");
    }

    /**
     * 构造方法，创建 CnIDCardRecognizer 实例。
     *
     * @param detectModel detect模型，不允许为 null
     * @param recModel rec模型，不允许为 null
     */
    public CnIdCardRecognizer(String detectModel, String recModel) {
        this.detectModel = detectModel;
        this.recModel = recModel;
    }

    /**
    * 从身份证图片中提取结构化信息
    *
    * @param imageData 身份证正面或反面图片（JPG/PNG）
    * @return 解析结果列表（通常 1 条）
    */
    public List<CnIdCardResult> recognize(byte[] imageData) {
        List<CnIdCardResult> results = new ArrayList<>();
        try {
            DetectedObjects cards = detectCard(imageData);
            if (cards == null || cards.getNumberOfObjects() == 0) {
                log.warn("[CnIdCard] 未检测到身份证");
                return results;
            }

            BufferedImage srcImg = bytesToBufferedImage(imageData);
            List<BoundingBox> bboxes = getBoundingBoxes(cards);
            List<Double> probs = getProbabilities(cards);

            for (int i = 0; i < cards.getNumberOfObjects(); i++) {
                BoundingBox box = bboxes.get(i);
                float score = probs.get(i).floatValue();

                int w = srcImg.getWidth();
                int h = srcImg.getHeight();
                Rectangle r = box.getBounds();
                int x1 = Math.max(0, (int) (r.getX() * w));
                int y1 = Math.max(0, (int) (r.getY() * h));
                int x2 = Math.min(w, (int) (r.getX() * w + r.getWidth() * w));
                int y2 = Math.min(h, (int) (r.getY() * h + r.getHeight() * h));

                if (x2 <= x1 || y2 <= y1) {
                    continue;
                }

                BufferedImage crop = srcImg.getSubimage(x1, y1, x2 - x1, y2 - y1);
                byte[] cropped = toByteArray(crop);
                String text = recognizeText(cropped);
                if (text == null || text.isBlank()) {
                    log.warn("[CnIdCard] OCR 未识别到文字");
                    continue;
                }

                CnIdCardResult parsed = PARSER.parse(text);
                if (parsed != null && parsed.isValid()) {
                    parsed.setConfidence(score);
                    results.add(parsed);
                }
            }
        } catch (Exception e) {
            log.error("[CnIdCard] 识别失败: {}", e.getMessage(), e);
        /**
        * detect卡片。
        * @param imageData 镜像数据
        * @return detect卡片的结果
        */
        }
        return results;
    }

    /**
     * detectCard。
     *
     * @param imageData image数据，不允许为 null
     * @return DetectedObjects 对象
     */
    private DetectedObjects detectCard(byte[] imageData) {
        try {
            ITranslator<Image, DetectedObjects> detector = createTranslator(detectModel);
            if (detector == null) {
                log.warn("[CnIdCard] 未找到检测模型: {}", detectModel);
                return null;
            }
            Image image = ImageFactory.getInstance().fromInputStream(new ByteArrayInputStream(imageData));
            return detector.translate(image);
        } catch (Exception e) {
            log.error("[CnIdCard] 检测失败: {}", e.getMessage(), e);
            /**
            * recognize文本。
            * @param cropImage crop镜像
            * @return recognize文本的结果
            * @param cards 卡片
            * @param modelId 模型id
            */
            return null;
        }
    }

    /**
     * recognize文本。
     *
     * @param cropImage 方法入参 cropImage
     * @return 结果字符串
     */
    private String recognizeText(byte[] cropImage) {
        try {
            ITranslator<byte[], String> recognizer = createTranslator(recModel);
            if (recognizer == null) {
                log.warn("[CnIdCard] 未找到识别模型: {}", recModel);
                return null;
            }
            return recognizer.translate(cropImage);
        } catch (Exception e) {
            log.error("[CnIdCard] 识别失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 创建Translator。
     *
     * @param modelId 模型ID，不允许为 null
     * @return ITranslator 对象
     */
    @SuppressWarnings("unchecked")
    private <I, O> ITranslator<I, O> createTranslator(String modelId) {
        try {
            com.chua.deeplearning.support.engine.ModelRegistry.Entry entry =
                    com.chua.deeplearning.support.engine.ModelRegistry.get(modelId);
            if (entry == null) {
                return null;
            }
            return ReflectUtils.instantiate(entry.translatorClassName());
        } catch (Exception e) {
            log.error("[CnIdCard] 创建 translator 失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取BoundingBoxes。
     *
     * @param cards 方法入参 cards
     * @return 结果列表，无数据时为空列表
     * @throws Exception 当执行过程不满足前置条件时
     */
    private List<BoundingBox> getBoundingBoxes(DetectedObjects cards) throws Exception {
        List<BoundingBox> boxes = (List<BoundingBox>) ReflectUtils.getField(cards, "boundingBoxes");
        if (boxes == null) {
            throw new IllegalStateException("读取 DJL DetectedObjects.boundingBoxes 字段失败");
        }
        return boxes;
    /**
    * 获取probabilities。
    * @param cards 卡片
    * @return 获取probabilities的结果
    * @param data 数据
    * @param img img
    */
    }

    /**
     * 获取Probabilities。
     *
     * @param cards 方法入参 cards
     * @return 结果列表，无数据时为空列表
     * @throws Exception 当执行过程不满足前置条件时
     */
    private List<Double> getProbabilities(DetectedObjects cards) throws Exception {
        List<Double> probabilities = (List<Double>) ReflectUtils.getField(cards, "probabilities");
        if (probabilities == null) {
            throw new IllegalStateException("读取 DJL DetectedObjects.probabilities 字段失败");
        }
        return probabilities;
    }

    /**
     * 转为Byte数组。
     *
     * @param img 方法入参 img
     * @return 结果值
     * @throws Exception 当执行过程不满足前置条件时
     */
    private byte[] toByteArray(BufferedImage img) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    /**
     * 字节数组转为BufferedImage。
     *
     * @param data 数据，不允许为 null
     * @return BufferedImage 对象
     * @throws Exception 当执行过程不满足前置条件时
     */
    private BufferedImage bytesToBufferedImage(byte[] data) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(data));
    }
}
