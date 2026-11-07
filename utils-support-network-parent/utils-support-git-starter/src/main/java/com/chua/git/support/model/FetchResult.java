package com.chua.git.support.model;

/**
 * Git 远端同步检查结果。
 *
 * <p>由 {@link com.chua.git.support.operation.RemoteOperation#fetch(String)} 产出，
 * 对应「{@code git fetch} + 比较本地 HEAD 与远端分支」这一动作。</p>
 *
 * <p>为什么不复用 {@link PullResult}：pull 会合并工作区（可能产生冲突、改动文件），
 * 而「有没有更新」只需要 fetch；轮询检查时用 pull 会把用户本地的改动合掉。</p>
 *
 * @param branch       检查的分支名
 * @param localCommit  本地 HEAD 的提交标识（未克隆/无提交时为 null）
 * @param remoteCommit 远端分支的提交标识（远端无该分支时为 null）
 * @param updated      远端是否领先本地
 * @param message      人类可读结论
 *
 * @author CH
 * @since 4.0.0.51
 */
public record FetchResult(
        String branch,
        String localCommit,
        String remoteCommit,
        boolean updated,
        String message
) {

    /**
     * 远端无该分支时的结果。
     *
     * @param branch 分支名
     * @return 结果
     */
    public static FetchResult missing(String branch) {
        return new FetchResult(branch, null, null, false, "远端没有分支 " + branch);
    }
}
