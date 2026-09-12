package com.chua.git.support.operation;

import com.chua.git.support.GitClient;
import com.chua.git.support.exception.GitClientException;
import com.chua.git.support.model.TagInfo;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Ref;

import java.util.ArrayList;
import java.util.List;

/**
   * 标签操作（Git 标签）。
 *
 * <p>对已打开的本地仓库，提供以下操作：</p>
 * <ul>
 *   <li>{@link #list()} — 列出所有标签</li>
 *   <li>{@link #create(String)} — 创建轻量标签</li>
 *   <li>{@link #create(String, String)} — 创建附注标签</li>
 *   <li>{@link #delete(String)} — 删除标签</li>
 * </ul>
 *
 * <pre>示例：
 * {@code
 * // 列出所有标签
 * List<TagInfo> tags = client.tag().list();
 *
 * // 创建附注标签
 * client.tag().create("v1.0", "发布 1.0 版本");
 *
 * // 删除标签
 * client.tag().delete("v0.9");
 * }</pre>("v0.9");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class TagOperation {

    /**
      * 所属 git客户端。
     */
    private final GitClient client;

    /**
     * 构建操作实例（仅框架内部调用）。
     *
     * @param client 所属 Git客户端
     */
    public TagOperation(GitClient client) {
        this.client = client;
    }

    // ==================== 查询方法 ====================

    /**
     * 列出所有标签。
     *
     * @return 标签信息列表
     */
    public List<TagInfo> list() {
        try {
            client.open();
            List<Ref> refs = client.getGit().tagList().call();

            List<TagInfo> result = new ArrayList<>();
            for (Ref ref : refs) {
                String name = ref.getName().substring("refs/tags/".length());
                String sha = ref.getObjectId().getName();
                result.add(new TagInfo(name, sha, "", false));
            }

            log.info("Git tag list 完成: {}, 共 {} 个标签", client.getLocalPath(), result.size());
            return result;
        } catch (Exception e) {
            throw new GitClientException("Git tag list 失败: " + e.getMessage(), e);
        }
    }

    // ==================== 创建方法 ====================

    /**
     * 创建轻量标签。
     *
     * @param name 标签名称（如 "v1.0"）
     * @return 当前操作实例
     */
    public TagOperation create(String name) {
        create(name, null);
        return this;
    }

    /**
     * 创建附注标签。
     *
     * @param name    标签名称
     * @param message 标签消息
     * @return 当前操作实例
     */
    public TagOperation create(String name, String message) {
        try {
            client.open();
            var cmd = client.getGit().tag();
            cmd.setName(name);
            if (message != null && !message.isBlank()) {
                cmd.setMessage(message);
            }
            cmd.call();
            log.info("Git tag 创建: {}", name);
        } catch (Exception e) {
            throw new GitClientException("Git tag 创建失败: " + e.getMessage(), e);
        }
        return this;
    }

    // ==================== 删除方法 ====================

    /**
     * 删除本地标签。
     *
     * @param name 标签名称
     * @return 当前操作实例
     */
    public TagOperation delete(String name) {
        try {
            client.open();
            client.getGit().tagDelete()
                    .setTags(name)
                    .call();
            log.info("Git tag 删除: {}", name);
        } catch (Exception e) {
            throw new GitClientException("Git tag 删除失败: " + e.getMessage(), e);
        }
        return this;
    }
}
