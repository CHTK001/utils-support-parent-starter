package com.chua.common.support.concurrent.backoff;

import com.chua.common.support.utils.ThreadUtils;


/**
* 避让器提供者 SPI 接口。
*
* <p>定义避让延迟计算的核心行为。通过 SPI 机制支持不同的避让策略
* （如指数退避、固定延迟、斐波那契退避等）。</p>
*
* @author CH
* @since 2026/07/24
 */
public interface BackoffProvider {

    /**
    * 计算下一次避让的等待时间（毫秒）。
    *
    * @param attempt 当前尝试次数（从 0 开始）
    * @return 等待时间（毫秒）
    */
    long nextDelay(int attempt);

    /**
    * 执行避让休眠。
    *
    * @param attempt 当前尝试次数（从 0 开始）
    */
    default void sleep(int attempt) {
        // ThreadUtils.sleep(long) 不抛 checked 异常（内部吞掉 InterruptedException），
        // 直接调用即可，避免 javac 不可达 catch 编译错误
        ThreadUtils.sleep(nextDelay(attempt));
    }

    /**
    * 重置内部状态。
    */
    default void reset() {
    }
}
