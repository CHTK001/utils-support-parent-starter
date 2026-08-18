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

    private final OcrPipeline delegate;

    public OnnxOcrPipeline(ImageDetector detector, OcrRecognizer recognizer,
                           ITranslator<Object, Object> direction, ITranslator<Object, Object> enhancer,
                           boolean enhanceInPipeline, boolean sortReadingOrder, float minConfidence,
                           int cropPadding, int cropMinHeight, boolean sigmoidDetect, boolean sigmoidRecognize) {
        this.delegate = new OcrPipeline(detector, recognizer, direction, enhancer,
                enhanceInPipeline, sortReadingOrder, minConfidence, cropPadding, cropMinHeight,
                sigmoidDetect, sigmoidRecognize);
    }

    public String recognize(byte[] imageData) {
        return delegate.recognize(imageData);
    }

    public List<OcrResult> recognizeDetail(byte[] imageData) {
        return delegate.recognizeDetail(imageData);
    }

    public OcrPipeline.OcrRecognizeResult recognizeDetailWithImage(byte[] imageData) {
        return delegate.recognizeDetailWithImage(imageData);
    }

    public byte[] correct(byte[] imageData) {
        return delegate.correct(imageData);
    }

    public byte[] enhance(byte[] imageData) {
        return delegate.enhance(imageData);
    }

    public ImageDetector detector() {
        return delegate.detector();
    }

    public OcrRecognizer recognizer() {
        return delegate.recognizer();
    }
}