package com.chua.git.support.operation;

import com.chua.git.support.GitClient;
import com.chua.git.support.exception.GitClientException;
import com.chua.git.support.listener.GitProgressListener;
import com.chua.git.support.model.PushResult;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.transport.RefSpec;

import java.util.concurrent.CompletableFuture;

/**
 * 推送操作（git push）。
 *
 * <p>将本地提交推送到远程仓库。支持：</p>
 * <ul>
 *   <li>{@link #remote(String)} — 指定远端（默认 "origin"）</li>
 *   <li>{@link #branch(String)} — 指定分支</li>
 *   <li>{@link #force()} — 强制推送（+refspec）</li>
 *   <li>{@link #tags()} — 推送标签</li>
 *   <li>{@link #async()} — 异步执行</li>
 *   <li>{@link #progressListener(GitProgressListener)} — 进度回调</li>
 *   <li>{@link #prompt(String)} — 提交备注，记录在日志中</li>
 * </ul>
 *
 * <pre>示例：
 * {@code
 * // 1. 默认推送
 * PushResult r = client.push().execute();
 *
 * // 2. 异步强推
 * CompletableFuture<PushResult> f = (CompletableFuture<PushResult>) client.push()
 *         .async().force().execute();
 *
 * // 3. 指定远程、分支、带进度
 * PushResult r = client.push()
 *         .progressListener(myListener)
 *         .remote("origin")
 *         .branch("main")
 *         .prompt("修复登录密码重置问题")
 *         .execute();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class PushOperation {

    /**
     * 所属 GitClient。
     */
    private final GitClient client;

    /**
     * 异步标记。
     */
    private boolean asyncMode;

    /**
     * 远端名称，默认 origin。
     */
    private String remote = "origin";

    /**
     * 待推送的分支名称，null 表示使用默认推送策略。
     */
    private String branch;

    /**
     * 是否强制推送。
     */
    private boolean force;

    /**
     * 是否推送标签。
     */
    private boolean pushTags;

    /**
     * 进度监听器。
     */
    private GitProgressListener progressListener;

    /**
     * 构建操作实例（仅框架内部调用）。
     *
     * @param client 所属 GitClient
     */
    public PushOperation(GitClient client) {
        this.client = client;
    }

    // ==================== 链式配置方法 ====================

    /**
     * 标记为异步模式。
     *
     * @return 当前操作实例
     */
    public PushOperation async() {
        this.asyncMode = true;
        return this;
    }

    /**
     * 设置推送远端名称。
     *
     * @param remote 远端名称，默认 "origin"
     * @return 当前操作实例
     */
    public PushOperation remote(String remote) {
        this.remote = remote;
        return this;
    }

    /**
     * 指定推送分支。
     *
     * @param branch 分支名称，如 "main"、"develop"
     * @return 当前操作实例
     */
    public PushOperation branch(String branch) {
        this.branch = branch;
        return this;
    }

    /**
     * 设置强制推送（等同于 --force）。
     *
     * <p>内部使用 RefSpec 前缀 '+' 实现。注意：这会覆盖远程分支历史，慎用。</p>
     *
     * @return 当前操作实例
     */
    public PushOperation force() {
        this.force = true;
        return this;
    }

    /**
     * 推送标签（git push --tags）。
     *
     * @return 当前操作实例
     */
    public PushOperation tags() {
        this.pushTags = true;
        return this;
    }

    /**
     * 设置提交备注 (prompt)，记录在操作日志中。
     *
     * <p>JGit 原生 push 本身不传输 commit message，此 prompt 仅作为
     * 业务语义补充，便于日志追踪。</p>
     *
     * @param prompt 备注内容
     * @return 当前操作实例
     */
    public PushOperation prompt(String prompt) {
        log.info("Git push prompt: {}", prompt);
        return this;
    }

    /**
     * 设置进度监听器。
     *
     * @param listener 进度监听器
     * @return 当前操作实例
     */
    public PushOperation progressListener(GitProgressListener listener) {
        this.progressListener = listener;
        return this;
    }

    // ==================== 执行方法 ====================

    /**
     * 执行推送。
     *
     * @return 同步模式返回 {@link PushResult}，异步模式返回 {@link CompletableFuture<PushResult>}
     */
    @SuppressWarnings("unchecked")
    public Object execute() {
        if (asyncMode) {
            return CompletableFuture.supplyAsync(this::doPush);
        }
        return doPush();
    }

    /**
     * 执行实际推送逻辑。
     *
     * <p>构造 JGit PushCommand，设远端、refspec、进度监视器，然后执行 push。
     * 最后统计所有返回的 RemoteRefUpdate 数量，形成 PushResult。</p>
     *
     * @return 推送结果
     */
    private PushResult doPush() {
        try {
            // 确保仓库已打开
            client.open();

            // 构建 JGit PushCommand
            PushCommand push = client.getGit().push();
            push.setRemote(remote);
            push.setForce(force);

            if (pushTags) {
                push.setPushTags();
            }

            // 进度监视器
            if (progressListener != null) {
                push.setProgressMonitor(new ProgressMonitorAdapter(progressListener));
            }

            // 指定分支推送（构造 refspec）
            if (branch != null) {
                String refSpec = "refs/heads/" + branch + ":refs/heads/" + branch;
                if (force) {
                    refSpec = "+" + refSpec;
                }
                push.setRefSpecs(new RefSpec(refSpec));
            }

            // 执行推送
            Iterable<org.eclipse.jgit.transport.PushResult> results = push.call();

            // 统计远程更新数量
            int totalUpdates = 0;
            for (org.eclipse.jgit.transport.PushResult jgitResult : results) {
                if (jgitResult.getRemoteUpdates() != null) {
                    totalUpdates += jgitResult.getRemoteUpdates().size();
                }
            }

            String msg = totalUpdates > 0 ? "Pushed " + totalUpdates + " ref(s)." : "Everything up-to-date.";
            log.info("Git push 完成: {}, 更新数量={}", client.getLocalPath(), totalUpdates);
            return new PushResult(true, totalUpdates, msg);
        } catch (Exception e) {
            throw new GitClientException("Push 失败: " + e.getMessage(), e);
        } finally {
            if (progressListener != null) {
                progressListener.onEnd();
            }
        }
    }
}