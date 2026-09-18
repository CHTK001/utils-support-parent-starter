package com.chua.feishu.support.bot;

import com.chua.common.support.ai.bot.BotClient;
import com.chua.common.support.ai.bot.BotInboundMessage;

/**
 * 飞书 Bot 端到端联调入口（非单元测试，需人工配合收发消息）
 *
 * <pre>
 * FEISHU_APP_ID=xxx FEISHU_APP_SECRET=yyy \
 *   mvn exec:java -pl utils-support-cloud-parent/utils-support-feishu-starter \
 *     -Dexec.mainClass=com.chua.feishu.support.bot.FeishuE2ERunner \
 *     -Dexec.classpathScope=test
 * </pre>
 *
 * @author CH
 * @since 2026/09/18
 */
public final class FeishuE2ERunner {

    private FeishuE2ERunner() {
    }

    /** 主入口 */
    public static void main(String[] args) throws Exception {
        String appId = require("FEISHU_APP_ID");
        String appSecret = require("FEISHU_APP_SECRET");

        FeishuBotClient client = new FeishuBotClient();
        client.configure(appId, appSecret, null);
        client.addErrorListener(e ->
                System.out.println("[ERROR] " + e.getMessage()));
        client.addMessageListener(msg -> onMessage(client, msg));
        client.start();

        System.out.println("== Feishu E2E 已启动(长连接)，请在飞书给机器人发消息 ==");

        // SDK 长连接内部为非守护线程，此处保持 main 存活便于 Ctrl+C
        while (client.isRunning()) {
            Thread.sleep(1000L);
        }
    }

    private static void onMessage(FeishuBotClient client,
            BotInboundMessage msg) {
        System.out.printf("[RECV] from=%s group=%s mentionedBot=%s"
                        + " mentioned=%s chatId=%s type=%s content=%s%n",
                msg.getFromUser(), msg.isFromGroup(),
                msg.getMentionedBot(), msg.getMentionedList(),
                msg.getChatId(), msg.getType(), msg.getContent());
        String reply = "reply: " + msg.getContent();
        var result = msg.isFromGroup()
                ? client.sendToGroup(msg.getChatId(), reply)
                : client.sendText(msg.getFromUser(), reply);
        System.out.printf("[SEND] success=%s msgId=%s error=%s%n",
                result.isSuccess(), result.getMsgId(),
                result.getErrorMessage());
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "env " + name + " is required");
        }
        return value;
    }
}
