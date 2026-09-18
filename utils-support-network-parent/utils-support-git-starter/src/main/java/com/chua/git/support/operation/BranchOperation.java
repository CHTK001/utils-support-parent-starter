package com.chua.git.support.operation;

import com.chua.git.support.GitClient;
import com.chua.git.support.exception.GitClientException;
import com.chua.git.support.model.BranchInfo;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
* 分支信息查询与操作。
*
* <p>对已打开的本地仓库，提供以下操作：</p>
* <ul>
*   <li>{@link #listAll()} — 所有分支（本地 + 远程）</li>
*   <li>{@link #listLocal()} — 仅本地分支</li>
*   <li>{@link #listRemote()} — 仅远程分支</li>
*   <li>{@link #current()} — 当前 HEAD 指向的分支</li>
*   <li>{@link #checkout(String)} — 切换分支</li>
*   <li>{@link #checkoutCreate(String)} — 创建并切换新分支</li>
*   <li>{@link #create(String, String)} — 创建分支（不切换）</li>
*   <li>{@link #delete(String)} — 删除分支</li>
*   <li>{@link #deleteForce(String)} — 强制删除分支</li>
*   <li>{@link #remoteUrls()} — 已配置的远端 URL</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class BranchOperation {

    /**
    * 所属 git客户端。
    */
    private final GitClient client;

    /**
    * 构建操作实例（仅框架内部调用）。
    *
    * @param client 所属 Git客户端
    */
    public BranchOperation(GitClient client) {
        this.client = client;
    }

    /**
    * 列出所有分支（本地 + 远程）。
    *
    * @return 分支信息列表，可能为空列表
    */
    public List<BranchInfo> listAll() {
        try {
            List<Ref> refs = client.getGit()
                    .branchList()
                    .setListMode(ListBranchCommand.ListMode.ALL)
                    .call();
            return refs.stream()
                    .map(BranchInfo::from)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new GitClientException("获取分支列表失败: " + e.getMessage(), e);
        }
    }

    /**
    * 仅列出本地分支。
    *
    * @return 本地分支列表
    */
    public List<BranchInfo> listLocal() {
        return listAll().stream()
                .filter(b -> !b.isRemote())
                .collect(Collectors.toList());
    }

    /**
    * 仅列出远程分支。
    *
    * @return 远程分支列表
    */
    public List<BranchInfo> listRemote() {
        return listAll().stream()
                .filter(BranchInfo::isRemote)
                .collect(Collectors.toList());
    }

    /**
    * 查询当前 HEAD 指向的分支。
    *
    * @return 当前分支信息
    * @throws GitClientException 若仓库处于 detached HEAD 状态（无分支指向）
    */
    public BranchInfo current() {
        try {
            Repository repo = client.getRepository();
            String refName = repo.getFullBranch();
            Ref ref = repo.findRef(refName);
            if (ref == null) {
                throw new GitClientException("无法找到当前分支: " + refName);
            }
            return BranchInfo.from(ref);
        } catch (GitClientException ex) {
            throw ex;
        } catch (Exception e) {
            throw new GitClientException("获取当前分支失败: " + e.getMessage(), e);
        }
    }

    // ==================== 分支操作方法 ====================

    /**
    * 切换到指定分支（Git checkout / Git switch）。
    *
    * @param branch 分支名称（如 "main"、"develop"）
    * @return 当前操作实例
    */
    public BranchOperation checkout(String branch) {
        try {
            client.open();
            client.getGit().checkout()
                    .setName(branch)
                    .call();
            log.info("Git checkout: {}", branch);
        } catch (Exception e) {
            throw new GitClientException("Git checkout 失败: " + e.getMessage(), e);
        }
        return this;
    }

    /**
    * 创建并切换到新分支（Git checkout -b）。
    *
    * @param branch 新分支名称
    * @return 当前操作实例
    */
    public BranchOperation checkoutCreate(String branch) {
        try {
            client.open();
            client.getGit().checkout()
                    .setName(branch)
                    .setCreateBranch(true)
                    .call();
            log.info("Git branch 创建并切换: {}", branch);
        } catch (Exception e) {
            throw new GitClientException("Git branch 创建失败: " + e.getMessage(), e);
        }
        return this;
    }

    /**
    * 基于指定起点创建新分支（不切换）。
    *
    * @param branch     新分支名称
    * @param startPoint 起点引用（如 "main"、"v1.0"、提交 SHA）
    * @return 当前操作实例
    */
    public BranchOperation create(String branch, String startPoint) {
        try {
            client.open();
            client.getGit().branchCreate()
                    .setName(branch)
                    .setStartPoint(startPoint)
                    .call();
            log.info("Git branch 创建: {} <- {}", branch, startPoint);
        } catch (Exception e) {
            throw new GitClientException("Git branch 创建失败: " + e.getMessage(), e);
        }
        return this;
    }

    /**
    * 删除本地分支（Git 分支 -d）。
    *
    * @param branch 分支名称
    * @return 当前操作实例
    */
    public BranchOperation delete(String branch) {
        try {
            client.open();
            client.getGit().branchDelete()
                    .setBranchNames(branch)
                    .call();
            log.info("Git branch 删除: {}", branch);
        } catch (Exception e) {
            throw new GitClientException("Git branch 删除失败: " + e.getMessage(), e);
        }
        return this;
    }

    /**
    * 强制删除本地分支（Git 分支 -D），允许删除未合并分支。
    *
    * @param branch 分支名称
    * @return 当前操作实例
    */
    public BranchOperation deleteForce(String branch) {
        try {
            client.open();
            client.getGit().branchDelete()
                    .setBranchNames(branch)
                    .setForce(true)
                    .call();
            log.info("Git branch 强制删除: {}", branch);
        } catch (Exception e) {
            throw new GitClientException("Git branch 强制删除失败: " + e.getMessage(), e);
        }
        return this;
    }

    /**
    * 查询已配置的远程仓库 URL。
    *
    * <p>遍历 {@code .git/config} 中的 [remote] 段获取 url 值。</p>
    *
    * @return 远程 URL 列表，通常只有一条
    */
    public List<String> remoteUrls() {
        try {
            List<String> urls = new ArrayList<>();
            Repository repo = client.getRepository();
            for (String remoteName : repo.getConfig().getSubsections("remote")) {
                String url = repo.getConfig().getString("remote", remoteName, "url");
                urls.add(url);
            }
            return urls;
        } catch (Exception e) {
            throw new GitClientException("获取远程 URL 失败: " + e.getMessage(), e);
        }
    }
}
