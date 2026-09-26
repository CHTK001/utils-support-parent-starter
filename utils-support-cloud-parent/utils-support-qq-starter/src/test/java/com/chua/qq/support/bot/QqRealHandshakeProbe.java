package com.chua.qq.support.bot;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.chua.common.support.ai.bot.BotInboundMessage;
import com.chua.common.support.lang.json.Json;

/**
 * QQ 真机握手探针（非单元测试，需真实凭据）。
 *
 * <pre>
 * QQ_APP_ID=xxx QQ_APP_SECRET=yyy \
 *   java -Dstdout.encoding=UTF-8 -cp "$CP;target/classes;target/test-classes" \
 *     com.chua.qq.support.bot.QqRealHandshakeProbe
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class QqRealHandshakeProbe {

    /**
     * 构造方法，工具类禁止实例化。
     */
    private QqRealHandshakeProbe() {
    }

    /**
     * 主入口，依次验证令牌换取、网关地址、长连接鉴权与事件接收。
     *
     * @param args 命令行参数，未使用
     * @throws Exception 当反射调用私有方法失败或等待超时被中断时
     */
    public static void main(String[] args) throws Exception {
        String appId = require("QQ_APP_ID");
        String appSecret = require("QQ_APP_SECRET");
        String baseUrl = System.getenv("QQ_BASE_URL");
        int waitSeconds = Integer.parseInt(
                System.getenv().getOrDefault("QQ_WAIT_SECONDS", "30"));

        QqBotClient client = new QqBotClient();
        client.configure(appId, appSecret, null);
        if (baseUrl != null && !baseUrl.isBlank()) {
            client.baseUrl(baseUrl);
        }
        String intents = System.getenv("QQ_INTENTS");
        if (intents != null && !intents.isBlank()) {
            client.intents(Integer.parseInt(intents.trim()));
        }
        AtomicInteger inbound = new AtomicInteger();
        client.addErrorListener(error ->
                System.out.println("   [ERR-LISTENER] " + error));
        client.addMessageListener(msg -> {
            int n = inbound.incrementAndGet();
            System.out.println("   [INBOUND#" + n + "] " + describe(msg));
        });

        System.out.println("== 配置 baseUrl=" + client.getConfig().get("baseUrl")
                + " tokenUrl=" + client.getConfig().get("tokenUrl")
                + " intents=" + client.getConfig().get("intents"));

        System.out.println("== A. 真实令牌换取 ==");
        String authorization = null;
        try {
            client.start();
            Method getAuthToken = QqBotClient.class
                    .getDeclaredMethod("getAuthToken");
            getAuthToken.setAccessible(true);
            authorization = (String) getAuthToken.invoke(client);
            System.out.println("   OK 头长度=" + authorization.length()
                    + " 掩码=" + mask(authorization));
        } catch (Exception error) {
            System.out.println("   FAIL " + rootCause(error));
        }

        if (authorization != null) {
            System.out.println("== B. 真实网关地址 ==");
            try {
                Method getGatewayUrl = QqBotClient.class
                        .getDeclaredMethod("getGatewayUrl", String.class);
                getGatewayUrl.setAccessible(true);
                Object url = getGatewayUrl.invoke(client, authorization);
                System.out.println("   " + hostOf(String.valueOf(url)));
            } catch (Exception error) {
                System.out.println("   FAIL " + rootCause(error));
            }
        }

        System.out.println("== C. 长连接鉴权与 READY（等待 "
                + waitSeconds + " 秒）==");
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000L);
            Map<String, Object> config = client.getConfig();
            if (config.get("sessionId") != null) {
                break;
            }
        }
        System.out.println("   " + Json.toJson(client.getConfig()));
        System.out.println("   入站消息 " + inbound.get() + " 条");
        client.stop();
        System.out.println("== 探针结束 ==");
    }

    /**
     * 描述入站消息关键字段。
     *
     * @param msg 入站消息，不允许为 null
     * @return 单行描述文本
     */
    private static String describe(BotInboundMessage msg) {
        return "from=" + msg.getFromUser() + " name=" + msg.getFromUserName()
                + " group=" + msg.isFromGroup() + " chatId=" + msg.getChatId()
                + " msgId=" + msg.getMsgId() + " type=" + msg.getType()
                + " mentionedBot=" + msg.getMentionedBot()
                + " mentioned=" + msg.getMentionedList()
                + " event=" + msg.getRawFields().get("event")
                + " content=" + msg.getContent();
    }

    /**
     * 掩码显示令牌，只保留前后各若干字符。
     *
     * @param value 原始令牌，可为 null
     * @return 掩码文本
     */
    private static String mask(String value) {
        if (value == null || value.length() < 16) {
            return String.valueOf(value);
        }
        return value.substring(0, 10) + "..." + value.substring(value.length() - 4)
                + "(len=" + value.length() + ")";
    }

    /**
     * 只保留网关地址的协议与主机部分，避免泄露签名参数。
     *
     * @param url 网关地址，不为 null
     * @return 协议与主机文本
     */
    private static String hostOf(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return "unparsed " + mask(url);
        }
        int path = url.indexOf('/', scheme + 3);
        return path < 0 ? url : url.substring(0, path);
    }

    /**
     * 取异常链根因文本。
     *
     * @param error 异常，不允许为 null
     * @return 根因描述
     */
    private static String rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + current.getMessage();
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
