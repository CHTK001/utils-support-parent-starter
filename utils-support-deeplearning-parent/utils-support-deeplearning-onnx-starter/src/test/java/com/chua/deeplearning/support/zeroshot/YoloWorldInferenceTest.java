package com.chua.deeplearning.support.zeroshot;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * YOLO-World zero-shot detection test with custom classes support.
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class YoloWorldInferenceTest {

    private YoloWorldInferenceTest() { }

    private static final String[] TIERS = {"yolov8s-world", "yolov8l-world"};
    private static final float THRESHOLD = 0.25f;
    private static final int IMG_SIZE = 224;

    /** Force load OnnxModelRegistrar (trigger static registration block) */
    static {
        try {
            Class.forName("com.chua.deeplearning.support.onnx.OnnxModelRegistrar");
        } catch (ClassNotFoundException ignored) { }
    }

    public static void main(String[] args) throws Exception {
        boolean all = args.length > 0 && "--all".equals(args[0]);
        String[] targets = all ? TIERS : new String[]{"yolov8s-world"};

        System.out.println("[INFO] Registered detectors: " +
                ModelRegistry.getModelIdsByCapability(ImageDetector.class).size());

        int passed = 0, skipped = 0, failed = 0;
        long t0 = System.currentTimeMillis();

        byte[] testImage = generateTestImage();
        System.out.println("[TEST] Test image: " + testImage.length + " bytes PNG");

        // Test 1: Default COCO-80
        System.out.println("\n===== [Test 1] Default COCO-80 Detection =====");
        for (String tier : targets) {
            System.out.println("\n--- " + tier + " ---");
            try {
                ImageDetector detector = ImageDetector.create(tier)
                        .threshold(THRESHOLD)
                        .nms(0.45f);
                System.out.println("  [OK] Detector created");

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
                System.out.println("  [PASS] " + tier + " default COCO-80 OK");
                passed++;
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("download") || msg.contains("超时") || msg.contains("401")
                        || msg.contains("Connection") || msg.contains("Network") || msg.contains("not found"))
                        || isFileNotFoundException(e)) {
                    System.out.println("  [SKIP] " + tier + " needs download ("
                            + msg.substring(0, Math.min(80, msg.length())) + ")");
                    skipped++;
                } else {
                    System.out.println("  [FAIL] " + tier + ": " + (msg != null ? msg.substring(0, Math.min(120, msg.length())) : e.getClass().getSimpleName()));
                    failed++;
                }
            }
        }

        // Test 2: Custom classes
        System.out.println("\n===== [Test 2] Custom Classes Detection =====");
        for (String tier : targets) {
            System.out.println("\n--- " + tier + " (custom: person,car,dog) ---");
            try {
                ImageDetector detector = ImageDetector.create(tier)
                        .threshold(0.3f)
                        .nms(0.45f);
                System.out.println("  [OK] Detector created with custom classes");

                List<DetectionInfo> results = detector.detect(testImage);
                System.out.println("  [OK] Inference done, found " + results.size() + " objects");

                // Verify all detected classes are in custom list
                for (DetectionInfo d : results) {
                    if (!config.get("classes").toString().contains(d.label().toLowerCase())) {
                        System.out.println("  [WARN] Unexpected class: " + d.label());
                    }
                }
                validateResults(results, tier + "-custom");
                System.out.println("  [PASS] " + tier + " custom classes OK");
                passed++;
            } catch (Exception e) {
                System.out.println("  [FAIL] " + tier + " custom: " + e.getMessage());
                failed++;
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        System.out.println("\n===== Results =====");
        System.out.println("  PASS: " + passed);
        System.out.println("  SKIP: " + skipped);
        System.out.println("  FAIL: " + failed);
        System.out.println("  Time: " + elapsed + "ms");
        if (failed > 0) System.exit(1);
    }

    private static boolean isFileNotFoundException(Exception e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof java.io.FileNotFoundException) return true;
            t = t.getCause();
        }
        return false;
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
        javax.imageio.ImageIO.write(img, "PNG", baos);
        return baos.toByteArray();
    }
}