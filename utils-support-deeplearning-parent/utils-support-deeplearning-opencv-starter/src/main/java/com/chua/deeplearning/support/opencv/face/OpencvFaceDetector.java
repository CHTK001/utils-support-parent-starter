package com.chua.deeplearning.support.opencv.face;

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
 * 纯 OpenCV 人脸检测翻译器。
 * <p>
 * 使用 OpenCV Haar 级联分类器做人脸检测，不依赖 DJL 或 ONNX Runtime。
 * 输入为图像字节数组，输出为检测到的人脸列表（{@link PredictRectangle}）。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OpencvFaceDetector extends OpencvModelTranslator {

    /**
     * Haar 级联分类器模型文件路径。
     */
    private final String modelPath;

    /**
     * OpenCV 人脸检测级联分类器。
     */
    private final CascadeClassifier classifier;

    /**
     * 检测置信度（Haar 级联无置信度输出，固定为 1.0）。
     */
    private static final float DEFAULT_CONFIDENCE = 1.0f;

    /**
     * 图像缩放因子，用于加速检测。
     */
    private static final double SCALE_FACTOR = 1.1;

    /**
     * 最小邻域数量，用于过滤误检。
     */
    private static final int MIN_NEIGHBORS = 3;

    /**
     * 最小人脸尺寸（像素）。
     */
    private static final Size MIN_FACE_SIZE = new Size(30, 30);

    /**
     * 构造人脸检测翻译器。
     *
     * @param modelPath Haar 级联分类器模型文件路径（支持文件系统路径或 classpath 路径）
     */
    public OpencvFaceDetector(String modelPath) {
        super("opencv-face-detector");
        this.modelPath = modelPath;
        File modelFile = resolveModelPath(modelPath);
        this.classifier = new CascadeClassifier(modelFile.getAbsolutePath());
        if (classifier.empty()) {
            throw new IllegalStateException("加载人脸检测模型失败: " + modelFile.getAbsolutePath());
        }
        log.info("OpencvFaceDetector 初始化完成，模型: {}", modelFile.getAbsolutePath());
    }

    /**
     * 执行人脸检测推理。
     *
     * @param input 输入对象，必须为 byte[]（图像字节数组）
     * @return 检测到的人脸列表
     */
    @Override
    protected Object doTranslate(Object input) {
        if (!(input instanceof byte[] imageBytes)) {
            throw new IllegalArgumentException("OpencvFaceDetector 仅支持 byte[] 输入，实际: "
                    + (input == null ? "null" : input.getClass().getName()));
        }

        Mat src = bytesToMat(imageBytes);
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.equalizeHist(gray, gray);

            MatOfRect faces = new MatOfRect();
            classifier.detectMultiScale(
                    gray,
                    faces,
                    SCALE_FACTOR,
                    MIN_NEIGHBORS,
                    0,
                    MIN_FACE_SIZE,
                    new Size()
            );

            Rect[] rects = faces.toArray();
            List<PredictRectangle> results = new ArrayList<>(rects.length);
            for (Rect rect : rects) {
                results.add(new PredictRectangle(
                        (float) rect.x,
                        (float) rect.y,
                        (float) rect.width,
                        (float) rect.height,
                        DEFAULT_CONFIDENCE,
                        0,
                        "face"
                ));
            }

            log.info("OpencvFaceDetector 检测完成，共检测到 {} 个人脸", results.size());
            return results;
        } finally {
            src.release();
            gray.release();
        }
    }
}
