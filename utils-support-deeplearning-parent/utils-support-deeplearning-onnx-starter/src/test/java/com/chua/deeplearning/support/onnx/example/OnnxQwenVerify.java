package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;

public final class OnnxQwenVerify {

    private OnnxQwenVerify() {
    }

    public static void main(String[] args) throws Exception {
        ModelRegistry.discoverAll();

        var entry = ModelRegistry.get("qwen2-1.5b-onnx");
        System.out.println("[qwen2-onnx] 注册: " + (entry != null ? "OK " + entry.relativePath() : "FAIL"));
        if (entry == null) {
            System.exit(1);
        }
        var path = ModelRegistry.resolveModelPath("qwen2-1.5b-onnx");
        System.out.println("[qwen2-onnx] 路径: " + path);
        System.out.println("[qwen2-onnx] 存在: " + (path != null && path.toFile().exists()));
        if (path == null || !path.toFile().exists()) {
            System.exit(1);
        }

        Object translator = ModelRegistry.createTranslator("qwen2-1.5b-onnx", null);
        try {
            long t0 = System.currentTimeMillis();
            String reply = (String) ((ITranslator<Object, Object>) translator).translate("你好，用一句话介绍一下自己");
            long cost = System.currentTimeMillis() - t0;
            System.out.println("[qwen2-onnx] 回复(" + cost + "ms): " + reply);
            boolean ok = reply != null && !reply.isBlank();
            System.out.println(ok ? "[OnnxQwenVerify] ALL PASS" : "[OnnxQwenVerify] FAIL");
            if (!ok) {
                System.exit(1);
            }
        } finally {
            if (translator instanceof AutoCloseable ac) {
                try { ac.close(); } catch (Exception ignore) {}
            }
        }
    }
}