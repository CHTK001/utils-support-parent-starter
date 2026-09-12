package com.chua.git.support.model;

import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;

/**
* Git 提交日志条目。
*
* <p>封装一次提交的核心信息，由 {@link com.chua.git.support.operation.LogOperation} 产出。</p>
*
* @param sha          完整提交哈希（40 或 64 位）
* @param shortSha    提交哈希短前缀（默认 7 位）
* @param message      提交消息
* @param author       提交者名称（未设置则为空串）
* @param authorEmail  提交者邮箱
* @param commitTime   提交时间（Unix 毫秒）
*
* @author CH
* @since 4.0.0.42
 */
public record LogEntry(
        String sha,
        String shortSha,
        String message,
        String author,
        String authorEmail,
        long commitTime
) {

    /**
    * 从 jgit {@link RevCommit} 构造。
    *
    * @param commit jgit revcommit 对象
    * @return LogEntry
     */
    public static LogEntry from(RevCommit commit) {
        PersonIdent author = commit.getAuthorIdent();
        String sha = commit.name();
        return new LogEntry(
                sha,
                sha.substring(0, Math.min(7, sha.length())),
                commit.getFullMessage(),
                author != null ? author.getName() : "",
                author != null ? author.getEmailAddress() : "",
                (long) commit.getCommitTime() * 1000L
        );
    }
}
