package com.chua.common.support.ai.agent;

import java.util.Map;

/**
 * Agent 工具执行器。
 *
 * <p>由 {@link AgentTool} 携带，在模型发起 function calling 时被调用。
 * 实现应只做纯业务逻辑，异常由适配层统一转换为工具错误结果。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@FunctionalInterface
public interface AgentToolExecutor {

    /**
     * 执行工具。
     *
     * @param arguments 模型给出的参数（已按 JSON Schema 反序列化为 Map），无参数时为空 Map
     * @return 执行结果；适配层会用 {@link String#valueOf(Object)} 转成文本回灌模型
     */
    Object execute(Map<String, Object> arguments);
}
