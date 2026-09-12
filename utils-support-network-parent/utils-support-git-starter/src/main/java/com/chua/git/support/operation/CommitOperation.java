package com.chua.git.support.operation;

import com.chua.git.support.GitClient;
import com.chua.git.support.exception.GitClientException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;

import java.util.concurrent.CompletableFuture;

/**
* 暂存与提交操作（Git 添加 / Git commit）。
*
* <p>支持：</p>
* <ul>
*   <li>{@link #add(String...)} — 暂存指定路径</li>
*   <li>{@link #addAll()} — 暂存所有变更（等同于 git add -A）</li>
*   <li>{@link #remove(String...)} — 从暂存区移除指定路径</li>
*   <li>{@link #commit(String)} — 提交暂存区内容</li>
*   <li>{@link #author(String, String)} — 指定作者</li>
*   <li>{@link #amend()} — 修改最后一次提交</li>
*   <li>{@link #full(String)} — 暂存所有变更并提交（add -A + commit）</li>
*   <li>{@link #async()} — 异步执行</li>
* </ul>
*
* <pre>示例：
* {@code
* // 1. 暂存并提交
* client.commit().addAll().commit("feat: 新增用户登录");
*
* // 2. 指定路径暂存
* client.commit().add("src/main/java").commit("fix: 修复 NPE");
*
* // 3. 异步全量提交
* CompletableFuture<String> f = (CompletableFuture<String>) client.commit()
*         .async().full("chore: 更新依赖版本");
* }</pre>ore: 更新依赖版本");
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class CommitOperation {

    /**
    * 所属 git客户端。
     */
    private final GitClient client;

    /**
    * 异步标记。
     */
    private boolean asyncMode;

    /**
    * 提交消息。
     */
    private String message;

    /**
    * 自定义作者名称。
     */
    private String authorName;

    /**
    * 自定义作者邮箱。
     */
    private String authorEmail;

    /**
    * 是否 amend 模式。
     */
    private boolean amend;

    /**
    * 是否已暂存过文件。
     */
    private boolean staged;

    /**
    * 构建操作实例（仅框架内部调用）。
    *
    * @param client 所属 Git客户端
     */
    public CommitOperation(GitClient client) {
        this.client = client;
    }

    // ==================== 链式配置方法 ====================

    /**
    * 标记为异步模式。
    *
    * @return 当前操作实例
     */
    public CommitOperation async() {
        this.asyncMode = true;
        return this;
    }

    /**
    * 暂存指定路径。
    *
    * @param paths 文件路径（相对于仓库根目录）
    * @return 当前操作实例
     */
    public CommitOperation add(String... paths) {
        try {
            client.open();
            AddCommand cmd = client.getGit().add();
            for (String path : paths) {
                cmd.addFilepattern(path);
            }
            cmd.call();
            this.staged = true;
            log.info("Git add: {}", String.join(", ", paths));
        } catch (Exception e) {
            throw new GitClientException("Git add 失败: " + e.getMessage(), e);
        }
        return this;
    }

    /**
    * 暂存所有变更（等同于 Git 添加 -A）。
    *
    * @return 当前操作实例
     */
    public CommitOperation addAll() {
        try {
            client.open();
            client.getGit().add().addFilepattern(".").call();
            this.staged = true;
            log.info("Git add -A: .");
        } catch (Exception e) {
            throw new GitClientException("Git add -A 失败: " + e.getMessage(), e);
        }
        return this;
    }

    /**
    * 从暂存区移除指定路径（Git rm --缓存）。
    *
    * @param paths 文件路径
    * @return 当前操作实例
     */
    public CommitOperation remove(String... paths) {
        try {
            client.open();
            RmCommand cmd = client.getGit().rm();
            for (String path : paths) {
                cmd.addFilepattern(path);
            }
            cmd.setCached(true);
            cmd.call();
            this.staged = true;
            log.info("Git rm --cached: {}", String.join(", ", paths));
        } catch (Exception e) {
            throw new GitClientException("Git rm 失败: " + e.getMessage(), e);
        }
        return this;
    }

    /**
    * 设置提交消息。
    *
    * @param msg 提交消息
    * @return 当前操作实例
     */
    public CommitOperation message(String msg) {
        this.message = msg;
        return this;
    }

    /**
    * 设置自定义作者。
    *
    * @param name  作者名称
    * @param email 作者邮箱
    * @return 当前操作实例
     */
    public CommitOperation author(String name, String email) {
        this.authorName = name;
        this.authorEmail = email;
        return this;
    }

    /**
    * 标记为 amend 模式（修改上一次提交）。
    *
    * @return 当前操作实例
     */
    public CommitOperation amend() {
        this.amend = true;
        return this;
    }

    // ==================== 执行方法 ====================

    /**
    * 执行提交。
    *
    * <p>提交前需先调用 {@link #add(String...)} 或 {@link #addAll()} 暂存文件，
    * 否则提交空提交（amend 模式除外）。</p>
    *
    * @param msg 提交消息
    * @return 提交 SHA，异步模式返回 {@link CompletableFuture<String>}
     */
    @SuppressWarnings("unchecked")
    public Object commit(String msg) {
        this.message = msg;
        if (asyncMode) {
            return CompletableFuture.supplyAsync(this::doCommit);
        }
        return doCommit();
    }

    /**
    * 暂存所有变更并立即提交（组合操作）。
    *
    * @param msg 提交消息
    * @return 提交 SHA，异步模式返回 {@link CompletableFuture<String>}
     */
    @SuppressWarnings("unchecked")
    public Object full(String msg) {
        if (asyncMode) {
            return CompletableFuture.supplyAsync(() -> {
                addAll();
                this.message = msg;
                return doCommit();
            });
        }
        addAll();
        this.message = msg;
        return doCommit();
    }

    // ==================== 内部方法 ====================

    /**
    * 执行commit。
    * @return 执行commit的结果
     */
    private String doCommit() {
        try {
            client.open();
            CommitCommand cmd = client.getGit().commit();
            if (message != null) {
                cmd.setMessage(message);
            }
            if (authorName != null && authorEmail != null) {
                cmd.setAuthor(authorName, authorEmail);
            }
            if (amend) {
                cmd.setAmend(true);
            }
            if (!staged && !amend) {
                cmd.setAllowEmpty(false);
            }

            RevCommit result = cmd.call();
            String sha = result.name();
            log.info("Git commit 完成: {}, SHA={}", client.getLocalPath(), sha.substring(0, Math.min(7, sha.length())));
            return sha;
        } catch (Exception e) {
            throw new GitClientException("Git commit 失败: " + e.getMessage(), e);
        }
    }
}
