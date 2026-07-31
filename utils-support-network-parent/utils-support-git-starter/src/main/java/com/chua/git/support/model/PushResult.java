package com.chua.git.support.model;

/**
 * Git 推送结果记录。
 *
 * <p>由 {@link com.chua.git.support.GitClient#push()} 操作返回，表示一次
 * {@code git push} 的执行状态：</p>
 * <ul>
 *   <li>{@code success} — {@code true} 表示推送整体成功（所有远端都接受）</li>
 *   <li>{@code refUpdates} — 更新的远程引用数量，若为 0 则表示本地与远端同步</li>
 *   <li>{@code messages} — 人类可读的描述，如 "Pushed 2 ref(s)."</li>
 * </ul>
 *
 * <p>即使 {@code success=true}，{@code refUpdates=0} 也仅表示当前没有新的提交需要推送。</p>
 *
 * @param success    推送是否成功
 * @param refUpdates 更新的远程引用数量
 * @param messages   推送过程的消息
 *
 * @author CH
 * @since 4.0.0.42
 */
public record PushResult(boolean success, int refUpdates, String messages) {

    /**
     * 快速创建"无变更"结果。
     *
     * @return 推送成功但无新内容的 PushResult
     */
    public static PushResult noChange() {
        return new PushResult(true, 0, "Everything up-to-date.");
    }
}