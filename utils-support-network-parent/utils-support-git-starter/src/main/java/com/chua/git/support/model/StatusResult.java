package com.chua.git.support.model;

import java.util.List;
import java.util.Map;

/**
* Git 工作区状态记录。
*
* <p>由 {@link com.chua.git.support.operation.StatusOperation} 产出，
* 对应 {@code git status} 的核心信息。</p>
*
* <p>文件按状态分类存储，值为文件路径列表。</p>
*
* @param indexToWorkTree  暂存区与工作区的差异（已暂存变更）
* @param workTreeToIndex  工作区未暂存变更（modified）
* @param untracked        未跟踪文件
* @param ignored          被 .gitignore 忽略的文件
*
* @author CH
* @since 4.0.0.42
 */
public record StatusResult(
        Map<String, List<String>> indexToWorkTree,
        Map<String, List<String>> workTreeToIndex,
        List<String> untracked,
        List<String> ignored
) {

    /**
    * 工作区是否干净（无任何变更）。
    *
    * @return 无 staged / unstaged / untracked 变更时返回 true
    */
    public boolean isClean() {
        return indexToWorkTree.values().stream().noneMatch(l -> !l.isEmpty())
                && workTreeToIndex.values().stream().noneMatch(l -> !l.isEmpty())
                && untracked.isEmpty();
    }
}
