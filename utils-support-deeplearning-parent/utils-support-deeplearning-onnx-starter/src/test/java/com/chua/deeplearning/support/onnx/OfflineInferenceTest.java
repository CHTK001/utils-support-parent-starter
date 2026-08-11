package com.chua.deeplearning.support.onnx;

import ai.djl.Model;
import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.translate.Translator;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.ImageClassifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Color;

/**
 * 离线端到端推理测试：用 utils-support-models-onnx-efficientnet 的 torchvision 替代模型
 * 跑图像分类。整个流程零网络请求：模型从 m2 jar 中 classpath 加载。
 *
 * 在 utils-support-deeplearning-onnx-starter 中用 java 直接运行：
 * <pre>
 *   java -cp "target/classes;...models-parent jars..." OfflineInferenceTest
 * </pre>
 */
public class OfflineInferenceTest {
    public static void main(String[] args) throws Exception {
        // 触发 SPI 注册
        new OnnxModelRegistrar().register(null);
        ModelRegistry.discoverAll();

        // 1) 类路径解析：efficientnet 模型（用 SPI 注册的 modelId）
        Path effPath = ModelRegistry.resolveModelPath("efficientnet-b1-classification");
        if (effPath == null || !Files.exists(effPath)) {
            System.out.println("[FAIL] efficientnet 模型路径无效: " + effPath);
            System.exit(2);
        }
        System.out.println("[OK] EfficientNet ONNX: " + effPath + " (" + Files.size(effPath) + " bytes)");

        // 2) 构建测试图：224x224 RGB
        BufferedImage img = new BufferedImage(224, 224, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 224; y++) {
            for (int x = 0; x < 224; x++) {
                img.setRGB(x, y, new Color((y*4)&0xff, (x*4)&0xff, ((y+x)*2)&0xff).getRGB());
            }
        }
        Path tmp = Files.createTempFile("inference-test-", ".png");
        ImageIO.write(img, "png", tmp.toFile());
        System.out.println("[OK] 测试图已生成: " + tmp);

        Image image = ImageFactory.getInstance().fromFile(Paths.get(tmp.toString()));
        System.out.println("[OK] ImageFactory 加载: " + image.getWidth() + "x" + image.getHeight());

        // 3) 推理
        long t0 = System.currentTimeMillis();
        try (Model model = Model.newInstance("efficientnet-test", "OnnxRuntime")) {
            model.load(effPath, "efficientnet");
            System.out.println("[OK] ONNX 模型加载耗时 " + (System.currentTimeMillis() - t0) + "ms");

            Translator<Image, Classifications> translator =
                new com.chua.deeplearning.support.onnx.classification
                    .EfficientNetLite0ClassificationTranslator();
            try (Predictor<Image, Classifications> p = model.newPredictor(translator)) {
                long t1 = System.currentTimeMillis();
                Classifications out = p.predict(image);
                long el = System.currentTimeMillis() - t1;
                System.out.println("[OK] 推理耗时 " + el + "ms");
                System.out.println("[OK] Top-5 结果:");
                int shown = 0;
                for (Classifications.Classification c : out.items()) {
                    if (shown++ >= 5) break;
                    System.out.println("    " + c.getClassName() + " 概率=" + String.format("%.4f", c.getProbability()));
                }
            }
        }

        Files.deleteIfExists(tmp);
        System.out.println("\n=== 离线端到端推理验证 PASS ===");
        System.exit(0);
    }
}
