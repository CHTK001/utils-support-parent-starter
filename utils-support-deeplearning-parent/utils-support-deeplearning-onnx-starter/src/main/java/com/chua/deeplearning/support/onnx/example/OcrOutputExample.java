package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.ocr.OcrResult;
import com.chua.deeplearning.support.ocr.OcrPipeline;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * OCR 识别示例：输出一张结果图（原文件名 + 检测框 + 中文标注）。
 *
 * <pre>{@code
 * java ... OcrOutputExample <图片路径> [输出目录]
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class OcrOutputExample {

    /**
     * 主入口。
     *
     * @param args [图片路径] [输出目录，默认 G:\images\output]
     * @throws Exception 异常
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法: OcrOutputExample <图片路径> [输出目录]");
            return;
        }
        String imgPath = args[0];
        String outDir = args.length > 1 ? args[1] : "G:\\images\\output";
        String name = Path.of(imgPath).getFileName().toString();
        Path outFile = Path.of(outDir, name);

        OcrPipeline pipeline = OcrPipeline.builder()
                .detector("paddleocrv6-medium-det")
                .recognizer("paddleocrv6-medium-rec")
                .direction("pp-word-rotate")
                .enhancer("text-bsr")
                .minConfidence(0.6f)
                .build();

        byte[] data = Files.readAllBytes(Path.of(imgPath));
        // 识别（返回实际使用图 + 结果，保证绘图与识别一致）
        long t0 = System.currentTimeMillis();
        OcrPipeline.OcrRecognizeResult detail = pipeline.recognizeDetailWithImage(data);
        List<OcrResult> results = detail.results();
        byte[] usedImage = detail.image();
        long ms = System.currentTimeMillis() - t0;
        log.info("[OCR] {} 识别完成: {} 条文本, {}ms", name, results.size(), ms);

        // 在识别所用图上绘制检测框 + 中文标注
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(usedImage));
        if (img == null) {
            throw new IllegalStateException("无法解码图片: " + imgPath);
        }
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 20));
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        for (OcrResult r : results) {
            if (r.text() == null || r.text().isBlank()) {
                continue;
            }
            log.info("[OCR] [{}] {}", idx, r.text());
            sb.append(r.text()).append("\n");
            int x = (int) r.boundingBox().x();
            int y = (int) r.boundingBox().y();
            int w = (int) r.boundingBox().width();
            int h = (int) r.boundingBox().height();
            g.setColor(Color.RED);
            g.setStroke(new BasicStroke(2));
            g.drawRect(x, y, w, h);
            int textW = g.getFontMetrics().stringWidth(r.text());
            int barH = 22;
            int barY = Math.max(0, y - barH);
            g.setColor(new Color(0, 180, 0, 180));
            g.fillRect(x, barY, Math.max(textW + 6, w), barH);
            g.setColor(Color.WHITE);
            g.drawString(r.text(), x + 3, barY + 16);
            idx++;
        }
        g.dispose();
        ImageIO.write(img, "png", new File(outFile.toString()));
        log.info("[OCR] 结果图 -> {}", outFile);
    }
}
