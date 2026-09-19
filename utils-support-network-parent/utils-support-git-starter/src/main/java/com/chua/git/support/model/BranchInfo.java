package com.chua.git.support.model;

import org.eclipse.jgit.lib.Ref;

/**
 * Git 分支信息记录。
 *
 * <p>用于封装 JGit {@link Ref} 对象中的关键数据，将远程/本地分支的处理细节屏蔽掉。
 * @{@code isRemote} 标志可以快速区分分支类型。</p>
 *
 * @param name       分支完整引用名，如 {@code refs/heads/main} 或 {@code refs/remotes/origin/main}
 * @param shortName  分支短名称，本地分支为 {@code main}，远程分支不含远端前缀也为 {@code main}
 * @param objectId   分支 HEAD 指向的提交 标识（40 位 SHA-1 或 64 位 SHA-256）
 * @param isRemote   是否为远程分支（引用名前缀为 {@code refs/remotes/}）
 *
 * @author CH
 * @since 4.0.0.42
 * @return 分支信息的结果
 */
public record BranchInfo(String name, String shortName, String objectId, boolean isRemote) {

    /**
     * 从 jgit 的 {@link Ref} 对象织造 {@code BranchInfo}。
     *
     * <p>解析规则：</p>
     * <ul>
     *   <li>{@code refs/heads/main} → name="refs/heads/main", shortName="main", isRemote=false</li>
     *   <li>{@code refs/remotes/origin/main} → name="refs/remotes/origin/main", shortName="main", isRemote=true</li>
     * </ul>
     *
     * @param ref 来自 {@link org.eclipse.jgit.api.Git#branchList()} 的引用
     * @return 分支信息
     */
    public static BranchInfo from(Ref ref) {
        String name = ref.getName();
        boolean isRemote = name.startsWith("refs/remotes/");
        String shortName = isRemote
                ? name.substring("refs/remotes/".length()).replaceFirst("^[^/]+/", "")
                : name.substring("refs/heads/".length());
        return new BranchInfo(name, shortName, ref.getObjectId().getName(), isRemote);
    }
}
