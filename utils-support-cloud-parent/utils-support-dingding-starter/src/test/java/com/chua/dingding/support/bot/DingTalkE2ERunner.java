package com.chua.dingding.support.bot;

import com.chua.common.support.ai.bot.BotInboundMessage;
import com.chua.common.support.ai.bot.BotOutboundMessage;
import com.chua.common.support.lang.json.Json;

/**
 * 钉钉机器人端到端联调入口（非单元测试，需人工配合收发消息）。
 * <p>
 * 走 Stream 长连接，本机不需要公网地址与回调域名。企业机器人只推送 @ 它的群消息与单聊消息，
 * 入站帧自带 {@code sessionWebhook} 被动回复窗口（默认约 90 分钟），客户端在窗口内优先被动回复，
 * 窗口过期后回落开放平台主动消息接口。
 * </p>
 * <pre>
 * DINGTALK_CLIENT_ID=xxx DINGTALK_CLIENT_SECRET=yyy \
 *   mvn.cmd -o -DskipTests=false -DforkCount=0 \
 *     -Dexec.mainClass=com.chua.dingding.support.bot.DingTalkE2ERunner \
 *     -Dexec.classpathScope=test \
 *     -Djavax.net.ssl.trustStoreType=Windows-ROOT \
 *     -Dstdout.encoding=UTF-8 exec:java
 * </pre>
 * <p>可选环境变量：{@code DINGTALK_ROBOT_CODE}（默认取 Client ID）、
 * {@code DINGTALK_API_BASE_URL}、{@code DINGTALK_LEGACY_BASE_URL}、
 * {@code DINGTALK_IDLE_TIMEOUT_MS}、{@code DINGTALK_E2E_IMAGE_PATH}、
 * {@code DINGTALK_E2E_FILE_PATH}、{@code DINGTALK_E2E_TO_USER}、{@code DINGTALK_E2E_GROUP_ID}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class DingTalkE2ERunner {

    /**
     * 构造方法，工具类禁止实例化。
     */
    private DingTalkE2ERunner() {
    }

    /**
     * 主入口，读取环境变量凭据后启动 Stream 长连接并自动回复。
     *
     * @param args 命令行参数，未使用
     * @throws Exception 当等待连接期间线程被中断时
     */
    public static void main(String[] args) throws Exception {
        String clientId = require("DINGTALK_CLIENT_ID");
        String clientSecret = require("DINGTALK_CLIENT_SECRET");

        DingTalkBotClient client = new DingTalkBotClient();
        client.token(clientId).secret(clientSecret);
        String robotCode = System.getenv("DINGTALK_ROBOT_CODE");
        if (robotCode != null && !robotCode.isBlank()) {
            client.robotCode(robotCode.trim());
        }
        String apiBaseUrl = System.getenv("DINGTALK_API_BASE_URL");
        if (apiBaseUrl != null && !apiBaseUrl.isBlank()) {
            client.baseUrl(apiBaseUrl.trim());
        }
        String legacyBaseUrl = System.getenv("DINGTALK_LEGACY_BASE_URL");
        if (legacyBaseUrl != null && !legacyBaseUrl.isBlank()) {
            client.legacyBaseUrl(legacyBaseUrl.trim());
        }
        String idle = System.getenv("DINGTALK_IDLE_TIMEOUT_MS");
        if (idle != null && !idle.isBlank()) {
            client.idleTimeoutMillis(Long.parseLong(idle.trim()));
        }
        client.addErrorListener(e -> System.out.println("[ERROR] " + e.getMessage()));
        client.addMessageListener(msg -> onMessage(client, msg));
        client.start();

        System.out.println("== DingTalk E2E 已启动(Stream 长连接), "
                + "请在钉钉单聊机器人或在群里 @ 机器人发消息 ==");
        System.out.println("[CONFIG] " + Json.toJson(client.getConfig()));
        proactive(client, "DINGTALK_E2E_TO_USER", false);
        proactive(client, "DINGTALK_E2E_GROUP_ID", true);
        boolean connected = client.isWebSocketConnected();
        while (client.isRunning()) {
            Thread.sleep(1000L);
            boolean now = client.isWebSocketConnected();
            if (now == connected) {
                continue;
            }
            connected = now;
            System.out.println("[LINK] websocket=" + now + " " + Json.toJson(client.getConfig()));
        }
        System.out.println("[EXIT] 客户端已停止");
    }

    /**
     * 按环境变量做主动推送，用于验证不依赖入站窗口的发送链路。
     *
     * @param client  钉钉客户端
     * @param envName 目标 ID 环境变量名
     * @param toGroup 是否群聊
     */
    private static void proactive(DingTalkBotClient client, String envName, boolean toGroup) {
        String target = System.getenv(envName);
        if (target == null || target.isBlank()) {
            return;
        }
        String id = target.trim();
        var result = toGroup
                ? client.sendToGroup(id, "proactive: hello from stream client")
                : client.sendText(id, "proactive: hello from stream client");
        System.out.printf("[SEND-ACTIVE] group=%s success=%s msgId=%s code=%s error=%s%n",
                toGroup, result.isSuccess(), result.getMsgId(), result.getErrorCode(),
                result.getErrorMessage());
    }

    /**
     * 收到入站消息后打印详情并回发文本与当前连接观测值。
     *
     * @param client 钉钉客户端，不允许为 null
     * @param msg    入站消息，不允许为 null
     */
    private static void onMessage(DingTalkBotClient client, BotInboundMessage msg) {
        System.out.printf("[RECV] from=%s name=%s group=%s mentionedBot=%s chatId=%s type=%s"
                        + " mediaId=%s mediaUrl=%s content=%s%n",
                msg.getFromUser(), msg.getFromUserName(), msg.isFromGroup(),
                msg.getMentionedBot(), msg.getChatId(), msg.getType(), msg.getMediaId(),
                msg.getMediaUrl(), msg.getContent());
        System.out.println("[RAW] " + Json.toJson(msg.getRawFields()));
        if (msg.getContent() == null) {
            return;
        }
        String reply = "reply: " + msg.getContent();
        var result = msg.isFromGroup()
                ? client.sendToGroup(msg.getChatId(), reply)
                : client.sendText(msg.getFromUser(), reply);
        System.out.printf("[SEND] success=%s msgId=%s code=%s error=%s%n",
                result.isSuccess(), result.getMsgId(), result.getErrorCode(),
                result.getErrorMessage());
        String image = System.getenv("DINGTALK_E2E_IMAGE_PATH");
        if (image != null && !image.isBlank()) {
            var media = client.send(BotOutboundMessage.builder()
                    .type(BotInboundMessage.Type.IMAGE)
                    .toUser(msg.isFromGroup() ? msg.getChatId() : msg.getFromUser())
                    .toGroup(msg.isFromGroup())
                    .mediaPath(image.trim())
                    .build());
            System.out.printf("[SEND-IMG] success=%s code=%s error=%s%n", media.isSuccess(),
                    media.getErrorCode(), media.getErrorMessage());
        }
        String file = System.getenv("DINGTALK_E2E_FILE_PATH");
        if (file != null && !file.isBlank()) {
            var media = client.sendFile(msg.isFromGroup() ? msg.getChatId() : msg.getFromUser(),
                    file.trim());
            System.out.printf("[SEND-FILE] success=%s code=%s error=%s%n", media.isSuccess(),
                    media.getErrorCode(), media.getErrorMessage());
        }
        System.out.printf("[LINK] mode=%s websocket=%s endpoint=%s groups=%d users=%d sessions=%d%n",
                client.getConfig().get("mode"), client.getConfig().get("streamConnected"),
                client.getConfig().get("gatewayEndpoint"), client.listGroups().size(),
                client.listUsers().size(), client.getConfig().get("observedSessions"));
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
