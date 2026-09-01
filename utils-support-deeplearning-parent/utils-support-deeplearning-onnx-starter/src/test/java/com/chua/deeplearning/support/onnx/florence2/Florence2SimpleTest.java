package com.chua.deeplearning.support.onnx.florence2;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

public class Florence2SimpleTest {
    public static void main(String[] args) throws Exception {
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        Path testImage = tmpDir.resolve("florence2_test.png");
        Path modelDir = Path.of(System.getProperty("deeplearning.model.cache-dir",
                System.getProperty("java.io.tmpdir")), "vision/florence2");

        System.out.println("=== Florence-2 Simple Test ===");
        System.out.println("Model cache: " + modelDir);

        // Generate test image
        byte[] imageData = generateTestImage();
        Files.write(testImage, imageData);
        System.out.println("Test image: " + testImage + " (" + imageData.length + " bytes)");

        // Check models
        boolean hasModels = Files.exists(modelDir.resolve("vision_encoder.onnx"))
                && Files.exists(modelDir.resolve("decoder_model_merged.onnx"));
        System.out.println("Models ready: " + hasModels);

        if (!hasModels) {
            System.out.println("Please download models first (run Python script to download from HuggingFace)");
            return;
        }

        // Run inference
        var translator = new Florence2Translator();
        try {
            String[] tasks = {"<CAPTION>", "<OCR>", "<DETAILED_CAPTION>"};
            for (String task : tasks) {
                System.out.println("\n--- Task: " + task + " ---");
                long t0 = System.currentTimeMillis();
                try {
                    String result = translator.translate(new Object[]{imageData, task});
                    long elapsed = System.currentTimeMillis() - t0;
                    System.out.println("Result: " + result);
                    System.out.println("Time: " + elapsed + " ms");
                } catch (Exception e) {
                    System.out.println("ERROR: " + e.getMessage());
                    if (e.getCause() != null) {
                        System.out.println("Caused by: " + e.getCause().getMessage());
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
