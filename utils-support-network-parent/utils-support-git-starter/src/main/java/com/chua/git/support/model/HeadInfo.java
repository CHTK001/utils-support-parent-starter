package com.chua.git.support.model;

/**
 * 本地仓库 HEAD 概况。
 *
 * <p>由 {@link com.chua.git.support.GitClient#head()} 产出，用于「Git 仓库」面板的
 * 顶部一行：当前分支、本地提交、远端同名分支提交、是否落后。</p>
 *
 * <p>为什么单独一个 record：业务侧只需要这几个值，不该为了拿它们去 import jgit 的
 * {@code Repository}/{@code ObjectId} —— 一旦业务代码直接依赖 jgit，
 * 换实现（或加 SSH 支持）时就要改一片业务类。</p>
 *
 * @param branch       当前分支名（detached HEAD 时为提交短码）
 * @param commit       本地 HEAD 完整提交标识
 * @param shortCommit  本地 HEAD 短标识（8 位）
 * @param remoteCommit 远端同名分支提交标识（未 fetch 或远端无该分支时为 null）
 * @param behind       远端是否领先本地
 *
 * @author CH
 * @since 4.0.0.51
 */
public record HeadInfo(
        String branch,
        String commit,
        String shortCommit,
        String remoteCommit,
        boolean behind
) {
}
