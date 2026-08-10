package com.chua.openai.support;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatResponse;

/**
 * 豆包客户端集成测试（手动）
 *
 * <p>运行方式：在 common-starter 安装后，通过 java -cp 或 IDE 运行 main 方法。
 *
 * <p>注意：
 * <ul>
 *   <li>需要有效的豆包 Cookie（含 sessionid、ttwid、passport_csrf_token）</li>
 *   <li>逆向接口随时变化，cookie 可能被风控</li>
 *   <li>建议先使用 {@link #verifySpiLoad(String)} 验证 SPI 加载，再决定是否发起实际请求</li>
 * </ul>
 *
 * @author CH
 * @since 2026/08/10
 */
public final class DoubaoChatClientSmokeTest {

    private DoubaoChatClientSmokeTest() {
    }

    /**
     * 验证 SPI 加载和客户端实例化（不发请求）
     *
     * @param cookie 任意非空字符串作为占位 appkey
     * @return 创建的 ChatClient 实例
     */
    public static ChatClient verifySpiLoad(String cookie) {
        ChatClient client = ChatClient.create("doubao", cookie);
        if (client == null) {
            throw new IllegalStateException("SPI 加载失败：未找到 provider=doubao 的实现");
        }
        if (!(client instanceof DoubaoChatClient)) {
            throw new IllegalStateException("SPI 加载异常：类型=" + client.getClass().getName());
        }
        System.out.println("[Doubao] SPI 加载成功: " + client.getClass().getName());
        return client;
    }

    /**
     * 同步调用示例（需要有效 cookie）
     *
     * @param cookie 完整 cookie 字符串
     * @param prompt 用户输入
     */
    public static void syncChat(String cookie, String prompt) {
        ChatClient client = ChatClient.create("doubao", cookie);
        client.model("doubao");
        client.system("你是一名简洁的助手");
        System.out.println("[Doubao] 同步调用开始...");
        String result = client.chatSync(prompt);
        System.out.println("[Doubao] 响应: " + result);
    }

    /**
     * 流式调用示例（需要有效 cookie）
     *
     * @param cookie    完整 cookie 字符串
     * @param prompt    用户输入
     */
    public static void streamChat(String cookie, String prompt) {
        ChatClient client = ChatClient.create("doubao-think", cookie);
        client.model("doubao-think");
        StringBuilder buffer = new StringBuilder();
        client.chat(prompt, response -> {
            switch (response.getState()) {
                case START:
                    System.out.println("[Doubao] 开始");
                    break;
                case STREAMING:
                    if (response.getReasoningContent() != null) {
                        System.out.print("[思考]" + response.getReasoningContent());
                    }
                    if (response.getContent() != null) {
                        buffer.append(response.getContent());
                        System.out.print(response.getContent());
                    }
                    break;
                case STOP:
                    System.out.println("\n[Doubao] 完成");
                    if (response.getUsage() != null) {
                        System.out.println("[Doubao] 用量: " + response.getUsage());
                    }
                    break;
                case ERROR:
                    System.err.println("[Doubao] 错误: " + response.getErrorMessage());
                    break;
                default:
                    break;
            }
        });
        System.out.println("[Doubao] 完整响应: " + buffer);
    }

    /**
     * 主入口（命令行）
     *
     * <pre>
     * 用法：
     *   java -cp ... com.chua.openai.support.DoubaoChatClientSmokeTest verify <占位cookie>
     *   java -cp ... com.chua.openai.support.DoubaoChatClientSmokeTest sync   <cookie> <prompt>
     *   java -cp ... com.chua.openai.support.DoubaoChatClientSmokeTest stream <cookie> <prompt>
     * </pre>
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("用法:");
            System.err.println("  verify <cookie>          验证 SPI 加载");
            System.err.println("  sync   <cookie> <prompt> 同步调用");
            System.err.println("  stream <cookie> <prompt> 流式调用");
            System.exit(1);
        }
        String mode = args[0];
        switch (mode) {
            case "verify":
                verifySpiLoad(args[1]);
                break;
            case "sync":
                syncChat(args[1], args[2]);
                break;
            case "stream":
                streamChat(args[1], args[2]);
                break;
            default:
                System.err.println("未知模式: " + mode);
                System.exit(2);
                break;
        }
    }
}
