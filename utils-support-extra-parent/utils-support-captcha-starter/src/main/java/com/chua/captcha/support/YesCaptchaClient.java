package com.chua.captcha.support;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * yescaptcha 验证码解析服务客户端
 *
 * <p>通过 SPI 机制以 "yescaptcha" 名称注册，对接 yescaptcha（2Captcha 兼容）的
 * {@code /createTask} 与 {@code /getTaskResult} 接口。</p>
 *
 * <p>{@link #submitCaptcha(byte[], Map)} 的 {@code imageData} 用于图文类验证码
 * （{@link CaptchaType#TEXT_CAPTCHA}），按裸 Base64 作为任务的 {@code body} 字段发送；
 * 若 {@code options} 中显式给出 {@code body}，则以 {@code body} 为准。</p>
 *
 * @author CH
 * @since 2026-09-07
 */
@Slf4j
@Spi("yescaptcha")
public class YesCaptchaClient implements CaptchaParser {

    /**
     * 各验证码类型对应的 yescaptcha 任务类型；未列出的类型一律拒绝，避免静默降级为 reCAPTCHA 任务
     */
    private static final Map<CaptchaType, String> TASK_TYPES = buildTaskTypes();

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
     * yescaptcha客户端。
     *
     * @param setting 服务配置，不能为 null
     */
    public YesCaptchaClient(CaptchaSetting setting) {
        this.setting = setting;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(setting.getConnectTimeout()))
                .build();
    }

    /**
     * SPI 无参实例：配置取自 JVM 参数或环境变量（{@code captcha.api-token} 等），
     * 缺令牌时在调用点由 {@link CaptchaSetting#requireApiToken()} 明确报错
     */
    public YesCaptchaClient() {
        this(CaptchaSetting.fromEnvironment(CaptchaSetting.YESCAPTCHA_API_URL));
    }

    /**
     * withPersistence。
     *
     * @param taskPersistence 任务持久化
     * @return 当前客户端
     */
    public YesCaptchaClient withPersistence(TaskPersistence taskPersistence) {
        this.taskPersistence = taskPersistence;
        return this;
    }

    @Override
    public String submitCaptcha(byte[] imageData, Map<String, String> options) {
        Map<String, Object> body = new HashMap<>();
        body.put("clientKey", setting.requireApiToken());
        body.put("task", buildTask(imageData, options == null ? Map.of() : options));
        return createTask(body);
    }

    @Override
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
     * 获取Balance。
     *
     * @return 账户余额
     */
    public double getBalance() {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("clientKey", setting.requireApiToken());
            String json = doPost("/getBalance", body);
            if (json != null) {
                Map<String, Object> result = Json.fromJson(json, Map.class);
                if (result != null && "0".equals(String.valueOf(result.get("errorId")))) {
                    Object balance = result.get("balance");
                    if (balance instanceof Number) {
                        return ((Number) balance).doubleValue();
                    }
                    if (balance instanceof String) {
                        return Double.parseDouble((String) balance);
                    }
                }
            }
        } catch (Exception e) {
            log.error("getBalance exception", e);
        }
        return 0.0;
    }

    /**
     * 构建 createTask 的 task 段。
     *
     * @param imageData 验证码图片字节，图文类任务用它编码 body 字段，可为 null
     * @param options   解析选项，不能为 null
     * @return 任务体
     */
    private Map<String, Object> buildTask(byte[] imageData, Map<String, String> options) {
        String captchaType = options.getOrDefault("captchaType", CaptchaType.RECAPTCHA_V2.getType());
        CaptchaType type = CaptchaType.parse(captchaType);
        if (type == null) {
            throw new IllegalArgumentException("不支持的验证码类型: " + captchaType + "，可选: " + supportedTypes());
        }
        Map<String, Object> task = new HashMap<>();
        String taskType = TASK_TYPES.get(type);
        if (taskType == null) {
            throw new IllegalArgumentException("yescaptcha 暂不支持验证码类型: " + captchaType);
        }
        task.put("type", taskType);
        if (type == CaptchaType.TEXT_CAPTCHA) {
            putIfNotBlank(task, "body", resolveImageBody(imageData, options));
        } else {
            putIfNotBlank(task, "websiteURL", options.get("url"));
            putIfNotBlank(task, "websiteKey", options.get("siteKey"));
            putIfNotBlank(task, "websiteAction", options.get("action"));
            putIfNotBlank(task, "proxy", options.get("proxy"));
        }
        return task;
    }

    /**
     * 图文任务的 body：优先取 options 里显式给出的 body，否则由 imageData 裸 Base64 编码。
     *
     * @param imageData 验证码图片字节
     * @param options   解析选项
     * @return Base64 图片内容
     */
    private static String resolveImageBody(byte[] imageData, Map<String, String> options) {
        String body = options.get("body");
        if (body != null && !body.isEmpty()) {
            return body;
        }
        if (imageData == null || imageData.length == 0) {
            throw new IllegalArgumentException("TextCaptcha 需要验证码图片：请传入 imageData 或在 options 中给出 body");
        }
        return Base64.getEncoder().encodeToString(imageData);
    }

    /**
     * 建立类型到任务类型的映射表。
     *
     * @return 映射表
     */
    private static Map<CaptchaType, String> buildTaskTypes() {
        Map<CaptchaType, String> map = new EnumMap<>(CaptchaType.class);
        map.put(CaptchaType.RECAPTCHA_V2, "NoCaptchaTaskProxyless");
        map.put(CaptchaType.RECAPTCHA_V3, "RecaptchaV3TaskProxyless");
        map.put(CaptchaType.RECAPTCHA_V2_ENTERPRISE, "RecaptchaV2EnterpriseTaskProxyless");
        map.put(CaptchaType.RECAPTCHA_V3_ENTERPRISE, "RecaptchaV3EnterpriseTask");
        map.put(CaptchaType.HCAPTCHA, "HCaptchaTaskProxyless");
        map.put(CaptchaType.FUNCAPTCHA, "FunCaptchaTaskProxyless");
        map.put(CaptchaType.TURNSTILE, "TurnstileTaskProxyless");
        map.put(CaptchaType.GEETEST, "GeeTestTaskProxyless");
        map.put(CaptchaType.MT_CAPTCHA, "McaptchaTaskProxyless");
        map.put(CaptchaType.TEXT_CAPTCHA, "ImageToTextTask");
        return map;
    }

    /**
     * 列出本客户端支持的任务类型名，用于错误提示。
     *
     * @return 类型名列表
     */
    private static String supportedTypes() {
        return String.join(", ", TASK_TYPES.keySet().stream().map(CaptchaType::getType).toList());
    }

    /**
     * 创建Task。
     *
     * @param body 请求体，不允许为 null
     * @return 任务标识，失败时为 null
     */
    private String createTask(Map<String, Object> body) {
        try {
            String json = doPost("/createTask", body);
            if (json != null) {
                Map<String, Object> result = Json.fromJson(json, Map.class);
                Object errorId = result.get("errorId");
                if (errorId != null && "0".equals(String.valueOf(errorId))) {
                    return (String) result.get("taskId");
                }
                log.error("createTask error: {}", result.get("errorDescription"));
            }
        } catch (Exception e) {
            log.error("createTask exception", e);
        }
        return null;
    }

    /**
     * 获取Task结果。
     *
     * @param taskId taskID，不允许为 null
     * @return Captcha响应 对象
     */
    private CaptchaResponse getTaskResult(String taskId) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("clientKey", setting.requireApiToken());
            body.put("taskId", taskId);

            String json = doPost("/getTaskResult", body);
            if (json != null) {
                Map<String, Object> result = Json.fromJson(json, Map.class);
                Object errorId = result.get("errorId");
                if (errorId != null && !"0".equals(String.valueOf(errorId))) {
                    return CaptchaResponse.builder()
                            .success(false)
                            .taskId(taskId)
                            .message(String.valueOf(result.get("errorDescription")))
                            .errorCode(String.valueOf(result.get("errorCode")))
                            .build();
                }

                String status = (String) result.get("status");
                if ("ready".equals(status)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> solution = (Map<String, Object>) result.get("solution");
                    String token = null;
                    if (solution != null) {
                        token = (String) solution.get("gRecaptchaResponse");
                        if (token == null) {
                            token = (String) solution.get("code");
                        }
                    }

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
                } else if ("processing".equals(status)) {
                    return CaptchaResponse.builder()
                            .success(false)
                            .taskId(taskId)
                            .message("Task is still processing")
                            .build();
                }
            }
        } catch (Exception e) {
            log.error("getTaskResult exception", e);
        }
        return CaptchaResponse.builder()
                .success(false)
                .taskId(taskId)
                .message("Exception")
                .errorCode("EXCEPTION")
                .build();
    }

    /**
     * doPost。
     *
     * @param path 接口路径
     * @param body 请求体，不允许为 null
     * @return 响应正文，非 200 时为 null
     * @throws Exception 当执行过程不满足前置条件时
     */
    private String doPost(String path, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(setting.apiUrlOr(CaptchaSetting.YESCAPTCHA_API_URL) + path))
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
     * 放入IfNotBlank。
     *
     * @param map 映射
     * @param key 键
     * @param value 值
     */
    private static void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isEmpty()) {
            map.put(key, value);
        }
    }
}
