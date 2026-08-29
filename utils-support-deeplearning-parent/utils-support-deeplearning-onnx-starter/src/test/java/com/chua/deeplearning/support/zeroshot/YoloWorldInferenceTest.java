package com.chua.deeplearning.support.zeroshot;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.onnx.OnnxModelRegistrar;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * YOLO-World 三档端到端推理测试。
 *
 * <p>测试覆盖：
 * <ul>
 *   <li>yolov8s-world (~40MB，本地模型) — 必须通过</li>
 *   <li>yolov8m-world (~70MB，自动下载) — 尝试通过</li>
 *   <li>yolov8l-world (~130MB，自动下载) — 尝试通过</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class YoloWorldInferenceTest {

    private YoloWorldInferenceTest() { }

    private static final String[] TIERS = {"yolov8s-world", "yolov8m-world", "yolov8l-world"};
    private static final float THRESHOLD = 0.25f;
    private static final int IMG_SIZE = 224;

    public static void main(String[] args) throws Exception {
        boolean all = args.length > 0 && "--all".equals(args[0]);
        String[] targets = all ? TIERS : new String[]{"yolov8s-world"};

        // 强制注册所有模型
        OnnxModelRegistrar registrar = new OnnxModelRegistrar();
        registrar.register(ModelRegistry.getInstance());
        System.out.println("[INFO] Registered detectors: " +
                ModelRegistry.getModelIdsByCapability(ImageDetector.class).size());

        int passed = 0, skipped = 0, failed = 0;
        long t0 = System.currentTimeMillis();

        byte[] testImage = generateTestImage();
        System.out.println("[TEST] Test image: " + testImage.length + " bytes JPEG");

        for (String tier : targets) {
            System.out.println("\n===== [Tier] " + tier + " =====");
            try {
                ImageDetector detector = ImageDetector.create(tier)
                        .threshold(THRESHOLD)
                        .nms(0.45f);
                System.out.println("  [OK] Detector created, threshold=" + THRESHOLD);

                List<DetectionInfo> results = detector.detect(testImage);
                System.out.println("  [OK] Inference done, found " + results.size() + " objects");

                if (!results.isEmpty()) {
                    results.stream().limit(3).forEach(d ->
                            System.out.println("    - " + d.label() + " @" +
                                    String.format("%.2f,%.2f,%.2f,%.2f", d.x(), d.y(), d.width(), d.height()) +
                                    " conf=" + String.format("%.3f", d.confidence())));
                } else {
                    System.out.println("  [WARN] No objects detected");
                }

                validateResults(results, tier);
                System.out.println("  [PASS] " + tier + " end-to-end OK");
                passed++;
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("download") || msg.contains("超时") || msg.contains("401")
                        || msg.contains("Connection") || msg.contains("Network"))) {
                    System.out.println("  [SKIP] " + tier + " needs download ("
                            + msg.substring(0, Math.min(80, msg.length())) + ")");
                    skipped++;
                } else {
                    System.out.println("  [FAIL] " + tier + ": " + e.getMessage());
                    failed++;
                }
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        System.out.println("\n===== Results =====");
        System.out.println("  PASS: " + passed + "/" + targets.length);
        System.out.println("  SKIP: " + skipped);
        System.out.println("  FAIL: " + failed);
        System.out.println("  Time: " + elapsed + "ms");
        if (failed > 0) System.exit(1);
    }

    private static void validateResults(List<DetectionInfo> results, String tier) {
        for (DetectionInfo d : results) {
            if (d.x() < 0 || d.y() < 0 || d.width() <= 0 || d.height() <= 0)
                throw new AssertionError(tier + " invalid coord: " + d);
            if (d.confidence() < 0 || d.confidence() > 1)
                throw new AssertionError(tier + " invalid conf: " + d.confidence());
        }
    }

    private static byte[] generateTestImage() throws IOException {
        BufferedImage img = new BufferedImage(IMG_SIZE, IMG_SIZE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < IMG_SIZE; y++)
            for (int x = 0; x < IMG_SIZE; x++)
                img.setRGB(x, y, 0xFFFFFF);
        for (int y = 60; y < 160; y++)
            for (int x = 60; x < 160; x++)
                img.setRGB(x, y, 0x0066FF);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "JPEG", baos);
        return baos.toByteArray();
    }
}
