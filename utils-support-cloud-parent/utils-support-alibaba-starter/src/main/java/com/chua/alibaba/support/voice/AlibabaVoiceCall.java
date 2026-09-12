package com.chua.alibaba.support.voice;

import com.aliyun.teaopenapi.Client;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teaopenapi.models.OpenApiRequest;
import com.aliyun.teautil.models.RuntimeOptions;
import com.chua.common.support.lang.json.JsonObject;
import com.chua.common.support.network.voice.CallRequest;
import com.chua.common.support.network.voice.CallResponse;
import com.chua.common.support.network.voice.VoiceCall;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.message.MessageEnvironment;

import java.util.HashMap;
import java.util.Map;

/**
* 阿里云语音电话实现（基于 dysmsapi Tea SDK）
*
* <p>通过阿里云 dysmsapi20170525 SDK 的 Tea-OpenAPI 框架调用语音服务。
* 使用 单个callbytts 接口实现文本转语音外呼。
*
* <h3>环境配置</h3>
* <pre>
*   voice.accessKey           阿里云 AccessKey（必填）
*   voice.secretKey           阿里云 SecretKey（必填）
*   voice.ttsCode             语音模板 Code（必填）
*   voice.calledShowNumber    主叫号码（必填）
* </pre>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("alibaba-voice")
public class AlibabaVoiceCall implements VoiceCall {

    /** 消息环境 */
    private final MessageEnvironment environment;

    /** 创建 alibabavoicecall 实例 */
    public AlibabaVoiceCall() {
        this(new MessageEnvironment());
    }

    /**
    * 创建 alibabavoicecall 实例
    * @param environment 环境
     */
    public AlibabaVoiceCall(MessageEnvironment environment) {
        this.environment = environment;
    }

    @Override
    /** 获取提供者 */
    public String getProvider() {
        return "alibaba-voice";
    }

    @Override
    /** 调用 */
    public CallResponse call(CallRequest request) throws Exception {
        long start = System.currentTimeMillis();

        String accessKey = environment.get("voice.accessKey");
        String secretKey = environment.get("voice.secretKey");
        String ttsCode = environment.get("voice.ttsCode");
        String calledShowNumber = environment.get("voice.calledShowNumber");

        if (accessKey == null || accessKey.isBlank()) {
            throw new IllegalArgumentException("voice.accessKey 配置项必填");
        }
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("voice.secretKey 配置项必填");
        }
        if (ttsCode == null || ttsCode.isBlank()) {
            throw new IllegalArgumentException("voice.ttsCode 配置项必填");
        }
        if (calledShowNumber == null || calledShowNumber.isBlank()) {
            throw new IllegalArgumentException("voice.calledShowNumber 配置项必填");
        }

        Config config = new Config()
                .setAccessKeyId(accessKey)
                .setAccessKeySecret(secretKey)
                .setEndpoint("dyvmsapi.aliyuncs.com");

        Client client = new Client(config);

        Map<String, Object> body = new HashMap<>(5);
        body.put("CalledNumber", request.getTo());
        body.put("CalledShowNumber", calledShowNumber);
        body.put("TtsCode", ttsCode);

        if (request.getMessage() != null && !request.getMessage().isBlank()) {
            Map<String, String> ttsParam = new HashMap<>();
            ttsParam.put("message", request.getMessage());
            body.put("TtsParam", JsonObject.create().fluentPut(ttsParam).toJSONString());
        }

        if (request.getTimeout() != null) {
            body.put("OutId", String.valueOf(request.getTimeout()));
        }

        OpenApiRequest apiRequest = new OpenApiRequest();
        apiRequest.setBody(body);

        Map<String, ?> resp = client.doRPCRequest(
                "SingleCallByTts", "2017-05-25",
                "RPC", "GET",
                "", null,
                apiRequest,
                new RuntimeOptions()
        );

        long duration = System.currentTimeMillis() - start;

        String code = (String) resp.get("Code");
        if ("OK".equals(code)) {
            String callId = (String) resp.get("CallId");
            return CallResponse.builder()
                    .success(true)
                    .callId(callId)
                    .status("initiated")
                    .duration((long) duration)
                    .build();
        } else {
            String message = (String) resp.get("Message");
            return CallResponse.builder()
                    .success(false)
                    .errorMessage(code + ": " + message)
                    .duration((long) duration)
                    .build();
        }
    }

    @Override
    /** 获取调用状态 */
    public CallResponse getCallStatus(String callId) throws Exception {
        long start = System.currentTimeMillis();

        String accessKey = environment.get("voice.accessKey");
        String secretKey = environment.get("voice.secretKey");

        Config config = new Config()
                .setAccessKeyId(accessKey)
                .setAccessKeySecret(secretKey)
                .setEndpoint("dyvmsapi.aliyuncs.com");

        Client client = new Client(config);

        Map<String, Object> body = new HashMap<>(1);
        body.put("CallId", callId);

        OpenApiRequest apiRequest = new OpenApiRequest();
        apiRequest.setBody(body);

        Map<String, ?> resp = client.doRPCRequest(
                "QueryCallDetailByCallId", "2017-05-25",
                "RPC", "GET",
                "", null,
                apiRequest,
                new RuntimeOptions()
        );

        long duration = System.currentTimeMillis() - start;

        String code = (String) resp.get("Code");
        if ("OK".equals(code)) {
            Object data = resp.get("Data");
            return CallResponse.builder()
                    .success(true)
                    .callId(callId)
                    .status("queried")
                    .data(new HashMap<>(Map.of("detail", data != null ? data : "")))
                    .duration(duration)
                    .build();
        }

        return CallResponse.builder()
                .success(false)
                .callId(callId)
                .errorMessage((String) resp.get("Message"))
                .duration(duration)
                .build();
    }

    @Override
    /** Hangup */
    public CallResponse hangup(String callId) throws Exception {
        String accessKey = environment.get("voice.accessKey");
        String secretKey = environment.get("voice.secretKey");

        Config config = new Config()
                .setAccessKeyId(accessKey)
                .setAccessKeySecret(secretKey)
                .setEndpoint("dyvmsapi.aliyuncs.com");

        Client client = new Client(config);

        Map<String, Object> body = new HashMap<>();
        body.put("CallId", callId);

        OpenApiRequest apiRequest = new OpenApiRequest();
        apiRequest.setBody(body);

        Map<String, ?> resp = client.doRPCRequest(
                "CancelCall", "2017-05-25",
                "RPC", "GET",
                "", null,
                apiRequest,
                new RuntimeOptions()
        );

        String code = (String) resp.get("Code");
        return "OK".equals(code)
                ? CallResponse.success(callId)
                : CallResponse.failure((String) resp.get("Message"));
    }
}