package com.chua.deeplearning.support.onnx.florence2;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
public class Florence2QuickTest {
    public static void main(String[] args) throws Exception {
        Path testImage = Path.of(System.getProperty("java.io.tmpdir"), "florence2_quick_test.png");
        byte[] imageData = generateTestImage();
        Files.write(testImage, imageData);
        System.out.println("Test image: " + testImage + " (" + imageData.length + " bytes)");
        var translator = new Florence2Translator();
        try {
            for (String task : new String[]{"<CAPTION>", "<OCR>"}) {
                System.out.println("\n--- Task: " + task + " ---");
                long t0 = System.currentTimeMillis();
                try {
                    String result = translator.translate(new Object[]{imageData, task});
                    System.out.println("Result: " + result);
                    System.out.println("Time: " + (System.currentTimeMillis() - t0) + " ms");
                } catch (Exception e) { System.out.println("ERROR: " + e.getMessage()); }
            }
        } finally { translator.close(); }
        System.out.println("\n=== Test Complete ===");
    }
    private static byte[] generateTestImage() throws Exception {
        BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(240, 240, 255)); g.fillRect(0, 0, 400, 300);
        g.setColor(Color.RED); g.fillRect(30, 30, 100, 80);
        g.setColor(Color.GREEN); g.fillOval(160, 50, 90, 90);
        g.setColor(Color.BLUE); int[] px={300,350,400}, py={40,120,40}; g.fillPolygon(px, py, 3);
        g.setColor(Color.DARK_GRAY); g.setFont(new Font("SansSerif", Font.BOLD, 20));
        g.drawString("Hello Florence", 60, 200); g.drawString("Multi-modal AI", 80, 230);
        g.setColor(Color.YELLOW); g.fillRect(250, 170, 50, 50);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }
}