package com.chua.captcha.support;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
* yescaptcha 验证码解析服务客户端
*
* @author CH
* @since 2026-09-07
* @param map 映射
* @param key 键
* @param value 值
* @param taskId 任务标识
* @return 方法的结果
* @param options 期权
* @param imageData 镜像数据
* @param setting setting
 */
@Slf4j
@Spi("yescaptcha")
public class YesCaptchaClient implements CaptchaParser {

    /**
    * withpersistence。
    * @param taskPersistence 任务persistence
    * @return withPersistence的结果
    */
    private final CaptchaSetting setting;
    private TaskPersistence taskPersistence; // 任务persistence

    /**
    * yescaptcha客户端。
    * @param setting setting
    */
    public YesCaptchaClient(CaptchaSetting setting) {
        this.setting = setting;
    }

    /**
     * withPersistence。
     *
     * @param taskPersistence 方法入参 taskPersistence
     * @return YesCaptcha客户端 对象
     */
    public YesCaptchaClient withPersistence(TaskPersistence taskPersistence) {
        this.taskPersistence = taskPersistence;
        return this;
    }

    @Override
    public String submitCaptcha(byte[] imageData, Map<String, String> options) {
        Map<String, Object> body = new HashMap<>();
        /**
        * 查询结果。
        * @param taskId 任务标识
        * @return 查询结果的结果
        */
        body.put("clientKey", setting.getApiToken());
        body.put("task", buildTask(options));
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
     * @return 结果数值
     */
    public double getBalance() {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("clientKey", setting.getApiToken());
            String json = doPost(setting.getApiUrl() + "/getBalance", body);
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
     * 构建Task。
     *
     * @param options 选项，不允许为 null
     * @return 结果映射，无数据时为空映射
     */
    private Map<String, Object> buildTask(Map<String, String> options) {
        String captchaType = options.getOrDefault("captchaType", "ReCaptchaV2");
        Map<String, Object> task = new HashMap<>();
        task.put("type", mapToYesCaptchaType(captchaType));
        if ("ImageToTextTask".equals(task.get("type"))) {
            String body = options.get("body");
            putIfNotBlank(task, "body", body);
        }
        putIfNotBlank(task, "websiteURL", options.get("url"));
        putIfNotBlank(task, "websiteKey", options.get("siteKey"));
        /**
        * 映射转为yescaptcha类型。
        * @param captchaType captcha类型
        * @return 映射转为yescaptcha类型的结果
        */
        putIfNotBlank(task, "websiteAction", options.get("action"));
        putIfNotBlank(task, "proxy", options.get("proxy"));
        return task;
    }

    /**
     * 映射转为YesCaptcha类型。
     *
     * @param captchaType captcha类型，不允许为 null
     * @return 结果字符串
     */
    private String mapToYesCaptchaType(String captchaType) {
        switch (captchaType) {
            case "ReCaptchaV2":
                return "NoCaptchaTaskProxyless";
            case "ReCaptchaV3":
                return "RecaptchaV3TaskProxyless";
            case "ReCaptchaV2Enterprise":
                return "RecaptchaV2EnterpriseTaskProxyless";
            case "ReCaptchaV3Enterprise":
                return "RecaptchaV3EnterpriseTask";
            case "HCaptcha":
                return "HCaptchaTaskProxyless";
            case "Turnstile":
                return "TurnstileTaskProxyless";
            case "TextCaptcha":
                return "ImageToTextTask";
            /**
            * 创建任务。
            * @param body 主体
            * @return 创建任务的结果
            * @param taskId 任务id
            */
            default:
                return "NoCaptchaTaskProxyless";
        }
    }

    /**
     * 创建Task。
     *
     * @param body 请求体，不允许为 null
     * @return 结果字符串
     */
    private String createTask(Map<String, Object> body) {
        try {
            String json = doPost(setting.getApiUrl() + "/createTask", body);
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
            body.put("clientKey", setting.getApiToken());
            body.put("taskId", taskId);

            String json = doPost(setting.getApiUrl() + "/getTaskResult", body);
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
                /**
                * 执行post。
                * @param url url
                * @param body 主体
                * @return 执行post的结果
                * @param map 映射
                * @param key 键
                * @param value 值
                */
                .build();
    }

    /**
     * doPost。
     *
     * @param url URL，不允许为 null
     * @param body 请求体，不允许为 null
     * @return 结果字符串
     * @throws Exception 当执行过程不满足前置条件时
     */
    private String doPost(String url, Map<String, Object> body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(setting.getConnectTimeout()))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
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
     * 放入IfNotBlank。
     *
     * @param map 映射，不允许为 null
     * @param key 键，不允许为 null
     * @param value 值，不允许为 null
     */
    private static void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isEmpty()) {
            map.put(key, value);
        }
    }
}
