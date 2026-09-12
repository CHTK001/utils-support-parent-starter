package com.chua.common.support.network.voice;

import lombok.Builder;
import lombok.Data;

/**
* 语音呼叫请求参数。
*
* @author CH
* @since 4.0.0.42
 */
@Data
@Builder
public class CallRequest {

    /**
    * 被叫号码（E.164 格式，如 +8613800138000）
     */
    private String to;

    /**
    * 主叫号码（可选）
     */
    private String from;

    /**
    * 语音播报文本内容（TTS 场景）
     */
    private String message;

    /**
    * 语言区域（如 zh-CN、en-US）
     */
    private String locale;

/**
* 自定义参数（提供商特定配置）
     */
    private String custom;

    /**
    * 呼叫超时时间（毫秒，可选）
     */
    private Long timeout;
}
