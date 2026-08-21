package com.chua.deeplearning.support.llama;

import com.chua.common.support.ai.chat.ChatClient;

public final class Qwen2CommonVerify {

    private Qwen2CommonVerify() {
    }

    public static void main(String[] args) {
        boolean pass = true;
        try (ChatClient client = ChatClient.create("llama", "").model("qwen2-1.5b")) {
            // 常识题（之前卡住）
            pass = ask(client, "常识", "一年有几个季节？分别是什么？")
                    && ask(client, "开放", "介绍一下人工智能的应用场景。")
                    && ask(client, "列表", "请列出5种水果。");
        } catch (Exception e) {
            System.out.println("[Qwen2CommonVerify] FAIL: " + e.getMessage());
            pass = false;
        }
        System.out.println(pass ? "[Qwen2CommonVerify] ALL PASS" : "[Qwen2CommonVerify] FAIL");
        if (!pass) {
            System.exit(1);
        }
    }

    private static boolean ask(ChatClient client, String label, String prompt) {
        System.out.println("[" + label + "] 提问: " + prompt);
        long t0 = System.currentTimeMillis();
        String reply;
        try {
            reply = client.chatSync(prompt);
        } catch (Exception e) {
            System.out.println("[" + label + "] FAIL: " + e.getMessage());
            return false;
        }
        long cost = System.currentTimeMillis() - t0;
        boolean ok = reply != null && !reply.isBlank() && reply.length() > 3;
        System.out.println("[" + label + "] 回复(" + (ok ? "OK" : "空") + ", " + cost + "ms): "
                + (reply != null ? reply.trim() : "NULL"));
        return ok;
    }
}