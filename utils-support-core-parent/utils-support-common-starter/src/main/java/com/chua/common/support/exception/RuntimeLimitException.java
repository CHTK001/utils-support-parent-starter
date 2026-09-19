package com.chua.common.support.exception;


/**
 * 运行时限制异常。
 * <p>
 * 当业务逻辑触发了预定义的运行时限制条件（如频率限制、并发数限制、资源配额耗尽等）时抛出此异常。
 * 该异常继承自 RuntimeException，属于非受检异常，通常用于在系统层面快速中断不符合约束条件的操作。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class RuntimeLimitException extends RuntimeException {
}
