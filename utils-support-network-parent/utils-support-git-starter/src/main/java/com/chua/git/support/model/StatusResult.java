package com.chua.git.support.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
     * 规范构造器：对四个集合组件做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。两个状态映射的 value 本身
     * 也是可变列表，故连同内层列表一并深拷贝；使用
     * {@link Collections#unmodifiableMap(Map)} 包装 {@link LinkedHashMap} 而非
     * {@link Map#copyOf(Map)}，以保留 added/removed/modified/missing 的分类插入顺序。</p>
     */
    public StatusResult {
        indexToWorkTree = immutableStatusMap(indexToWorkTree);
        workTreeToIndex = immutableStatusMap(workTreeToIndex);
        untracked = List.copyOf(Objects.requireNonNull(untracked, "untracked 不能为 null"));
        ignored = List.copyOf(Objects.requireNonNull(ignored, "ignored 不能为 null"));
    }

    /**
     * 深拷贝一个「状态分类 到 文件路径列表」的映射并转为不可变。
     *
     * @param source 源映射，不可为 {@code null}
     * @return 保持插入顺序的不可变深拷贝
     */
    private static Map<String, List<String>> immutableStatusMap(Map<String, List<String>> source) {
        Objects.requireNonNull(source, "状态 映射 不能为 null");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

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
