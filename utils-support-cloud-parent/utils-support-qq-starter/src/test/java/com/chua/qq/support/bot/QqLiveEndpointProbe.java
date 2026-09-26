package com.chua.qq.support.bot;

import com.chua.common.support.ai.bot.BotInboundMessage;
import com.chua.common.support.ai.bot.BotOutboundMessage;
import com.chua.common.support.ai.bot.BotSendResult;

/**
 * QQ 真实域名端点探针（无效凭证，仅观察平台对请求形状与路径的真实答复）。
 * <p>
 * 无凭证环境下用来区分「路径不存在」与「鉴权失败」，并校验令牌域名的推导是否正确。
 * 只发起十余次请求，跑完即退出。
 * </p>
 *
 * <pre>
 * java -Djavax.net.ssl.trustStoreType=Windows-ROOT -cp "$CP;target/classes;target/test-classes" \
 *   com.chua.qq.support.bot.QqLiveEndpointProbe
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class QqLiveEndpointProbe {

    /**
     * 探针用（无效）用户 openid。
     */
    private static final String USER_OPENID =
            "USER6b2f0c1e0a4d8e9f0a1b2c3d4e5f6a7b";

    /**
     * 探针用（无效）群 openid。
     */
    private static final String GROUP_OPENID =
            "GROUP8c3d1e2f0a4b8c9d0e1f2a3b4c5d6e7f";

    /**
     * 构造方法，工具类禁止实例化。
     */
    private QqLiveEndpointProbe() {
    }

    /**
     * 主入口。
     *
     * @param args 命令行参数，未使用
     * @throws Exception 等待期间线程被中断
     */
    public static void main(String[] args) throws Exception {
        phaseToken();
        phaseApi();
    }

    /**
     * 观察令牌域名的真实答复。
     *
     * @throws Exception 等待期间线程被中断
     */
    private static void phaseToken() throws Exception {
        System.out.println("== A. 令牌换取（无效 appId/clientSecret）==");
        QqBotClient client = new QqBotClient();
        client.configure("1000000000", "invalid_client_secret", null);
        client.addErrorListener(e -> System.out.println("   [CLIENT-ERROR] "
                + e.getClass().getSimpleName() + ": " + e.getMessage()));
        client.start();
        Thread.sleep(4000L);
        client.stop();
        System.out.println();
    }

    /**
     * 观察 REST 端点的真实答复。
     *
     * @throws Exception 等待期间线程被中断
     */
    private static void phaseApi() throws Exception {
        System.out.println("== B. REST 端点（无效 access_token）==");
        QqBotClient client = new QqBotClient();
        client.configure("1000000000", "invalid_client_secret", "invalid_access_token");
        client.addErrorListener(e -> System.out.println("   [CLIENT-ERROR] "
                + e.getClass().getSimpleName() + ": " + e.getMessage()));
        client.start();
        Thread.sleep(3000L);
        print("GET /gateway(长连接侧)", String.valueOf(client.getConfig().get("sessionId")));
        print("sendText", toText(client.sendText(USER_OPENID, "探针文本")));
        print("sendToGroup", toText(client.sendToGroup(GROUP_OPENID, "探针群文本")));
        print("sendImage(url)", toText(client.sendImage(USER_OPENID, "https://example.com/a.png")));
        print("send(群语音)", toText(client.send(BotOutboundMessage.builder()
                .type(BotInboundMessage.Type.VOICE)
                .toUser(GROUP_OPENID)
                .toGroup(true)
                .mediaPath("https://example.com/a.silk")
                .build())));
        client.stop();
    }

    /**
     * @param result 发送结果
     * @return 摘要文本
     */
    private static String toText(BotSendResult result) {
        return "success=" + result.isSuccess() + " code=" + result.getErrorCode()
                + " error=" + result.getErrorMessage();
    }

    /**
     * 打印一行观测值。
     *
     * @param label 标签
     * @param value 观测值
     */
    private static void print(String label, String value) {
        System.out.println("   " + label + " -> " + value);
    }
}
