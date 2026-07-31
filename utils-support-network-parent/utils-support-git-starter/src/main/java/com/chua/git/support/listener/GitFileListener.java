package com.chua.git.support.listener;

/**
 * Git 文件变更监听器，用于接收仓库中文件级的新增、修改、删除事件。
 *
 * <p>配合 {@link com.chua.git.support.operation.FetchOperation} 使用时，每次定时拉取
 * 完成后会自动对基准树和当前树做 diff，将每个文件的变更通过本接口通知调用方。</p>
 *
 * <pre>示例：监听每次 pull 后的文件变更
 * {@code
 * client.open().pull()
 *         .interval(10, TimeUnit.SECONDS)
 *         .listener(event -> {
 *             System.out.println("文件 [" + event.changeType() + "]: " + event.filePath());
 *         })
 *         .start();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface GitFileListener {

    /**
     * 文件发生变更时调用。
     *
     * <p>注意：此方法是在拉取后 diff 的结果中调用，重复拉取可能对已存在的变更再报告一次。
     * 若需去重，监听者可自行维护已处理文件的 set。</p>
     *
     * @param event 变更事件详情
     */
    void onChanged(GitFileEvent event);
}