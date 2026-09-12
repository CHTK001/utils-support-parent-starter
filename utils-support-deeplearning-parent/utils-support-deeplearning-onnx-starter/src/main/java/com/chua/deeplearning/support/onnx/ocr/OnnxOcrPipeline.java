package com.chua.deeplearning.support.onnx.ocr;

import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.ocr.OcrPipeline;
import com.chua.deeplearning.support.ocr.OcrRecognizer;
import com.chua.deeplearning.support.ocr.OcrResult;
import com.chua.deeplearning.support.translator.ITranslator;
import java.util.List;

/**
   * ONNX OCR 管线（委托给 核心 模块的 ocrpipeline）。
 *
 * <p>保持包名兼容性，实际逻辑由 {@link com.chua.deeplearning.support.ocr.OcrPipeline} 实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OnnxOcrPipeline {

    /** 委托对象 */
    /** Delegate */
    private final OcrPipeline delegate;

    /**
      * 创建 onnxocrpipeline 实例
     * @param detector detector
     * @param recognizer recognizer
     * @param direction direction
     * @param enhancer enhancer
     * @param enhanceInPipeline 增强入pipeline
     * @param sortReadingOrder 排序读取订单
     * @param minConfidence 最小信心
     * @param cropPadding croppadding
     * @param cropMinHeight crop最小height
     * @param cropRotateThreshold croprotate阈值
     * @param qualityGate qualitygate
     * @param blurThreshold blur阈值
     * @param sigmoidDetect sigmoiddetect
     * @param sigmoidRecognize sigmoidrecognize
     */
    public OnnxOcrPipeline(ImageDetector detector, OcrRecognizer recognizer,
                           ITranslator<Object, Object> direction, ITranslator<Object, Object> enhancer,
                           boolean enhanceInPipeline, boolean sortReadingOrder, float minConfidence,
                           int cropPadding, int cropMinHeight, float cropRotateThreshold,
                           boolean qualityGate, float blurThreshold,
                           boolean sigmoidDetect, boolean sigmoidRecognize) {
        this.delegate = new OcrPipeline(detector, recognizer, direction, enhancer,
                enhanceInPipeline, sortReadingOrder, minConfidence, cropPadding, cropMinHeight,
                cropRotateThreshold, qualityGate, blurThreshold,
                sigmoidDetect, sigmoidRecognize);
    }

    /**
     * Recognize
     *
     * @param imageData 镜像数据
     * @return recognize的结果
     */
    public String recognize(byte[] imageData) {
        return delegate.recognize(imageData);
    }

    /**
     * recognizedetail
     *
     * @param imageData 镜像数据
     * @return recognizeDetail的结果
     */
    public List<OcrResult> recognizeDetail(byte[] imageData) {
        return delegate.recognizeDetail(imageData);
    }

    /**
     * recognizedetailwith镜像
     *
     * @param imageData 镜像数据
     * @return recognizedetailwith镜像的结果
     */
    public OcrPipeline.OcrRecognizeResult recognizeDetailWithImage(byte[] imageData) {
        return delegate.recognizeDetailWithImage(imageData);
    }

    /**
     * Correct
     *
     * @param imageData 镜像数据
     * @return correct的结果
     */
    public byte[] correct(byte[] imageData) {
        return delegate.correct(imageData);
    }

    /**
     * 增强
     *
     * @param imageData 镜像数据
     * @return 增强的结果
     */
    public byte[] enhance(byte[] imageData) {
        return delegate.enhance(imageData);
    }

    /**
     * Detector
     *
     * @return detector的结果
     */
    public ImageDetector detector() {
        return delegate.detector();
    }

    /**
     * Recognizer
     *
     * @return recognizer的结果
     */
    public OcrRecognizer recognizer() {
        return delegate.recognizer();
    }
}