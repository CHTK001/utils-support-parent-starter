package com.chua.alibaba.support.sms;

import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.spi.annotations.SpiParam;
import com.chua.common.support.task.message.MessageEnvironment;
import com.chua.common.support.task.message.MessagePush;
import com.chua.common.support.task.message.MessageRequest;
import com.chua.common.support.task.message.MessageResponse;
import com.chua.common.support.task.message.TemplateInfo;
import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.teaopenapi.models.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * 阿里云短信推送实现
 *
 * <p>基于阿里云 Dysmsapi SDK 的短信发送实现。
 *
 * <h3>环境配置</h3>
 * <pre>
 *   sms.accessKey   阿里云 AccessKey（必填）
 *   sms.secretKey   阿里云 SecretKey（必填）
 *   sms.signName    短信签名（必填）
 *   sms.templateCode 短信模板代码
 * </pre>
 *
 * @since 4.0.0.42
 */
@Spi("alibaba-sms")
@SpiDescribe(
        value = "阿里云短信",
        type = "SMS",
        desc = "基于阿里云 Dysmsapi SDK 发送短信验证码与通知",
        optional = {
                @SpiParam(value = "sms.accessKey", desc = "阿里云 AccessKey", type = "String"),
                @SpiParam(value = "sms.secretKey", desc = "阿里云 SecretKey", type = "String"),
                @SpiParam(value = "sms.signName", desc = "短信签名", type = "String"),
                @SpiParam(value = "sms.templateCode", desc = "短信模板编码", type = "String")
        }
)
@Slf4j
public class AlibabaSmsPush implements MessagePush {

    /** 消息环境 */
    /** 环境 */
    private final MessageEnvironment environment;
    /** 模板映射 */
    private final Map<String, TemplateInfo> templates = new ConcurrentHashMap<>();

    public AlibabaSmsPush() {
        this(new MessageEnvironment());
    }

    public AlibabaSmsPush(MessageEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public String getProvider() {
        return "alibaba-sms";
    }

    @Override
    public MessageResponse send(MessageRequest request) throws Exception {
        long start = System.currentTimeMillis();

        String accessKey = environment.get("sms.accessKey");
        String secretKey = environment.get("sms.secretKey");
        String signName = environment.get("sms.signName");

        if (accessKey == null || accessKey.isBlank()) {
            throw new IllegalArgumentException("sms.accessKey 配置项必填");
        }
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("sms.secretKey 配置项必填");
        }
        if (signName == null || signName.isBlank()) {
            throw new IllegalArgumentException("sms.signName 配置项必填");
        }

        Config config = new Config()
                .setAccessKeyId(accessKey)
                .setAccessKeySecret(secretKey)
                .setEndpoint("dysmsapi.aliyuncs.com");

        Client client = new Client(config);

        SendSmsRequest req = new SendSmsRequest();
        req.setPhoneNumbers(request.getTo());
        req.setSignName(signName);
        req.setTemplateCode(request.getTemplateId());

        if (request.getTemplateParams() != null && !request.getTemplateParams().isEmpty()) {
            JsonObject paramJson = new JsonObject();
            for (Map.Entry<String, String> entry : request.getTemplateParams().entrySet()) {
                paramJson.fluentPut(entry.getKey(), entry.getValue());
            }
            req.setTemplateParam(paramJson.toJSONString());
        }

        SendSmsResponse resp = client.sendSms(req);

        long duration = System.currentTimeMillis() - start;

        String code = resp.getBody().getCode();
        if ("OK".equals(code)) {
            return MessageResponse.builder()
                    .success(true)
                    .messageId(resp.getBody().getBizId())
                    .durationMillis(duration)
                    .build();
        } else {
            return MessageResponse.builder()
                    .success(false)
                    .errorMessage(code + ": " + resp.getBody().getMessage())
                    .durationMillis(duration)
                    .build();
        }
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

    @Override
    public MessageResponse sendTemplate(String templateId, String to, Map<String, String> params) throws Exception {
        MessageRequest request = MessageRequest.builder()
                .to(to)
                .templateId(templateId)
                .templateParams(params)
                .build();
        return send(request);
    }
}
