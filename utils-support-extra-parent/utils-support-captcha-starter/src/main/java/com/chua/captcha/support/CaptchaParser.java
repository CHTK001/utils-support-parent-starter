package com.chua.captcha.support;

import java.util.Map;

/**
* 验证码解析器接口
* <p>
* 定义验证码解析服务的核心行为：提交验证码图片进行解析、查询解析结果。
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
    * @param imageData 验证码图片的字节数据
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
}
