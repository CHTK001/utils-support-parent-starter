package com.chua.common.support.task.flow;

/**
 * 日志输出节点接口。
 *
 * <p>将指定消息输出到日志系统，用于流程调试与执行痕迹记录。</p>
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
public interface LogNode extends FlowNode {

    @Override
    default String type() {
        return "log";
    }
}
