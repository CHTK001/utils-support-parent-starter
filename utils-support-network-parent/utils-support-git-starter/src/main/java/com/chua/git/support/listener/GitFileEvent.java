package com.chua.git.support.listener;

/**
 * Git 文件变更事件，封装一次 拉手/watch 中检测到的单文件变更信息。
 *
 * <p>当 {@link GitClient#pull(GitFileListener)} 或
 * {@link FetchOperation#listener(GitFileListener)} 检测到 HEAD 树发生变化时，
 * 会对比前后两个树对象，将差异文件以本事件形式逐个通知监听器。</p>
 *
 * <p>变更类型以 {@link ChangeType} 枚举表示：</p>
 * <ul>
 *   <li><b>ADD</b> —— 新增文件</li>
 *   <li><b>MODIFY</b> —— 文件内容被修改（含 RENAME、COPY）</li>
 *   <li><b>DELETE</b> —— 文件被删除</li>
 * </ul>
 *
 * @param changeType 变更类型
 * @param filePath   变更文件相对仓库根目录的路径，如 {@code src/main/Main.java}
 *
 * @author CH
 * @since 4.0.0.42
 * @return git文件事件的结果
 */
public record GitFileEvent(ChangeType changeType, String filePath) {

    /**
     * 变更类型枚举。
     * @author CH
     * @since 4.0.0
     */
    public enum ChangeType {
        /** 文件新增 */
        ADD,
        /** 文件修改（包含重命名、拷贝） */
        MODIFY,
        /** 文件删除 */
        DELETE
    }
}
