package com.chua.deeplearning.support.onnx.florence2;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Florence-2 真实推理测试入口。
 * <p>
 * 运行：mvn exec:java -pl utils-support-deepleaming-onnx-starter \
 *   -Dexec.mainClass="com.chua.deeplearning.support.onnx.florence2.Florence2InferenceTest"
 * </p>
 */
public class Florence2InferenceTest {

    public static void main(String[] args) throws Exception {
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        Path testImage = tmpDir.resolve("florence2_real_test.png");
        Path modelDir = Path.of(System.getProperty("deeplearning.model.cache-dir",
                System.getProperty("java.io.tmpdir")), "vision/florence2");

        System.out.println("=== Florence-2 Real Inference Test ===");
        System.out.println("Model cache dir: " + modelDir.toAbsolutePath());

        // Step 1: Generate test image
        System.out.println("\n[1] Generating test image...");
        byte[] imageData = generateTestImage();
        Files.createDirectories(testImage.getParent());
        Files.write(testImage, imageData);
        System.out.println("    Test image: " + testImage + " (" + imageData.length + " bytes)");

        // Step 2: Check model files
        Path visionPath = modelDir.resolve("vision_encoder.onnx");
        Path embedPath = modelDir.resolve("embed_tokens.onnx");
        Path decoderPath = modelDir.resolve("decoder_model_merged.onnx");
        Path tokenizerPath = modelDir.resolve("tokenizer.json");

        boolean modelsReady = Files.exists(visionPath) && Files.exists(decoderPath)
                && Files.exists(tokenizerPath);
        System.out.println("\n[2] Model files check:");
        System.out.println("    vision_encoder.onnx:   " + (Files.exists(visionPath) ? "YES (" + Files.size(visionPath)/1024/1024 + "MB)" : "NO"));
        System.out.println("    embed_tokens.onnx:     " + (Files.exists(embedPath) ? "YES (" + Files.size(embedPath)/1024/1024 + "MB)" : "NO"));
        System.out.println("    decoder_model_merged:  " + (Files.exists(decoderPath) ? "YES (" + Files.size(decoderPath)/1024/1024 + "MB)" : "NO"));
        System.out.println("    tokenizer.json:        " + (Files.exists(tokenizerPath) ? "YES" : "NO"));

        if (!modelsReady) {
            System.out.println("\n    Models not found. Please run download first:");
            System.out.println("    python script below, or wait for auto-download on first translate() call.");
        }

        // Step 3: Run inference
        System.out.println("\n[3] Running Florence-2 inference...");
        var translator = new Florence2Translator();
        try {
            String[] tasks = {"<CAPTION>", "<OCR>", "<DETAILED_CAPTION>", "<OD>"};
            for (String task : tasks) {
                System.out.println("\n  --- Task: " + task + " ---");
                try {
                    long t0 = System.currentTimeMillis();
                    String result = translator.translate(new Object[]{imageData, task});
                    long elapsed = System.currentTimeMillis() - t0;
                    System.out.println("  Result: " + result);
                    System.out.println("  Time:   " + elapsed + " ms");
                } catch (Exception e) {
                    System.out.println("  ERROR: " + e.getMessage());
                    if (e.getCause() != null) {
                        System.out.println("  Cause:  " + e.getCause().getMessage());
                    }
                }
            }
        } finally {
            translator.close();
        }

        System.out.println("\n=== Test Complete ===");
    }

    private static byte[] generateTestImage() throws Exception {
        int w = 400, h = 300;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(240, 240, 255));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.RED);
        g.fillRect(30, 30, 100, 80);
        g.setColor(Color.GREEN);
        g.fillOval(160, 50, 90, 90);
        g.setColor(Color.BLUE);
        int[] px = {300, 350, 400};
        int[] py = {40, 120, 40};
        g.fillPolygon(px, py, 3);
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.drawString("Hello Florence", 60, 200);
        g.drawString("Multi-modal AI", 80, 230);
        g.setColor(Color.YELLOW);
        g.fillRect(250, 170, 50, 50);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }
}
