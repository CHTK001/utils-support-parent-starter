package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.utils.ImageUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

class DocLayoutYoloBlackBoxTest {

    @Test
    @DisplayName("doc-layout-yolo 黑盒: D:/images -> G:/images/output/doc-layout-yolo/")
    void docLayoutYolo() throws Exception {
        String modelId = "doc-layout-yolo";
        File inputDir = new File("D:/images");
        File outputRoot = new File("G:/images/output/" + modelId);
        outputRoot.mkdirs();
        File[] files = inputDir.listFiles((d, n) -> {
            String s = n.toLowerCase();
            return s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".png") || s.endsWith(".webp");
        });
        if (files == null || files.length == 0) {
            System.out.println("[doc-layout-yolo] D:/images 无图片，跳过");
            return;
        }
        ModelRegistry.discoverAll();
        ImageUtils.load();
        var raw = ModelRegistry.createTranslator(modelId, null);
        try {
            int ok = 0;
            for (File f : files) {
                byte[] img = Files.readAllBytes(f.toPath());
                Object result = raw.translate(img);
                System.out.printf("[doc-layout-yolo] %s -> %s%n", f.getName(), result.getClass().getSimpleName());
                if (result instanceof java.util.List<?> list) {
                    System.out.printf("  检出: %d 个区域%n", list.size());
                    for (Object item : list) {
                        System.out.printf("    %s%n", item);
                    }
                }
                ok++;
            }
            System.out.printf("[doc-layout-yolo] 共处理 %d 张，成功 %d 张%n", files.length, ok);
        } finally {
            if (raw instanceof AutoCloseable ac) try { ac.close(); } catch (Exception ignored) {}
        }
    }
}