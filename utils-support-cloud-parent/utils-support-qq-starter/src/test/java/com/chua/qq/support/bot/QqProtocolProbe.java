package com.chua.qq.support.bot;

import java.util.concurrent.atomic.AtomicInteger;

import com.chua.common.support.ai.bot.BotSendResult;

/**
 * QQ 协议探针：对着本地假网关跑一遍 QqBotClient，把"客户端实际打了哪些端点、
 * 发了哪些 WS 帧、监听器收到几条消息"原样打印出来。
 *
 * <p>非断言工具，用于在没有真实 QQ 凭据时定位实现与官方契约的偏差。</p>
 *
 * <pre>
 * mvn -o -q -pl utils-support-cloud-parent/utils-support-qq-starter test-compile
 * java -cp "$(cat cp.txt);target/classes;target/test-classes" com.chua.qq.support.bot.QqProtocolProbe
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class QqProtocolProbe {

    /**
     * 单聊 openid 样例。
     */
    private static final String USER_OPENID = "USER6b2f0c1e0a4d8e9f0a1b2c3d4e5f6a7b";

    /**
     * 群 openid 样例。
     */
    private static final String GROUP_OPENID = "GROUP8c3d1e2f0a4b8c9d0e1f2a3b4c5d6e7f";

    /**
     * 入站消息 id 样例。
     */
    private static final String INBOUND_MSG_ID = "ROBOT1.0_probe_inbound_msg_id";

    /**
     * 构造方法，工具类禁止实例化。
     */
    private QqProtocolProbe() {
    }

    /**
     * 主入口。
     *
     * @param args 命令行参数，未使用
     * @throws Exception 等待各阶段时线程被中断
     */
    public static void main(String[] args) throws Exception {
        try (FakeQqGateway gateway = new FakeQqGateway()) {
            stubRoutes(gateway);
            AtomicInteger inbound = new AtomicInteger();
            QqBotClient client = new QqBotClient();
            client.baseUrl(gateway.baseUrl());
            client.addMessageListener(msg -> {
                inbound.incrementAndGet();
                System.out.printf("  [INBOUND] from=%s name=%s group=%s chatId=%s msgId=%s content=%s%n",
                        msg.getFromUser(), msg.getFromUserName(), msg.isFromGroup(),
                        msg.getChatId(), msg.getMsgId(), msg.getContent());
            });
            client.addErrorListener(err ->
                    System.out.println("  [CLIENT-ERROR] " + err.getClass().getSimpleName()
                            + ": " + err.getMessage()));
            client.configure("1000018791", "probe_secret", "probe_access_token");
            client.start();
            sleep(4000L);

            System.out.println("== 1. 连接阶段客户端实际打的 REST ==");
            dumpHttp(gateway);
            System.out.println("   WS 是否握手成功: " + gateway.websocketConnected());
            System.out.println("   监听器收到入站消息: " + inbound.get() + " 条");

            System.out.println("== 2. 主动发送 ==");
            print("sendText", client.sendText(USER_OPENID, "单聊回复"));
            print("sendToGroup", client.sendToGroup(GROUP_OPENID, "群回复"));
            print("sendImage(url)", client.sendImage(USER_OPENID, "https://example.com/a.png"));
            print("sendVoice(url)", client.sendVoice(USER_OPENID, "https://example.com/a.silk"));
            sleep(1000L);
            dumpHttp(gateway);

            System.out.println("== 3. 下行一条官方结构的群事件后 ==");
            if (gateway.websocketConnected()) {
                gateway.pushDispatch(7, "GROUP_AT_MESSAGE_CREATE", groupEvent());
                sleep(1500L);
                System.out.println("   监听器收到入站消息: " + inbound.get() + " 条");
                System.out.println("   WS 上行帧:");
                for (String frame = gateway.pollClientFrame(200L); frame != null;
                        frame = gateway.pollClientFrame(200L)) {
                    System.out.println("     " + frame);
                }
            } else {
                System.out.println("   WS 未连上，跳过（事件映射改由单测用反射验证）");
            }

            client.stop();
        }
    }

    /**
     * 注册假网关各端点。
     *
     * @param gateway 假网关
     */
    private static void stubRoutes(FakeQqGateway gateway) {
        gateway.route("POST", "/app/getAppAccessToken", 200, call ->
                "{\"access_token\":\"probe_access_token\",\"expires_in\":\"7200\"}");
        gateway.route("GET", "/gateway", 200, call ->
                "{\"url\":\"" + gateway.websocketUrl() + "\"}");
        gateway.route("POST", "/v2/users/", 200, call -> call.path.endsWith("/files")
                ? "{\"file_uuid\":\"uuid_1\",\"file_info\":\"FILEINFO_1\",\"ttl\":300}"
                : "{\"id\":\"SENT_MSG_1\"}");
        gateway.route("POST", "/v2/groups/", 200, call -> "{\"id\":\"SENT_GROUP_1\"}");
    }

    /**
     * 打印已记录的 REST 调用。
     *
     * @param gateway 假网关
     */
    private static void dumpHttp(FakeQqGateway gateway) {
        if (gateway.httpCalls().isEmpty()) {
            System.out.println("   (无任何 REST 调用)");
            return;
        }
        for (FakeQqGateway.HttpCall call : gateway.httpCalls()) {
            System.out.printf("   %s %s%n      auth=%s body=%s%n",
                    call.method, call.path, call.headers.get("authorization"), call.body);
        }
    }

    /**
     * 打印发送结果。
     *
     * @param label 场景名
     * @param result 发送结果
     */
    private static void print(String label, BotSendResult result) {
        System.out.printf("   %-16s success=%s msgId=%s code=%s error=%s%n",
                label, result.isSuccess(), result.getMsgId(),
                result.getErrorCode(), result.getErrorMessage());
    }

    /**
     * 官方结构的 GROUP_AT_MESSAGE_CREATE 事件体。
     *
     * @return 事件体 JSON
     */
    private static String groupEvent() {
        return "{\"id\":\"" + INBOUND_MSG_ID + "\","
                + "\"author\":{\"id\":\"MEMB1\",\"member_openid\":\"MEMB1\","
                + "\"member_role\":\"member\",\"username\":\"小明\",\"bot\":false},"
                + "\"content\":\"探针提问\",\"group_openid\":\"" + GROUP_OPENID + "\","
                + "\"message_type\":0,\"timestamp\":\"2026-09-20T10:00:00+08:00\"}";
    }

    /**
     * 静默 sleep。
     *
     * @param millis 毫秒
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
