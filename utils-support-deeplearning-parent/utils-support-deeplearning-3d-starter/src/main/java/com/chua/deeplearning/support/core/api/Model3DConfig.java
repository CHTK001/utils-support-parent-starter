package com.chua.deeplearning.support.core.api;

import com.chua.common.support.constant.CommonConstant;
import lombok.Data;

import java.time.Duration;

/**
 * 3D 生成配置
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
public class Model3DConfig {

    /** 是否启用 3D 生成 */
    /** Enable */
    private boolean enable = true;

    /** API 端点 URL */
    /** 端点 */
    private String endpoint = "https://api.trellis.ai/v1";

    /** API 密钥（NVIDIA/HF/Replicate） */
    /** API密钥 */
    private String apiKey = CommonConstant.EMPTY_STRING;

    /** 连接超时时间 */
    /** 连接超时 */
    private Duration connectTimeout = Duration.ofSeconds(30);

    /** 读取超时时间 */
    /** 读取超时 */
    private Duration readTimeout = Duration.ofMinutes(5);

    /** 写入超时时间 */
    /** 写入超时 */
    private Duration writeTimeout = Duration.ofSeconds(30);

    /** 默认质量 */
    /** 默认quality */
    private String defaultQuality = "standard";

    /** 默认风格 */
    /** 默认style */
    private String defaultStyle = "original";

    /** 是否启用调试日志 */
    /** 调试日志 */
    private boolean debugLog = false;

    /** 最大并发请求数 */
    /** 最大值并发 */
    private int maxConcurrent = 5;
}