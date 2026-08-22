package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ModelRealVerify {
    public static void main(String[] args) throws Exception {
        ModelRegistry.discoverAll();
        ImageUtils.load();
        String[][] tests = {
            {"fer-plus", "D:/images/1people.png"},
            {"efficient-net-lite4-classification", "D:/images/1ai.png"},
            {"efficientnet-b1-classification", "D:/images/1ai.png"},
            {"doc-orientation", "D:/images/ticket_90.png"},
            {"pp-word-rotate", "D:/images/ticket_180.png"},
            {"yolov5-plate-recognize", "D:/images/car plate1.webp"},
            {"card-correction-detector", "D:/images/1people.png"},
            {"insightface-scrfd", "D:/images/1people.png"},
            {"anime-face-detector", "D:/images/anime_face1.png"},
        };
        for (String[] test : tests) {
            String modelId = test[0];
            String imgPath = test[1];
            System.out.println("=== " + modelId + " ===");
            var entry = ModelRegistry.get(modelId);
            if (entry == null) { System.out.println("  未注册"); continue; }
            var path = ModelRegistry.resolveModelPath(modelId);
            if (path == null || !path.toFile().exists()) { System.out.println("  模型不存在"); continue; }
            try {
                byte[] img = Files.readAllBytes(Path.of(imgPath));
                var t = (ITranslator<Object, Object>) ModelRegistry.createTranslator(modelId, null);
                long t0 = System.currentTimeMillis();
                Object r = t.translate(img);
                long cost = System.currentTimeMillis() - t0;
                String result = (r != null) ? r.toString().substring(0, Math.min(120, r.toString().length())) : "null";
                System.out.println("  耗时: " + cost + "ms  结果: " + result);
                if (r != null && r.toString().length() > 2) {
                    System.out.println("  ✅ 真实推理通过");
                } else {
                    System.out.println("  ⚠️ 结果为空");
                }
                if (t instanceof AutoCloseable ac) { try { ac.close(); } catch (Exception e) {} }
            } catch (Exception e) {
                System.out.println("  ❌ FAIL: " + e.getMessage());
            }
        }
        System.out.println("[ModelRealVerify] DONE");
    }
}