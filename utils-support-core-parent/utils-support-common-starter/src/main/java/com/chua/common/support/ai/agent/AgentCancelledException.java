package com.chua.common.support.ai.agent;

import com.chua.common.support.utils.StringUtils;

/**
 * 运行被取消异常。
 *
 * <p>执行方在循环点检查 {@link AgentCancellationToken} 命中取消后，
 * 可抛出本异常快速退出嵌套循环；外层应捕获并转换为带原因的
 * 取消响应 / 事件，不按普通错误处理（不重试、不告警堆栈）。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public class AgentCancelledException extends RuntimeException {

    /**
     * 序列化版本号
     */
    private static final long serialVersionUID = 1L;

    /**
     * 构造取消异常。
     *
     * @param reason 取消原因，可为 null
     */
    public AgentCancelledException(String reason) {
        super(StringUtils.isEmpty(reason) ? "运行已被取消" : ("运行已被取消: " + reason));
    }
}
