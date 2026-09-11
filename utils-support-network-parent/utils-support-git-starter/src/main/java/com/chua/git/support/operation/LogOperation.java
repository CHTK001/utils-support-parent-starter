package com.chua.git.support.operation;

import com.chua.git.support.GitClient;
import com.chua.git.support.exception.GitClientException;
import com.chua.git.support.model.LogEntry;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import java.util.ArrayList;
import java.util.List;

/**
 * 提交日志查询操作（git log）。
 *
 * <p>对已打开的本地仓库，提供以下查询：</p>
 * <ul>
 *   <li>{@link #list()} — 全部提交</li>
 *   <li>{@link #list(int)} — 最近 N 条</li>
 *   <li>{@link #listBetween(String, String)} — 两个引用之间的日志</li>
 * </ul>
 *
 * <pre>示例：
 * {@code
 * // 最近 10 条日志
 * List<LogEntry> log = client.log().list(10);
 *
 * // 全部日志
 * List<LogEntry> all = client.log().list();
 *
 * // 两个版本之间的日志
 * List<LogEntry> between = client.log().listBetween("v1.0", "v0.9");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class LogOperation {

    /**
     * 所属 GitClient。
     */
    private final GitClient client;

    /**
     * 数量限制。
     */
    private int maxCount = Integer.MAX_VALUE;

    /**
     * 构建操作实例（仅框架内部调用）。
     *
     * @param client 所属 GitClient
     */
    public LogOperation(GitClient client) {
        this.client = client;
    }

    /**
     * 限制返回条数。
     *
     * @param count 最大返回条数
     * @return 当前操作实例
     */
    public LogOperation limit(int count) {
        this.maxCount = count;
        return this;
    }

    // ==================== 查询方法 ====================

    /**
     * 查询全部提交日志。
     *
     * @return 提交日志列表（从新到旧）
     */
    public List<LogEntry> list() {
        return doList(null, null);
    }

    /**
     * 查询最近 N 条提交日志。
     *
     * @param count 最大返回条数
     * @return 提交日志列表（从新到旧）
     */
    public List<LogEntry> list(int count) {
        this.maxCount = count;
        return doList(null, null);
    }

    /**
     * 查询两个引用之间的提交日志（不含 toRef）。
     *
     * @param fromRef 起点引用（较新，如 "v1.0"、"HEAD"）
     * @param toRef   终点引用（较旧，如 "v0.9"）
     * @return 提交日志列表
     */
    public List<LogEntry> listBetween(String fromRef, String toRef) {
        return doList(fromRef, toRef);
    }

    // ==================== 内部方法 ====================

    private List<LogEntry> doList(String fromRef, String toRef) {
        try {
            client.open();
            Repository repo = client.getRepository();
            List<LogEntry> result = new ArrayList<>();

            try (org.eclipse.jgit.revwalk.RevWalk revWalk = new org.eclipse.jgit.revwalk.RevWalk(repo)) {
                if (fromRef != null && toRef != null) {
                    ObjectId fromOid = resolveRef(repo, fromRef);
                    ObjectId toOid = resolveRef(repo, toRef);
                    org.eclipse.jgit.revwalk.RevCommit from = revWalk.parseCommit(fromOid);
                    org.eclipse.jgit.revwalk.RevCommit to = revWalk.parseCommit(toOid);
                    revWalk.markStart(from);
                    revWalk.markUninteresting(to);
                } else if (fromRef != null) {
                    ObjectId fromOid = resolveRef(repo, fromRef);
                    org.eclipse.jgit.revwalk.RevCommit from = revWalk.parseCommit(fromOid);
                    revWalk.markStart(from);
                } else {
                    ObjectId headOid = repo.resolve("HEAD");
                    org.eclipse.jgit.revwalk.RevCommit head = revWalk.parseCommit(headOid);
                    revWalk.markStart(head);
                }

                int limit = maxCount;
                for (org.eclipse.jgit.revwalk.RevCommit commit : revWalk) {
                    if (result.size() >= limit) {
                        break;
                    }
                    result.add(LogEntry.from(commit));
                }
            }

            log.info("Git log 查询完成: {}, 返回 {} 条", client.getLocalPath(), result.size());
            return result;
        } catch (GitClientException e) {
            throw e;
        } catch (Exception e) {
            throw new GitClientException("Git log 查询失败: " + e.getMessage(), e);
        }
    }

    private ObjectId resolveRef(Repository repo, String ref) throws Exception {
        ObjectId oid = repo.resolve(ref);
        if (oid == null) {
            oid = org.eclipse.jgit.lib.ObjectId.fromString(ref);
        }
        if (oid == null) {
            throw new GitClientException("引用无法解析: " + ref);
        }
        return oid;
    }
}
