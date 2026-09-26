package com.chua.qq.support.bot;

import com.chua.common.support.ai.bot.BotInboundMessage;
import com.chua.common.support.ai.bot.BotOutboundMessage;
import com.chua.common.support.lang.json.Json;

/**
 * QQ 机器人端到端联调入口（非单元测试，需人工配合收发消息）。
 * <p>
 * QQ 平台的群聊/单聊消息在被动回复窗口（群 5 分钟、单聊 60 分钟）内需回带入站
 * {@code msg_id}，客户端会自动记录并按 {@code msg_seq} 递增回带；窗口过期后即为主动消息，
 * 平台按频次限额放行或拒绝，因此本入口以「先收后回」为主链路。
 * </p>
 * <pre>
 * QQ_APP_ID=xxx QQ_APP_SECRET=yyy \
 *   mvn exec:java -pl utils-support-cloud-parent/utils-support-qq-starter \
 *     -Dexec.mainClass=com.chua.qq.support.bot.QqE2ERunner \
 *     -Dexec.classpathScope=test
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class QqE2ERunner {

    /**
     * 构造方法，工具类禁止实例化。
     */
    private QqE2ERunner() {
    }

    /**
     * 主入口，读取环境变量凭据后启动 QQ 机器人长连接并自动回复。
     *
     * @param args 命令行参数，未使用
     * @throws Exception 当等待连接期间线程被中断时
     */
    public static void main(String[] args) throws Exception {
        String appId = require("QQ_APP_ID");
        String appSecret = require("QQ_APP_SECRET");

        QqBotClient client = new QqBotClient();
        client.configure(appId, appSecret, System.getenv("QQ_ACCESS_TOKEN"));
        String baseUrl = System.getenv("QQ_BASE_URL");
        if (baseUrl != null && !baseUrl.isBlank()) {
            client.baseUrl(baseUrl);
        }
        String intents = System.getenv("QQ_INTENTS");
        if (intents != null && !intents.isBlank()) {
            client.intents(Integer.parseInt(intents.trim()));
        }
        client.addErrorListener(e -> System.out.println("[ERROR] " + e.getMessage()));
        client.addMessageListener(msg -> onMessage(client, msg));
        client.start();

        System.out.println("== QQ E2E 已启动(长连接), 请在 QQ 群 @机器人 或单聊发消息 ==");
        while (client.isRunning()) {
            Thread.sleep(1000L);
        }
    }

    /**
     * 收到入站消息后打印详情并回发文本与当前连接观测值。
     *
     * @param client QQ 机器人客户端，不允许为 null
     * @param msg    入站消息，不允许为 null
     */
    private static void onMessage(QqBotClient client, BotInboundMessage msg) {
        System.out.printf("[RECV] from=%s group=%s mentionedBot=%s mentioned=%s"
                        + " chatId=%s type=%s eventType=%s mediaUrl=%s event=%s"
                        + " msgSeq=%s content=%s%n",
                msg.getFromUser(), msg.isFromGroup(), msg.getMentionedBot(),
                msg.getMentionedList(), msg.getChatId(), msg.getType(),
                msg.getEventType(), msg.getMediaUrl(),
                msg.getRawFields().get("event"),
                msg.getRawFields().get("msg_seq"), msg.getContent());
        if (msg.getType() == BotInboundMessage.Type.EVENT || msg.getContent() == null) {
            System.out.println("[EVENT-RAW] " + Json.toJson(msg.getRawFields()));
            return;
        }
        String reply = "reply: " + msg.getContent();
        var result = msg.isFromGroup()
                ? client.sendToGroup(msg.getChatId(), reply)
                : client.sendText(msg.getFromUser(), reply);
        System.out.printf("[SEND] success=%s msgId=%s code=%s error=%s%n",
                result.isSuccess(), result.getMsgId(), result.getErrorCode(),
                result.getErrorMessage());
        String imageUrl = System.getenv("QQ_E2E_IMAGE_URL");
        if (msg.isFromGroup() && imageUrl != null && !imageUrl.isBlank()) {
            var media = client.send(BotOutboundMessage.builder()
                    .type(BotInboundMessage.Type.IMAGE)
                    .toUser(msg.getChatId())
                    .toGroup(true)
                    .mediaPath(imageUrl.trim())
                    .build());
            System.out.printf("[SEND-IMG] success=%s msgId=%s code=%s error=%s%n",
                    media.isSuccess(), media.getMsgId(), media.getErrorCode(),
                    media.getErrorMessage());
        }
        System.out.printf("[LINK] sessionId=%s lastSeq=%s websocket=%s groups=%d users=%d%n",
                client.getConfig().get("sessionId"), client.getConfig().get("lastSeq"),
                client.getConfig().get("websocketConnected"),
                client.listGroups().size(), client.listUsers().size());
    }

    /**
     * 读取必填环境变量。
     *
     * @param name 环境变量名，不允许为 null
     * @return 环境变量值，保证非空白
     * @throws IllegalStateException 当环境变量缺失或为空白时
     */
    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("env " + name + " is required");
        }
        return value;
    }
}
