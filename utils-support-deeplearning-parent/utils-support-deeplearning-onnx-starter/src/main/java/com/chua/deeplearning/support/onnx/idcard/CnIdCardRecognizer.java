package com.chua.deeplearning.support.onnx.idcard;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.translate.Translator;
import com.chua.deeplearning.support.idcard.CnIdCardParser;
import com.chua.deeplearning.support.idcard.CnIdCardResult;
import com.chua.deeplearning.support.translator.ITranslator;
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
 * <pre>{@code
 * CnIdCardRecognizer recognizer = new CnIdCardRecognizer();
 * CnIdCardResult result = recognizer.recognize(imageBytes);
 * System.out.println(result.getName());
 * System.out.println(result.getIdNumber());
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public class CnIdCardRecognizer {

    private static final CnIdCardParser PARSER = new CnIdCardParser();

    private final String detectModel;
    private final String recModel;

    public CnIdCardRecognizer() {
        this("id-card-detect", "paddleocrv6-rec");
    }

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
            // 1. 检测身份证区域
            DetectedObjects cards = detectCard(imageData);
            if (cards == null || cards.getProbabilities().isEmpty()) {
                log.warn("[CnIdCard] 未检测到身份证");
                return results;
            }

            // 2. 对每个检测到的身份证区域进行 OCR
            BufferedImage srcImg = bytesToBufferedImage(imageData);
            for (int i = 0; i < cards.getProbabilities().size(); i++) {
                Rectangle box = cards.getBoundingBoxes().get(i);
                float score = cards.getProbabilities().get(i).floatValue();

                // 裁剪身份证区域
                int w = srcImg.getWidth();
                int h = srcImg.getHeight();
                int x1 = Math.max(0, (int) (box.getX() * w));
                int y1 = Math.max(0, (int) (box.getY() * h));
                int x2 = Math.min(w, (int) (box.getX() * w + box.getWidth() * w));
                int y2 = Math.min(h, (int) (box.getY() * h + box.getHeight() * h));

                if (x2 <= x1 || y2 <= y1) continue;

                BufferedImage crop = srcImg.getSubimage(x1, y1, x2 - x1, y2 - y1);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(crop, "png", baos);
                byte[] cropped = baos.toByteArray();

                // 3. OCR 识别
                String text = recognizeText(cropped);
                if (text == null || text.isBlank()) {
                    log.warn("[CnIdCard] OCR 未识别到文字");
                    continue;
                }

                // 4. 结构化解析
                CnIdCardResult parsed = PARSER.parse(text);
                if (parsed != null && parsed.isValid()) {
                    parsed.setConfidence(score);
                    results.add(parsed);
                }
            }
        } catch (Exception e) {
            log.error("[CnIdCard] 识别失败: {}", e.getMessage(), e);
        }
        return results;
    }

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
            return null;
        }
    }

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

    @SuppressWarnings("unchecked")
    private <I, O> ITranslator<I, O> createTranslator(String modelId) {
        try {
            com.chua.deeplearning.support.engine.ModelRegistry.Entry entry =
                    com.chua.deeplearning.support.engine.ModelRegistry.get(modelId);
            if (entry == null) return null;
            return (ITranslator<I, O>) Class.forName(entry.translatorClassName())
                    .getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            log.error("[CnIdCard] 创建 translator 失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private BufferedImage bytesToBufferedImage(byte[] data) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(data));
    }
}
