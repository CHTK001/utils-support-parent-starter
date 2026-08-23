package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.utils.ImageUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class SafetyFireReflectiveTest {

    @Test
    @DisplayName("safety-helmet/fire-smoke/reflective-clothes 黑盒实测")
    void all() throws Exception {
        ModelRegistry.discoverAll();
        ImageUtils.load();
        run("safety-helmet", new String[]{"1safety-helmet.webp", "1safety-helmet1.png", "6safety-helmet.webp"});
        run("fire-smoke", new String[]{"fire_smoke.png", "fire_smoke2.jpg", "smoke_scene1.jpg"});
        run("reflective-clothes", new String[]{"1safety-helmet.webp", "smart_construction.jpg", "fullbody2.jpg"});
    }

    private void run(String modelId, String[] names) throws Exception {
        File outputRoot = new File("G:/images/output/" + modelId);
        outputRoot.mkdirs();
        ImageDetector detector = ImageDetector.create(modelId);
        System.out.println("===== " + modelId + " =====");
        for (String n : names) {
            File f = new File("G:/images/" + n);
            if (!f.exists()) {
                System.out.println("  缺失: " + n);
                continue;
            }
            byte[] img = Files.readAllBytes(f.toPath());
            List<DetectionInfo> results = detector.detect(img);
            results = results.stream().filter(d -> d.confidence() >= 0.3f).toList();
            System.out.printf("  %s -> %d 目标%n", n, results.size());
            for (DetectionInfo d : results) {
                System.out.printf("     %-12s conf=%.2f [%.0f,%.0f,%.0f,%.0f]%n",
                        d.label(), d.confidence(), d.x(), d.y(), d.width(), d.height());
            }
            if (!results.isEmpty()) {
                byte[] drawn = new com.chua.deeplearning.support.draw.DrawerPipeline(0f)
                        .target(img)
                        .boxes(results, results.stream().map(d -> d.label() + " " + String.format("%.2f", d.confidence())).toList())
                        .done();
                Path out = outputRoot.toPath().resolve(n.replaceAll("\\.[^.]+$", "_result.jpg"));
                Files.write(out, drawn);
                System.out.println("    已输出: " + out);
            }
        }
    }
}