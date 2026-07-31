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
 * 分支信息查询操作。
 *
 * <p>对已打开的本地仓库，提供以下只读查询：</p>
 * <ul>
 *   <li>{@link #listAll()} — 所有分支（本地 + 远程）</li>
 *   <li>{@link #listLocal()} — 仅本地分支</li>
 *   <li>{@link #listRemote()} — 仅远程分支</li>
 *   <li>{@link #current()} — 当前 HEAD 指向的分支</li>
 *   <li>{@link #remoteUrls()} — 已配置的远端 URL</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class BranchOperation {

    /**
     * 所属 GitClient。
     */
    private final GitClient client;

    /**
     * 构建操作实例（仅框架内部调用）。
     *
     * @param client 所属 GitClient
     */
    public BranchOperation(GitClient client) {
        this.client = client;
    }

    /**
     * 列出所有分支（本地 + 远程）。
     *
     * <p>内部调用 {@code git branchList().setListMode(ALL)}。</p>
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
     * <p>过滤掉 {@link BranchInfo#isRemote()} 为 true 的结果。</p>
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