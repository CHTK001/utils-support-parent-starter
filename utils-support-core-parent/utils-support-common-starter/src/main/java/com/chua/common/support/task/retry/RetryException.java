package com.chua.common.support.task.retry;


/**
* 重试异常
*
* <p>当所有重试均已耗尽但仍未成功时抛出此异常。
* 包含最终的重试次数和最后一次捕获的异常原因。
*
* @author CH
* @since 1.0.0
 */
public class RetryException extends Exception {

    /**
    * 已执行的重试次数
     */
    private final int attemptCount;

    /**
    * 最后一次异常
     */
    private final Throwable lastCause;

    /**
    * 构造重试异常
    *
    * @param attemptCount 已执行的重试次数
    * @param lastCause    最后一次异常
     */
    public RetryException(int attemptCount, Throwable lastCause) {
        super("Retry failed after " + attemptCount + " attempts", lastCause);
        this.attemptCount = attemptCount;
        this.lastCause = lastCause;
    }

    /**
    * 获取已执行的重试次数
    *
    * @return 重试次数
     */
    public int getAttemptCount() { return attemptCount; }

    /**
    * 获取最后一次异常
    *
    * @return 最后一次异常
     */
    public Throwable getLastCause() { return lastCause; }
}
