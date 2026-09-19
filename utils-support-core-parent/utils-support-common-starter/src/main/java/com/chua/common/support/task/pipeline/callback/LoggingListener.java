package com.chua.common.support.task.pipeline.callback;

import com.chua.common.support.task.pipeline.core.PipelineContext;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 日志回调监听器。
 *
 * <p>内置的 {@link PipelineListener} 实现，在节点执行前后、异常时、完成时输出日志。
 * 通过 {@link com.chua.common.support.task.pipeline.builder.PipelineBuilder#logging()} 便捷注册。</p>
 *
 * <p>日志级别：</p>
 * <ul>
 *   <li>节点执行前/后 — {@link Level#FINE}</li>
 *   <li>流水线完成 — {@link Level#INFO}</li>
 *   <li>异常 — {@link Level#SEVERE}</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class LoggingListener implements PipelineListener {

    /**
     * 日志记录器
    */
    private static final Logger LOGGER = Logger.getLogger(LoggingListener.class.getName());

    /**
     * 节点级别
    */
    private final Level nodeLevel;
    /**
     * 完成级别
    */
    private final Level completeLevel;
    /**
     * 错误级别
    */
    private final Level errorLevel;

    /**
     * 构造默认日志监听器。
     * <p>节点执行前/后使用 FINE 级别，完成使用 INFO 级别，异常使用 SEVERE 级别。</p>
     */
    public LoggingListener() {
        this(Level.FINE, Level.INFO, Level.SEVERE);
    }

    /**
     * 构造日志监听器，自定义日志级别。
     *
     * @param nodeLevel    节点执行前/后的日志级别
     * @param completeLevel 流水线完成的日志级别
     * @param errorLevel   异常的日志级别
     */
    public LoggingListener(Level nodeLevel, Level completeLevel, Level errorLevel) {
        this.nodeLevel = nodeLevel;
        this.completeLevel = completeLevel;
        this.errorLevel = errorLevel;
    }

    /**
     * 节点进入时按节点级别输出进入日志。
    */
    @Override
    public void beforeNode(PipelineContext<?> context) {
        if (LOGGER.isLoggable(nodeLevel)) {
            LOGGER.log(nodeLevel, "[Pipeline:{0}] >> Enter node: {1}",
                    new Object[]{context.getPipelineId(), context.getCurrentNodeId()});
        }
    }

    /**
     * 节点离开时按节点级别输出离开日志。
    */
    @Override
    public void afterNode(PipelineContext<?> context) {
        if (LOGGER.isLoggable(nodeLevel)) {
            LOGGER.log(nodeLevel, "[Pipeline:{0}] << Leave node: {1}",
                    new Object[]{context.getPipelineId(), context.getCurrentNodeId()});
        }
    }

    /**
     * 节点异常时按错误级别输出日志；默认返回 空 终止流水线（可覆写定制恢复路径）。
     */
    @Override
    public String onError(PipelineContext<?> context, Throwable e) {
        LOGGER.log(errorLevel, "[Pipeline:" + context.getPipelineId()
                + "] !! Error at node: " + context.getCurrentNodeId(), e);
 // 默认返回 空（终止流水线），不干预错误恢复路由
        return null;
    }

    /**
     * 流水线完成时按完成级别输出历史轨迹。
    */
    @Override
    public void onComplete(PipelineContext<?> context) {
        if (LOGGER.isLoggable(completeLevel)) {
            LOGGER.log(completeLevel, "[Pipeline:{0}] == Completed. History: {1}",
                    new Object[]{context.getPipelineId(), context.getHistory()});
        }
    }
}
