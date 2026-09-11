package com.chua.git.support;

import com.chua.git.support.exception.GitClientException;
import com.chua.git.support.listener.GitFileEvent;
import com.chua.git.support.listener.GitFileListener;
import com.chua.git.support.listener.GitProgressListener;
import com.chua.git.support.operation.BranchOperation;
import com.chua.git.support.operation.CloneOperation;
import com.chua.git.support.operation.CommitOperation;
import com.chua.git.support.operation.DeployOperation;
import com.chua.git.support.operation.FetchOperation;
import com.chua.git.support.operation.LogOperation;
import com.chua.git.support.operation.PushOperation;
import com.chua.git.support.operation.StatusOperation;
import com.chua.git.support.operation.TagOperation;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Git 客户端，基于 Eclipse JGit 7.x 提供链式操作 API。
 *
 * <p>该类是唯一对外入口，内部封装了 JGit 的 {@link Git} 和 {@link Repository} 对象，
 * 通过"操作子类模式"将 clone、pull、push、branch、log、status、commit、tag、deploy 等
 * 具体动作委托给独立的 Operation 类。</p>
 *
 * <h3>设计原则</h3>
 * <ol>
 *   <li><b>懒加载</b>：{@link #open()} 在首次调用时触发，避免构造阶段即打开仓库</li>
 *   <li><b>线程安全</b>：{@link #open()} 使用双检锁确保 Java 其他线程可见性</li>
 *   <li><b>操作子模式</b>：每个 Operation 持有 {@code this} 引用，链式构建参数后一次性执行</li>
 *   <li><b>AutoCloseable</b>：支持 try-with-resources 自动关闭资源</li>
 * </ol>
 *
 * <h3>快速入门</h3>
 * <pre>{@code
 * // 1. 使用 builder 创建
 * GitClient client = GitClient.builder()
 *         .remoteUrl("https://gitee.com/user/repo.git")
 *         .localPath(Paths.get("/tmp/repo"))
 *         .credentials("username", "password")
 *         .build();
 *
 * // 2. 打开仓库（首次使用时自动调用）
 * client.open();
 *
 * // 3. 拉取
 * PullResult pullResult = (PullResult) client.pull().execute();
 *
 * // 4. 推送
 * PushResult pushResult = (PushResult) client.push().execute();
 *
 * // 5. 分支信息
 * List<BranchInfo> branches = client.branch().listAll();
 *
 * // 6. 提交日志
 * List<LogEntry> log = client.log().list(10);
 *
 * // 7. 工作区状态
 * StatusResult status = client.status().execute();
 *
 * // 8. 提交
 * client.commit().addAll().commit("feat: 新功能");
 *
 * // 9. 标签
 * List<TagInfo> tags = client.tag().list();
 *
 * // 10. 关闭
 * client.close();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class GitClient implements AutoCloseable {

    // ==================== 字段 ====================

    /**
     * 远程仓库 HTTP(S) 地址，clone 和 push 时使用。
     */
    private final String remoteUrl;

    /**
     * 本地 Git 仓库所在目录路径。
     */
    private final java.nio.file.Path localPath;

    /**
     * HTTPS 认证用户名。
     */
    private final String username;

    /**
     * HTTPS 认证密码或 Personal Access Token。
     */
    private final String password;

    /**
     * SSH 私钥文件绝对路径。
     */
    private final String sshPrivateKeyPath;

    /**
     * SSH 私钥加密短语，可选。
     */
    private final String sshPassphrase;

    /**
     * 缓存的 JGit Git 实例，{@code volatile} 配合 DCL 使用。
     */
    private volatile Git git;

    /**
     * 可重入锁，保证 {@link #open()} 方法的高并发安全。
     */
    private final ReentrantLock lock = new ReentrantLock();

    // ==================== 构造与工厂方法 ====================

    /**
     * 私有构造，通过 Builder 构建。
     */
    private GitClient(Builder builder) {
        this.remoteUrl = builder.remoteUrl;
        this.localPath = builder.localPath;
        this.username = builder.username;
        this.password = builder.password;
        this.sshPrivateKeyPath = builder.sshPrivateKeyPath;
        this.sshPassphrase = builder.sshPassphrase;
    }

    /**
     * 创建 Builder，支持链式配置各项参数。
     *
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 快速构造：仅指定本地路径，不关联远程仓库。
     *
     * @param localPath 本地仓库路径
     * @return 新的 GitClient 实例
     */
    public static GitClient ofLocal(java.nio.file.Path localPath) {
        return builder().localPath(localPath).build();
    }

    // ==================== 仓库生命周期 ====================

    /**
     * 打开本地 Git 仓库。
     *
     * <p>调用时先检查缓存字段 {@code git} 是否已初始化；若未初始化则通过
     * 双重检查锁 (DCL) 确保只打开一次。如果目录下不存在 {@code .git} 目录，
     * 抛出 {@link GitClientException}。</p>
     *
     * @return 当前 GitClient（链式）
     * @throws GitClientException 如果目录不是有效的 Git 仓库
     */
    public GitClient open() {
        if (git != null) {
            return this;
        }
        lock.lock();
        try {
            if (git != null) {
                return this;
            }
            java.io.File gitDir = new java.io.File(localPath.toFile(), ".git");
            if (gitDir.exists() && gitDir.isDirectory()) {
                git = Git.open(localPath.toFile());
                log.info("打开本地 Git 仓库: {}", localPath);
            } else {
                throw new GitClientException("目录不是 Git 仓库，请先 clone: " + localPath);
            }
            return this;
        } catch (IOException e) {
            throw new GitClientException("打开 Git 仓库失败: " + localPath, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        if (git != null) {
            git.close();
            git = null;
        }
    }

    // ==================== 公开方法 ====================

    /**
     * 返回缓存的 JGit Git 实例（如未打开则先调用 open）。
     *
     * @return JGit Git 对象
     */
    public Git getGit() {
        if (git == null) {
            open();
        }
        return git;
    }

    /**
     * 返回 JGit Repository 对象。
     *
     * @return Repository 实例
     */
    public Repository getRepository() {
        return getGit().getRepository();
    }

    /**
     * 构造 HTTPS 凭据提供者。
     *
     * @return {@link UsernamePasswordCredentialsProvider}，若未设置凭据则返回 null
     */
    public org.eclipse.jgit.transport.CredentialsProvider getCredentialsProvider() {
        if (username != null && password != null) {
            return new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(username, password);
        }
        return null;
    }

    /**
     * 获取远程仓库 URL。
     *
     * @return 远程 URL（可能为 nullptr）
     */
    public String getRemoteUrl() {
        return remoteUrl;
    }

    /**
     * 获取本地仓库路径。
     *
     * @return 本地路径
     */
    public java.nio.file.Path getLocalPath() {
        return localPath;
    }

    // ==================== 操作入口 ====================

    /**
     * 准备拉取操作（git pull）。
     *
     * <pre>{@code
     * PullResult result = (PullResult) client.pull().execute();
     * CompletableFuture<PullResult> future = (CompletableFuture<PullResult>) client.pull().async().execute();
     * }</pre>
     *
     * @return FetchOperation 链式构建器
     */
    public FetchOperation pull() {
        return new FetchOperation(this);
    }

    /**
     * 准备分支查询操作。
     *
     * <pre>{@code
     * List<BranchInfo> all = client.branch().listAll();
     * BranchInfo cur = client.branch().current();
     * }</pre>
     *
     * @return BranchOperation 链式构建器
     */
    public BranchOperation branch() {
        return new BranchOperation(this);
    }

    /**
     * 准备克隆操作（git clone）。
     *
     * <pre>{@code
     * GitClient cloned = (GitClient) client.cloneOp().execute();
     * }</pre>
     *
     * @return CloneOperation 链式构建器
     */
    public CloneOperation cloneOp() {
        return new CloneOperation(this);
    }

    /**
     * 准备推送操作（git push）。
     *
     * <pre>{@code
     * PushResult result = (PushResult) client.push().execute();
     * }</pre>
     *
     * @return PushOperation 链式构建器
     */
    public PushOperation push() {
        return new PushOperation(this);
    }

    /**
     * 准备部署流水线操作（git pull → compile → deploy）。
     *
     * <pre>{@code
     * // 自动 pull + Maven 编译（需要 classpath 中有 maven-starter 实现）
     * DeployResult result = (DeployResult) client.deploy()
     *         .goals("clean", "compile", "package")
     *         .execute();
     *
     * // 异步
     * CompletableFuture<DeployResult> f = (CompletableFuture<DeployResult>) client.deploy().async().execute();
     * }</pre>
     *
     * @return DeployOperation 链式构建器
     */
    public DeployOperation deploy() {
        return new DeployOperation(this);
    }

    /**
     * 准备提交日志查询操作（git log）。
     *
     * <pre>{@code
     * List<LogEntry> log = client.log().list(10);
     * }</pre>
     *
     * @return LogOperation 链式构建器
     */
    public LogOperation log() {
        return new LogOperation(this);
    }

    /**
     * 准备工作区状态查询操作（git status）。
     *
     * <pre>{@code
     * StatusResult status = client.status().execute();
     * boolean clean = status.isClean();
     * }</pre>
     *
     * @return StatusOperation 链式构建器
     */
    public StatusOperation status() {
        return new StatusOperation(this);
    }

    /**
     * 准备暂存与提交操作（git add / git commit）。
     *
     * <pre>{@code
     * client.commit().addAll().commit("feat: 新增功能");
     * client.commit().add("src/").commit("fix: 修复 bug");
     * }</pre>
     *
     * @return CommitOperation 链式构建器
     */
    public CommitOperation commit() {
        return new CommitOperation(this);
    }

    /**
     * 准备标签操作（git tag）。
     *
     * <pre>{@code
     * List<TagInfo> tags = client.tag().list();
     * client.tag().create("v1.0", "发布 1.0");
     * client.tag().delete("v0.9");
     * }</pre>
     *
     * @return TagOperation 链式构建器
     */
    public TagOperation tag() {
        return new TagOperation(this);
    }

    // ==================== 核心操作 ====================

    /**
     * 无进度监听拉取。
     */
    public com.chua.git.support.model.PullResult pull(GitFileListener listener) {
        return pull(listener, null);
    }

    /**
     * 拉取，内置生成前后树 diff 和文件变更通知。
     *
     * @param listener         文件变更监听器，用于 diff 回调；为 null 则跳过 diff
     * @param progressListener 进度监听器，透传至 JGit ProgressMonitor；可为 null
     * @return PullResult 包含更新状态、引用数、消息
     */
    public com.chua.git.support.model.PullResult pull(GitFileListener listener, GitProgressListener progressListener) {
        try {
            Repository repo = getRepository();
            // 记录拉取前的 HEAD 树对象 ID
            ObjectId beforeHead = repo.resolve("HEAD^{tree}");
            PullCommand pullCmd = getGit().pull();
            if (progressListener != null) {
                pullCmd.setProgressMonitor(new com.chua.git.support.operation.ProgressMonitorAdapter(progressListener));
            }
            org.eclipse.jgit.api.PullResult jgitResult = pullCmd.call();
            // 拉取后 HEAD 树
            ObjectId afterHead = repo.resolve("HEAD^{tree}");

            // 告警变更文件
            if (listener != null && beforeHead != null && afterHead != null && !beforeHead.equals(afterHead)) {
                diffAndNotify(repo, beforeHead, afterHead, listener);
            }

            boolean hasUpdate = beforeHead == null || !beforeHead.equals(afterHead);
            log.info("Git pull 完成: {}, 有更新={}", localPath, hasUpdate);
            return new com.chua.git.support.model.PullResult(hasUpdate, 0, hasUpdate ? "Updated." : "Already up to date.");
        } catch (Exception e) {
            throw new GitClientException("Pull 失败: " + e.getMessage(), e);
        } finally {
            if (progressListener != null) {
                progressListener.onEnd();
            }
        }
    }

    /**
     * 对比前后树，将每个差异文件封装为 {@link GitFileEvent} 通知监听器。
     */
    private void diffAndNotify(Repository repo, ObjectId before, ObjectId after, GitFileListener listener) {
        try (ObjectReader reader = repo.newObjectReader(); RevWalk revWalk = new RevWalk(repo)) {
            AbstractTreeIterator oldTree = new CanonicalTreeParser(null, reader, before);
            AbstractTreeIterator newTree = new CanonicalTreeParser(null, reader, after);
            List<DiffEntry> diffs = Git.wrap(repo).diff()
                    .setOldTree(oldTree)
                    .setNewTree(newTree)
                    .call();
            for (DiffEntry entry : diffs) {
                listener.onChanged(new GitFileEvent(mapChangeType(entry.getChangeType()), entry.getNewPath()));
            }
        } catch (Exception e) {
            log.warn("Diff 计算失败", e);
        }
    }

    /**
     * 将 JGit 的 DiffEntry.ChangeType 映射到外部枚举。
     */
    private GitFileEvent.ChangeType mapChangeType(DiffEntry.ChangeType ct) {
        switch (ct) {
            case ADD:      return GitFileEvent.ChangeType.ADD;
            case MODIFY:
            case RENAME:
            case COPY:     return GitFileEvent.ChangeType.MODIFY;
            case DELETE:   return GitFileEvent.ChangeType.DELETE;
            default:       return GitFileEvent.ChangeType.MODIFY;
        }
    }

    // ==================== Builder ====================

    /**
     * Git 客户端链式组装器。
     *
     * 所有 setter 方法都返回 this 自身，最终调用 build 完成校验并实例化。
     *
     * @since 4.0.0.42
     */
    public static class Builder {

        /** 远端 URL */
        private String remoteUrl;
        /** 本地路径 */
        private java.nio.file.Path localPath;
        /** 用户名 */
        private String username;
        /** 密码/token */
        private String password;
        /** SSH 私钥路径 */
        private String sshPrivateKeyPath;
        /** SSH 私钥密码 */
        private String sshPassphrase;

        /** RemoteUrl */
        public Builder remoteUrl(String remoteUrl) {
            this.remoteUrl = remoteUrl;
            return this;
        }
        /** LocalPath */
        public Builder localPath(java.nio.file.Path localPath) {
            this.localPath = localPath;
            return this;
        }

        /**
         * HTTPS 凭据。
         *
         * @param username 用户名
         * @param password 密码或 Access Token
         */
        public Builder credentials(String username, String password) {
            this.username = username;
            this.password = password;
            return this;
        }

        /**
         * SSH 凭据。
         *
         * @param privateKeyPath 私钥文件绝对路径
         * @param passphrase     私钥密码（无密码可传空串）
         */
        public Builder sshKey(String privateKeyPath, String passphrase) {
            this.sshPrivateKeyPath = privateKeyPath;
            this.sshPassphrase = passphrase;
            return this;
        }

        /**
         * 检查必要参数后构造 GitClient。
         */
        public GitClient build() {
            if (localPath == null) {
                throw new IllegalArgumentException("localPath 不能为空");
            }
            return new GitClient(this);
        }
    }
}
