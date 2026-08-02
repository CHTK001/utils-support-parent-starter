package com.chua.hunyuan.support.message;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * 飞书消息推送实现
 *
 * <p>基于飞书机器人 Webhook 的消息发送实现，支持文本和富文本格式。
 *
 * <h3>环境配置</h3>
 * <pre>
 *   feishu.webhookUrl   机器人 Webhook 地址（必填）
 *   feishu.appId        飞书应用 ID（API 模式）
 *   feishu.appSecret    飞书应用密钥（API 模式）
 * </pre>
 *
 * @author CH
 * @since 2026/07/17
 */
@Spi("feishu")
@SpiDescribe(
        value = "飞书消息",
        type = "FEISHU",
        desc = "基于飞书机器人 Webhook 发送文本与富文本消息",
        optional = {
                @SpiParam(value = "feishu.webhookUrl", desc = "机器人 Webhook 地址", type = "String"),
                @SpiParam(value = "feishu.appId", desc = "飞书应用 ID", type = "String"),
                @SpiParam(value = "feishu.appSecret", desc = "飞书应用密钥", type = "String")
        }
)
@Slf4j
public class FeishuPush implements MessagePush {

    private final MessageEnvironment environment;
    private final Map<String, TemplateInfo> templates = new ConcurrentHashMap<>();

    public FeishuPush() {
        this(new MessageEnvironment());
    }

    public FeishuPush(MessageEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public String getProvider() {
        return "feishu";
    }

    @Override
    public MessageResponse send(MessageRequest request) throws Exception {
        long start = System.currentTimeMillis();

        String webhookUrl = environment.get("feishu.webhookUrl");
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalArgumentException("feishu.webhookUrl 配置项必填");
        }

        String msgType = "rich_text".equalsIgnoreCase(request.getContentType()) ? "post" : "text";

        JsonObject json = new JsonObject();

        if ("post".equals(msgType)) {
            String title = request.getSubject() != null ? request.getSubject() : "";

            JsonObject textItem = new JsonObject();
            textItem.fluentPut("tag", "text");
            textItem.fluentPut("text", request.getContent());

            JsonObject[][] contentArr = new JsonObject[1][];
            contentArr[0] = new JsonObject[]{textItem};

            JsonObject zhCn = new JsonObject();
            zhCn.fluentPut("title", title);
            zhCn.fluentPut("content", contentArr);

            JsonObject postContent = new JsonObject();
            postContent.fluentPut("zh_cn", zhCn);

            JsonObject content = new JsonObject();
            content.fluentPut("post", postContent);

            json.fluentPut("msg_type", "post");
            json.fluentPut("content", content);
        } else {
            JsonObject content = new JsonObject();
            content.fluentPut("text", request.getContent());

            json.fluentPut("msg_type", "text");
            json.fluentPut("content", content);
        }

        HttpClient httpClient = HttpClientFactory.getClient();
        ClientRequest httpRequest = ClientRequest.of(webhookUrl, HttpMethod.POST)
                .header("Content-Type", "application/json");
        httpRequest.setBody(json.toJSONString());

        ClientResponse response = httpClient.execute(httpRequest);

        long duration = System.currentTimeMillis() - start;

        int statusCode = response.getStatusCode();
        String responseBody = response.getBodyString();

        if (statusCode == 200 && responseBody.contains("\"StatusCode\":0")) {
            return MessageResponse.builder()
                    .success(true)
                    .messageId(UUID.randomUUID().toString())
                    .durationMillis(duration)
                    .build();
        } else {
            return MessageResponse.builder()
                    .success(false)
                    .errorMessage("飞书 Webhook 返回: HTTP " + statusCode + " - " + responseBody)
                    .durationMillis(duration)
                    .build();
        }
    }

    @Override
    public MessageResponse sendTemplate(String templateId, String to, Map<String, String> params) throws Exception {
        MessageRequest request = MessageRequest.builder()
                .to(to)
                .templateId(templateId)
                .templateParams(params)
                .build();
        return send(request);
    }

    @Override
    public List<TemplateInfo> listTemplates() {
        return new ArrayList<>(templates.values());
    }

    @Override
    public TemplateInfo getTemplate(String templateId) {
        return templates.get(templateId);
    }

    public void registerTemplate(TemplateInfo template) {
        templates.put(template.id(), template);
    }
}
