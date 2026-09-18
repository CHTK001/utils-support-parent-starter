package com.chua.git.support.operation;

import com.chua.git.support.GitClient;
import com.chua.git.support.exception.GitClientException;
import com.chua.git.support.model.StatusResult;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作区状态查询操作（git status）。
 *
 * <p>对已打开的本地仓库，查询暂存区、工作区、未跟踪文件状态。</p>
 *
 * <pre>示例：
 * {@code
 * StatusResult status = client.status().execute();
 * boolean clean = status.isClean();
 * List<String> untracked = status.untracked();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class StatusOperation {

    /**
    * 所属 GitClient。
    */
    private final GitClient client;

    /**
    * 构建操作实例（仅框架内部调用）。
    *
    * @param client 所属 GitClient
    */
    public StatusOperation(GitClient client) {
        this.client = client;
    }

    /**
    * 执行状态查询。
    *
    * @return 工作区状态
    */
    public StatusResult execute() {
        try {
            client.open();
            Status status = client.getGit().status().call();

            Map<String, List<String>> indexMap = buildMap(
                    toList(status.getAdded()),
                    toList(status.getRemoved()),
                    toList(status.getModified()),
                    toList(status.getMissing())
            );
            Map<String, List<String>> workTreeMap = buildMap(
                    toList(status.getChanged()),
                    toList(status.getRemoved()),
                    toList(status.getModified()),
                    toList(status.getMissing())
            );

            List<String> untracked = toList(status.getUntracked());
            List<String> ignored = toList(status.getIgnoredNotInIndex());

            log.info("Git status 完成: {}, 未跟踪文件数={}", client.getLocalPath(), untracked.size());
            return new StatusResult(indexMap, workTreeMap, untracked, ignored);
        } catch (Exception e) {
            throw new GitClientException("Git status 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 转为列出。
     *
     * @param set 设置，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    private List<String> toList(Set<String> set) {
        return set != null ? new ArrayList<>(set) : new ArrayList<>();
    }

    private Map<String, List<String>> buildMap(List<String> added, List<String> removed,
                                                List<String> modified, List<String> missing) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        if (!added.isEmpty()) {
            map.put("added", added);
        }
        if (!removed.isEmpty()) {
            map.put("removed", removed);
        }
        if (!modified.isEmpty()) {
            map.put("modified", modified);
        }
        if (!missing.isEmpty()) {
            map.put("missing", missing);
        }
        return map;
    }
}
