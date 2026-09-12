package com.chua.captcha.support;

import lombok.Builder;
import lombok.Data;

/**
 * 验证码解析响应结果
 * <p>
 * 封装验证码解析服务返回的处理结果，包含解析是否成功、
   * 解析得到的 令牌、任务 标识、错误码及执行耗时等信息。
 * </p>
 *
 * @author CH
 * @since 2026-03-14
 */
@Data
@Builder
public class CaptchaResponse {

    /**
     * 是否解析成功
     */
    private boolean success;

    /**
      * 解析得到的 令牌
     */
    private String token;

    /**
      * 任务 标识
     */
    private String taskId;

    /**
     * 响应消息
     */
    private String message;

    /**
     * 错误码
     */
    private String errorCode;

    /**
     * 执行耗时（毫秒）
     */
    private long executionTime;
}
