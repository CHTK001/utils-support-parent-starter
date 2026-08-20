package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.engine.ModelRegistry;

/**
 * 校验 MiniLM 两个模型在 ModelRegistry 中的注册条目。
 */
public final class MiniLMRegistryVerify {

    private MiniLMRegistryVerify() {
    }

    /** Main */
    public static void main(String[] args) {
        ModelRegistry.discoverAll();
        boolean pass = true;
        pass &= check("minilm-embedding", "nlp/embedding/minilm/model_quantized.onnx");
        pass &= check("minilm-fp32-embedding", "nlp/embedding/minilm-fp32/model.onnx");
        if (pass) {
            System.out.println("[MiniLMRegistryVerify] ALL PASS");
        } else {
            System.out.println("[MiniLMRegistryVerify] FAIL");
            System.exit(1);
        }
    }

    private static boolean check(String modelId, String expectedPath) {
        var entry = ModelRegistry.get(modelId);
        if (entry == null) {
            System.out.println("[" + modelId + "] FAIL: 未注册");
            return false;
        }
        String actual = entry.relativePath();
        boolean ok = expectedPath.equals(actual);
        System.out.printf("[%s] path=%s expected=%s -> %s%n",
                modelId, actual, expectedPath, ok ? "OK" : "MISMATCH");
        return ok;
    }
}
