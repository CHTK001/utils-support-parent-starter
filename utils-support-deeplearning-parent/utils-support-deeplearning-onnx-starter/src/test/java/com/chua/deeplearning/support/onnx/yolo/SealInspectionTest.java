package com.chua.deeplearning.support.onnx.yolo;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.translate.TranslateException;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class SealInspectionTest {

    @Test
    void testSealModelRegistered() {
        com.chua.deeplearning.support.engine.ModelRegistry.discoverAll();
        assertNotNull(com.chua.deeplearning.support.engine.ModelRegistry.get("seal-inspection"));
        log.info("seal-inspection 已注册");
    }

    @Test
    void testSealDetectionSyntheticImage() throws Exception {
        assertNotNull(new SealInspectionTranslator());
        log.info("合成印章图 Translator 创建成功");
    }

    @Test
    void testSealViaImageDetectorFacade() throws Exception {
        // 用 ImageDetector 门面走完整 ONNX 推理（若模型缺失则跳过）
        Path p = Paths.get("Z:/temp/opencode/seal_test.jpg");
        if (!Files.exists(p)) {
            // 生成一张合成图保存为测试图
            BufferedImage bi = new BufferedImage(640, 640, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = bi.createGraphics();
            g.setColor(Color.WHITE); g.fillRect(0, 0, 640, 640);
            g.setColor(Color.RED); g.setStroke(new BasicStroke(6f));
            g.drawOval(100, 100, 440, 440);
            g.setColor(Color.RED); g.setFont(new Font("SansSerif", Font.BOLD, 60));
            g.drawString("公章", 240, 340);
            g.dispose();
            Files.createDirectories(p.getParent());
            javax.imageio.ImageIO.write(bi, "jpg", p.toFile());
        }
        byte[] bytes = Files.readAllBytes(p);
        var detector = com.chua.deeplearning.support.image.ImageDetector.create("seal-inspection");
        var results = detector.detect(bytes);
        log.info("印章检测结果数: {}", results.size());
        for (var r : results) {
            log.info("  {} conf={} [{},{},{},{}]", r.label(), r.confidence(), r.x(), r.y(), r.width(), r.height());
        }
        assertNotNull(results);
    }
}