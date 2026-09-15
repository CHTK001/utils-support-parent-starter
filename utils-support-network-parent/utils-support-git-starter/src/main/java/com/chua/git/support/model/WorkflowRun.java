package com.chua.git.support.model;

/**
 * CI 工作流运行（一次流水线执行）信息记录。
 *
 * <p>由 {@link com.chua.git.support.operation.ActionsOperation} 产出，描述一次
 * 工作流运行的状态、分支、提交与时间信息，便于查询 CI 执行结果。</p>
 *
 * @param id           运行唯一标识
 * @param name         工作流名称
 * @param status       运行状态，如 queued / in_progress / completed
 * @param conclusion   运行结论，如 success / failure / cancelled，仅 completed 后有值
 * @param branch       触发运行的分支
 * @param headSha      触发运行的提交 SHA
 * @param runNumber    运行序号（该工作流第几次运行）
 * @param createdAt    创建时间（ISO-8601 格式字符串）
 * @param updatedAt    更新时间（ISO-8601 格式字符串）
 * @param htmlUrl      运行结果页面地址，用于在浏览器中查看日志
 *
 * @author CH
 * @since 4.0.0.42
 */
public record WorkflowRun(long id, String name, String status, String conclusion, String branch,
                          String headSha, long runNumber, String createdAt, String updatedAt, String htmlUrl) {
}
