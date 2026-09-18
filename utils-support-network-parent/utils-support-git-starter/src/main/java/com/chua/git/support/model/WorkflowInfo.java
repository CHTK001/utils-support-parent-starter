package com.chua.git.support.model;

/**
 * CI 工作流（流水线）信息记录。
 *
 * <p>由 {@link com.chua.git.support.operation.ActionsOperation} 产出，用于描述仓库中
 * 已注册的一条 CI 工作流（GitHub Actions 的工作流或 Gitee Go 流水线）。</p>
 *
 * @param id       工作流唯一标识（GitHub 为数字 ID，Gitee 可为流水线编号）
 * @param name     工作流显示名称
 * @param path     工作流文件路径，如 .github/workflows/ci.yml
 * @param state    工作流状态，如 active / disabled
 * @param htmlUrl  工作流页面地址，用于在浏览器中查看配置
 *
 * @author CH
 * @since 4.0.0.42
 * @return 结果值
 */
public record WorkflowInfo(long id, String name, String path, String state, String htmlUrl) {
}
