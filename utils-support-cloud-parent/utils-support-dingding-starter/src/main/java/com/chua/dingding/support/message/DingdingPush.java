package com.chua.dingding.support.message;

import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.network.client.ClientResponse;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * 钉钉消息推送实现
 *
 * <p>基于钉钉机器人 Webhook 的消息发送实现，支持文本和 Markdown 格式。
 *
 * <h3>环境配置</h3>
 * <pre>
 *   dingding.webhookUrl   机器人 Webhook 地址（必填）
 *   dingding.secret       加签密钥（可选）
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("dingding")
@SpiDescribe(
        value = "钉钉机器人",
        type = "DINGDING",
        desc = "基于钉钉机器人 Webhook 发送文本与 Markdown 消息",
        optional = {
                @SpiParam(value = "dingding.webhookUrl", desc = "机器人 Webhook 地址", type = "String"),
                @SpiParam(value = "dingding.secret", desc = "加签密钥", type = "String")
        }
)
public class DingdingPush implements MessagePush {

    /** 消息环境 */
    private final MessageEnvironment environment;
    /** 模板映射 */
    private final Map<String, TemplateInfo> templates = new ConcurrentHashMap<>();

    public DingdingPush() {
        this(new MessageEnvironment());
    }

    public DingdingPush(MessageEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public String getProvider() {
        return "dingding";
    }

    @Override
    public MessageResponse send(MessageRequest request) throws Exception {
        long start = System.currentTimeMillis();

        String webhookUrl = environment.get("dingding.webhookUrl");
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalArgumentException("dingding.webhookUrl 配置项必填");
        }

        String secret = environment.get("dingding.secret");

        String msgType = "markdown".equalsIgnoreCase(request.getContentType()) ? "markdown" : "text";

        JsonObject json = new JsonObject();
        json.fluentPut("msgtype", msgType);

        JsonObject contentObj = new JsonObject();
        if ("markdown".equals(msgType)) {
            contentObj.fluentPut("title", request.getSubject() != null ? request.getSubject() : "");
            contentObj.fluentPut("text", request.getContent());
        } else {
            contentObj.fluentPut("content", request.getContent());
        }
        json.fluentPut(msgType, contentObj);

        String url = webhookUrl;
        if (secret != null && !secret.isBlank()) {
            long timestamp = Instant.now().toEpochMilli();
            String stringToSign = timestamp + "\n" + secret;

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String sign = Base64.getEncoder().encodeToString(hash);

            String separator = webhookUrl.contains("?") ? "&" : "?";
            url = webhookUrl + separator + "timestamp=" + timestamp + "&sign=" + sign;
        }

        ClientResponse response = HttpClientFactory.of(url)
                .header("Content-Type", "application/json")
                .connectTimeout(5000)
                .readTimeout(10000)
                .body(json.toJSONString())
                .post();

        long duration = System.currentTimeMillis() - start;

        int statusCode = response.getStatusCode();
        String responseBody = response.getBodyString();

        if (statusCode == 200 && responseBody.contains("\"errcode\":0")) {
            return MessageResponse.builder()
                    .success(true)
                    .messageId(UUID.randomUUID().toString())
                    .durationMillis(duration)
                    .build();
        } else {
            return MessageResponse.builder()
                    .success(false)
                    .errorMessage("钉钉 Webhook 返回: HTTP " + statusCode + " - " + responseBody)
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
