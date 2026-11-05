// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.skywalking.support.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Apache SkyWalking OAP GraphQL 客户端。
 * <p>
 * 只依赖 JDK HttpClient + Jackson，<b>不引入</b> {@code org.apache.skywalking} 任何构件：
 * </p>
 * <ul>
 *   <li>SW 官方 Java 客户端只提供 agent/plugin 与 OAP 服务端依赖，没有「查询客户端」；</li>
 *   <li>UI 与后端交互的唯一稳定契约就是 OAP 的 HTTP GraphQL（默认 12800 端口），
 *       自己发 HTTP 比拉一堆 SkyWalking jar 更轻、也不会跟 agent 的版本打架。</li>
 * </ul>
 * <p>
 * 解析策略是「宽容解析」：直接按字段名取 {@link JsonNode}，不生成与 schema 一一对应的
 * POJO。这样 OAP 9.x / 10.x / 11.x 之间字段增删只会导致某个字段取不到（返回 null），
 * 不会让整个查询在反序列化阶段炸掉。
 * </p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
public class SkywalkingOapClient implements AutoCloseable {

    /**
     * GraphQL 端点路径
     */
    private static final String GRAPHQL_PATH = "/graphql";

    /**
     * JSON 解析器（线程安全，可共享）
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String endpoint;
    private final String authorization;
    private final Duration timeout;

    /**
     * 使用进程级设置创建客户端
     */
    public SkywalkingOapClient() {
        this(SkywalkingSettings.url(),
                SkywalkingSettings.username(),
                SkywalkingSettings.password(),
                SkywalkingSettings.timeout());
    }

    /**
     * 创建客户端
     *
     * @param endpoint   OAP 地址，如 {@code http://127.0.0.1:12800}
     * @param username   用户名，可空
     * @param password   密码，可空
     * @param timeoutMs  单次请求超时毫秒
     */
    public SkywalkingOapClient(String endpoint, String username, String password, long timeoutMs) {
        this.endpoint = SkywalkingSettings.normalize(endpoint);
        long safeTimeout = timeoutMs > 0 ? timeoutMs : SkywalkingSettings.DEFAULT_TIMEOUT;
        this.timeout = Duration.ofMillis(safeTimeout);
        this.authorization = basic(username, password);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * OAP 地址（不含 /graphql）
     *
     * @return 地址
     */
    public String endpoint() {
        return endpoint;
    }

    /**
     * 执行 GraphQL 查询并返回 {@code data} 节点
     *
     * @param query GraphQL 语句
     * @return data 节点
     * @throws SkywalkingException 网络失败、HTTP 非 2xx、GraphQL 返回 errors
     */
    public JsonNode data(String query) {
        return data(query, Map.of());
    }

    /**
     * 执行带变量的 GraphQL 查询并返回 {@code data} 节点
     *
     * @param query     GraphQL 语句
     * @param variables 变量
     * @return data 节点
     * @throws SkywalkingException 网络失败、HTTP 非 2xx、GraphQL 返回 errors
     */
    public JsonNode data(String query, Map<String, Object> variables) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("query", query);
        payload.set("variables", MAPPER.valueToTree(
                variables == null ? Map.of() : new LinkedHashMap<>(variables)));
        String body = payload.toString();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + GRAPHQL_PATH))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (!authorization.isEmpty()) {
            builder.header("Authorization", authorization);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkywalkingException("查询 SkyWalking 被中断: " + endpoint, e);
        } catch (Exception e) {
            throw new SkywalkingException("SkyWalking OAP 不可达(" + endpoint + "): " + e.getMessage(), e);
        }

        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new SkywalkingException("SkyWalking OAP 鉴权失败(" + status + ")，请检查 skywalking.username/password");
        }
        if (status < 200 || status >= 300) {
            String preview = abbreviate(response.body());
            throw new SkywalkingException("SkyWalking OAP 返回 HTTP " + status + ": " + preview);
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new SkywalkingException("SkyWalking OAP 响应不是合法 JSON: " + abbreviate(response.body()), e);
        }
        if (root == null) {
            throw new SkywalkingException("SkyWalking OAP 响应为空");
        }
        JsonNode errors = root.get("errors");
        if (errors != null && errors.isArray() && !errors.isEmpty()) {
            throw new SkywalkingException("SkyWalking GraphQL 报错: " + errors.get(0).path("message").asText());
        }
        JsonNode data = root.get("data");
        if (data == null || data.isNull()) {
            throw new SkywalkingException("SkyWalking GraphQL 响应缺少 data 节点");
        }
        return data;
    }

    /**
     * 探测 OAP 版本
     *
     * @return 版本号，取不到返回 {@code unknown}
     */
    public String version() {
        try {
            JsonNode node = data("query { version }");
            String version = node.path("version").asText("");
            return version.isEmpty() ? "unknown" : version;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 构建 Basic 鉴权头
     *
     * @param username 用户名
     * @param password 密码
     * @return 头值，未配置返回空串
     */
    private static String basic(String username, String password) {
        if (username == null || username.isBlank()) {
            return "";
        }
        String raw = username + ":" + (password == null ? "" : password);
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 截断长响应，避免异常消息被刷爆
     *
     * @param text 原文
     * @return 截断后的文本
     */
    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    @Override
    public void close() {
        try {
            httpClient.close();
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("[SkyWalking] 关闭 HTTP 客户端失败: {}", e.getMessage());
            }
        }
    }
}