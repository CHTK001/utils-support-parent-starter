package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;

import java.nio.file.Files;
import java.nio.file.Path;

public final class LivenessVerify {
    public static void main(String[] args) throws Exception {
        ModelRegistry.discoverAll();
        ImageUtils.load();
        String[] models = {"face-liveness-flrgb", "face-liveness-flxc"};
        String imagePath = args.length > 0 ? args[0] : "D:/images/1ai.png";
        for (String modelId : models) {
            System.out.println("=== " + modelId + " ===");
            var entry = ModelRegistry.get(modelId);
            if (entry == null) { System.out.println("  未注册"); continue; }
            var path = ModelRegistry.resolveModelPath(modelId);
            if (path == null || !path.toFile().exists()) { System.out.println("  模型不存在"); continue; }
            byte[] img = Files.readAllBytes(Path.of(imagePath));
            var t = (ITranslator<Object, Object>) ModelRegistry.createTranslator(modelId, null);
            long t0 = System.currentTimeMillis();
            Object r = t.translate(img);
            long cost = System.currentTimeMillis() - t0;
            System.out.println("  耗时: " + cost + "ms  结果: " + r);
            if (t instanceof AutoCloseable ac) { try { ac.close(); } catch (Exception e) {} }
        }
        System.out.println("[LivenessVerify] DONE");
    }
}