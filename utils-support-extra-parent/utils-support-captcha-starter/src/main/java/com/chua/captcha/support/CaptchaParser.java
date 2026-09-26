package com.chua.captcha.support;

import com.chua.common.support.constant.CaptchaConstant;

import java.util.HashMap;
import java.util.Map;

/**
 * 验证码解析器接口
 * <p>
 * 定义验证码解析服务的核心行为：提交验证码图片进行解析、查询解析结果。
 * {@link #awaitResult} 与 {@link #solve} 提供轮询等待的一次式入口。
 * 实现类可对接不同的验证码解析服务提供商（如 captcha运行、yescaptcha 等）。
 * </p>
 *
 * @author CH
 * @since 2026-03-14
 */
public interface CaptchaParser {

    /**
     * 提交验证码图片进行解析
     *
     * @param imageData 验证码图片的字节数据，图文类验证码（{@link CaptchaType#TEXT_CAPTCHA}）必需
     * @param options 解析选项配置（如验证码类型、site键、代理设置等）
     * @return 解析任务 标识，可用于后续查询结果
     */
    String submitCaptcha(byte[] imageData, Map<String, String> options);

    /**
     * 根据任务 标识 查询解析结果
     *
     * @param taskId 解析任务 标识
     * @return 解析结果响应
     */
    CaptchaResponse queryResult(String taskId);

    /**
     * 轮询等待解析结果。
     *
     * @param taskId 解析任务 标识
     * @param timeoutMillis 最长等待时间（毫秒）
     * @param pollIntervalMillis 轮询间隔（毫秒）
     * @return 终态响应；超时返回 {@link CaptchaConstant#ERROR_TIMEOUT}，等待被中断时同样返回该错误码
     */
    default CaptchaResponse awaitResult(String taskId, long timeoutMillis, long pollIntervalMillis) {
        long begin = System.currentTimeMillis();
        long interval = Math.max(1L, pollIntervalMillis);
        while (true) {
            CaptchaResponse response = queryResult(taskId);
            long cost = System.currentTimeMillis() - begin;
            if (response == null) {
                response = CaptchaResponse.builder().taskId(taskId).build();
            }
            response.setExecutionTime(cost);
            if (response.isSuccess() || response.getErrorCode() != null) {
                return response;
            }
            if (cost >= timeoutMillis) {
                response.setSuccess(false);
                response.setErrorCode(CaptchaConstant.ERROR_TIMEOUT);
                response.setMessage("等待验证码解析超时: " + cost + "ms");
                return response;
            }
            try {
                Thread.sleep(Math.min(interval, timeoutMillis - cost));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                response.setSuccess(false);
                response.setErrorCode(CaptchaConstant.ERROR_TIMEOUT);
                response.setMessage("等待验证码解析被中断");
                return response;
            }
        }
    }

    /**
     * 轮询等待解析结果，超时与间隔取自请求参数。
     *
     * @param taskId 解析任务 标识
     * @param request 解析请求参数
     * @return 终态响应
     */
    default CaptchaResponse awaitResult(String taskId, CaptchaRequest request) {
        return awaitResult(taskId, request.getTimeout(), request.getPollInterval());
    }

    /**
     * 提交并等待结果的一次式入口。
     *
     * @param request 解析请求参数
     * @param imageData 验证码图片字节，图文类验证码必需，可为 null
     * @return 终态响应；提交失败返回 {@link CaptchaConstant#ERROR_SUBMIT_FAILED}
     */
    default CaptchaResponse solve(CaptchaRequest request, byte[] imageData) {
        Map<String, String> options = new HashMap<>();
        if (request.getType() != null) {
            options.put("captchaType", request.getType().getType());
        }
        if (request.getSiteKey() != null) {
            options.put("siteKey", request.getSiteKey());
        }
        if (request.getUrl() != null) {
            // 两家服务商取名的字段不同，同时给出以命中各自的键
            options.put("url", request.getUrl());
            options.put("siteReferer", request.getUrl());
        }
        if (request.getAction() != null) {
            options.put("action", request.getAction());
            options.put("siteAction", request.getAction());
        }
        if (request.getProxy() != null) {
            options.put("proxy", request.getProxy());
        }
        String taskId = submitCaptcha(imageData, options);
        if (taskId == null || taskId.isEmpty()) {
            return CaptchaResponse.builder()
                    .success(false)
                    .errorCode(CaptchaConstant.ERROR_SUBMIT_FAILED)
                    .message("提交验证码解析任务失败，未获得任务标识")
                    .build();
        }
        return awaitResult(taskId, request);
    }
}
