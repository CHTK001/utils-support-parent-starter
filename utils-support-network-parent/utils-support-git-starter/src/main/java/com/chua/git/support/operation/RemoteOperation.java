package com.chua.git.support.operation;

import com.chua.git.support.GitClient;
import com.chua.git.support.exception.GitClientException;
import com.chua.git.support.listener.GitProgressListener;
import com.chua.git.support.model.FetchResult;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;

/**
 * 远端同步检查操作（fetch + 本地/远端提交比对）。
 *
 * <p>与 {@link FetchOperation} 的区别：那个是「pull」（fetch + merge，会改工作区），
 * 本类只做 fetch 并报告差异，用于「有没有新提交」的轮询检查 ——
 * 轮询不该动用户的工作区，也不该因为本地有未提交改动而失败。</p>
 *
 * <pre>典型用法：
 * {@code
 * FetchResult r = client.remote().fetch("main");
 * if (r.updated()) { 触发发布 }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class RemoteOperation {

    /**
     * 所属 git 客户端
     */
    private final GitClient client;

    /**
     * 进度监听器（可选）
     */
    private GitProgressListener progressListener;

    /**
     * 构建操作实例（仅框架内部调用）。
     *
     * @param client 所属 Git 客户端
     */
    public RemoteOperation(GitClient client) {
        this.client = client;
    }

    /**
     * 设置进度监听器（fetch 需要下载对象时才会回调）。
     *
     * @param listener 进度监听器
     * @return 当前操作实例
     */
    public RemoteOperation progressListener(GitProgressListener listener) {
        this.progressListener = listener;
        return this;
    }

    /**
     * fetch 指定分支并与本地 HEAD 比较。
     *
     * @param branch 分支名（如 main）；空则用当前分支
     * @return 比对结果
     */
    public FetchResult fetch(String branch) {
        try {
            client.open();
            String target = branch == null || branch.isBlank()
                    ? currentBranch()
                    : branch.trim();
            var fetch = client.getGit().fetch()
                    .setRemote("origin")
                    // refspec 的源必须是完整引用名：写 "main" 会被当成「远端没有该引用」而报
                    // "Remote does not have main available for fetch"
                    .setRefSpecs(new RefSpec("+refs/heads/" + target + ":refs/remotes/origin/" + target));
            // 只要分支指针，不要 tags：轮询场景下全量 tag 会让检查变慢
            fetch.setTagOpt(TagOpt.NO_TAGS);
            if (progressListener != null) {
                fetch.setProgressMonitor(new ProgressMonitorAdapter(progressListener));
            }
            fetch.call();

            Repository repo = client.getRepository();
            ObjectId local = repo.resolve("HEAD");
            ObjectId remote = repo.resolve("refs/remotes/origin/" + target);
            if (remote == null) {
                log.info("Git fetch 完成但远端无分支: {} @ {}", target, client.getLocalPath());
                return FetchResult.missing(target);
            }
            boolean updated = local == null || !local.equals(remote);
            String message = updated ? "远端有新提交" : "已是最新";
            log.info("Git fetch 完成: {} @ {}, updated={}", target, client.getLocalPath(), updated);
            return new FetchResult(target,
                    local == null ? null : local.getName(),
                    remote.getName(), updated, message);
        } catch (GitClientException e) {
            throw e;
        } catch (Exception e) {
            throw new GitClientException("Git fetch 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 当前 HEAD 指向的分支名（detached 时返回提交短码）。
     *
     * @return 分支名
     */
    public String currentBranch() {
        try {
            client.open();
            return client.getRepository().getBranch();
        } catch (Exception e) {
            throw new GitClientException("获取当前分支失败: " + e.getMessage(), e);
        }
    }
}
