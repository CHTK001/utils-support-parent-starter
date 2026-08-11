package com.chua.wechat.support.message;

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
import com.chua.common.support.utils.MapUtils;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 微信消息推送实现
 * <p>
 * 支持三种推送模式（通过 contentType 或 {@code wechat.type} 配置区分）：
 * <ul>
 *   <li>webhook：企业微信群机器人 Webhook，发送文本/Markdown 消息</li>
 *   <li>mp：公众号模板消息，需 appId/appSecret 与模板 ID</li>
 *   <li>mini：小程序订阅消息，需 appId/appSecret 与模板 ID</li>
 * </ul>
 * 接收人（to）为企业微信/公众号/小程序用户的 openid。
 * </p>
 *
 * <h3>环境配置</h3>
 * <pre>
 *   wechat.type        推送类型: webhook/mp/mini（默认 webhook）
 *   wechat.webhookUrl  企业微信群机器人 Webhook 地址（webhook 模式必填）
 *   wechat.appId       公众号/小程序 AppID（mp/mini 模式必填）
 *   wechat.appSecret   公众号/小程序 AppSecret（mp/mini 模式必填）
 *   wechat.templateId  默认模板 ID（mp/mini 模式，发送时也可通过 request.templateId 指定）
 * </pre>
 *
 * @author CH
 * @since 2026/08/03
 */
@Spi("wechat")
@SpiDescribe(
        value = "微信推送",
        type = "WECHAT",
        desc = "支持企业微信群机器人 Webhook、公众号模板消息、小程序订阅消息",
        optional = {
                @SpiParam(value = "wechat.type", defaultValue = "webhook", desc = "推送类型: webhook/mp/mini", type = "String"),
                @SpiParam(value = "wechat.webhookUrl", desc = "企业微信群机器人 Webhook 地址", type = "String"),
                @SpiParam(value = "wechat.appId", desc = "公众号/小程序 AppID", type = "String"),
                @SpiParam(value = "wechat.appSecret", desc = "公众号/小程序 AppSecret", type = "String"),
                @SpiParam(value = "wechat.templateId", desc = "默认模板 ID", type = "String")
        }
)
@Slf4j
public class WechatPush implements MessagePush {

    /**
     * 微信 access_token 获取地址
     */
    private static final String API_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";

    /**
     * 公众号模板消息发送地址
     */
    private static final String API_TEMPLATE_SEND_URL = "https://api.weixin.qq.com/cgi-bin/message/template/send";

    /**
     * 小程序订阅消息发送地址
     */
    private static final String API_SUBSCRIBE_SEND_URL = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send";

    /**
     * 推送类型：企业微信群机器人 Webhook
     */
    private static final String TYPE_WEBHOOK = "webhook";

    /**
     * 推送类型：公众号模板消息
     */
    private static final String TYPE_MP = "mp";

    /**
     * 推送类型：小程序订阅消息
     */
    private static final String TYPE_MINI = "mini";

    /**
     * access_token 提前过期余量（毫秒），避免边界失效
     */
    private static final long TOKEN_EXPIRE_MARGIN_MILLIS = 200L;

    private final MessageEnvironment environment;

    private final Map<String, TemplateInfo> templates = new ConcurrentHashMap<>();

    /**
     * access_token 缓存（按 appId:appSecret 维度）
     */
    private static final ConcurrentMap<String, TokenCache> TOKEN_CACHE = new ConcurrentHashMap<>();

    /**
     * access_token 缓存项
     *
     * @param token    访问令牌
     * @param expireAt 过期时间戳（毫秒）
     */
    private record TokenCache(String token, long expireAt) {
    }

    public WechatPush() {
        this(new MessageEnvironment());
    }

    public WechatPush(MessageEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public String getProvider() {
        return "wechat";
    }

    @Override
    public MessageResponse send(MessageRequest request) {
        long start = System.currentTimeMillis();
        String type = resolveType(request);
        try {
            MessageResponse response = switch (type) {
                case TYPE_MP -> sendTemplateMessage(request);
                case TYPE_MINI -> sendSubscribeMessage(request);
                default -> sendWebhook(request);
            };
            return MessageResponse.builder()
                    .success(response.isSuccess())
                    .messageId(response.getMessageId())
                    .errorMessage(response.getErrorMessage())
                    .durationMillis(System.currentTimeMillis() - start)
                    .data(response.getData())
                    .build();
        } catch (Exception e) {
            log.error("[微信推送] 发送失败, type={}, to={}", type, request.getTo(), e);
            return MessageResponse.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .durationMillis(System.currentTimeMillis() - start)
                    .build();
        }
    }

    /**
     * 解析推送类型
     *
     * @param request 消息请求
     * @return 推送类型（webhook/mp/mini）
     */
    private String resolveType(MessageRequest request) {
        String contentType = request.getContentType();
        if (StringUtils.isNotBlank(contentType)) {
            String lower = contentType.toLowerCase();
            if (lower.contains(TYPE_WEBHOOK)) {
                return TYPE_WEBHOOK;
            }
            if (lower.contains(TYPE_MINI)) {
                return TYPE_MINI;
            }
            if (lower.contains(TYPE_MP) || lower.contains("template")) {
                return TYPE_MP;
            }
        }
        return normalizeType(environment.get("wechat.type", TYPE_WEBHOOK));
    }

    /**
     * 归一化配置的推送类型
     *
     * @param rawType 原始类型
     * @return 归一化类型
     */
    private String normalizeType(String rawType) {
        if (StringUtils.isBlank(rawType)) {
            return TYPE_WEBHOOK;
        }
        String lower = rawType.trim().toLowerCase();
        if (lower.contains(TYPE_MINI) || lower.contains("subscribe")) {
            return TYPE_MINI;
        }
        if (lower.contains(TYPE_MP) || lower.contains("template") || lower.contains("公众号")) {
            return TYPE_MP;
        }
        return TYPE_WEBHOOK;
    }

    /**
     * 企业微信群机器人 Webhook 推送
     *
     * @param request 消息请求
     * @return 消息响应
     */
    private MessageResponse sendWebhook(MessageRequest request) {
        String webhookUrl = environment.get("wechat.webhookUrl");
        if (StringUtils.isBlank(webhookUrl)) {
            return MessageResponse.failure("wechat.webhookUrl 配置项必填");
        }

        String contentType = request.getContentType();
        boolean markdown = contentType != null && contentType.toLowerCase().contains("markdown");

        JsonObject json = new JsonObject();
        JsonObject content = new JsonObject();
        if (markdown) {
            content.fluentPut("content", request.getContent());
            json.fluentPut("msgtype", "markdown");
            json.fluentPut("markdown", content);
        } else {
            content.fluentPut("content", request.getContent());
            json.fluentPut("msgtype", "text");
            json.fluentPut("text", content);
        }

        String responseBody = executeJsonPost(webhookUrl, json);
        return parseWechatResult(responseBody, "企业微信 Webhook");
    }

    /**
     * 公众号模板消息推送
     *
     * @param request 消息请求
     * @return 消息响应
     */
    private MessageResponse sendTemplateMessage(MessageRequest request) {
        String appId = environment.get("wechat.appId");
        String appSecret = environment.get("wechat.appSecret");
        if (StringUtils.isBlank(appId) || StringUtils.isBlank(appSecret)) {
            return MessageResponse.failure("wechat.appId/wechat.appSecret 配置项必填");
        }
        String openId = request.getTo();
        if (StringUtils.isBlank(openId)) {
            return MessageResponse.failure("接收人 openid 不能为空");
        }
        String templateId = resolveTemplateId(request);
        if (StringUtils.isBlank(templateId)) {
            return MessageResponse.failure("模板 ID 未配置（wechat.templateId 或 request.templateId）");
        }

        JsonObject body = new JsonObject();
        body.fluentPut("touser", openId);
        body.fluentPut("template_id", templateId);
        if (StringUtils.isNotBlank(request.getSubject())) {
            body.fluentPut("url", request.getSubject());
        }
        body.fluentPut("data", buildData(request.getTemplateParams()));

        String accessToken = getAccessToken(appId, appSecret);
        String url = API_TEMPLATE_SEND_URL + "?access_token=" + accessToken;
        String responseBody = executeJsonPost(url, body);
        return parseWechatResult(responseBody, "公众号模板消息");
    }

    /**
     * 小程序订阅消息推送
     *
     * @param request 消息请求
     * @return 消息响应
     */
    private MessageResponse sendSubscribeMessage(MessageRequest request) {
        String appId = environment.get("wechat.appId");
        String appSecret = environment.get("wechat.appSecret");
        if (StringUtils.isBlank(appId) || StringUtils.isBlank(appSecret)) {
            return MessageResponse.failure("wechat.appId/wechat.appSecret 配置项必填");
        }
        String openId = request.getTo();
        if (StringUtils.isBlank(openId)) {
            return MessageResponse.failure("接收人 openid 不能为空");
        }
        String templateId = resolveTemplateId(request);
        if (StringUtils.isBlank(templateId)) {
            return MessageResponse.failure("模板 ID 未配置（wechat.templateId 或 request.templateId）");
        }

        JsonObject body = new JsonObject();
        body.fluentPut("touser", openId);
        body.fluentPut("template_id", templateId);
        if (StringUtils.isNotBlank(request.getSubject())) {
            body.fluentPut("page", request.getSubject());
        }
        body.fluentPut("miniprogram_state", environment.get("wechat.miniprogramState", "formal"));
        body.fluentPut("data", buildData(request.getTemplateParams()));

        String accessToken = getAccessToken(appId, appSecret);
        String url = API_SUBSCRIBE_SEND_URL + "?access_token=" + accessToken;
        String responseBody = executeJsonPost(url, body);
        return parseWechatResult(responseBody, "小程序订阅消息");
    }

    /**
     * 解析模板 ID：优先请求携带，否则使用配置默认值
     *
     * @param request 消息请求
     * @return 模板 ID
     */
    private String resolveTemplateId(MessageRequest request) {
        if (StringUtils.isNotBlank(request.getTemplateId())) {
            return request.getTemplateId();
        }
        return environment.get("wechat.templateId");
    }

    /**
     * 将模板参数转换为微信 data 结构（{key: {value: xxx}}）
     *
     * @param params 模板参数
     * @return 微信 data JSON
     */
    private JsonObject buildData(Map<String, String> params) {
        JsonObject data = new JsonObject();
        if (MapUtils.isEmpty(params)) {
            return data;
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            JsonObject item = new JsonObject();
            item.fluentPut("value", entry.getValue() == null ? "" : entry.getValue());
            data.fluentPut(entry.getKey(), item);
        }
        return data;
    }

    /**
     * 获取 access_token（带缓存）
     *
     * @param appId     公众号/小程序 AppID
     * @param appSecret 公众号/小程序 AppSecret
     * @return access_token
     */
    private String getAccessToken(String appId, String appSecret) {
        String cacheKey = appId + ":" + appSecret;
        long now = System.currentTimeMillis();
        TokenCache cached = TOKEN_CACHE.get(cacheKey);
        if (cached != null && cached.expireAt() > now) {
            return cached.token();
        }

        String url = API_TOKEN_URL + "?grant_type=client_credential&appid=" + appId + "&secret=" + appSecret;
        ClientRequest request = ClientRequest.of(url, HttpMethod.GET);
        HttpClient httpClient = HttpClientFactory.getClient();
        try {
            ClientResponse response = httpClient.execute(request);
            String responseBody = response.getBodyString();
            JsonObject json = Json.getJsonObject(responseBody);
            int errCode = MapUtils.getNumber(json, "errcode", -1).intValue();
            if (errCode != 0) {
                throw new IllegalStateException("获取 access_token 失败: " + responseBody);
            }
            String token = MapUtils.getString(json, "access_token");
            long expiresIn = MapUtils.getNumber(json, "expires_in", 7200).longValue();
            long expireAt = now + (expiresIn * 1000L) - TOKEN_EXPIRE_MARGIN_MILLIS;
            TOKEN_CACHE.put(cacheKey, new TokenCache(token, expireAt));
            return token;
        } catch (Exception e) {
            TOKEN_CACHE.remove(cacheKey);
            if (e instanceof IllegalStateException ise) {
                throw ise;
            }
            throw new IllegalStateException("获取 access_token 异常: " + e.getMessage(), e);
        }
    }

    /**
     * 执行 JSON POST 请求
     *
     * @param url 请求地址
     * @param body 请求体
     * @return 响应体字符串
     */
    private String executeJsonPost(String url, JsonObject body) {
        ClientRequest request = ClientRequest.of(url, HttpMethod.POST)
                .header("Content-Type", "application/json");
        request.setBody(body.toJSONString());
        HttpClient httpClient = HttpClientFactory.getClient();
        ClientResponse response = httpClient.execute(request);
        return response.getBodyString();
    }

    /**
     * 解析微信接口返回结果
     *
     * @param responseBody 响应体
     * @param bizName      业务名称（用于错误提示）
     * @return 消息响应
     */
    private MessageResponse parseWechatResult(String responseBody, String bizName) {
        if (StringUtils.isBlank(responseBody)) {
            return MessageResponse.failure(bizName + "返回为空");
        }
        try {
            JsonObject json = Json.getJsonObject(responseBody);
            int errCode = MapUtils.getNumber(json, "errcode", -1).intValue();
            if (errCode == 0) {
                return MessageResponse.success(String.valueOf(MapUtils.getNumber(json, "msgid", 0).longValue()));
            }
            String errMsg = MapUtils.getString(json, "errmsg");
            return MessageResponse.failure(bizName + "发送失败: " + errCode + " - " + errMsg);
        } catch (Exception e) {
            return MessageResponse.failure(bizName + "返回解析失败: " + responseBody);
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

    /**
     * 注册模板
     *
     * @param template 模板信息
     */
    public void registerTemplate(TemplateInfo template) {
        templates.put(template.id(), template);
    }
}
