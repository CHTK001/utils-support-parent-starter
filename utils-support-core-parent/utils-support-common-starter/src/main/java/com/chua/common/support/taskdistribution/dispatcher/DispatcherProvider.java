package com.chua.common.support.taskdistribution.dispatcher;

import com.chua.common.support.taskdistribution.task.Task;
import com.chua.common.support.taskdistribution.task.TaskResult;

/**
 * 统一缓冲派发接口。
 *
 * <p>所有数据（任务、结果）通过此接口录入，内部缓冲后异步派发。
 * 发布端和工作端共用同一套接口，实现数据流统一管理。
 * 支持取消、暂停、恢复和批量处理。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DispatcherProvider extends AutoCloseable {

    /**
     * 接收任务（发布端调用）。
     *
     * @param task 任务
     */
    void receive(Task<?> task);

    /**
     * 接收结果（工作端调用）。
     *
     * @param result 执行结果
     */
    void receive(TaskResult<?> result);

    /**
     * 取消任务。
     *
     * @param taskId 任务 ID
     * @return true 表示取消成功
     */
    default boolean cancel(String taskId) {
        return false;
    }

    /**
     * 暂停任务（服务端不再派发该任务，工作端可继续执行）。
     *
     * @param taskId 任务 ID
     * @return true 表示暂停成功
     */
    default boolean pause(String taskId) {
        return false;
    }

    /**
     * 恢复暂停的任务。
     *
     * @param taskId 任务 ID
     * @return true 表示恢复成功
     */
    default boolean resume(String taskId) {
        return false;
    }

    /**
     * 暂停全局派发（所有新任务暂不派发）。
     */
    default void pauseAll() {
    }

    /**
     * 恢复全局派发。
     */
    default void resumeAll() {
    }

    /**
     * 设置每次批量处理的任务数。
     *
     * @param batchSize 批量大小（>0 生效）
     */
    default void setBatchSize(int batchSize) {
    }

    /**
     * 获取当前队列中的任务数。
     *
     * @return 任务数
     */
    default int pendingCount() {
        return 0;
    }

    /**
     * 注册数据监听器。
     *
     * @param listener 监听器
     * @return this
     */
    DispatcherProvider listener(DispatcherListener listener);

    /**
     * 启动消费线程。
     */
    void start();

    /**
     * 等待消费线程结束。
     *
     * @throws InterruptedException 中断异常
     */
    void await() throws InterruptedException;

    /**
     * 关闭释放资源。
     */
    @Override
    void close();
}