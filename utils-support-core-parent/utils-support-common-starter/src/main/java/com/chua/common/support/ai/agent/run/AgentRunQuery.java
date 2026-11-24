package com.chua.common.support.ai.agent.run;

/**
 * Agent 运行列表查询条件。
 *
 * @param sessionId 按会话过滤；null 不过滤
 * @param agentId   按 Agent 标识过滤；null 不过滤
 * @param status    按状态名过滤；null 不过滤
 * @param userId    按归属用户过滤；null 不过滤（管理员视角）
 * @param limit     分页大小（1~200，缺省 20）
 * @param offset    分页偏移（缺省 0）
 * @author CH
 * @since 4.0.0.52
 */
public record AgentRunQuery(Long sessionId, String agentId, String status, String userId,
                            Integer limit, Integer offset) {

    /**
     * 紧凑构造器：规范化分页参数（limit 缺省 20、上限 200；offset 非负）。
     */
    public AgentRunQuery {
        limit = limit == null || limit < 1 ? 20 : Math.min(limit, 200);
        offset = offset == null || offset < 0 ? 0 : offset;
    }
}
