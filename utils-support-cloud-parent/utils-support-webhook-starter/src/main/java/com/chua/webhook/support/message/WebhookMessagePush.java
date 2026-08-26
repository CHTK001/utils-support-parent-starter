package com.chua.webhook.support.message;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.network.client.ClientRequest;
import com.chua.common.support.network.client.ClientResponse;
import com.chua.common.support.network.client.HttpClient;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.spi.annotations.SpiParam;
import com.chua.common.support.task.message.MessageEnvironment;
import com.chua.common.support.task.message.MessagePush;
import com.chua.common.support.task.message.MessageRequest;
import com.chua.common.support.task.message.MessageResponse;
import com.chua.common.support.task.message.TemplateInfo;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用 Webhook 消息推送实现
 * <p>
 * 向任意 Webhook 地址发送消息，兼容钉钉、企业微信群机器人及自建网关。
 * 按 contentType 选择消息格式：
 * <ul>
 *   <li>text（默认）：{@code {"msgtype":"text","text":{"content":"..."}}}，兼容钉钉/企业微信</li>
 *   <li>markdown：{@code {"msgtype":"markdown","markdown":{"content":"..."}}}，兼容钉钉/企业微信</li>
 *   <li>json/raw：将消息内容作为原始 JSON 请求体透传，适合自建网关</li>
 * </ul>
 * </p>
 *
 * <h3>环境配置</h3>
 * <pre>
 *   webhook.url  Webhook 地址（必填）
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("webhook")
@SpiDescribe(
        value = "Webhook 推送",
        type = "WEBHOOK",
        desc = "发送文本/Markdown 到任意 Webhook 地址（钉钉/企业微信/自建网关）",
        optional = {
                @SpiParam(value = "webhook.url", desc = "Webhook 地址", type = "String")
        }
)
/**
 * public class WebhookMessagePush implements MessagePush {
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class WebhookMessagePush implements MessagePush {

    /**
     * 推送文本格式（钉钉/企业微信通用）
     */
    private static final String CONTENT_TYPE_TEXT = "text";

    /**
     * 推送 Markdown 格式
     */
    private static final String CONTENT_TYPE_MARKDOWN = "markdown";

    /**
     * 原始 JSON 透传格式
     */
    private static final String CONTENT_TYPE_RAW = "raw";

    /** 消息环境 */
    private final MessageEnvironment environment;

    /** 模板映射 */
    private final Map<String, TemplateInfo> templates = new ConcurrentHashMap<>();

    /** 创建 WebhookPush 实例 */
    public WebhookPush() {
        this(new MessageEnvironment());
    }

    /**
     * 创建 WebhookPush 实例
     * @param environment environment
     */
    public WebhookPush(MessageEnvironment environment) {
        this.environment = environment;
    }

    @Override
    /** 获取Provider */
    public String getProvider() {
        return "webhook";
    }

    @Override
    /**
     * 发送
     * @param request request
     */
    public MessageResponse send(MessageRequest request) {
        long start = System.currentTimeMillis();
        String webhookUrl = environment.get("webhook.url");
        if (StringUtils.isBlank(webhookUrl)) {
            return MessageResponse.failure("webhook.url 配置项必填");
        }
        try {
            String contentType = request.getContentType();
            String body = buildRequestBody(contentType, request.getContent());
            ClientRequest httpRequest = ClientRequest.of(webhookUrl, HttpMethod.POST)
                    .header("Content-Type", "application/json");
            httpRequest.setBody(body);
            HttpClient httpClient = HttpClientFactory.getClient();
            ClientResponse response = httpClient.execute(httpRequest);
            return parseResult(response.getStatusCode(), response.getBodyString(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[Webhook 推送] 发送失败, url={}", webhookUrl, e);
            return MessageResponse.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .durationMillis(System.currentTimeMillis() - start)
                    .build();
        }
    }

    /**
     * 构建请求体
     *
     * @param contentType 内容类型
     * @param content     消息内容
     * @return 请求体 JSON 字符串
     */
    private String buildRequestBody(String contentType, String content) {
        String normalized = contentType == null ? "" : contentType.toLowerCase();
        if (normalized.contains(CONTENT_TYPE_MARKDOWN)) {
            JsonObject body = new JsonObject();
            JsonObject markdown = new JsonObject();
            markdown.fluentPut("content", content);
            body.fluentPut("msgtype", CONTENT_TYPE_MARKDOWN);
            body.fluentPut("markdown", markdown);
            return body.toJSONString();
        }
        if (normalized.contains(CONTENT_TYPE_RAW) || normalized.contains("json")) {
            try {
                return Json.getJsonObject(content).toJSONString();
            } catch (Exception ignored) {
                // 内容不是合法 JSON 时回退为文本格式
            }
        }
        JsonObject body = new JsonObject();
        JsonObject text = new JsonObject();
        text.fluentPut("content", content);
        body.fluentPut("msgtype", CONTENT_TYPE_TEXT);
        body.fluentPut("text", text);
        return body.toJSONString();
    }

    /**
     * 解析 Webhook 返回结果
     *
     * @param statusCode HTTP 状态码
     * @param body       响应体
     * @param duration   耗时（毫秒）
     * @return 消息响应
     */
    private MessageResponse parseResult(int statusCode, String body, long duration) {
        if (statusCode >= 200 && statusCode < 300) {
            Integer errCode = extractErrorCode(body);
            if (errCode != null && errCode != 0) {
                return MessageResponse.builder()
                        .success(false)
                        .errorMessage("Webhook 返回错误: " + body)
                        .durationMillis(duration)
                        .build();
            }
            return MessageResponse.builder()
                    .success(true)
                    .messageId(UUID.randomUUID().toString())
                    .durationMillis(duration)
                    .build();
        }
        return MessageResponse.builder()
                .success(false)
                .errorMessage("Webhook HTTP " + statusCode + ": " + body)
                .durationMillis(duration)
                .build();
    }

    /**
     * 从响应体提取错误码（钉钉/企业微信返回 errcode 字段）
     *
     * @param body 响应体
     * @return 错误码，无该字段返回 null
     */
    private Integer extractErrorCode(String body) {
        if (StringUtils.isBlank(body)) {
            return null;
        }
        try {
            JsonObject json = Json.getJsonObject(body);
            Object errCode = json.get("errcode");
            if (errCode == null) {
                return null;
            }
            if (errCode instanceof Number number) {
                return number.intValue();
            }
            return Integer.parseInt(String.valueOf(errCode));
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    /** ListTemplates */
    public List<TemplateInfo> listTemplates() {
        return new ArrayList<>(templates.values());
    }

    @Override
    /**
     * 获取Template
     * @param templateId templateId
     */
    public TemplateInfo getTemplate(String templateId) {
        return templates.get(templateId);
    }

    /**
     * 注册模板
     *
     * @param template 模板信息
     */
    public void registerTemplate(TemplateInfo template) {
        templates.put(template.id(), template);
    }
}
