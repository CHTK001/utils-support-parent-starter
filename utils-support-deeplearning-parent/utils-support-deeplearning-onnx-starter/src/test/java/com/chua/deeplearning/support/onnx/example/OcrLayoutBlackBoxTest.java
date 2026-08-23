package com.chua.deeplearning.support.onnx.example;

import ai.djl.modality.cv.output.DetectedObjects;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

class OcrLayoutBlackBoxTest {

    @Test
    @DisplayName("pp-doc-layout 黑盒: D:/images -> G:/images/output/pp-doc-layout/")
    void ppDocLayout() throws Exception {
        String modelId = "pp-doc-layout";
        File inputDir = new File("D:/images");
        File outputRoot = new File("G:/images/output/" + modelId);
        outputRoot.mkdirs();
        File[] files = inputDir.listFiles((d, n) -> {
            String s = n.toLowerCase();
            return s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".png") || s.endsWith(".webp");
        });
        if (files == null || files.length == 0) {
            System.out.println("[pp-doc-layout] D:/images 无图片，跳过");
            return;
        }
        ModelRegistry.discoverAll();
        ImageUtils.load();
        @SuppressWarnings("unchecked")
        ITranslator<byte[], Object> tr =
                (ITranslator<byte[], Object>) (ITranslator<?, ?>) ModelRegistry.createTranslator(modelId, null);
        try {
            int ok = 0;
            for (File f : files) {
                byte[] img = Files.readAllBytes(f.toPath());
                Object result = tr.translate(img);
                System.out.printf("[pp-doc-layout] %s -> %s%n", f.getName(), result.getClass().getSimpleName());
                if (result instanceof DetectedObjects det) {
                    System.out.printf("  区域数: %d%n", det.getNumberOfObjects());
                    for (int i = 0; i < det.getNumberOfObjects(); i++) {
                        DetectedObjects.DetectedObject obj = (DetectedObjects.DetectedObject) det.item(i);
                        var bounds = obj.getBoundingBox().getBounds();
                        System.out.printf("  %-16s conf=%.3f box=[%.0f,%.0f,%.0f,%.0f]%n",
                                obj.getClassName(), obj.getProbability(),
                                bounds.getX(), bounds.getY(),
                                bounds.getX() + bounds.getWidth(),
                                bounds.getY() + bounds.getHeight());
                    }
                } else if (result instanceof java.util.List<?> list) {
                    System.out.printf("  列表大小: %d%n", list.size());
                    for (Object item : list) {
                        System.out.printf("    %s%n", item);
                    }
                } else {
                    System.out.printf("  结果: %s%n", result);
                }
                ok++;
            }
            System.out.printf("[pp-doc-layout] 共处理 %d 张，成功 %d 张 -> %s%n", files.length, ok, outputRoot.getAbsolutePath());
        } finally {
            if (tr instanceof AutoCloseable ac) try { ac.close(); } catch (Exception ignored) {}
        }
    }

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
        @SuppressWarnings("unchecked")
        ITranslator<byte[], Object> tr =
                (ITranslator<byte[], Object>) (ITranslator<?, ?>) ModelRegistry.createTranslator(modelId, null);
        try {
            int ok = 0;
            for (File f : files) {
                byte[] img = Files.readAllBytes(f.toPath());
                Object result = tr.translate(img);
                System.out.printf("[doc-layout-yolo] %s -> %s%n", f.getName(), result.getClass().getSimpleName());
                if (result instanceof DetectedObjects det) {
                    System.out.printf("  区域数: %d%n", det.getNumberOfObjects());
                    for (int i = 0; i < det.getNumberOfObjects(); i++) {
                        DetectedObjects.DetectedObject obj = (DetectedObjects.DetectedObject) det.item(i);
                        var bounds = obj.getBoundingBox().getBounds();
                        System.out.printf("  %-14s conf=%.3f box=[%.0f,%.0f,%.0f,%.0f]%n",
                                obj.getClassName(), obj.getProbability(),
                                bounds.getX(), bounds.getY(),
                                bounds.getX() + bounds.getWidth(),
                                bounds.getY() + bounds.getHeight());
                    }
                } else if (result instanceof java.util.List<?> list) {
                    System.out.printf("  列表大小: %d%n", list.size());
                } else {
                    System.out.printf("  结果: %s%n", result);
                }
                ok++;
            }
            System.out.printf("[doc-layout-yolo] 共处理 %d 张，成功 %d 张 -> %s%n", files.length, ok, outputRoot.getAbsolutePath());
        } finally {
            if (tr instanceof AutoCloseable ac) try { ac.close(); } catch (Exception ignored) {}
        }
    }
}
