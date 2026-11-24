package com.chua.common.support.ai.agent.run;

import java.util.List;

/**
 * Agent 运行记录存储契约（SPI）。
 *
 * <p><b>为什么需要它</b></p>
 * <p>Agent 体系要补「运行可观测」：每次执行的状场、步骤、工具调用、失败原因需要持久化，
 * 供前端时间线与失败分析消费。本契约把「运行/步骤存哪、怎么存」下沉为接口：
 * 业务实现（JDBC）放在 ai-starter，测试与工具场景可替换为内存实现；
 * 消费方只依赖本契约，不依赖具体存储。</p>
 *
 * <p><b>语义约定</b></p>
 * <ul>
 *   <li>Run 状态机由业务层维护；本契约只做透明存取，不做状态判定。</li>
 *   <li>步骤按 {@code seq} 升序读取；并发写入同一步骤由业务层保证单线程（P0 链路为同步执行）。</li>
 *   <li>实现必须支持并发读写；摘要字段在写入前已由业务层截断。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.52
 */
public interface AgentRunStore {

    /**
     * 新增一条运行记录。
     *
     * @param run 运行记录（runId 唯一）
     */
    void insertRun(AgentRunRecord run);

    /**
     * 按运行标识全量更新运行记录（含状态、汇总、结束时间）。
     *
     * @param run 运行记录（runId 必须已存在）
     * @return 影响行数（0 = 运行不存在）
     */
    int updateRun(AgentRunRecord run);

    /**
     * 读取运行记录。
     *
     * @param runId 运行标识
     * @return 运行记录；不存在返回 null
     */
    AgentRunRecord findRun(String runId);

    /**
     * 统计满足条件的运行数。
     *
     * @param query 查询条件
     * @return 运行数
     */
    long countRuns(AgentRunQuery query);

    /**
     * 分页查询运行列表（按开始时间倒序）。
     *
     * @param query 查询条件（含分页参数）
     * @return 运行记录列表
     */
    List<AgentRunRecord> listRuns(AgentRunQuery query);

    /**
     * 新增一条步骤记录。
     *
     * @param step 步骤记录（stepId 唯一）
     */
    void insertStep(AgentStepRecord step);

    /**
     * 统计运行下的步骤数（用于计算下一步 seq）。
     *
     * @param runId 运行标识
     * @return 步骤数
     */
    long countSteps(String runId);

    /**
     * 读取运行的完整步骤时间线（按 seq 升序）。
     *
     * @param runId 运行标识
     * @return 步骤记录列表
     */
    List<AgentStepRecord> listSteps(String runId);
}
