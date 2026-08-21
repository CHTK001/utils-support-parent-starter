package com.chua.deeplearning.support.llama;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.deeplearning.support.engine.ModelRegistry;

public final class Qwen2_05bVerify {

    private Qwen2_05bVerify() {
    }

    public static void main(String[] args) {
        ModelRegistry.discoverAll();

        // 1. 注册 + 路径
        var entry = ModelRegistry.get("qwen2-0.5b");
        if (entry == null) {
            System.out.println("[qwen2-0.5b] FAIL: 未注册");
            System.exit(1);
        }
        var path = ModelRegistry.resolveModelPath("qwen2-0.5b");
        System.out.println("[qwen2-0.5b] 路径: " + path);
        if (path == null || !path.toFile().exists()) {
            System.out.println("[qwen2-0.5b] FAIL: 模型不存在 -> " + (path == null ? "null" : path.toFile().exists()));
            System.exit(1);
        }

        // 2. chat
        try (ChatClient client = ChatClient.create("llama", "").model("qwen2-0.5b")) {
            System.out.println("[qwen2-0.5b] 提问: 你好，用一句话介绍自己");
            long t0 = System.currentTimeMillis();
            String reply = client.chatSync("你好，用一句话介绍自己");
            long cost = System.currentTimeMillis() - t0;
            boolean ok = reply != null && !reply.isBlank();
            System.out.println("[qwen2-0.5b] 回复(" + (ok ? "OK" : "空") + ", " + cost + "ms): " + (reply != null ? reply : "NULL"));
            ok = ok && cost < 60000;
            System.out.println(ok ? "[qwen2-0.5b] ALL PASS" : "[qwen2-0.5b] FAIL");
            if (!ok) {
                System.exit(1);
            }
        } catch (Exception e) {
            System.out.println("[qwen2-0.5b] FAIL: " + e.getMessage());
            System.exit(1);
        }
    }
}