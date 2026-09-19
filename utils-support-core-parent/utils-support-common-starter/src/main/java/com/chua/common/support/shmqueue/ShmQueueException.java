package com.chua.common.support.shmqueue;

/**
 * shmqueue 异常。
 *
 * <p>错误码与 C 端 SHMQ_ERR_* 一一对应，可通过 {@link #getCode()} 获取原始整数值。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ShmQueueException extends RuntimeException {

    /**
     * 错误码（与 C 端 SHMQ_ERR_* 宏对齐）
     */
    private final int code;

    /**
     * 构造异常。
     *
     * @param code    C 端错误码
     * @param message 错误描述
     */
    public ShmQueueException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 获取错误码。
     *
     * @return C 端错误码
     */
    public int getCode() {
        return code;
    }
}
