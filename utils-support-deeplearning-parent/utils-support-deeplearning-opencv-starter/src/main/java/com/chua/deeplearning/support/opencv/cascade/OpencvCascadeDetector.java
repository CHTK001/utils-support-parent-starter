package com.chua.deeplearning.support.opencv.cascade;

import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.opencv.OpencvModelTranslator;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用 打开cv Haar/LBP 级联检测翻译器。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OpencvCascadeDetector extends OpencvModelTranslator {

    /**
     * 级联模型路径。
     */
    private final String modelPath;

    /**
     * 类别标签。
     */
    private final String label;

    /**
     * 级联分类器。
     */
    private final CascadeClassifier classifier;

    /**
     * 默认置信度。
     */
    private static final float DEFAULT_CONFIDENCE = 1.0f;

    /**
     * 缩放因子。
     */
    private final double scaleFactor;

    /**
     * 最小邻域数。
     */
    private final int minNeighbors;

    /**
     * 最小目标尺寸。
     */
    private final Size minSize;

    /**
     * 构造级联检测器。
     *
     * @param modelName   模型名称
     * @param modelPath   模型路径
     * @param label       类别标签
     * @param scaleFactor 缩放因子
     * @param minNeighbors 最小邻域
     * @param minWidth    最小宽
     * @param minHeight   最小高
     */
    public OpencvCascadeDetector(String modelName,
                                 String modelPath,
                                 String label,
                                 double scaleFactor,
                                 int minNeighbors,
                                 int minWidth,
                                 int minHeight) {
        super(modelName);
        this.modelPath = modelPath;
        this.label = label;
        this.scaleFactor = scaleFactor;
        this.minNeighbors = minNeighbors;
        this.minSize = new Size(minWidth, minHeight);
        File modelFile = resolveModelPath(modelPath);
        this.classifier = new CascadeClassifier(modelFile.getAbsolutePath());
        if (classifier.empty()) {
            throw new IllegalStateException("加载级联模型失败: " + modelFile.getAbsolutePath());
        }
        log.info("OpencvCascadeDetector 初始化完成: name={}, model={}", modelName, modelFile.getAbsolutePath());
    }

    @Override
    /**
     * 执行translate
    */
    protected Object doTranslate(Object input) {
        if (!(input instanceof byte[] imageBytes)) {
            throw new IllegalArgumentException("仅支持 byte[] 输入，实际: "
                    + (input == null ? "null" : input.getClass().getName()));
        }

        Mat src = bytesToMat(imageBytes);
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.equalizeHist(gray, gray);

            MatOfRect objects = new MatOfRect();
            classifier.detectMultiScale(gray, objects, scaleFactor, minNeighbors, 0, minSize, new Size());

            Rect[] rects = objects.toArray();
            List<PredictRectangle> results = new ArrayList<>(rects.length);
            for (Rect rect : rects) {
                results.add(new PredictRectangle(
                        (float) rect.x,
                        (float) rect.y,
                        (float) rect.width,
                        (float) rect.height,
                        DEFAULT_CONFIDENCE,
                        0,
                        label
                ));
            }
            log.info("{} 检测完成，数量={}", name(), results.size());
            return results;
        } finally {
            src.release();
            gray.release();
        }
    }
}
