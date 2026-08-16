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
    public String getProvider() {
        return "sinch";
    }

    @Override
    public CallResponse call(CallRequest request) throws Exception {
        // TODO: 使用 Sinch SDK 发起语音呼叫
        // SinchClient sinch = SinchClientBuilder.create()
        //         .applicationKey(appKey)
        //         .applicationSecret(appSecret)
        //         .build();
        // CallingApi api = sinch.calling();
        // var callRequest = api.calloutTextToSpeech()
        //         .setTo(request.getTo())
        //         .setMessage(request.getMessage());
        // var result = callRequest.execute();
        // return CallResponse.success(result.getCallId());
        throw new UnsupportedOperationException("Sinch 语音呼叫尚未实现");
    }

    @Override
    public CallResponse getCallStatus(String callId) throws Exception {
        throw new UnsupportedOperationException("Sinch 呼叫状态查询尚未实现");
    }

    @Override
    public CallResponse hangup(String callId) throws Exception {
        throw new UnsupportedOperationException("Sinch 挂断呼叫尚未实现");
    }
}