package com.chua.deeplearning.support.llama;

import com.chua.common.support.ai.chat.ChatClient;

public final class Qwen2ExtraVerify {

    private Qwen2ExtraVerify() {
    }

    public static void main(String[] args) {
        boolean allPass = true;
        allPass &= run("翻译", "把'深度学习'翻译成英文。");
        allPass &= run("常识", "一年有几个季节？分别是什么？");
        System.out.println(allPass ? "[Qwen2ExtraVerify] ALL PASS" : "[Qwen2ExtraVerify] FAIL");
        if (!allPass) {
            System.exit(1);
        }
    }

    private static boolean run(String label, String prompt) {
        try (ChatClient client = ChatClient.create("llama", "").model("qwen2-1.5b")) {
            System.out.println("[" + label + "] 提问: " + prompt);
            long t0 = System.currentTimeMillis();
            String reply = client.chatSync(prompt);
            long cost = System.currentTimeMillis() - t0;
            boolean ok = reply != null && !reply.isBlank() && reply.length() > 3;
            System.out.println("[" + label + "] 回复(" + (ok ? "OK" : "空") + ", " + cost + "ms): "
                    + (reply != null ? reply.trim().replaceAll("\\s+", " ").substring(0, Math.min(150, reply.trim().length())) : "NULL"));
            return ok;
        } catch (Exception e) {
            System.out.println("[" + label + "] FAIL: " + e.getMessage());
            return false;
        }
    }
}