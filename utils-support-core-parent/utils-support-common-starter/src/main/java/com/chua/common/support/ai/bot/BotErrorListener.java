package com.chua.common.support.ai.bot;


/**
* Bot 错误监听器
* <p>
* 通过 {@link BotClient#addErrorListener(BotErrorListener)} 注册，
* 接收 HTTP 调用等异常。
* </p>
*
* @author CH
* @since 2026/07/18
 */
public interface BotErrorListener {

    /**
    * 错误回调
    *
    * @param throwable 异常
     */
    void onError(Throwable throwable);
}
