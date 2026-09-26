package com.chua.dingding.support.bot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;

/**
 * 钉钉开放平台真实域名探针（非单元测试，无需凭证）。
 * <p>用假凭证打真实域名，采集平台原话，用于钉死端点路径与请求体字段口径：
 * 路径写错会得到 "InvalidAction.NotFound" 一类的路由级错误，路径正确而凭证错误会得到
 * 鉴权级错误，两者可区分。零写入，只读探测。</p>
 * <pre>
 * mvn.cmd -o -DskipTests=false -DforkCount=0 \
 *   -Dexec.mainClass=com.chua.dingding.support.bot.DingTalkStreamLiveProbe \
 *   -Dexec.classpathScope=test \
 *   -Djavax.net.ssl.trustStoreType=Windows-ROOT \
 *   -Dstdout.encoding=UTF-8 exec:java
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class DingTalkStreamLiveProbe {

    /**
     * 新版开放平台域名
     */
    private static final String API = "https://api.dingtalk.com";

    /**
     * 旧版域名，素材上传与自定义机器人 Webhook 使用
     */
    private static final String LEGACY = "https://oapi.dingtalk.com";

    /**
     * 输出文件，规避控制台 GBK 乱码
     */
    private static final String OUT = "D:/ch/project/.tmp/dingtalk_live_probe.txt";

    /**
     * 构造方法，工具类禁止实例化。
     */
    private DingTalkStreamLiveProbe() {
    }

    /**
     * 主入口，逐个探测端点并把响应原文写入 {@link #OUT}。
     *
     * @param args 命令行参数，未使用
     * @throws Exception 探测过程中的网络或序列化异常
     */
    public static void main(String[] args) throws Exception {
        List<String> lines = new ArrayList<>();
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15L))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        JsonObject open = new JsonObject()
                .fluent("clientId", "dingfakeclientid0000000000")
                .fluent("clientSecret", "fake-client-secret-for-probe")
                .fluent("ua", "chua-dingtalk-stream-probe/1.0")
                .fluent("localIp", "127.0.0.1")
                .fluent("subscriptions", List.of(new JsonObject()
                        .fluent("type", "CALLBACK")
                        .fluent("topic", "/v1.0/im/bot/messages/get")));
        lines.add(probe(http, "Stream 建连换取 ticket", "POST",
                API + "/v1.0/gateway/connections/open", null, Json.toJson(open)));

        JsonObject tokenBody = new JsonObject()
                .fluent("appKey", "dingfakeclientid0000000000")
                .fluent("appSecret", "fake-client-secret-for-probe");
        lines.add(probe(http, "换取 access_token", "POST",
                API + "/v1.0/oauth2/accessToken", null, Json.toJson(tokenBody)));

        String fakeToken = "fake-access-token-for-probe";
        JsonObject batchSend = new JsonObject()
                .fluent("robotCode", "dingfakeclientid0000000000")
                .fluent("userIds", List.of("manager0001"))
                .fluent("msgKey", "sampleText")
                .fluent("msgParam", Json.toJson(new JsonObject().fluent("content", "probe")));
        lines.add(probe(http, "单聊主动消息", "POST",
                API + "/v1.0/robot/oToMessages/batchSend", fakeToken, Json.toJson(batchSend)));

        JsonObject groupSend = new JsonObject()
                .fluent("robotCode", "dingfakeclientid0000000000")
                .fluent("openConversationId", "cidFakeGroup==")
                .fluent("msgKey", "sampleText")
                .fluent("msgParam", Json.toJson(new JsonObject().fluent("content", "probe")));
        lines.add(probe(http, "群聊主动消息", "POST",
                API + "/v1.0/robot/groupMessages/send", fakeToken, Json.toJson(groupSend)));

        JsonObject download = new JsonObject()
                .fluent("robotCode", "dingfakeclientid0000000000")
                .fluent("downloadCode", "fake-download-code");
        lines.add(probe(http, "富媒体下载码换地址", "POST",
                API + "/v1.0/robot/messageFiles/download", fakeToken, Json.toJson(download)));

        lines.add(probe(http, "旧版素材上传(multipart)", "POST",
                LEGACY + "/media/upload?access_token=" + fakeToken + "&type=file", null,
                multipart("media", "probe.txt", "hello dingtalk".getBytes(StandardCharsets.UTF_8))));

        lines.add(probe(http, "旧版自定义机器人 Webhook", "POST",
                LEGACY + "/robot/send?access_token=" + fakeToken, null,
                Json.toJson(new JsonObject()
                        .fluent("msgtype", "text")
                        .fluent("text", new JsonObject().fluent("content", "probe")))));

        String report = String.join(System.lineSeparator(), lines);
        System.out.println(report);
        Files.write(Paths.get(OUT), report.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 发起一次探测请求。
     *
     * @param http     HTTP 客户端
     * @param title    用例标题
     * @param method   HTTP 方法
     * @param url      完整地址
     * @param token    访问令牌，非空时写入 {@code x-acs-dingtalk-access-token}
     * @param body     请求体
     * @return 一行探测结论
     */
    private static String probe(HttpClient http, String title, String method, String url,
            String token, Object body) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("case", title);
        record.put("url", url);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20L));
            if (token != null) {
                builder.header("x-acs-dingtalk-access-token", token);
            }
            String contentType = body instanceof byte[] ? "multipart/form-data; boundary=chuaProbe"
                    : "application/json";
            builder.header("Content-Type", contentType);
            builder.method(method, publisher(body));
            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            record.put("httpStatus", response.statusCode());
            record.put("body", trim(response.body()));
        } catch (IOException e) {
            record.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            record.put("error", "interrupted");
        }
        return Json.toJson(record);
    }

    /**
     * 构造请求体发布器。
     *
     * @param body 字符串或字节数组请求体
     * @return 请求体发布器
     */
    private static HttpRequest.BodyPublisher publisher(Object body) {
        if (body instanceof byte[] bytes) {
            return HttpRequest.BodyPublishers.ofByteArray(bytes);
        }
        return HttpRequest.BodyPublishers.ofString(String.valueOf(body), StandardCharsets.UTF_8);
    }

    /**
     * 组装 multipart 表单。
     *
     * @param field    文件字段名
     * @param fileName 文件名
     * @param payload  文件内容
     * @return 表单字节
     */
    private static byte[] multipart(String field, String fileName, byte[] payload) {
        String boundary = "chuaProbe";
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + fileName
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.UTF_8);
        byte[] tailBytes = tail.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[headBytes.length + payload.length + tailBytes.length];
        System.arraycopy(headBytes, 0, all, 0, headBytes.length);
        System.arraycopy(payload, 0, all, headBytes.length, payload.length);
        System.arraycopy(tailBytes, 0, all, headBytes.length + payload.length, tailBytes.length);
        return all;
    }

    /**
     * 截断过长的响应体。
     *
     * @param text 响应体
     * @return 截断后的文本
     */
    private static String trim(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replaceAll("\\s+", " ").trim();
        return cleaned.length() > 400 ? cleaned.substring(0, 400) : cleaned;
    }
}
