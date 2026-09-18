package com.chua.git.support.model;

/**
* Git 拉取（获取 + 合并）结果记录。
*
* <p>由 {@link com.chua.git.support.GitClient#pull()} 操作返回，字段含义如下：</p>
* <ul>
*   <li>{@code updated} — {@code true} 表示 HEAD 树有变化，即有新的提交被合并到本地</li>
*   <li>{@code fetchedRefs} — {@link PullResult} 发生了引用的数量（当前版本通过 diff 计算更新，未单独统计）</li>
*   <li>{@code messages} — 人类可读结果描述（如 "Already up to date."、"Updated."）</li>
* </ul>
*
* @param updated     是否有新变更被拉取
* @param fetchedRefs 发生的引用数（当前版本始终为 0）
* @param messages    结果描述字符串
*
* @author CH
* @since 4.0.0.42
* @return 拉手结果的结果
 */
public record PullResult(boolean updated, int fetchedRefs, String messages) {

    /**
    * 快速创建"最新"结果。
    *
    * @return 一个指示无更新需要的 拉手结果
    */
    public static PullResult noUpdate() {
        return new PullResult(false, 0, "Already up to date.");
    }

    /**
    * 根据 成功 状态总结一句话输出。
    *
    * @return 内容概述："Updated." 或 "No 更新."
    */
    @Override
    public String toString() {
        return updated ? messages : "No update.";
    }
}
