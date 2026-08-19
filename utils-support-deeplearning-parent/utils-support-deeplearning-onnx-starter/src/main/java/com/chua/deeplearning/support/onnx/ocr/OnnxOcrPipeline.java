package com.chua.deeplearning.support.onnx.ocr;

import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.ocr.OcrPipeline;
import com.chua.deeplearning.support.ocr.OcrRecognizer;
import com.chua.deeplearning.support.ocr.OcrResult;
import com.chua.deeplearning.support.translator.ITranslator;
import java.util.List;

/**
 * ONNX OCR 管线（委托给 core 模块的 OcrPipeline）。
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
     * 创建 OnnxOcrPipeline 实例
     * @param detector detector
     * @param recognizer recognizer
     * @param direction direction
     * @param enhancer enhancer
     * @param enhanceInPipeline enhanceInPipeline
     * @param sortReadingOrder sortReadingOrder
     * @param minConfidence minConfidence
     * @param cropPadding cropPadding
     * @param cropMinHeight cropMinHeight
     * @param cropRotateThreshold cropRotateThreshold
     * @param qualityGate qualityGate
     * @param blurThreshold blurThreshold
     * @param sigmoidDetect sigmoidDetect
     * @param sigmoidRecognize sigmoidRecognize
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

    /** Recognize */
    public String recognize(byte[] imageData) {
        return delegate.recognize(imageData);
    }

    /** RecognizeDetail */
    public List<OcrResult> recognizeDetail(byte[] imageData) {
        return delegate.recognizeDetail(imageData);
    }

    /** RecognizeDetailWithImage */
    public OcrPipeline.OcrRecognizeResult recognizeDetailWithImage(byte[] imageData) {
        return delegate.recognizeDetailWithImage(imageData);
    }

    /** Correct */
    public byte[] correct(byte[] imageData) {
        return delegate.correct(imageData);
    }

    /** Enhance */
    public byte[] enhance(byte[] imageData) {
        return delegate.enhance(imageData);
    }

    /** Detector */
    public ImageDetector detector() {
        return delegate.detector();
    }

    /** Recognizer */
    public OcrRecognizer recognizer() {
        return delegate.recognizer();
    }
}