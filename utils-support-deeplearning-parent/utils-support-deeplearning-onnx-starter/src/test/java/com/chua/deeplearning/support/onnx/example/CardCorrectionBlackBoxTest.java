package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.onnx.classification.CardCorrectionTranslator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CardCorrectionBlackBoxTest {

    @Test
    @DisplayName("card-correction-detector 黑盒: D:/images -> G:/images/output/card-correction-detector/")
    void blackBox() throws Exception {
        String modelId = "card-correction-detector";
        File inputDir = new File("D:/images");
        File outputRoot = new File("G:/images/output/" + modelId);
        outputRoot.mkdirs();
        File[] files = inputDir.listFiles((d, n) -> {
            String s = n.toLowerCase();
            return s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".png") || s.endsWith(".webp");
        });
        assertNotNull(files, "D:/images 不存在或不可读");
        assertTrue(files.length > 0, "D:/images 无图片");
        CardCorrectionTranslator tr = CardCorrectionTranslator.shared();
        int ok = 0;
        for (File f : files) {
            byte[] img = Files.readAllBytes(f.toPath());
            List<DetectionInfo> det = tr.translate(img);
            System.out.println("[card-correction] " + f.getName() + " -> detections=" + det.size());
            for (DetectionInfo d : det) {
                System.out.printf("  box=(%.0f,%.0f) %.0fx%.0f conf=%.2f%n", d.x(), d.y(), d.width(), d.height(), d.confidence());
            }
            byte[] corrected = tr.correct(img);
            if (corrected != null) {
                Path out = outputRoot.toPath().resolve(f.getName().replaceAll("\\.[^.]+$", "_corrected.png"));
                Files.write(out, corrected);
                System.out.println("  corrected -> " + out);
                assertTrue(Files.exists(out));
                ok++;
            }
        }
        System.out.println("[card-correction] 共处理 " + files.length + " 张, 矫正成功 " + ok + " 张 -> " + outputRoot.getAbsolutePath());
    }
}
