package com.chua.deeplearning.support.llama;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.llama.translator.Qwen2ChatTranslator;

public final class Qwen2ModelVerify {

    private Qwen2ModelVerify() {
    }

    public static void main(String[] args) throws Exception {
        ModelRegistry.discoverAll();
        var entry = ModelRegistry.get("qwen2-1.5b");
        if (entry == null) {
            System.out.println("[Qwen2] FAIL: 未注册");
            System.exit(1);
        }
        System.out.println("[Qwen2] 注册路径: " + entry.relativePath());

        // 测试模型路径解析
        try {
            var path = ModelRegistry.resolveModelPath("qwen2-1.5b");
            System.out.println("[Qwen2] 模型路径: " + path);
            if (path != null && path.toFile().exists()) {
                System.out.println("[Qwen2] 模型文件存在: " + path.toFile().length() / (1024 * 1024) + " MB");
            } else {
                System.out.println("[Qwen2] FAIL: 模型文件不存在");
                System.exit(1);
            }
        } catch (Exception e) {
            System.out.println("[Qwen2] FAIL: 路径解析失败 - " + e.getMessage());
            System.exit(1);
        }

        System.out.println("[Qwen2ModelVerify] ALL PASS");
    }
}