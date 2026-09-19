package com.chua.common.support.ai.chat.protocol;


/**
 * @deprecated 已由 {@link FileAiTokenProvider} + {@link AiTokenProvider} 替代。
 * 请使用 {@link FileAiTokenProvider} 实现基于文件的令牌管理，
 * 或通过 {@link AggregateChatClient#setTokenProvider(AiTokenProvider)} 设置自定义令牌提供者。
 * 此类将不会包含任何实现，引用此类的代码将无法编译。
 * @author CH
 * @since 4.0.0.42
 */
@Deprecated
public class AiTokenManager {
    /**
     * 创建 AiTokenManager 实例
    */
    private AiTokenManager() {
        throw new UnsupportedOperationException("AiTokenManager 已废弃，请使用 FileAiTokenProvider + AiTokenProvider");
    }
}
