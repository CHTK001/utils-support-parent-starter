package com.chua.common.support.ai.audio;

/**
* 语音识别（ASR）客户端接口别名。
*
* <p>早期版本的 ASR 客户端接口名为 {@code AudioClient}，后续统一为
* {@link VirtualClient}（与 {@link TextToAudioClient} 的命名风格保持一致）。
* 为兼容历史引用（{@code spring-support-ai-starter} 等模块仍使用旧名），
* 保留本类型别名。后续消费方应统一改用 {@link VirtualClient}。</p>
*
* @author CH
* @since 4.0.0.42
 */
public interface AudioClient extends VirtualClient {
}
