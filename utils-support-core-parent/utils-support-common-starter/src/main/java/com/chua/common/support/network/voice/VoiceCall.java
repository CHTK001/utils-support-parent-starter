package com.chua.common.support.network.voice;


/**
 * 语音电话统一接口 — 通过 SPI 机制支持多提供商实现。
 *
 * <p>各语音服务提供商（如 Sinch、Twilio 等）实现此接口，
 * 通过 {@code ServiceProvider.of(VoiceCall.class).getExtension(provider)} 加载。</p>
 *
 * @author CH
 * @since 4.0.0.42
*/
public interface VoiceCall {

    /**
    * 获取提供商标识。
    *
    * @return 提供商名称（如 "sinch"、"twilio"）
    */
    String getProvider();

    /**
    * 发起语音呼叫。
    *
    * @param request 呼叫请求参数
    * @return 呼叫响应
    * @throws Exception 呼叫失败时抛出异常
    */
    CallResponse call(CallRequest request) throws Exception;

    /**
    * 查询呼叫状态。
    *
    * @param callId 呼叫 ID
    * @return 呼叫状态响应
    * @throws Exception 查询失败时抛出异常
    */
    CallResponse getCallStatus(String callId) throws Exception;

    /**
    * 挂断呼叫。
    *
    * @param callId 呼叫 ID
    * @return 挂断响应
    * @throws Exception 挂断失败时抛出异常
    */
    CallResponse hangup(String callId) throws Exception;
}
