package com.chua.common.support.taskdistribution.node;

import com.chua.common.support.taskdistribution.task.Task;
import com.chua.common.support.taskdistribution.task.TaskResult;

import java.util.Collections;
import java.util.Map;

/**
 * 万物皆节点接口。
 *
 * <p>所有参与者（发布端、工作端、中间件）都实现此接口。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface Node {

    /**
     * 获取节点唯一标识。
     *
     * @return 节点 ID
     */
    String nodeId();

    /**
     * 获取节点能力标签。
     *
     * @return 标签映射，示例：{"cap": "cpu", "group": "prod"}
     */
    default Map<String, String> tags() {
        return Collections.emptyMap();
    }

    /**
     * 接收到任务时回调（工作端实现）。
     *
     * @param task 任务
     */
    default void onTask(Task<?> task) {
    }

    /**
     * 接收到结果时回调（发布端实现）。
     *
     * @param result 执行结果
     */
    default void onResult(TaskResult<?> result) {
    }

    /**
     * 节点启动（初始化连接等）。
     */
    default void start() throws Exception {
    }

    /**
     * 节点关闭（释放资源）。
     */
    default void stop() throws Exception {
    }
}