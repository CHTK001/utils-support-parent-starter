package com.chua.sinch.support.voice;

import com.chua.common.support.network.voice.CallRequest;
import com.chua.common.support.network.voice.CallResponse;
import com.chua.common.support.network.voice.VoiceCall;
import com.chua.common.support.spi.annotations.Spi;

/**
 * Sinch 语音电话实现
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("sinch")
public class SinchVoiceCall implements VoiceCall {

    @Override
    /**
     * 获取提供者
    */
    public String getProvider() {
        return "sinch";
    }

    @Override
    /**
     * 调用
    */
    public CallResponse call(CallRequest request) throws Exception {
        // Sinch SDK 未引入依赖，暂不支持语音呼叫。
 // 引入 com.sinch:sinch-sdk 后可通过 sinch客户端构建器 创建客户端调用 callingapi。
        throw new UnsupportedOperationException("Sinch 语音呼叫尚未实现，请引入 sinch-sdk 依赖后实现");
    }

    @Override
    /**
     * 获取调用状态
    */
    public CallResponse getCallStatus(String callId) throws Exception {
        throw new UnsupportedOperationException("Sinch 呼叫状态查询尚未实现");
    }

    @Override
    /**
     * Hangup
    */
    public CallResponse hangup(String callId) throws Exception {
        throw new UnsupportedOperationException("Sinch 挂断呼叫尚未实现");
    }
}