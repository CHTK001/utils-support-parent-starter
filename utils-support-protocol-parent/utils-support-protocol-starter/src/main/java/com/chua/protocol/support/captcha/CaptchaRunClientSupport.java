package com.chua.protocol.support.captcha;

import com.chua.common.support.captcha.CaptchaClient;
import com.chua.common.support.captcha.CaptchaRequest;
import com.chua.common.support.captcha.CaptchaResponse;
import com.chua.common.support.captcha.CaptchaSetting;
import com.chua.common.support.captcha.TaskPersistence;
import com.chua.common.support.core.spi.ServiceProvider;
import com.chua.common.support.core.utils.StringUtils;
import com.chua.common.support.core.utils.ThreadUtils;
import com.chua.common.support.network.http.BodyStringData;
import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpClientInvoker;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.http.HttpRequest;
import com.chua.common.support.network.http.HttpResponse;
import com.chua.common.support.network.http.ResponseCallback;
import com.chua.common.support.network.http.invoke.AbstractHttpClientInvoker;
import com.chua.common.support.text.json.Json;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * CaptchaRun 真实实现。
 */
@Slf4j
public class CaptchaRunClientSupport implements CaptchaClient {

    private final CaptchaSetting setting;
    private TaskPersistence taskPersistence;

    public CaptchaRunClientSupport(CaptchaSetting setting) {
        this.setting = setting;
    }

    public CaptchaRunClientSupport withPersistence(TaskPersistence taskPersistence) {
        this.taskPersistence = taskPersistence;
        return this;
    }

    @Override
    public CaptchaResponse solveCaptcha(CaptchaRequest request) {
        long startTime = System.currentTimeMillis();
        try {
            String taskId = createTask(request);
            if (StringUtils.isBlank(taskId)) {
                return CaptchaResponse.builder().success(false).message("创建验证码任务失败")
                        .executionTime(System.currentTimeMillis() - startTime).build();
            }

            long timeout = request.getTimeout() > 0 ? request.getTimeout() : setting.getDefaultTimeout();
            long pollInterval = request.getPollInterval() > 0 ? request.getPollInterval() : setting.getDefaultPollInterval();
            long endTime = startTime + timeout;
            while (System.currentTimeMillis() < endTime) {
                CaptchaResponse response = getTaskResult(taskId);
                if (response.isSuccess()) {
                    response.setExecutionTime(System.currentTimeMillis() - startTime);
                    if (taskPersistence != null) {
                        taskPersistence.save(taskId, response);
                    }
                    return response;
                }
                if (StringUtils.isNotBlank(response.getErrorCode())) {
                    response.setExecutionTime(System.currentTimeMillis() - startTime);
                    return response;
                }
                Thread.sleep(pollInterval);
            }
            return CaptchaResponse.builder().success(false).taskId(taskId).message("验证码识别超时")
                    .errorCode("TIMEOUT").executionTime(System.currentTimeMillis() - startTime).build();
        } catch (Exception e) {
            log.error("验证码识别失败", e);
            return CaptchaResponse.builder().success(false).message("识别过程发生异常: " + e.getMessage())
                    .errorCode("EXCEPTION").executionTime(System.currentTimeMillis() - startTime).build();
        }
    }

    @Override
    public void solveCaptchaAsync(CaptchaRequest request, ResponseCallback<CaptchaResponse> callback) {
        ExecutorService executor = ThreadUtils.newSingleWorkThreadExecutor("captcha-solver");
        executor.execute(() -> {
            try {
                CaptchaResponse response = solveCaptcha(request);
                callback.onResponse(response);
            } catch (Exception e) {
                log.error("异步验证码识别失败", e);
                callback.onResponse(CaptchaResponse.builder().success(false)
                        .message("异步识别发生异常: " + e.getMessage()).errorCode("ASYNC_EXCEPTION").build());
                callback.onFailure(e);
            } finally {
                executor.shutdownNow();
            }
        });
    }

    @Override
    public CaptchaResponse queryTask(String taskId) {
        if (taskPersistence != null) {
            var cached = taskPersistence.query(taskId);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        return getTaskResult(taskId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getUserInfo() {
        try {
            HttpHeader headers = new HttpHeader();
            headers.addHeader("API_KEY", setting.getApiToken());
            HttpRequest httpRequest = HttpRequest.builder()
                    .url(setting.getApiUrl() + "/v1/user")
                    .headers(headers)
                    .connectTimeoutMill(setting.getConnectTimeout())
                    .readTimeoutMill(setting.getReadTimeout())
                    .build();
            HttpClientInvoker invoker = ServiceProvider.of(AbstractHttpClientInvoker.class)
                    .getNewExtension("httpclient", httpRequest, HttpMethod.GET);
            HttpResponse response = invoker.execute();
            if (response.code() == 200) {
                return Json.fromJson(response.asStringBody(), Map.class);
            }
            log.error("获取账户信息失败: {} - {}", response.code(), response.asStringBody());
            return java.util.Collections.emptyMap();
        } catch (Exception e) {
            log.error("获取账户信息时发生异常", e);
            return java.util.Collections.emptyMap();
        }
    }

    @Override
    public double getBalance() {
        Map<String, Object> info = getUserInfo();
        Object balance = info.get("balance");
        if (balance instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0;
    }

    private String createTask(CaptchaRequest request) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("kind", request.getType().getKind());
            body.put("url", request.getUrl());
            body.put("siteKey", request.getSiteKey());
            if (StringUtils.isNotBlank(request.getAction())) {
                body.put("action", request.getAction());
            }
            if (StringUtils.isNotBlank(request.getProxy())) {
                body.put("proxy", request.getProxy());
            }

            HttpHeader headers = new HttpHeader();
            headers.addHeader("API_KEY", setting.getApiToken());
            headers.addHeader("Content-Type", "application/json");
            HttpRequest httpRequest = HttpRequest.builder()
                    .url(setting.getApiUrl() + "/v1/request")
                    .headers(headers)
                    .connectTimeoutMill(setting.getConnectTimeout())
                    .readTimeoutMill(setting.getReadTimeout())
                    .bodyData(new BodyStringData(Json.toJson(body)))
                    .build();

            HttpClientInvoker invoker = ServiceProvider.of(AbstractHttpClientInvoker.class)
                    .getNewExtension("httpclient", httpRequest, HttpMethod.POST);
            HttpResponse response = invoker.execute();
            if (response.code() == 200) {
                Map<String, Object> result = Json.fromJson(response.asStringBody(), Map.class);
                return (String) result.get("id");
            }
            log.error("创建验证码任务失败: {} - {}", response.code(), response.asStringBody());
            return null;
        } catch (Exception e) {
            log.error("创建验证码任务时发生异常", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private CaptchaResponse getTaskResult(String taskId) {
        try {
            HttpHeader headers = new HttpHeader();
            headers.addHeader("API_KEY", setting.getApiToken());
            HttpRequest httpRequest = HttpRequest.builder()
                    .url(setting.getApiUrl() + "/v1/response/" + taskId)
                    .headers(headers)
                    .connectTimeoutMill(setting.getConnectTimeout())
                    .readTimeoutMill(setting.getReadTimeout())
                    .build();
            HttpClientInvoker invoker = ServiceProvider.of(AbstractHttpClientInvoker.class)
                    .getNewExtension("httpclient", httpRequest, HttpMethod.GET);
            HttpResponse response = invoker.execute();
            if (response.code() == 200) {
                Map<String, Object> result = Json.fromJson(response.asStringBody(), Map.class);
                Boolean ready = (Boolean) result.get("ready");
                if (Boolean.TRUE.equals(ready)) {
                    Map<String, Object> responseData = (Map<String, Object>) result.get("response");
                    String token = responseData != null ? (String) responseData.get("gRecaptchaResponse") : null;
                    return CaptchaResponse.builder().success(true).taskId(taskId).token(token).message("验证码识别成功").build();
                }
                return CaptchaResponse.builder().success(false).taskId(taskId).message("任务仍在处理中").build();
            }
            log.error("获取任务结果失败: {} - {}", response.code(), response.asStringBody());
            return CaptchaResponse.builder().success(false).taskId(taskId)
                    .message("HTTP 请求失败，状态码: " + response.code()).errorCode("HTTP_" + response.code()).build();
        } catch (Exception e) {
            log.error("获取任务结果时发生异常", e);
            return CaptchaResponse.builder().success(false).taskId(taskId)
                    .message("处理过程发生异常: " + e.getMessage()).errorCode("EXCEPTION").build();
        }
    }
}
