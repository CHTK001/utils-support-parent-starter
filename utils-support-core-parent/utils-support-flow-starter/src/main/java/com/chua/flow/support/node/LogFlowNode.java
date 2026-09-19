package com.chua.flow.support.node;

import com.chua.common.support.task.flow.FlowContext;
import com.chua.common.support.task.flow.FlowProps;
import com.chua.common.support.task.flow.LogNode;
import lombok.extern.slf4j.Slf4j;

/**
 * 日志输出节点。
 *
 * <p>将指定消息输出到 SLF4J 日志，用于流程调试与执行痕迹记录。</p>
 *
 * <p>节点属性说明：</p>
 * <ul>
 *   <li>{@code message} — 日志内容（支持 {} 占位符，可包含上下文属性引用）</li>
 *   <li>{@code level} — 日志级别，可选 info / warn / error，默认 info</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class LogFlowNode implements LogNode {

    /**
     * 默认日志级别
     */
    private static final String DEFAULT_LEVEL = "info";

    /**
     * 执行日志输出节点。
     *
     * <p>读取 message 与 level 属性，按指定级别输出日志。</p>
     *
     * @param context 当前流程上下文
     */
    @Override
    public void execute(FlowContext context) {
        FlowProps props = context.currentNodeProps();
        String message = props.getString("message");
        if (message == null || message.isEmpty()) {
            return;
        }
        String level = props.getString("level", DEFAULT_LEVEL);
        switch (level) {
            case "warn" -> log.warn("{}", message);
            case "error" -> log.error("{}", message);
            default -> log.info("{}", message);
        }
    }
}
