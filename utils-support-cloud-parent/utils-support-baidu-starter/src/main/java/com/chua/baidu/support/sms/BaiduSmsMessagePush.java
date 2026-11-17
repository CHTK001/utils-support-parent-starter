// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.baidu.support.sms;

import com.baidubce.auth.DefaultBceCredentials;
import com.baidubce.services.sms.SmsClient;
import com.baidubce.services.sms.SmsClientConfiguration;
import com.baidubce.services.sms.model.v3.GetTemplateResponse;
import com.baidubce.services.sms.model.v3.ListTemplateRequest;
import com.baidubce.services.sms.model.v3.ListTemplateResponse;
import com.chua.common.support.collection.wrap.Wraps;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import com.chua.common.support.utils.StringUtils;

/**
 * 百度云短信推送实现
 *
 * <p>基于百度云 SMS HTTP API 的短信发送实现。
 *
 * <h3>环境配置</h3>
 * <pre>
 *   sms.accessKey   百度云 AccessKey（必填）
 *   sms.secretKey   百度云 SecretKey（必填）
 *   sms.signName    短信签名
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("baidu-sms")
@SpiDescribe(
        value = "百度云短信",
        type = "SMS",
        desc = "基于百度云 SMS HTTP API 发送短信验证码与通知",
        optional = {
                @SpiParam(value = "sms.accessKey", desc = "百度云 AccessKey", type = "String"),
                @SpiParam(value = "sms.secretKey", desc = "百度云 SecretKey", type = "String"),
                @SpiParam(value = "sms.signName", desc = "短信签名", type = "String")
        }
)
/**
 * 公共 类 baidusms消息push implements 消息push {
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class BaiduSmsMessagePush implements MessagePush {

    /**
     * 百度云 SMS API 地址
     */
    private static final String SMS_API_URL = "https://sms.bce.baidu.com/api/v2/sms";

    /**
     * 消息环境
    */
    private final MessageEnvironment environment;
    /**
     * 模板映射
    */
    private final Map<String, TemplateInfo> templates = new ConcurrentHashMap<>();

    /**
     * 创建 baidusms消息push 实例
    */
    public BaiduSmsMessagePush() {
        this(new MessageEnvironment());
    }

    /**
     * 创建 baidusms消息push 实例
     * @param environment 环境
     */
    public BaiduSmsMessagePush(MessageEnvironment environment) {
        this.environment = environment;
    }

    /**
     * 获取提供者
    */
    @Override
    public String getProvider() {
        return "baidu-sms";
    }

    /**
     * 发送
     * @param request 请求
     */
    @Override
    public MessageResponse send(MessageRequest request) throws Exception {
        long start = System.currentTimeMillis();

        String accessKey = environment.get("sms.accessKey");
        String secretKey = environment.get("sms.secretKey");

        if (StringUtils.isBlank(accessKey)) {
            throw new IllegalArgumentException("sms.accessKey 配置项必填");
        }
        if (StringUtils.isBlank(secretKey)) {
            throw new IllegalArgumentException("sms.secretKey 配置项必填");
        }

        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String content = request.getContent() != null ? request.getContent() : "";
        String templateId = request.getTemplateId() != null ? request.getTemplateId() : "";

        JsonObject jsonBody = new JsonObject();
        jsonBody.fluentPut("phoneNumbers", request.getTo());
        jsonBody.fluentPut("content", content);
        jsonBody.fluentPut("templateId", templateId);

        String authorization = generateAuthorization(accessKey, secretKey, timestamp);

        HttpClient httpClient = HttpClientFactory.getClient();
        ClientRequest httpRequest = ClientRequest.of(SMS_API_URL, HttpMethod.POST)
                .header("Content-Type", "application/json")
                .header("Authorization", authorization)
                .header("X-Bce-Date", timestamp);
        httpRequest.setBody(jsonBody.toJSONString());

        ClientResponse response = httpClient.execute(httpRequest);

        long duration = System.currentTimeMillis() - start;

        int statusCode = response.getStatusCode();
        String responseBody = response.getBodyString();

        if (statusCode == 200 && responseBody.contains("\"code\":0")) {
            return MessageResponse.builder()
                    .success(true)
                    .messageId(UUID.randomUUID().toString())
                    .durationMillis(duration)
                    .build();
        } else {
            return MessageResponse.builder()
                    .success(false)
                    .errorMessage("百度云 SMS 返回: HTTP " + statusCode + " - " + responseBody)
                    .durationMillis(duration)
                    .build();
        }
    }

    /**
     * 拉取百度云已审核通过的短信模板列表
     *
     * <p>调用百度云 SMS v3 接口（/sms/v3/template）分页拉取，仅保留审核状态为 READY
     * （审核通过，模板可用）的模板。AK 无权限或查询失败时抛出异常，由上层捕获后转成
     * 运营商错误信息返回。手工登记模板码作为兜底。
     *
     * @return 百度云已审核模板列表
     */
    @Override
    public List<TemplateInfo> listTemplates() {
        List<TemplateInfo> result = Wraps.list();
        try {
            String accessKey = environment.get("sms.accessKey");
            String secretKey = environment.get("sms.secretKey");
            if (StringUtils.isBlank(accessKey)) {
                throw new IllegalArgumentException("sms.accessKey 配置项必填");
            }
            if (StringUtils.isBlank(secretKey)) {
                throw new IllegalArgumentException("sms.secretKey 配置项必填");
            }

            SmsClientConfiguration configuration = new SmsClientConfiguration();
            configuration.setCredentials(new DefaultBceCredentials(accessKey, secretKey));
            configuration.setEndpoint("sms.bj.baidubce.com");

            SmsClient client = new SmsClient(configuration);

            int pageNo = 1;
            // [P3C A20 豁免] 单页大小：百度云 SMS v3 模板分页最大 100 条，按 50 拉取
            int pageSize = 50;
            while (true) {
                ListTemplateRequest req = new ListTemplateRequest();
                req.setPageNo(pageNo);
                req.setPageSize(pageSize);
                ListTemplateResponse resp = client.listTemplate(req);
                if (null == resp) {
                    break;
                }
                List<GetTemplateResponse> list = resp.getTemplates();
                if (null == list || list.isEmpty()) {
                    break;
                }
                for (GetTemplateResponse item : list) {
                    String templateId = item.getTemplateId();
                    if (StringUtils.isBlank(templateId)) {
                        continue;
                    }
                    // 仅同步审核通过且可用的模板
                    if (null != item.getStatus() && !"READY".equals(item.getStatus())) {
                        continue;
                    }
                    result.add(new TemplateInfo(templateId, item.getName(),
                            item.getContent(), "sms", null));
                }
                int total = resp.getTotalCount();
                if (total <= 0 || pageNo * pageSize >= total) {
                    break;
                }
                pageNo++;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("查询短信模板列表失败: " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * 获取Template
     * @param templateId templateid
     */
    @Override
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

    /**
     * 发送Template
     * @param templateId templateid
     * @param to 转为
     * @param params 参数
     */
    @Override
    public MessageResponse sendTemplate(String templateId, String to, Map<String, String> params) throws Exception {
        MessageRequest request = MessageRequest.builder()
                .to(to)
                .templateId(templateId)
                .templateParams(params)
                .build();
        return send(request);
    }

    /**
     * 生成百度云 AK/SK 认证头
     * @param accessKey 百度云 访问密钥
     * @param secretKey 百度云 密钥
     * @param timestamp 时间戳
     * @return Authorization 头值
     * @throws Exception 异常
    */
    private String generateAuthorization(String accessKey, String secretKey, String timestamp) throws Exception {
        String method = "POST";
        String path = "/api/v2/sms";
        String query = "";

        String canonicalRequest = method + "\n" + path + "\n" + query + "\n"
                + "host:sms.bce.baidu.com\n"
                + "x-bce-date:" + timestamp + "\n"
                + "\n"
                + "host;x-bce-date\n";

        String stringToSign = "HMAC-SHA256\n" + timestamp + "\n" + canonicalRequest;

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(hash);

        return "bce-auth-v1/" + accessKey + "/" + timestamp + "/1800/host;x-bce-date/" + signature;
    }
}