package com.chua.common.support.taskdistribution.dispatcher;

import com.chua.common.support.taskdistribution.task.Task;
import com.chua.common.support.taskdistribution.task.TaskResult;

/**
 * 派发数据监听器。
 *
 * <p>由派发引擎内部消费缓冲时调用，用于执行实际的派发逻辑。</p>
 *
 * @author CH
 * @since 4.0.0.42
*/
public interface DispatcherListener {

    /**
    * 消费到任务时回调。
    *
    * @param task 任务
    */
    void onTask(Task<?> task);

    /**
    * 消费到结果时回调。
    *
    * @param result 执行结果
    */
    void onResult(TaskResult<?> result);
}
