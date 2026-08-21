package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;

import java.nio.file.Files;
import java.nio.file.Path;

public final class DepthAnythingOrtVerify {

    private DepthAnythingOrtVerify() {
    }

    public static void main(String[] args) throws Exception {
        ModelRegistry.discoverAll();
        String imagePath = args.length > 0 ? args[0] : "D:/images/1ai.png";
        byte[] imageBytes = Files.readAllBytes(Path.of(imagePath));

        var entry = ModelRegistry.get("depth-anything");
        System.out.println("[depth-anything] 注册: " + (entry != null ? "OK" : "FAIL"));
        if (entry == null) { System.exit(1); }

        var path = ModelRegistry.resolveModelPath("depth-anything");
        System.out.println("[depth-anything] 路径: " + path);
        if (path == null || !path.toFile().exists()) { System.exit(1); }

        var translator = (ITranslator<Object, Object>) ModelRegistry.createTranslator("depth-anything", null);
        long t0 = System.currentTimeMillis();
        Object out = translator.translate(imageBytes);
        long cost = System.currentTimeMillis() - t0;
        boolean ok = out instanceof byte[] && ((byte[]) out).length > 100;
        if (ok) {
            var outDir = Path.of("D:/images/output/depth-anything");
            Files.createDirectories(outDir);
            Files.write(outDir.resolve("depth-anything.png"), (byte[]) out);
        }
        System.out.println("[depth-anything] 耗时=" + cost + "ms 输出=" + (out instanceof byte[] ? ((byte[]) out).length + "bytes" : out.getClass().getName()));
        System.out.println(ok ? "[DepthAnythingOrtVerify] ALL PASS" : "[DepthAnythingOrtVerify] FAIL");
        if (!ok) { System.exit(1); }
    }
}