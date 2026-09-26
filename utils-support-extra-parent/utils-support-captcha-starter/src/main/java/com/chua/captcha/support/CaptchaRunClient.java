package com.chua.captcha.support;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * captcha运行 验证码解析服务客户端
 *
 * <p>通过 SPI 机制以 "captcha-run" 名称注册，使用 {@code Authorization: Bearer} 携带令牌，
 * 未显式配置 {@code apiUrl} 时落到 {@link CaptchaSetting#CAPTCHA_RUN_API_URL}，
 * 不会沿用其他服务商的地址。</p>
 *
 * <p>{@link #submitCaptcha(byte[], Map)} 的 {@code imageData} 按裸 Base64 作为 {@code body} 字段发送，
 * {@code options} 中显式给出 {@code body} 时以其为准。</p>
 *
 * @author CH
 * @since 2026-03-14
 */
@Slf4j
@Spi("captcha-run")
public class CaptchaRunClient implements CaptchaParser {

    /**
     * 设置
    */
    private final CaptchaSetting setting;
    /**
     * 复用的 HTTP 客户端，每次请求新建会持续堆积选择器线程
    */
    private final HttpClient client;
    /**
     * 任务persistence
    */
    private TaskPersistence taskPersistence;

    /**
     * 创建 captcha运行客户端 实例
     * @param setting setting
     */
    public CaptchaRunClient(CaptchaSetting setting) {
        this.setting = setting;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(setting.getConnectTimeout()))
                .build();
    }

    /**
     * SPI 无参实例：配置取自 JVM 参数或环境变量（{@code captcha.api-token} 等），
     * 缺令牌时在调用点由 {@link CaptchaSetting#requireApiToken()} 明确报错
     */
    public CaptchaRunClient() {
        this(CaptchaSetting.fromEnvironment(CaptchaSetting.CAPTCHA_RUN_API_URL));
    }

    /**
     * withpersistence
     *
     * @param taskPersistence 任务persistence
     * @return withPersistence的结果
     */
    public CaptchaRunClient withPersistence(TaskPersistence taskPersistence) {
        this.taskPersistence = taskPersistence;
        return this;
    }

    @Override
    /**
     * 提交Captcha
    */
    public String submitCaptcha(byte[] imageData, Map<String, String> options) {
        Map<String, String> opts = options == null ? Map.of() : options;
        String captchaType = opts.getOrDefault("captchaType", CaptchaType.RECAPTCHA_V2.getType());
        CaptchaType type = CaptchaType.parse(captchaType);
        if (type == null) {
            throw new IllegalArgumentException("不支持的验证码类型: " + captchaType
                    + "，可选: " + String.join(", ", java.util.Arrays.stream(CaptchaType.values())
                    .map(CaptchaType::getType).toList()));
        }

        Map<String, Object> body = new HashMap<>();
        body.put("captchaType", type.getType());
        putIfNotBlank(body, "siteKey", opts.get("siteKey"));
        putIfNotBlank(body, "siteReferer", opts.get("siteReferer"));
        putIfNotBlank(body, "siteAction", opts.get("siteAction"));
        putIfNotBlank(body, "host", opts.get("host"));
        putIfNotBlank(body, "port", opts.get("port"));
        putIfNotBlank(body, "login", opts.get("login"));
        putIfNotBlank(body, "password", opts.get("password"));
        putIfNotBlank(body, "domain", opts.get("domain"));
        putIfNotBlank(body, "anchor", opts.get("anchor"));
        putIfNotBlank(body, "reload", opts.get("reload"));
        String imageBody = opts.get("body");
        if (StringUtils.isBlank(imageBody) && imageData != null && imageData.length > 0) {
            imageBody = Base64.getEncoder().encodeToString(imageData);
        }
        putIfNotBlank(body, "body", imageBody);
        if (type == CaptchaType.TEXT_CAPTCHA && StringUtils.isBlank(imageBody)) {
            throw new IllegalArgumentException("TextCaptcha 需要验证码图片：请传入 imageData 或在 options 中给出 body");
        }

        if (opts.containsKey("useCache")) {
            body.put("useCache", Boolean.parseBoolean(opts.get("useCache")));
        }
        if (opts.containsKey("isInvisible")) {
            body.put("isInvisible", Boolean.parseBoolean(opts.get("isInvisible")));
        }

        return createTask(body);
    }

    @Override
    /**
     * 查询结果
    */
    public CaptchaResponse queryResult(String taskId) {
        if (taskPersistence != null) {
            var cached = taskPersistence.query(taskId);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        return getTaskResult(taskId);
    }

    /**
     * 获取用户信息
     *
     * @return 获取用户信息的结果
     */
    public Map<String, Object> getUserInfo() {
        try {
            String json = doGet("/v2/users/self");
            if (json != null) {
                return Json.fromJson(json, Map.class);
            }
        } catch (Exception e) {
            log.error("getUserInfo exception", e);
        }
        return java.util.Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    /**
     * 获取Balance
     *
     * @return 获取balance的结果
     */
    public double getBalance() {
        try {
            String json = doGet("/v2/users/self/wallet");
            if (json != null) {
                Map<String, Object> wallet = Json.fromJson(json, Map.class);
                Object balance = wallet.get("balance");
                if (balance instanceof Number) {
                    return ((Number) balance).doubleValue();
                }
                if (balance instanceof String) {
                    return Double.parseDouble((String) balance);
                }
            }
        } catch (Exception e) {
            log.error("getBalance exception", e);
        }
        return 0.0;
    }

    /**
     * 执行获取
     *
     * @param path 接口路径
     * @return 执行获取的结果
     */
    private String doGet(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(setting.apiUrlOr(CaptchaSetting.CAPTCHA_RUN_API_URL) + path))
                .header("Authorization", "Bearer " + setting.requireApiToken())
                .timeout(Duration.ofMillis(setting.getReadTimeout()))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            return response.body();
        }
        log.error("GET failed: {} - {}", response.statusCode(), response.body());
        return null;
    }

    /**
     * 执行post
     *
     * @param path 接口路径
     * @param body 主体
     * @return 执行post的结果
     */
    private String doPost(String path, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(setting.apiUrlOr(CaptchaSetting.CAPTCHA_RUN_API_URL) + path))
                .header("Authorization", "Bearer " + setting.requireApiToken())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(setting.getReadTimeout()))
                .POST(HttpRequest.BodyPublishers.ofString(Json.toJson(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            return response.body();
        }
        log.error("POST failed: {} - {}", response.statusCode(), response.body());
        return null;
    }

    /**
     * 创建任务
     *
     * @param body 主体
     * @return 创建任务的结果
     */
    private String createTask(Map<String, Object> body) {
        try {
            String json = doPost("/v2/tasks", body);
            if (json != null) {
                Map<String, Object> result = Json.fromJson(json, Map.class);
                return (String) result.get("taskId");
            }
        } catch (Exception e) {
            log.error("Create task exception", e);
        }
        return null;
    }

    /**
     * 获取任务结果
     *
     * @param taskId 任务标识
     * @return 获取任务结果的结果
     */
    private CaptchaResponse getTaskResult(String taskId) {
        try {
            String json = doGet("/v2/tasks/" + taskId);
            if (json != null) {
                Map<String, Object> result = Json.fromJson(json, Map.class);
                String status = (String) result.get("status");

                if ("Success".equals(status)) {
                    Map<String, Object> responseData = (Map<String, Object>) result.get("response");
                    String token = responseData != null ? (String) responseData.get("gRecaptchaResponse") : null;

                    CaptchaResponse cr = CaptchaResponse.builder()
                            .success(true)
                            .taskId(taskId)
                            .token(token)
                            .message("Captcha solved successfully")
                            .build();
                    if (taskPersistence != null) {
                        taskPersistence.save(taskId, cr);
                    }

                    return cr;
                } else if ("Fail".equals(status)) {
                    return CaptchaResponse.builder()
                            .success(false)
                            .taskId(taskId)
                            .message("Task failed")
                            .errorCode("FAIL")
                            .build();
                } else {
                    return CaptchaResponse.builder()
                            .success(false)
                            .taskId(taskId)
                            .message("Task is still processing")
                            .build();
                }
            }
        } catch (Exception e) {
            log.error("Get task result exception", e);
        }
        return CaptchaResponse.builder()
                .success(false)
                .taskId(taskId)
                .message("Exception")
                .errorCode("EXCEPTION")
                .build();
    }

    /**
     * 放入ifnotblank
     *
     * @param map 映射
     * @param key 键
     * @param value 值
     */
    private static void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            map.put(key, value);
        }
    }
}
