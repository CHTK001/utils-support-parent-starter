package com.chua.git.support.operation;

import com.chua.git.support.GitClient;
import com.chua.git.support.exception.GitClientException;
import com.chua.git.support.listener.GitProgressListener;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialsProvider;

import java.util.concurrent.CompletableFuture;

/**
 * 克隆操作（Git clone），将远程仓库克隆到本地磁盘。
 *
 * <p>克隆前必须通过 {@link GitClient.Builder} 设置 remoteUrl 和 localPath。
 * 操作完成后返回一个新的 {@link GitClient} 实例，指向刚克隆下来的本地仓库。</p>
 *
 * <pre>典型用法：
 * {@code
 * GitClient src = GitClient.builder()
 *         .remoteUrl("https://github.com/user/repo.git")
 *         .localPath(Paths.get("/tmp/repo"))
 *         .build();
 *
 * // 1. 同步，带进度
 * GitClient cloned = (GitClient) src.cloneOp()
 *         .progressListener(myListener)
 *         .execute();
 *
 * // 2. 异步
 * CompletableFuture<GitClient> f = (CompletableFuture<GitClient>) src.cloneOp()
 *         .async()
 *         .execute();
 * }</pre>t> f = (CompletableFuture<GitClient>) src.cloneOp()
 *         .async()
 *         .execute();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class CloneOperation {

    /**
     * 源 git客户端（携带 远程url、本地路径、凭证）。
     */
    private final GitClient client;

    /**
     * 异步标记：为 true 时 {@link #execute()} 返回 completable期货。
     */
    private boolean asyncMode;

    /**
     * 进度监听器，非空时会附加到 clone 命令的 进步监控。
     */
    private GitProgressListener progressListener;

    /**
     * 构建操作实例（仅框架内部调用）。
     *
     * @param client 源 Git客户端
     */
    public CloneOperation(GitClient client) {
        this.client = client;
    }

    // ==================== 链式配置方法 ====================

    /**
     * 标记为异步模式。
     *
     * @return 当前操作实例
     */
    public CloneOperation async() {
        this.asyncMode = true;
        return this;
    }

    /**
     * 设置进度监听器。
     *
     * @param listener 进度监听器
     * @return 当前操作实例
     */
    public CloneOperation progressListener(GitProgressListener listener) {
        this.progressListener = listener;
        return this;
    }

    // ==================== 执行方法 ====================

    /**
     * 执行 clone 操作。
     *
     * @return 同步模式返回 {@link GitClient}，异步模式返回 {@link CompletableFuture}{@code <GitClient>}
     */
    @SuppressWarnings("unchecked")
    public Object execute() {
        if (asyncMode) {
            return CompletableFuture.supplyAsync(this::doClone);
        }
        return doClone();
    }

    /**
     * 执行实际克隆逻辑。
     *
     * <p>先校验 remoteUrl 不为空，构造 JGit CloneCommand、设置进度监视器，
     * 调用 {@link CloneCommand#call()} 下载仓库。完成后关闭临时 Git 对象，
     * 返回新的 git客户端 实例（因为克隆后本地是不同仓库）。</p>
     *
     * @return 新克隆的 Git 仓库客户端
     */
    private GitClient doClone() {
        String remoteUrl = client.getRemoteUrl();
        if (remoteUrl == null || remoteUrl.isEmpty()) {
            throw new GitClientException("remoteUrl 不能为空");
        }
        java.nio.file.Path localPath = client.getLocalPath();
        try {
            CloneCommand clone = Git.cloneRepository()
                    .setURI(remoteUrl)
                    .setDirectory(localPath.toFile());
            CredentialsProvider credentialsProvider = client.getCredentialsProvider();
            if (credentialsProvider != null) {
                clone.setCredentialsProvider(credentialsProvider);
            }
            // 进度监视器
            if (progressListener != null) {
                clone.setProgressMonitor(new ProgressMonitorAdapter(progressListener));
            }
            Git git = clone.call();
            log.info("Git 克隆完成: {} -> {}", remoteUrl, localPath);
            git.close();

            if (progressListener != null) {
                progressListener.onEnd();
            }

 // 新仓库使用新的 Git客户端 实例
            return GitClient.ofLocal(localPath);
        } catch (Exception e) {
            throw new GitClientException("Clone 失败: " + e.getMessage(), e);
        }
    }
}
