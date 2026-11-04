package com.chua.skywalking.support.client;

/**
 * SkyWalking 客户端异常。
 * <p>
 * 仅在 {@code OtnlEngine} 实现内部流转：引擎在边界处捕获它并降级为空数据 +
 * 通过 {@code status()} 暴露原因，绝不允许它冒泡到 Web 层把整页打成 500。
 * </p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public class SkywalkingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造异常
     *
     * @param message 消息
     */
    public SkywalkingException(String message) {
        super(message);
    }

    /**
     * 构造异常
     *
     * @param message 消息
     * @param cause   原因
     */
    public SkywalkingException(String message, Throwable cause) {
        super(message, cause);
    }
}
