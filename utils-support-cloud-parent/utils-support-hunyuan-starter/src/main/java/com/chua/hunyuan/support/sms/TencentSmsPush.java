package com.chua.hunyuan.support.sms;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.spi.annotations.SpiParam;
import com.chua.common.support.task.message.MessageEnvironment;
import com.chua.common.support.task.message.MessagePush;
import com.chua.common.support.task.message.MessageRequest;
import com.chua.common.support.task.message.MessageResponse;
import com.chua.common.support.task.message.TemplateInfo;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.sms.v20190711.SmsClient;
import com.tencentcloudapi.sms.v20190711.models.SendSmsRequest;
import com.tencentcloudapi.sms.v20190711.models.SendSmsResponse;
import com.tencentcloudapi.sms.v20190711.models.SendStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * 腾讯云短信推送实现
 *
 * <p>基于腾讯云 SMS SDK（com.tencentcloudapi.sms.v20190711）的短信发送实现。
 *
 * <h3>环境配置</h3>
 * <pre>
 *   sms.secretId    腾讯云 SecretId（必填）
 *   sms.secretKey   腾讯云 SecretKey（必填）
 *   sms.appId       短信应用 ID（必填）
 *   sms.signName    短信签名（必填）
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("tencent-sms")
@SpiDescribe(
        value = "腾讯云短信",
        type = "SMS",
        desc = "基于腾讯云 SMS SDK 发送短信验证码与通知",
        optional = {
                @SpiParam(value = "sms.secretId", desc = "腾讯云 SecretId", type = "String"),
                @SpiParam(value = "sms.secretKey", desc = "腾讯云 SecretKey", type = "String"),
                @SpiParam(value = "sms.appId", desc = "短信应用 ID", type = "String"),
                @SpiParam(value = "sms.signName", desc = "短信签名", type = "String")
        }
)
@Slf4j
public class TencentSmsPush implements MessagePush {

    private final MessageEnvironment environment;
    private final Map<String, TemplateInfo> templates = new ConcurrentHashMap<>();

    public TencentSmsPush() {
        this(new MessageEnvironment());
    }

    public TencentSmsPush(MessageEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public String getProvider() {
        return "tencent-sms";
    }

    @Override
    public MessageResponse send(MessageRequest request) throws Exception {
        long start = System.currentTimeMillis();

        String secretId = environment.get("sms.secretId");
        String secretKey = environment.get("sms.secretKey");
        String appId = environment.get("sms.appId");
        String signName = environment.get("sms.signName");

        if (secretId == null || secretId.isBlank()) {
            throw new IllegalArgumentException("sms.secretId 配置项必填");
        }
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("sms.secretKey 配置项必填");
        }
        if (appId == null || appId.isBlank()) {
            throw new IllegalArgumentException("sms.appId 配置项必填");
        }
        if (signName == null || signName.isBlank()) {
            throw new IllegalArgumentException("sms.signName 配置项必填");
        }

        Credential credential = new Credential(secretId, secretKey);

        HttpProfile httpProfile = new HttpProfile();
        httpProfile.setEndpoint("sms.tencentcloudapi.com");

        ClientProfile clientProfile = new ClientProfile();
        clientProfile.setHttpProfile(httpProfile);

        SmsClient client = new SmsClient(credential, "ap-guangzhou", clientProfile);

        SendSmsRequest req = new SendSmsRequest();
        req.setPhoneNumberSet(new String[]{request.getTo()});
        req.setSmsSdkAppid(appId);
        req.setSign(signName);
        req.setTemplateID(request.getTemplateId());

        if (request.getTemplateParams() != null && !request.getTemplateParams().isEmpty()) {
            String[] params = request.getTemplateParams().values().toArray(new String[0]);
            req.setTemplateParamSet(params);
        }

        SendSmsResponse resp = client.SendSms(req);

        long duration = System.currentTimeMillis() - start;

        SendStatus[] statusSet = resp.getSendStatusSet();
        if (statusSet != null && statusSet.length > 0) {
            SendStatus status = statusSet[0];
            String code = status.getCode();
            if ("Ok".equals(code)) {
                return MessageResponse.builder()
                        .success(true)
                        .messageId(status.getSerialNo())
                        .durationMillis(duration)
                        .build();
            } else {
                return MessageResponse.builder()
                        .success(false)
                        .errorMessage(status.getCode() + ": " + status.getMessage())
                        .durationMillis(duration)
                        .build();
            }
        }

        return MessageResponse.builder()
                .success(false)
                .errorMessage("腾讯云 SMS 未返回发送状态")
                .durationMillis(duration)
                .build();
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
