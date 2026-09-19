package com.chua.deeplearning.support.opencv.pedestrian;

import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.opencv.OpencvModelTranslator;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.HOGDescriptor;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯 打开cv HOG 行人检测翻译器。
 * <p>使用 OpenCV 内置的默认行人检测器，无需外部模型文件。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OpencvHogPedestrianDetector extends OpencvModelTranslator {

    /**
     * HOG 描述子。
     */
    private final HOGDescriptor hog;

    /**
     * 命中阈值。
     */
    private final double hitThreshold;

    /**
     * 构造行人检测器。
     */
    public OpencvHogPedestrianDetector() {
        this(0.0);
    }

    /**
     * 构造行人检测器。
     *
     * @param hitThreshold 命中阈值
     */
    public OpencvHogPedestrianDetector(double hitThreshold) {
        super("opencv-pedestrian-hog");
        this.hitThreshold = hitThreshold;
        this.hog = new HOGDescriptor();
        this.hog.setSVMDetector(HOGDescriptor.getDefaultPeopleDetector());
        log.info("OpencvHogPedestrianDetector 初始化完成");
    }

    @Override
    /**
     * 执行translate
    */
    protected Object doTranslate(Object input) {
        if (!(input instanceof byte[] imageBytes)) {
            throw new IllegalArgumentException("仅支持 byte[] 输入");
        }

        Mat src = bytesToMat(imageBytes);
        Mat resized = new Mat();
        try {
            // HOG 默认检测窗口 64x128，较大图可适度缩放加速
            double scale = 1.0;
            if (src.cols() > 800) {
                scale = 800.0 / src.cols();
                resized = ImageUtils.resize(src,
                        (int) (src.cols() * scale), (int) (src.rows() * scale), Imgproc.INTER_LINEAR);
            } else {
                resized = src;
            }

            MatOfRect found = new MatOfRect();
            MatOfDouble weights = new MatOfDouble();
            hog.detectMultiScale(
                    resized,
                    found,
                    weights,
                    hitThreshold,
                    new Size(8, 8),
                    new Size(32, 32),
                    1.05,
                    2.0,
                    false
            );

            Rect[] rects = found.toArray();
            double[] weightArr = (weights == null || weights.empty() || weights.rows() == 0)
                    ? new double[0]
                    : weights.toArray();
            List<DetectionInfo> results = new ArrayList<>(rects.length);
            for (int i = 0; i < rects.length; i++) {
                Rect rect = rects[i];
                float conf = weightArr.length > i ? (float) weightArr[i] : 1.0f;
                float x = (float) (rect.x / scale);
                float y = (float) (rect.y / scale);
                float w = (float) (rect.width / scale);
                float h = (float) (rect.height / scale);
                results.add(new DetectionInfo("person", conf, x, y, w, h));
            }

            log.info("HOG 行人检测完成，数量={}", results.size());
            return results;
        } finally {
            if (resized != src) {
                resized.release();
            }
            src.release();
        }
    }
}
