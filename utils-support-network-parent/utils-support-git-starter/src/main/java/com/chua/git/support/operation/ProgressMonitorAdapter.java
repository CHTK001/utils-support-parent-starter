package com.chua.git.support.operation;

import com.chua.git.support.listener.GitProgressListener;
import org.eclipse.jgit.lib.ProgressMonitor;

/**
* jgit {@link ProgressMonitor} 与项目自定义的 {@link GitProgressListener} 适配器。
*
* <p>JGit 内部请求进度报告时只会调用 ProgressMonitor 的方法，而业务侧接口是
* {@link GitProgressListener}。该适配器将两者一一对应：</p>
*
* <pre>
* ProgressMonitor.start(totalTasks)  →  listener.onBegin(totalTasks)
* ProgressMonitor.beginTask(title, w) →  listener.onTaskBegin(title, w)
* ProgressMonitor.update(work)       →  listener.onUpdate(work)
* ProgressMonitor.endTask()          →  listener.onTaskEnd()
* ProgressMonitor.isCancelled()      →  listener.isCancelled()
* ProgressMonitor.showDuration(en)   →  (noop)
* </pre>
*
* <p>注意：{@link #showDuration(boolean)} 在 JGit 7.x 中是新增的抽象方法，
* 此处忽略该开关，仅保证编译通过。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class ProgressMonitorAdapter implements ProgressMonitor {

    /**
    * 委托的实际监听器。
     */
    private final GitProgressListener listener;

    /**
    * 构造适配器。
    *
    * @param listener 进度监听器，非空
     */
    public ProgressMonitorAdapter(GitProgressListener listener) {
        this.listener = listener;
    }

    /**
    * 操作开始。
    *
    * @param totalTasks 总任务数
     */
    @Override
    public void start(int totalTasks) {
        listener.onBegin(totalTasks);
    }

    /**
    * 任务开始。
    *
    * @param title     任务名称，如 "远程: 数量 对象"
    * @param totalWork 总工作量，-1 → 未知
     */
    @Override
    public void beginTask(String title, int totalWork) {
        listener.onTaskBegin(title, totalWork);
    }

    /**
    * 进度更新。
    *
    * @param completed 自上次 更新 之后的增量
     */
    @Override
    public void update(int completed) {
        listener.onUpdate(completed);
    }

    /**
    * 任务结束。
     */
    @Override
    public void endTask() {
        listener.onTaskEnd();
    }

    /**
    * 是否取消。
    *
    * @return {@code true} 中止
     */
    @Override
    public boolean isCancelled() {
        return listener.isCancelled();
    }

    /**
    * （jgit 7.x 新增）忽略时长显示开关。
    *
    * @param enabled 是否显示
     */
    @Override
    public void showDuration(boolean enabled) {
    }
}