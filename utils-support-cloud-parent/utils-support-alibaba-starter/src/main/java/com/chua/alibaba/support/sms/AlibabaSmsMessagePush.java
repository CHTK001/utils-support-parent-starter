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
import com.aliyun.dysmsapi20170525.models.QuerySmsTemplateListRequest;
import com.aliyun.dysmsapi20170525.models.QuerySmsTemplateListResponse;
import com.aliyun.dysmsapi20170525.models.QuerySmsTemplateListResponseBody.QuerySmsTemplateListResponseBodySmsTemplateList;
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
 * @author CH
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
/**
 * 公共 类 alibabasms消息push implements 消息push {
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class AlibabaSmsMessagePush implements MessagePush {

    /**
     * 消息环境
     */
    private final MessageEnvironment environment;
    /**
     * 模板映射
     */
    private final Map<String, TemplateInfo> templates = new ConcurrentHashMap<>();

    /**
     * 创建 alibabasms消息push 实例
     */
    public AlibabaSmsMessagePush() {
        this(new MessageEnvironment());
    }

    /**
     * 创建 alibabasms消息push 实例
     * @param environment 环境
     */
    public AlibabaSmsMessagePush(MessageEnvironment environment) {
        this.environment = environment;
    }

    @Override
    /**
     * 获取提供者
     */
    public String getProvider() {
        return "alibaba-sms";
    }

    @Override
    /**
     * 发送
     * @param request 请求
     */
    public MessageResponse send(MessageRequest request) throws Exception {
        long start = System.currentTimeMillis();

        String signName = environment.get("sms.signName");
        if (signName == null || signName.isBlank()) {
            throw new IllegalArgumentException("sms.signName 配置项必填");
        }

        Client client = buildClient();

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

    /**
     * 拉取运营商已审核通过的短信模板列表
     *
     * <p>调用阿里云 QuerySmsTemplateList 分页拉取，仅保留审核状态为 AUDIT_PASS 的模板，
     * 模板内容中的 ${var} 占位符由调用方解析为变量 schema。AK 无权限或查询失败时抛出异常，
     * 由上层捕获后转成运营商错误信息返回。手工登记模板码作为兜底。
     *
     * @return 运营商已审核模板列表
     */
    @Override
    /**
     * 列表templates
     */
    public List<TemplateInfo> listTemplates() {
        List<TemplateInfo> result = new ArrayList<>();
        try {
            Client client = buildClient();
            int pageIndex = 1;
            int pageSize = 50;
            while (true) {
                QuerySmsTemplateListRequest req = new QuerySmsTemplateListRequest()
                        .setPageIndex(pageIndex)
                        .setPageSize(pageSize);
                QuerySmsTemplateListResponse resp = client.querySmsTemplateList(req);
                if (null == resp || null == resp.getBody()) {
                    break;
                }
                if (!"OK".equals(resp.getBody().getCode())) {
                    throw new RuntimeException("查询短信模板列表失败: " + resp.getBody().getCode()
                            + ": " + resp.getBody().getMessage());
                }
                List<QuerySmsTemplateListResponseBodySmsTemplateList> list = resp.getBody().getSmsTemplateList();
                if (null == list || list.isEmpty()) {
                    break;
                }
                for (QuerySmsTemplateListResponseBodySmsTemplateList item : list) {
                    String templateCode = item.getTemplateCode();
                    if (null == templateCode || templateCode.isBlank()) {
                        continue;
                    }
                    // 仅同步审核通过的模板
                    if (null != item.getAuditStatus() && !"AUDIT_PASS".equals(item.getAuditStatus())) {
                        continue;
                    }
                    result.add(new TemplateInfo(templateCode, item.getTemplateName(),
                            item.getTemplateContent(), "sms", null));
                }
                Long total = resp.getBody().getTotalCount();
                if (null == total || (long) pageIndex * pageSize >= total) {
                    break;
                }
                pageIndex++;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("查询短信模板列表失败: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    /**
     * 获取Template
     * @param templateId templateid
     */
    public TemplateInfo getTemplate(String templateId) {
        return templates.get(templateId);
    }

    /**
     * 注册Template
     * @param template template
     */
    public void registerTemplate(TemplateInfo template) {
        templates.put(template.id(), template);
    }

    @Override
    /**
     * 发送Template
     * @param templateId templateid
     * @param to 转为
     * @param params 参数
     */
    public MessageResponse sendTemplate(String templateId, String to, Map<String, String> params) throws Exception {
        MessageRequest request = MessageRequest.builder()
                .to(to)
                .templateId(templateId)
                .templateParams(params)
                .build();
        return send(request);
    }

    /**
     * 依据环境中的 AccessKey/SecretKey 构造 Dysmsapi Client
     *
     * @return 阿里云短信 Client
     * @throws Exception 配置缺失或构造失败
     */
    private Client buildClient() throws Exception {
        String accessKey = environment.get("sms.accessKey");
        String secretKey = environment.get("sms.secretKey");
        if (null == accessKey || accessKey.isBlank()) {
            throw new IllegalArgumentException("sms.accessKey 配置项必填");
        }
        if (null == secretKey || secretKey.isBlank()) {
            throw new IllegalArgumentException("sms.secretKey 配置项必填");
        }
        Config config = new Config()
                .setAccessKeyId(accessKey)
                .setAccessKeySecret(secretKey)
                .setEndpoint("dysmsapi.aliyuncs.com");
        return new Client(config);
    }
}
