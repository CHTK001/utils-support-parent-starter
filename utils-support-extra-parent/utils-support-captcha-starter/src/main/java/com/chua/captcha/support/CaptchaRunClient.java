package com.chua.captcha.support;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
* captcha运行 验证码解析服务客户端
*
* @author CH
* @since 2026-03-14
 */
@Slf4j
@Spi("captcha-run")
public class CaptchaRunClient implements CaptchaParser {

    /** 设置 */
    private final CaptchaSetting setting;
    /** 任务persistence */
    private TaskPersistence taskPersistence;

    /**
    * 创建 captcha运行客户端 实例
    * @param setting setting
     */
    public CaptchaRunClient(CaptchaSetting setting) {
        this.setting = setting;
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
    /** 提交Captcha */
    public String submitCaptcha(byte[] imageData, Map<String, String> options) {
        if (options == null) {
            options = new HashMap<>();
        }

        Map<String, Object> body = new HashMap<>();
        body.put("captchaType", CaptchaType.fromType(options.getOrDefault("captchaType", "ReCaptchaV2")).getType());
        putIfNotBlank(body, "siteKey", options.get("siteKey"));
        putIfNotBlank(body, "siteReferer", options.get("siteReferer"));
        putIfNotBlank(body, "siteAction", options.get("siteAction"));
        putIfNotBlank(body, "host", options.get("host"));
        putIfNotBlank(body, "port", options.get("port"));
        putIfNotBlank(body, "login", options.get("login"));
        putIfNotBlank(body, "password", options.get("password"));
        putIfNotBlank(body, "domain", options.get("domain"));
        putIfNotBlank(body, "anchor", options.get("anchor"));
        putIfNotBlank(body, "reload", options.get("reload"));

        if (options.containsKey("useCache")) {
            body.put("useCache", Boolean.parseBoolean(options.get("useCache")));
        }
        if (options.containsKey("isInvisible")) {
            body.put("isInvisible", Boolean.parseBoolean(options.get("isInvisible")));
        }

        return createTask(body);
    }

    @Override
    /** 查询结果 */
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
            String json = doGet(setting.getApiUrl() + "/v2/users/self");
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
            String json = doGet(setting.getApiUrl() + "/v2/users/self/wallet");
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
    * @param url url
    * @return 执行获取的结果
     */
    private String doGet(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(setting.getConnectTimeout()))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + setting.getApiToken())
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
    * @param url url
    * @param body 主体
    * @return 执行post的结果
     */
    private String doPost(String url, Map<String, Object> body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(setting.getConnectTimeout()))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + setting.getApiToken())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(setting.getReadTimeout()))
                .POST(HttpRequest.BodyPublishers.ofString(Json.toJson(body)))
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
            String json = doPost(setting.getApiUrl() + "/v2/tasks", body);
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
            String json = doGet(setting.getApiUrl() + "/v2/tasks/" + taskId);
            if (json != null) {
                Map<String, Object> result = Json.fromJson(json, Map.class);
                String status = (String) result.get("status");

                if ("Success".equals(status)) {
                    Map<String, Object> responseData = (Map<String, Object>) result.get("response");
                    String token = responseData != null ? (String) responseData.get("gRecaptchaResponse") : null;

                    if (taskPersistence != null) {
                        CaptchaResponse cr = CaptchaResponse.builder()
                                .success(true)
                                .taskId(taskId)
                                .token(token)
                                .message("Captcha solved successfully")
                                .build();
                        taskPersistence.save(taskId, cr);
                    }

                    return CaptchaResponse.builder()
                            .success(true)
                            .taskId(taskId)
                            .token(token)
                            .message("Captcha solved successfully")
                            .build();
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
