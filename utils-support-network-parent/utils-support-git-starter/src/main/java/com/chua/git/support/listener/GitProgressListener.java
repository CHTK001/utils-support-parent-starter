package com.chua.git.support.listener;

/**
 * Git 操作进度监听器。
 *
 * <p>适用于 clone/pull/push 等需要网络传输或大量对象计算的长时间操作。
 * 回调遵循 jgit {@link org.eclipse.jgit.lib.ProgressMonitor} 的生命周期：</p>
 *
 * <ol>
 *   <li>{@link #onBegin(int)} —— 操作启动，传入总任务数</li>
 *   <li>{@link #onTaskBegin(String, int)} —— 某个具体任务（如"remote: Compressing objects"）开始</li>
 *   <li>{@link #onUpdate(int)} —— 任务进度，反复调用直到任务完成</li>
 *   <li>{@link #onTaskEnd()} —— 当前任务结束</li>
 *   <li>（重复 2-4 直到所有任务完成）</li>
 *   <li>{@link #onEnd()} —— 全部操作结束</li>
 * </ol>
 *
 * <p>{@link #isCancelled()} 提供取消机制，调用方可在任意时刻返回 {@code true} 中止操作。</p>
 *
 * <pre>使用示例：
 * {@code
 * client.cloneOp()
 *         .progressListener(new GitProgressListener() {
 *             public void onUpdate(int work) {
 *                 System.out.printf("已完成 %d%%\n", work);
 *             }
 *             // ... 其他方法
 *         })
 *         .execute();
 * }</pre> *             // ... 其他方法
 *         })
 * .执行();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface GitProgressListener {

    /**
     * 操作刚开始，告知调用方总体任务数。
     *
     * <p>仅调用一次。对于 clone/pull，通常任务数为 1 ~ 3。
     * 实际每个任务再用 {@link #onTaskBegin} 汇报工作量。</p>
     *
     * @param totalTasks 总任务数
     */
    void onBegin(int totalTasks);

    /**
     * 当前具体任务开始时回调。
     *
     * <p>与 {@code and} 的区别在于此回调里的 taskName 可能是
     * "远程: 数量 对象"、"远程: Compressing 对象"、"接收 对象" 等，
     * 便于 UI 显示当前正在做的工作。</p>
     *
     * @param taskName  任务名称，通常来自远端服务器
     * @param totalWork 总工作量，-1 表示未知
     */
    void onTaskBegin(String taskName, int totalWork);

    /**
     * 当前任务的进度更新。
     *
     * <p>从 0 开始累计，直至 {@literal totalWork}。</p>
     *
     * @param work 当前进度数值
     */
    void onUpdate(int work);

    /**
     * 当前任务结束。
     */
    void onTaskEnd();

    /**
     * 全部任务完成，操作即将返回结果。
     *
     * <p>在操作成功或失败后均会回ending返回。</p>
     */
    void onEnd();

    /**
     * 是否已取消当前 Git 操作。
     *
     * <p>JGit 的进度监视器在每个 update 之后都会查询一次 {@link #isCanceled()}，
     * 如果返回 {@value}true} 则中止当前操作。</p>
     *
     * @return true 表示取消
     */
    default boolean isCancelled() {
        return false;
    }
}
