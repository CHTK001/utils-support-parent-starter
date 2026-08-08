package com.chua.deeplearning.support.onnx.face;

import ai.djl.Model;
import ai.djl.inference.Predictor;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.DetectedObjects;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.onnx.OnnxModelRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;

/**
 * FacePlugin ONNX SDK 端到端验证测试。
 *
 * <p>验证链路：classpath 加载 ONNX 模型 → 人脸检测 → 特征提取 → 相似度比较。</p>
 *
 * <p>使用方法：
 * <pre>
 *   mvn -pl utils-support-deeplearning-parent/utils-support-deeplearning-onnx-starter test -Dtest=FacePluginSdkTest
 * </pre>
 * 需保证 utils-support-models-faceplugin 的模型资源在 classpath（依赖已配置）。
 * 测试图片取自 faceplugin-sdk/test 目录（1.jpg、2.png）。
 * </p>
 *
 * @author CH
 * @since 2026-08-08
 */
public class FacePluginSdkTest {

    private static final Logger log = LoggerFactory.getLogger(FacePluginSdkTest.class);

    /**
     * 测试图片目录
     */
    private static final String TEST_IMAGE_DIR = "D:/ch/project/faceplugin-sdk/test";

    /**
     * 相似度阈值
     */
    private static final float THRESHOLD = 75.0f;

    public static void main(String[] args) throws Exception {
        new OnnxModelRegistrar().register(null);
        ModelRegistry.discoverAll();

        testDetect();
        testFeatureAndSimilarity();
    }

    /**
     * 人脸检测验证
     */
    private static void testDetect() throws Exception {
        log.info("================ 人脸检测测试 ================");
        Path modelPath = ModelRegistry.resolveModelPath("faceplugin-face-detect-slim");
        log.info("检测模型路径: {}", modelPath);
        if (!Files.exists(modelPath)) {
            log.error("检测模型不存在: {}", modelPath);
            return;
        }

        Path imgPath = Paths.get(TEST_IMAGE_DIR, "1.jpg");
        if (!Files.exists(imgPath)) {
            log.error("测试图片不存在: {}", imgPath);
            return;
        }
        Image image = ImageFactory.getInstance().fromFile(imgPath);

        try (Model model = Model.newInstance("faceplugin-face-detect-slim", "OnnxRuntime")) {
            model.load(modelPath, "face_detect_slim");
            try (Predictor<Image, DetectedObjects> predictor =
                     model.newPredictor(new FacePluginDetectTranslator())) {
                DetectedObjects detected = predictor.predict(image);
                log.info("检测到 {} 个人脸", detected.getNumberOfObjects());
                for (int i = 0; i < detected.getNumberOfObjects(); i++) {
                    var item = detected.item(i);
                    if (item instanceof ai.djl.modality.cv.output.DetectedObjects.DetectedObject d) {
                        log.info("  {} confidence={} bbox={}",
                                d.getClassName(), d.getProbability(), d.getBoundingBox());
                    }
                }
            }
        }
    }

    /**
     * 特征提取 + 相似度验证
     */
    private static void testFeatureAndSimilarity() throws Exception {
        log.info("================ 人脸特征提取 + 相似度测试 ================");
        Path featPath = ModelRegistry.resolveModelPath("faceplugin-face-feature");
        log.info("特征模型路径: {}", featPath);
        if (!Files.exists(featPath)) {
            log.error("特征模型不存在: {}", featPath);
            return;
        }

        Path img1Path = Paths.get(TEST_IMAGE_DIR, "1.jpg");
        Path img2Path = Paths.get(TEST_IMAGE_DIR, "2.png");
        if (!Files.exists(img1Path) || !Files.exists(img2Path)) {
            log.error("测试图片不存在: {} / {}", img1Path, img2Path);
            return;
        }

        Image img1 = ImageFactory.getInstance().fromFile(img1Path);
        Image img2 = ImageFactory.getInstance().fromFile(img2Path);

        try (Model featModel = Model.newInstance("faceplugin-face-feature", "OnnxRuntime")) {
            featModel.load(featPath, "face_feature");
            try (Predictor<Image, float[]> featurePredictor = featModel.newPredictor(new FacePluginFeatureTranslator())) {
                float[] f1 = featurePredictor.predict(img1);
                float[] f2 = featurePredictor.predict(img2);
                log.info("特征维度: {}", f1.length);

                float dot = 0f;
                for (int i = 0; i < f1.length; i++) {
                    dot += f1[i] * f2[i];
                }
                float similarity = (dot + 1f) * 50f;
                log.info("相似度: {}% (阈值 {}%)", String.format("%.2f", similarity), (int) THRESHOLD);
                log.info("same person: {}", similarity >= THRESHOLD);
            }
        }
    }
}